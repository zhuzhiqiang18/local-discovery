package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;

import java.util.List;

/**
 * 拦截 DiscoveryClient.getInstances(String serviceId)
 * 先查本地注册中心，命中则返回本地实例，否则放行走 Nacos
 */
public class DiscoveryClientInterceptor {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(@Advice.Argument(0) String serviceId) {
        try {
            List<String[]> instances = DiscoveryBridge.lookup(serviceId);
            if (instances != null && !instances.isEmpty()) {
                // 通过反射创建 DefaultServiceInstance 列表
                Class<?> dsiClass = Class.forName("org.springframework.cloud.client.DefaultServiceInstance");
                var constructor = dsiClass.getConstructor(
                        String.class, String.class, String.class, int.class, boolean.class);

                java.util.ArrayList<Object> result = new java.util.ArrayList<>();
                int idx = 0;
                for (String[] inst : instances) {
                    String host = inst[0];
                    int port = Integer.parseInt(inst[1]);
                    boolean secure = Boolean.parseBoolean(inst[2]);
                    Object si = constructor.newInstance(
                            serviceId + "-local-" + idx, serviceId, host, port, secure);
                    result.add(si);
                    idx++;
                }
                return result;
            }
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] 拦截异常: " + e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Object intercepted,
            @Advice.Return(readOnly = false) List<?> result) {
        if (intercepted != null) {
            result = (List<?>) intercepted;
        }
    }
}
