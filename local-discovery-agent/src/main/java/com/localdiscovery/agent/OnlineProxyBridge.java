package com.localdiscovery.agent;

import java.lang.reflect.Method;

/**
 * 网关代理桥接器
 * 保存运行时下发的网关配置，供 LoadBalancer 拦截层和 Feign Client 拦截层使用
 *
 * 只使用 JDK 类型 + 反射，避免 ClassLoader 隔离问题
 */
public class OnlineProxyBridge {

    private static volatile boolean enabled = false;
    private static volatile String gatewayHost = "";
    private static volatile int gatewayPort = 443;
    private static volatile boolean gatewaySecure = true;
    private static volatile String gatewayUrl = "";

    public static boolean isEnabled() {
        return enabled && !gatewayHost.isEmpty();
    }

    public static String getGatewayHost() {
        return gatewayHost;
    }

    public static int getGatewayPort() {
        return gatewayPort;
    }

    public static boolean isGatewaySecure() {
        return gatewaySecure;
    }

    public static String getGatewayUrl() {
        return gatewayUrl;
    }

    /**
     * 更新网关配置（由 AgentRegistrar 心跳回调调用）
     * gatewayUrl 形如 https://api.prod.com  或 http://api.prod.com:8080
     */
    public static void update(boolean newEnabled, String newGatewayUrl) {
        String url = newGatewayUrl == null ? "" : newGatewayUrl.trim();
        String host = "";
        int port = 443;
        boolean secure = true;
        if (!url.isEmpty()) {
            try {
                String stripped = url;
                if (stripped.startsWith("https://")) {
                    stripped = stripped.substring(8);
                    secure = true;
                    port = 443;
                } else if (stripped.startsWith("http://")) {
                    stripped = stripped.substring(7);
                    secure = false;
                    port = 80;
                }
                // 去掉可能的 path
                int slash = stripped.indexOf('/');
                if (slash > 0) stripped = stripped.substring(0, slash);
                int colon = stripped.indexOf(':');
                if (colon > 0) {
                    host = stripped.substring(0, colon);
                    port = Integer.parseInt(stripped.substring(colon + 1));
                } else {
                    host = stripped;
                }
            } catch (Exception e) {
                System.err.println("[LocalDiscovery] 网关地址解析失败: " + url + " -> " + e.getMessage());
                host = "";
            }
        }
        boolean changed = enabled != newEnabled || !gatewayUrl.equals(url);
        enabled = newEnabled;
        gatewayUrl = url;
        gatewayHost = host;
        gatewayPort = port;
        gatewaySecure = secure;
        if (changed) {
            System.out.println("[LocalDiscovery] 网关代理 -> " + (isEnabled() ? "开启 " + url : "关闭"));
        }
    }

    /**
     * 从当前 HTTP 请求上下文中提取 Authorization
     * 通过反射调用 RequestContextHolder.getRequestAttributes() → getRequest().getHeader("Authorization")
     * 返回 null 表示当前线程没有 Web 请求上下文或没有 Authorization
     */
    public static String currentAuthorization() {
        try {
            Class<?> holder = Class.forName("org.springframework.web.context.request.RequestContextHolder");
            Method getAttrs = holder.getMethod("getRequestAttributes");
            Object attrs = getAttrs.invoke(null);
            if (attrs == null) return null;
            Class<?> servletAttrsClass = Class.forName("org.springframework.web.context.request.ServletRequestAttributes");
            if (!servletAttrsClass.isInstance(attrs)) return null;
            Method getRequest = servletAttrsClass.getMethod("getRequest");
            Object request = getRequest.invoke(attrs);
            if (request == null) return null;
            Method getHeader = request.getClass().getMethod("getHeader", String.class);
            Object auth = getHeader.invoke(request, "Authorization");
            return auth == null ? null : auth.toString();
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
