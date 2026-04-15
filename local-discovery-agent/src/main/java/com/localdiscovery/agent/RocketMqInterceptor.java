package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;

/**
 * 拦截 DefaultRocketMQListenerContainer.start()
 * 在 container 启动时捕获其引用，交给 MqBridge 管理
 */
public class RocketMqInterceptor {

    @Advice.OnMethodExit
    public static void onStart(@Advice.This Object container) {
        try {
            MqBridge.registerContainer(container);
        } catch (Throwable t) {
            // 静默处理，不影响正常启动
        }
    }
}
