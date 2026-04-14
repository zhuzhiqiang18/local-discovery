package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;

/**
 * 拦截 SpringApplication.run() 的返回
 * 在 ApplicationContext 就绪后，提取 spring.application.name 和 server.port
 */
public class SpringApplicationInterceptor {

    @Advice.OnMethodExit
    public static void onRun(@Advice.Return Object applicationContext) {
        try {
            if (applicationContext == null) return;

            // applicationContext.getEnvironment()
            var getEnvMethod = applicationContext.getClass().getMethod("getEnvironment");
            Object env = getEnvMethod.invoke(applicationContext);

            // environment.getProperty(key)
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
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] 获取应用信息失败: " + e.getMessage());
        }
    }
}
