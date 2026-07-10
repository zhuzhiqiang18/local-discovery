package com.localdiscovery.agent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feign Request 的重写逻辑集中在这里
 * 用普通 for 循环、无 lambda，避免 ByteBuddy 内联到 feign.Client 后的跨类访问问题
 */
public class FeignAuthBridge {

    /**
     * 判断请求是否走网关，是的话构造一个带 Authorization 的新 Request 返回，否则返回 null
     */
    public static Object rewriteIfNeeded(Object request) {
        try {
            if (request == null) return null;
            if (!OnlineProxyBridge.isEnabled()) return null;

            Class<?> requestClass = request.getClass();

            Method urlMethod = requestClass.getMethod("url");
            Object urlObj = urlMethod.invoke(request);
            if (urlObj == null) return null;
            String url = urlObj.toString();

            String gatewayHost = OnlineProxyBridge.getGatewayHost();
            if (gatewayHost.isEmpty() || !url.contains("://" + gatewayHost)) return null;

            Method headersMethod = requestClass.getMethod("headers");
            Object headers = headersMethod.invoke(request);
            if (headers instanceof Map) {
                Map<?, ?> src = (Map<?, ?>) headers;
                for (Object k : src.keySet()) {
                    if (k != null && k.toString().equalsIgnoreCase("Authorization")) {
                        return null;
                    }
                }
            }

            String auth = OnlineProxyBridge.currentAuthorization();
            if (auth == null || auth.isEmpty()) return null;

            Method httpMethodM = requestClass.getMethod("httpMethod");
            Object template = null;
            try {
                Method reqTemplateM = requestClass.getMethod("requestTemplate");
                template = reqTemplateM.invoke(request);
            } catch (NoSuchMethodException ignore) {}

            Object httpMethod = httpMethodM.invoke(request);

            // 拿原始 Body 对象（不是 byte[]），优先反射字段
            Object body = readBody(request, requestClass);

            // 定位 feign.Request.Body 类型
            Class<?> bodyClass = null;
            try {
                bodyClass = Class.forName("feign.Request$Body", true, requestClass.getClassLoader());
            } catch (Throwable ignore) {}

            Map<String, Collection<String>> newHeaders = new LinkedHashMap<>();
            if (headers instanceof Map) {
                Map<?, ?> src = (Map<?, ?>) headers;
                for (Map.Entry<?, ?> e : src.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    Collection<String> vs = (Collection<String>) e.getValue();
                    newHeaders.put(e.getKey().toString(), new ArrayList<String>(vs));
                }
            }
            newHeaders.put("Authorization", Collections.singletonList(auth));

            // 优先精确签名：feign.Request.create(HttpMethod, String, Map, Request.Body, RequestTemplate)
            Method[] methods = requestClass.getMethods();
            if (bodyClass != null) {
                for (Method m : methods) {
                    if (!m.getName().equals("create")) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 5 && pt[1] == String.class
                            && Map.class.isAssignableFrom(pt[2])
                            && pt[3] == bodyClass) {
                        Object bodyArg = adaptBody(body, bodyClass);
                        return m.invoke(null, httpMethod, url, newHeaders, bodyArg, template);
                    }
                }
                for (Method m : methods) {
                    if (!m.getName().equals("create")) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 4 && pt[1] == String.class
                            && Map.class.isAssignableFrom(pt[2])
                            && pt[3] == bodyClass) {
                        Object bodyArg = adaptBody(body, bodyClass);
                        return m.invoke(null, httpMethod, url, newHeaders, bodyArg);
                    }
                }
            }
            // 回退：byte[] 版
            for (Method m : methods) {
                if (!m.getName().equals("create")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length >= 4 && pt[1] == String.class
                        && Map.class.isAssignableFrom(pt[2])
                        && pt[3] == byte[].class) {
                    Object bodyArg = adaptBody(body, byte[].class);
                    Object[] args = new Object[pt.length];
                    args[0] = httpMethod; args[1] = url; args[2] = newHeaders; args[3] = bodyArg;
                    for (int i = 4; i < pt.length; i++) args[i] = null;
                    return m.invoke(null, args);
                }
            }
            return null;
        } catch (Throwable t) {
            System.err.println("[LocalDiscovery] Feign Request 重写失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 拿 feign.Request 的 Body 对象（不是 byte[]）
     * feign 11+ 内部字段是 requestBody（Request.Body 类型），旧版本是 body（byte[]）
     * 优先反射字段拿原始对象，退到方法调用
     */
    private static Object readBody(Object request, Class<?> requestClass) {
        try {
            java.lang.reflect.Field f = requestClass.getDeclaredField("requestBody");
            f.setAccessible(true);
            return f.get(request);
        } catch (NoSuchFieldException ignore) {
        } catch (Throwable ignore) {}
        try {
            java.lang.reflect.Field f = requestClass.getDeclaredField("body");
            f.setAccessible(true);
            return f.get(request);
        } catch (Throwable ignore) {}
        try {
            return requestClass.getMethod("body").invoke(request);
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * 把 body 适配到目标构造方法的参数类型
     * - 目标是 byte[]：如果 body 是 byte[] 直接返回，是 Request.Body 则调 asBytes()
     * - 目标是 Request.Body：如果 body 是 Request.Body 直接返回，是 byte[] 则调 Body.create(byte[])
     */
    private static Object adaptBody(Object body, Class<?> targetType) {
        if (body == null) return null;
        if (targetType.isInstance(body)) return body;
        if (targetType == byte[].class) {
            if (body instanceof byte[]) return body;
            try {
                return body.getClass().getMethod("asBytes").invoke(body);
            } catch (Throwable ignore) {
                return null;
            }
        }
        // 目标是 Request.Body（或类似）
        if (body instanceof byte[]) {
            try {
                Method create = targetType.getMethod("create", byte[].class);
                return create.invoke(null, (Object) body);
            } catch (Throwable ignore) {
                return null;
            }
        }
        return body;
    }
}
