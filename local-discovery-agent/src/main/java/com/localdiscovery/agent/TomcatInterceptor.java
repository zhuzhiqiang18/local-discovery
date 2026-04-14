package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;

/**
 * 拦截 TomcatWebServer.start()
 * Tomcat 启动完成后通过 TomcatBridge 回调 Agent
 */
public class TomcatInterceptor {

    @Advice.OnMethodExit
    public static void onStart(@Advice.This Object webServer) {
        try {
            TomcatBridge.onTomcatStarted(webServer);
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] Tomcat 回调异常: " + e.getMessage());
        }
    }
}
