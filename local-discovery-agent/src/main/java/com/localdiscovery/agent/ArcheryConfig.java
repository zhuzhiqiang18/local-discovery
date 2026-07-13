package com.localdiscovery.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Archery 配置
 *
 * 运行时由 AgentRegistrar 心跳下发（-D 系统属性方式已废弃）：
 *   注册中心控制面板配置全局 URL/Headers/Limit 和每服务的 instance/db
 *   心跳每 5 秒同步一次
 */
public class ArcheryConfig {

    private static volatile boolean enabled;
    private static volatile String baseUrl = "";
    private static volatile String instance = "";
    private static volatile String database = "";
    private static volatile int rowLimit = 1000;
    private static volatile Map<String, String> headers = Collections.emptyMap();

    public static boolean isEnabled() {
        return enabled && !baseUrl.isEmpty() && !instance.isEmpty();
    }

    public static String getBaseUrl()  { return baseUrl; }
    public static String getInstance() { return instance; }
    public static String getDatabase() { return database; }
    public static int    getRowLimit() { return rowLimit; }
    public static Map<String, String> getHeaders() { return headers; }

    /**
     * 心跳下发的完整配置更新
     * 变化时打日志，未变化保持静默
     */
    public static void update(boolean newEnabled, String newUrl, String newInstance,
                              String newDatabase, String newHeaders, int newLimit) {
        String url = normalize(newUrl);
        String inst = safe(newInstance);
        String db = safe(newDatabase);
        Map<String, String> hdrs = parseHeaders(newHeaders);
        int lim = newLimit > 0 ? newLimit : 1000;

        boolean changed = enabled != newEnabled
                || !baseUrl.equals(url)
                || !instance.equals(inst)
                || !database.equals(db)
                || !headers.equals(hdrs)
                || rowLimit != lim;

        enabled = newEnabled;
        baseUrl = url;
        instance = inst;
        database = db;
        headers = hdrs;
        rowLimit = lim;

        if (changed) {
            System.out.println("[LocalDiscovery] Archery -> " + (isEnabled() ? "启用" : "关闭")
                    + " url=" + url + " instance=" + inst + " db=" + db + " limit=" + lim);
        }
    }

    private static String normalize(String u) {
        if (u == null) return "";
        String s = u.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 解析 headers 配置，兼容：
     *   1) HTTP 头风格：Name:Value;Name:Value
     *   2) Cookie 风格：k=v; k=v          （分号分隔）
     *   3) Cookie 风格：k=v, k=v          （逗号分隔，Chrome DevTools 复制常见）
     *   4) 混合行分隔（换行/回车）
     * 所有无冒号的 `k=v` 片段统一归入一个 Cookie 头，用标准 "; " 拼接。
     */
    private static Map<String, String> parseHeaders(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        StringBuilder cookieAgg = new StringBuilder();
        // 顶层按 ; , \n \r 拆分
        for (String pair : raw.split("[;,\\r\\n]")) {
            String p = pair.trim();
            if (p.isEmpty()) continue;
            int colon = p.indexOf(':');
            int equal = p.indexOf('=');
            if (colon > 0 && (equal < 0 || colon < equal)) {
                String name = p.substring(0, colon).trim();
                String value = p.substring(colon + 1).trim();
                if ("cookie".equalsIgnoreCase(name)) {
                    appendCookies(cookieAgg, value);
                } else {
                    map.put(name, value);
                }
            } else if (equal > 0) {
                if (cookieAgg.length() > 0) cookieAgg.append("; ");
                cookieAgg.append(p);
            }
        }
        if (cookieAgg.length() > 0) {
            map.put("Cookie", cookieAgg.toString());
        }
        return map;
    }

    /** 把一整段 Cookie 字符串（可能含 ; 或 , 分隔的多个 k=v）标准化拼接到 agg */
    private static void appendCookies(StringBuilder agg, String cookieStr) {
        for (String part : cookieStr.split("[;,]")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (agg.length() > 0) agg.append("; ");
            agg.append(p);
        }
    }
}
