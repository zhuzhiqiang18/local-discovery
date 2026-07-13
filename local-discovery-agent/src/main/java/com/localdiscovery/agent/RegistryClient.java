package com.localdiscovery.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 注册中心 HTTP 客户端
 * 纯 JDK HttpURLConnection 实现，零外部依赖
 */
public class RegistryClient {

    /** 心跳响应：包含注册中心下发的运行时配置 */
    public static class HeartbeatResult {
        public final boolean mqEnabled;
        public final boolean onlineProxyEnabled;
        public final String gatewayUrl;
        // Archery 转发配置
        public final boolean archeryEnabled;
        public final String archeryUrl;
        public final String archeryInstance;
        public final String archeryDatabase;
        public final String archeryHeaders;
        public final int archeryLimit;

        public HeartbeatResult(boolean mqEnabled, boolean onlineProxyEnabled, String gatewayUrl,
                               boolean archeryEnabled, String archeryUrl, String archeryInstance,
                               String archeryDatabase, String archeryHeaders, int archeryLimit) {
            this.mqEnabled = mqEnabled;
            this.onlineProxyEnabled = onlineProxyEnabled;
            this.gatewayUrl = gatewayUrl;
            this.archeryEnabled = archeryEnabled;
            this.archeryUrl = archeryUrl;
            this.archeryInstance = archeryInstance;
            this.archeryDatabase = archeryDatabase;
            this.archeryHeaders = archeryHeaders;
            this.archeryLimit = archeryLimit;
        }
    }

    private final String registryUrl;

    public RegistryClient(String registryUrl) {
        // 去掉尾部斜杠
        this.registryUrl = registryUrl.endsWith("/") ? registryUrl.substring(0, registryUrl.length() - 1) : registryUrl;
    }

    /**
     * 注册服务实例
     */
    public boolean register(String serviceId, String host, int port) {
        String body = "serviceId=" + encode(serviceId) + "&host=" + encode(host) + "&port=" + port;
        return post("/api/register", body);
    }

    /**
     * 注销服务实例
     */
    public boolean deregister(String serviceId, String host, int port) {
        String body = "serviceId=" + encode(serviceId) + "&host=" + encode(host) + "&port=" + port;
        return post("/api/deregister", body);
    }

    /**
     * 心跳，返回注册中心下发的运行时配置
     * null 表示心跳失败
     */
    public HeartbeatResult heartbeat(String serviceId, String host, int port) {
        String body = "serviceId=" + encode(serviceId) + "&host=" + encode(host) + "&port=" + port;
        return postHeartbeat("/api/heartbeat", body);
    }

    /**
     * 查询服务实例
     * 返回 List<String[]>，每个元素是 {host, port, secure}
     * 空 List 表示注册中心没有此服务（穿透 Nacos）
     */
    public List<String[]> getInstances(String serviceId) {
        try {
            String response = get("/api/instances?serviceId=" + encode(serviceId));
            if (response == null || response.equals("[]")) {
                return Collections.emptyList();
            }
            return parseInstances(response);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 检查注册中心是否可达
     */
    public boolean isAvailable() {
        try {
            String response = get("/api/services");
            return response != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ====== HTTP 工具方法 ======

    private boolean post(String path, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(registryUrl + path).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return conn.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST 心跳并解析响应中的运行时配置
     * 返回 null 表示请求失败
     */
    private HeartbeatResult postHeartbeat(String path, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(registryUrl + path).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    boolean mqEnabled = !response.contains("\"mqEnabled\":false");
                    boolean proxyEnabled = response.contains("\"onlineProxyEnabled\":true");
                    String gatewayUrl = extractJsonString(response, "gatewayUrl");
                    boolean archeryEnabled = response.contains("\"archeryEnabled\":true");
                    String archeryUrl = extractJsonString(response, "archeryUrl");
                    String archeryInstance = extractJsonString(response, "archeryInstance");
                    String archeryDatabase = extractJsonString(response, "archeryDatabase");
                    String archeryHeaders = extractJsonString(response, "archeryHeaders");
                    int archeryLimit = extractJsonInt(response, "archeryLimit", 1000);
                    return new HeartbeatResult(mqEnabled, proxyEnabled, gatewayUrl,
                            archeryEnabled, archeryUrl, archeryInstance, archeryDatabase,
                            archeryHeaders, archeryLimit);
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) return "";
        int start = i + needle.length();
        int end = json.indexOf('"', start);
        if (end < 0) return "";
        return json.substring(start, end);
    }

    /** 提取 "key":number；缺失或解析失败返回 defaultValue */
    private static int extractJsonInt(String json, String key, int defaultValue) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return defaultValue;
        int start = i + needle.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if ((c >= '0' && c <= '9') || c == '-') end++;
            else break;
        }
        if (end == start) return defaultValue;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String get(String path) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(registryUrl + path).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 简单解析 JSON 数组: [{"host":"...", "port":..., ...}, ...]
     */
    private List<String[]> parseInstances(String json) {
        List<String[]> result = new ArrayList<>();
        // 按 } 分割每个对象
        String[] objects = json.split("\\},?\\s*\\{?");
        for (String obj : objects) {
            obj = obj.replaceAll("[\\[\\]{}]", "");
            Map<String, String> fields = new HashMap<>();
            // 匹配 "key":value 或 "key":"value"
            String[] pairs = obj.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replaceAll("\"", "");
                    String value = kv[1].trim().replaceAll("\"", "");
                    fields.put(key, value);
                }
            }
            String host = fields.get("host");
            String port = fields.get("port");
            if (host != null && port != null) {
                result.add(new String[]{host, port, "false"});
            }
        }
        return result;
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
