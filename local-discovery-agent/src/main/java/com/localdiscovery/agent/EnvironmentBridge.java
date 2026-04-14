package com.localdiscovery.agent;

import java.util.function.Consumer;

/**
 * Spring 应用名桥接器
 * 拦截 Environment.getProperty("spring.application.name") 获取服务名
 */
public class EnvironmentBridge {

    public static volatile Consumer<String[]> onApplicationReady;

    /**
     * 当检测到应用名和端口时回调
     * @param info {applicationName, port}
     */
    public static void applicationReady(String[] info) {
        Consumer<String[]> cb = onApplicationReady;
        if (cb != null) {
            cb.accept(info);
        }
    }
}
