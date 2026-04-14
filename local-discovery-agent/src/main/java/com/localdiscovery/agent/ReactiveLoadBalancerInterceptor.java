package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.util.List;

/**
 * 拦截 RoundRobinLoadBalancer / RandomLoadBalancer 的 choose(Request)
 * 覆盖响应式路径（Gateway、WebClient），与 LoadBalancerInterceptor 互补
 *
 * 通过 @FieldValue 读取目标类的 serviceId 字段，查本地注册中心
 * 命中则返回 Mono.just(DefaultResponse(localInstance))，跳过原方法
 */
public class ReactiveLoadBalancerInterceptor {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(@Advice.FieldValue("serviceId") String serviceId) {
        String lookupId = serviceId;
        if (lookupId != null && lookupId.contains("@@")) {
            lookupId = lookupId.substring(lookupId.indexOf("@@") + 2);
        }
        try {
            List<String[]> instances = DiscoveryBridge.lookup(lookupId);
            if (instances != null && !instances.isEmpty()) {
                String[] inst = instances.get(0);
                String host = inst[0];
                int port = Integer.parseInt(inst[1]);
                boolean secure = Boolean.parseBoolean(inst[2]);

                // 反射创建 DefaultServiceInstance
                Class<?> siClass = Class.forName("org.springframework.cloud.client.ServiceInstance");
                Class<?> dsiClass = Class.forName("org.springframework.cloud.client.DefaultServiceInstance");
                Object localInstance = dsiClass.getConstructor(
                        String.class, String.class, String.class, int.class, boolean.class
                ).newInstance(lookupId + "-local-0", lookupId, host, port, secure);

                // 反射创建 DefaultResponse(ServiceInstance)
                Class<?> responseClass = Class.forName("org.springframework.cloud.client.loadbalancer.DefaultResponse");
                Object response = responseClass.getConstructor(siClass).newInstance(localInstance);

                // 反射调用 Mono.just(response)
                Class<?> monoClass = Class.forName("reactor.core.publisher.Mono");
                Object mono = monoClass.getMethod("just", Object.class).invoke(null, response);

                System.out.println("[LocalDiscovery] (reactive) " + serviceId + " → 本地 " + host + ":" + port);
                return mono;
            }
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] ReactiveLoadBalancer 拦截异常: " + e.getMessage());
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
