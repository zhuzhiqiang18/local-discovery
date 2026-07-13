package com.localdiscovery.registry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 注册表
 * 管理所有服务实例，支持心跳检测和自动过期
 */
public class Registry {

    private static final Registry INSTANCE = new Registry();

    /** 心跳超时时间（毫秒），超过此时间未心跳则自动下线 */
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;

    /** 服务实例 */
    public static class ServiceInstance {
        private final String serviceId;
        private final String host;
        private final int port;
        private final String instanceId;
        private final long registerTime;
        private volatile long lastHeartbeat;
        private volatile boolean enabled;
        private volatile boolean mqEnabled;
        private final Map<String, String> metadata;

        public ServiceInstance(String serviceId, String host, int port, Map<String, String> metadata) {
            this.serviceId = serviceId;
            this.host = host;
            this.port = port;
            this.instanceId = serviceId + "-" + host + "-" + port;
            this.registerTime = System.currentTimeMillis();
            this.lastHeartbeat = this.registerTime;
            this.enabled = true;
            this.mqEnabled = true;
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        }

        public String getServiceId() { return serviceId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getInstanceId() { return instanceId; }
        public long getRegisterTime() { return registerTime; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isMqEnabled() { return mqEnabled; }
        public void setMqEnabled(boolean mqEnabled) { this.mqEnabled = mqEnabled; }
        public Map<String, String> getMetadata() { return metadata; }
        public String getUri() { return "http://" + host + ":" + port; }

        public void heartbeat() {
            this.lastHeartbeat = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS;
        }
    }

    /** serviceId -> (instanceId -> ServiceInstance) */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ServiceInstance>> registry = new ConcurrentHashMap<>();

    /** 全局网关代理开关：开启后 Agent 找不到本地实例时会走 gatewayUrl */
    private volatile boolean onlineProxyEnabled = false;

    /** 网关地址（含协议），如 https://api.prod.com */
    private volatile String gatewayUrl = "";

    // ============ Archery SQL 转发（全局配置） ============

    /** Archery 全局总开关 */
    private volatile boolean archeryEnabled = false;

    /** Archery 平台地址，例如 http://archery.internal */
    private volatile String archeryUrl = "";

    /** Archery 请求头（JWT/Cookie），形如 "Authorization:JWT xxx;Cookie:sessionid=yyy" */
    private volatile String archeryHeaders = "";

    /** Archery 单次查询默认行数上限 */
    private volatile int archeryLimit = 1000;

    /** 每个 serviceId 对应的 Archery 项目配置：instance_name + db_name + 单服务开关 */
    public static class ArcheryProject {
        public volatile String instanceName = "";
        public volatile String dbName = "";
        public volatile boolean enabled = true;

        public ArcheryProject() {}
        public ArcheryProject(String instanceName, String dbName, boolean enabled) {
            this.instanceName = instanceName == null ? "" : instanceName;
            this.dbName = dbName == null ? "" : dbName;
            this.enabled = enabled;
        }
    }

    private final ConcurrentHashMap<String, ArcheryProject> archeryProjects = new ConcurrentHashMap<>();

    private Registry() {}

    public static Registry getInstance() {
        return INSTANCE;
    }

    /**
     * 启动过期检测定时任务
     */
    public void startEviction() {
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "registry-eviction");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> {
            registry.forEach((serviceId, instances) -> {
                instances.entrySet().removeIf(entry -> {
                    if (entry.getValue().isExpired()) {
                        System.out.println("[Registry] 实例过期下线: " + entry.getKey());
                        return true;
                    }
                    return false;
                });
                if (instances.isEmpty()) {
                    registry.remove(serviceId);
                }
            });
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 注册服务实例
     */
    public ServiceInstance register(String serviceId, String host, int port, Map<String, String> metadata) {
        ServiceInstance instance = new ServiceInstance(serviceId, host, port, metadata);
        registry.computeIfAbsent(serviceId, k -> new ConcurrentHashMap<>())
                .put(instance.getInstanceId(), instance);
        System.out.println("[Registry] 注册: " + instance.getInstanceId() + " -> " + instance.getUri());
        return instance;
    }

    /**
     * 注销服务实例
     */
    public boolean deregister(String serviceId, String host, int port) {
        String instanceId = serviceId + "-" + host + "-" + port;
        ConcurrentHashMap<String, ServiceInstance> instances = registry.get(serviceId);
        if (instances != null) {
            ServiceInstance removed = instances.remove(instanceId);
            if (instances.isEmpty()) {
                registry.remove(serviceId);
            }
            if (removed != null) {
                System.out.println("[Registry] 注销: " + instanceId);
                return true;
            }
        }
        return false;
    }

    /**
     * 心跳
     */
    public boolean heartbeat(String serviceId, String host, int port) {
        String instanceId = serviceId + "-" + host + "-" + port;
        ConcurrentHashMap<String, ServiceInstance> instances = registry.get(serviceId);
        if (instances != null) {
            ServiceInstance instance = instances.get(instanceId);
            if (instance != null) {
                instance.heartbeat();
                return true;
            }
        }
        return false;
    }

    /**
     * 查询服务的所有可用实例
     */
    public List<ServiceInstance> getInstances(String serviceId) {
        ConcurrentHashMap<String, ServiceInstance> instances = registry.get(serviceId);
        if (instances == null) return Collections.emptyList();
        return instances.values().stream()
                .filter(ServiceInstance::isEnabled)
                .filter(i -> !i.isExpired())
                .toList();
    }

    /**
     * 查询所有已注册的服务名
     */
    public Set<String> getServiceIds() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    /**
     * 查询所有实例（含不可用的）
     */
    public List<ServiceInstance> getAllInstances() {
        List<ServiceInstance> all = new ArrayList<>();
        registry.values().forEach(map -> all.addAll(map.values()));
        return all;
    }

    /**
     * 启用/停用某个实例
     */
    public boolean toggleInstance(String serviceId, String host, int port, boolean enabled) {
        String instanceId = serviceId + "-" + host + "-" + port;
        ConcurrentHashMap<String, ServiceInstance> instances = registry.get(serviceId);
        if (instances != null) {
            ServiceInstance instance = instances.get(instanceId);
            if (instance != null) {
                instance.setEnabled(enabled);
                System.out.println("[Registry] " + instanceId + " -> " + (enabled ? "启用" : "停用"));
                return true;
            }
        }
        return false;
    }

    /**
     * 启用/停用某个实例的 MQ 消费
     */
    public boolean toggleMq(String serviceId, String host, int port, boolean mqEnabled) {
        String instanceId = serviceId + "-" + host + "-" + port;
        ConcurrentHashMap<String, ServiceInstance> instances = registry.get(serviceId);
        if (instances != null) {
            ServiceInstance instance = instances.get(instanceId);
            if (instance != null) {
                instance.setMqEnabled(mqEnabled);
                System.out.println("[Registry] " + instanceId + " MQ -> " + (mqEnabled ? "启用" : "停用"));
                return true;
            }
        }
        return false;
    }

    /**
     * 查询实例的 MQ 是否启用
     */
    public Boolean isMqEnabled(String serviceId, String host, int port) {
        String instanceId = serviceId + "-" + host + "-" + port;
        ConcurrentHashMap<String, ServiceInstance> instances = registry.get(serviceId);
        if (instances != null) {
            ServiceInstance instance = instances.get(instanceId);
            if (instance != null) {
                return instance.isMqEnabled();
            }
        }
        return null;
    }

    public int totalInstances() {
        return registry.values().stream().mapToInt(ConcurrentHashMap::size).sum();
    }

    public int totalServices() {
        return registry.size();
    }

    public boolean isOnlineProxyEnabled() {
        return onlineProxyEnabled;
    }

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    /**
     * 更新网关代理配置
     * 开启代理时联动关闭所有实例的 MQ 消费
     */
    public void updateOnlineProxy(boolean enabled, String gatewayUrl) {
        this.onlineProxyEnabled = enabled;
        if (gatewayUrl != null) {
            this.gatewayUrl = gatewayUrl.trim();
        }
        System.out.println("[Registry] 网关代理 -> " + (enabled ? "开启" : "关闭") + ", gatewayUrl=" + this.gatewayUrl);
        if (enabled) {
            registry.values().forEach(map -> map.values().forEach(i -> i.setMqEnabled(false)));
            System.out.println("[Registry] 已联动关闭所有实例的 MQ 消费");
        }
        ConfigStore.save();
    }

    /** 启动时从配置文件回填，不打日志、不落盘 */
    public void loadOnlineProxy(boolean enabled, String gatewayUrl) {
        this.onlineProxyEnabled = enabled;
        this.gatewayUrl = gatewayUrl == null ? "" : gatewayUrl.trim();
    }

    // ============ Archery 全局配置 ============

    public boolean isArcheryEnabled() { return archeryEnabled; }
    public String  getArcheryUrl()     { return archeryUrl; }
    public String  getArcheryHeaders() { return archeryHeaders; }
    public int     getArcheryLimit()   { return archeryLimit; }

    public void updateArcheryGlobal(Boolean enabled, String url, String headers, Integer limit) {
        if (enabled != null) this.archeryEnabled = enabled;
        if (url != null)     this.archeryUrl = url.trim();
        if (headers != null) this.archeryHeaders = headers;
        if (limit != null && limit > 0) this.archeryLimit = limit;
        System.out.println("[Registry] Archery 全局 -> enabled=" + this.archeryEnabled
                + " url=" + this.archeryUrl + " limit=" + this.archeryLimit);
        ConfigStore.save();
    }

    /** 启动时从配置文件回填，不打日志、不落盘 */
    public void loadArcheryGlobal(boolean enabled, String url, String headers, int limit) {
        this.archeryEnabled = enabled;
        this.archeryUrl = url == null ? "" : url;
        this.archeryHeaders = headers == null ? "" : headers;
        if (limit > 0) this.archeryLimit = limit;
    }

    // ============ Archery 每服务项目配置 ============

    public ArcheryProject getArcheryProject(String serviceId) {
        return archeryProjects.get(serviceId);
    }

    public Map<String, ArcheryProject> getAllArcheryProjects() {
        return Collections.unmodifiableMap(archeryProjects);
    }

    public void updateArcheryProject(String serviceId, String instanceName, String dbName, Boolean enabled) {
        if (serviceId == null || serviceId.isBlank()) return;
        archeryProjects.compute(serviceId, (k, existing) -> {
            ArcheryProject p = existing != null ? existing : new ArcheryProject();
            if (instanceName != null) p.instanceName = instanceName.trim();
            if (dbName != null)       p.dbName = dbName.trim();
            if (enabled != null)      p.enabled = enabled;
            return p;
        });
        ArcheryProject p = archeryProjects.get(serviceId);
        System.out.println("[Registry] Archery 项目 " + serviceId + " -> instance="
                + p.instanceName + " db=" + p.dbName + " enabled=" + p.enabled);
        ConfigStore.save();
    }

    public boolean removeArcheryProject(String serviceId) {
        boolean removed = archeryProjects.remove(serviceId) != null;
        if (removed) ConfigStore.save();
        return removed;
    }

    /** 启动时回填用：不存在则创建，供 ConfigStore 逐字段写入 */
    public ArcheryProject getOrCreateArcheryProject(String serviceId) {
        return archeryProjects.computeIfAbsent(serviceId, k -> new ArcheryProject());
    }
}
