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
     * 心跳，返回注册中心下发的 mqEnabled 状态
     * null 表示心跳失败，true/false 表示 MQ 启用/停用
     */
    public Boolean heartbeat(String serviceId, String host, int port) {
        String body = "serviceId=" + encode(serviceId) + "&host=" + encode(host) + "&port=" + port;
        return postWithMqStatus("/api/heartbeat", body);
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
     * POST 并解析响应中的 mqEnabled 字段
     * 返回 null 表示请求失败
     */
    private Boolean postWithMqStatus(String path, String body) {
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
                    // 解析 {"success":true,"mqEnabled":false}
                    if (response.contains("\"mqEnabled\":false")) {
                        return Boolean.FALSE;
                    }
                    return Boolean.TRUE;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
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
