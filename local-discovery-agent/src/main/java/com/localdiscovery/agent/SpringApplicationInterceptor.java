package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;

/**
 * 拦截 SpringApplication.run() 的返回
 *
 * Spring 启动完成后提取 spring.application.name 和 server.port → 触发自动注册
 * DiscoveryClient 拦截由 DiscoveryClientInterceptor 在字节码层面完成，无需在此注入代理
 */
public class SpringApplicationInterceptor {

    public static volatile boolean registered = false;

    @Advice.OnMethodExit
    public static void onRun(@Advice.Return Object applicationContext) {
        if (applicationContext == null || registered) return;
        registered = true;

        try {
            extractAndRegister(applicationContext);
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] Spring 启动后处理失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 提取 spring.application.name 和 server.port，触发自动注册
     */
    public static void extractAndRegister(Object applicationContext) throws Exception {
        var getEnvMethod = applicationContext.getClass().getMethod("getEnvironment");
        Object env = getEnvMethod.invoke(applicationContext);
        var getPropertyMethod = env.getClass().getMethod("getProperty", String.class);

        String appName = (String) getPropertyMethod.invoke(env, "spring.application.name");
        String portStr = (String) getPropertyMethod.invoke(env, "local.server.port");
        if (portStr == null) {
            portStr = (String) getPropertyMethod.invoke(env, "server.port");
        }
        if (portStr == null) portStr = "8080";

        if (appName != null && !appName.isBlank()) {
            EnvironmentBridge.applicationReady(new String[]{appName, portStr});
        }
    }
}
