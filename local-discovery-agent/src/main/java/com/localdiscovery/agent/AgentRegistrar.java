package com.localdiscovery.agent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 自动注册器
 * 检测到应用的服务名和端口后，自动注册到本地注册中心，并定期发送心跳
 */
public class AgentRegistrar {

    private final RegistryClient client;
    private volatile String serviceId;
    private volatile String host = "localhost";
    private volatile int port;
    private volatile boolean registered = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-discovery-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public AgentRegistrar(RegistryClient client) {
        this.client = client;
        // JVM 关闭时自动注销
        Runtime.getRuntime().addShutdownHook(new Thread(this::deregister, "local-discovery-shutdown"));
    }

    /**
     * 注册服务（在检测到服务名和端口后调用）
     */
    public void register(String serviceId, int port) {
        this.serviceId = serviceId;
        this.port = port;

        if (client.register(serviceId, host, port)) {
            registered = true;
            System.out.println("[LocalDiscovery] 已注册到本地注册中心: " + serviceId + " -> " + host + ":" + port);
            startHeartbeat();
        } else {
            System.err.println("[LocalDiscovery] 注册失败，注册中心可能未启动");
            // 启动重试
            startRetry();
        }
    }

    /**
     * 定期心跳
     */
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            if (serviceId != null && registered) {
                RegistryClient.HeartbeatResult result = client.heartbeat(serviceId, host, port);
                if (result == null) {
                    System.err.println("[LocalDiscovery] 心跳失败，尝试重新注册...");
                    registered = false;
                    client.register(serviceId, host, port);
                    registered = true;
                } else {
                    // 根据注册中心下发的状态控制本地 MQ 消费 & 网关代理
                    MqBridge.setMqEnabled(result.mqEnabled);
                    OnlineProxyBridge.update(result.onlineProxyEnabled, result.gatewayUrl);
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 注册失败后重试
     */
    private void startRetry() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!registered && serviceId != null) {
                if (client.register(serviceId, host, port)) {
                    registered = true;
                    System.out.println("[LocalDiscovery] 重试注册成功: " + serviceId);
                }
            }
        }, 3, 5, TimeUnit.SECONDS);
    }

    /**
     * 注销
     */
    private void deregister() {
        if (registered && serviceId != null) {
            client.deregister(serviceId, host, port);
            System.out.println("[LocalDiscovery] 已从注册中心注销: " + serviceId);
        }
    }

    public boolean isRegistered() {
        return registered;
    }

    public String getServiceId() {
        return serviceId;
    }
}
