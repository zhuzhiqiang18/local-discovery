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
}
