package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.util.List;

/**
 * 拦截 BlockingLoadBalancerClient.choose(String serviceId, Request request)
 * 每次 Feign/RestTemplate 调用都经过此方法，不受 LoadBalancer 缓存影响
 *
 * 先查本地注册中心，命中则返回本地 ServiceInstance，否则放行走原有逻辑（Nacos）
 */
public class LoadBalancerInterceptor {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(@Advice.Argument(0) String serviceId) {
        // Nacos 可能传入带 group 前缀的 serviceId，如 "CLOUD_GROUP@@service-a"
        String lookupId = serviceId;
        if (lookupId != null && lookupId.contains("@@")) {
            lookupId = lookupId.substring(lookupId.indexOf("@@") + 2);
        }
        try {
            List<String[]> instances = DiscoveryBridge.lookup(lookupId);
            if (instances != null && !instances.isEmpty()) {
                // 取第一个实例（简单轮询可后续优化）
                String[] inst = instances.get(0);
                String host = inst[0];
                int port = Integer.parseInt(inst[1]);
                boolean secure = Boolean.parseBoolean(inst[2]);

                // 通过反射创建 DefaultServiceInstance
                Class<?> dsiClass = Class.forName("org.springframework.cloud.client.DefaultServiceInstance");
                var constructor = dsiClass.getConstructor(
                        String.class, String.class, String.class, int.class, boolean.class);
                Object localInstance = constructor.newInstance(
                        lookupId + "-local-0", lookupId, host, port, secure);
                System.out.println("[LocalDiscovery] " + serviceId + " → 本地 " + host + ":" + port);
                return localInstance;
            }
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] LoadBalancer 拦截异常: " + e.getMessage());
        }
        return null;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Object intercepted,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result) {
        if (intercepted != null) {
            result = intercepted;
        }
    }
}
