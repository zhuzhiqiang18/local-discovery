package com.localdiscovery.agent;

import java.util.function.Consumer;

/**
 * Tomcat 桥接器：Advice 内联代码通过此类回调 Agent
 * 只使用 JDK 类型，避免 ClassLoader 问题
 */
public class TomcatBridge {

    public static volatile Consumer<Object> tomcatCallback;

    public static void onTomcatStarted(Object tomcatWebServer) {
        Consumer<Object> cb = tomcatCallback;
        if (cb != null) {
            cb.accept(tomcatWebServer);
        }
    }
}
