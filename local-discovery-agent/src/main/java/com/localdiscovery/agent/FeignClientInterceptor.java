package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * 拦截 feign.Client.execute(Request, Options)
 * 当请求 URL 指向配置的网关 host 时，注入当前线程的 Authorization header
 *
 * Advice 代码会被内联到目标类（feign.Client$Default），所以这里只做一次静态方法调用，
 * 全部逻辑放在 FeignAuthBridge 里，避免 lambda / instanceof pattern 之类被内联后触发 IllegalAccessError
 */
public class FeignClientInterceptor {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object request) {
        Object rewritten = FeignAuthBridge.rewriteIfNeeded(request);
        if (rewritten != null) {
            request = rewritten;
        }
    }
}
