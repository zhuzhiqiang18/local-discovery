package com.localdiscovery.agent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MQ 消费控制桥接器
 * 收集所有 RocketMQ ListenerContainer，通过底层 DefaultMQPushConsumer 的 suspend/resume 控制消费
 *
 * 只使用 JDK 类型 + 反射调用，避免 ClassLoader 问题
 */
public class MqBridge {

    /** 所有 DefaultRocketMQListenerContainer 实例引用 */
    private static final List<Object> containers = new CopyOnWriteArrayList<>();

    /** 当前 MQ 消费是否启用 */
    private static volatile boolean mqEnabled = true;

    /**
     * 注册一个 container（由 RocketMqInterceptor 调用）
     */
    public static void registerContainer(Object container) {
        if (containers.contains(container)) return;
        containers.add(container);
        System.out.println("[LocalDiscovery] 捕获 RocketMQ Container: " + container);
        // 如果当前 MQ 已被停用，立即暂停新注册的 consumer
        if (!mqEnabled) {
            suspendConsumer(container);
        }
    }

    /**
     * 设置 MQ 启用状态（由 AgentRegistrar 心跳回调调用）
     */
    public static void setMqEnabled(boolean enabled) {
        if (mqEnabled == enabled) return;
        mqEnabled = enabled;
        System.out.println("[LocalDiscovery] MQ 消费 -> " + (enabled ? "启用" : "停用"));
        for (Object container : containers) {
            if (enabled) {
                resumeConsumer(container);
            } else {
                suspendConsumer(container);
            }
        }
    }

    public static boolean isMqEnabled() {
        return mqEnabled;
    }

    /**
     * 通过反射获取 container 内部的 DefaultMQPushConsumer，调用 suspend()
     * DefaultRocketMQListenerContainer 有 getConsumer() 方法返回 DefaultMQPushConsumer
     */
    private static void suspendConsumer(Object container) {
        try {
            Object consumer = getConsumer(container);
            if (consumer != null) {
                consumer.getClass().getMethod("suspend").invoke(consumer);
                System.out.println("[LocalDiscovery] MQ Consumer 已暂停: " + container);
            }
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] 暂停 MQ Consumer 失败: " + e.getMessage());
        }
    }

    private static void resumeConsumer(Object container) {
        try {
            Object consumer = getConsumer(container);
            if (consumer != null) {
                consumer.getClass().getMethod("resume").invoke(consumer);
                System.out.println("[LocalDiscovery] MQ Consumer 已恢复: " + container);
            }
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] 恢复 MQ Consumer 失败: " + e.getMessage());
        }
    }

    private static Object getConsumer(Object container) {
        try {
            Method getConsumer = container.getClass().getMethod("getConsumer");
            return getConsumer.invoke(container);
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] 获取 MQ Consumer 失败: " + e.getMessage());
            return null;
        }
    }
}
