package com.localdiscovery.registry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;

/**
 * 注册中心配置持久化
 *
 * 持久化内容：Archery 全局配置、Archery 每服务项目配置、网关代理配置
 * 存储位置：~/.local-registry/config.properties
 *
 * 服务实例（registry ConcurrentHashMap）不持久化，重启后靠心跳自然重建。
 */
public class ConfigStore {

    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".local-registry");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");

    /** 启动时加载配置到 Registry 单例 */
    public static synchronized void load() {
        if (!Files.exists(CONFIG_FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("[ConfigStore] 加载失败: " + e.getMessage());
            return;
        }

        Registry r = Registry.getInstance();

        // 网关代理
        boolean proxyEnabled = "true".equals(p.getProperty("proxy.enabled", "false"));
        String gatewayUrl = p.getProperty("proxy.gatewayUrl", "");
        if (proxyEnabled || !gatewayUrl.isEmpty()) {
            r.loadOnlineProxy(proxyEnabled, gatewayUrl);
        }

        // Archery 全局
        boolean archeryEnabled = "true".equals(p.getProperty("archery.enabled", "false"));
        String archeryUrl = p.getProperty("archery.url", "");
        String archeryHeaders = p.getProperty("archery.headers", "");
        int archeryLimit = parseInt(p.getProperty("archery.limit"), 1000);
        r.loadArcheryGlobal(archeryEnabled, archeryUrl, archeryHeaders, archeryLimit);

        // Archery 每服务项目：archery.project.<serviceId>.instanceName / dbName / enabled
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("archery.project.")) continue;
            String rest = key.substring("archery.project.".length());
            int dot = rest.lastIndexOf('.');
            if (dot <= 0) continue;
            String serviceId = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            Registry.ArcheryProject proj = r.getOrCreateArcheryProject(serviceId);
            switch (field) {
                case "instanceName" -> proj.instanceName = p.getProperty(key, "");
                case "dbName"       -> proj.dbName = p.getProperty(key, "");
                case "enabled"      -> proj.enabled = "true".equals(p.getProperty(key, "true"));
            }
        }

        System.out.println("[ConfigStore] 已加载: " + CONFIG_FILE);
    }

    /** 变更后立即落盘 */
    public static synchronized void save() {
        Registry r = Registry.getInstance();
        Properties p = new Properties();

        p.setProperty("proxy.enabled", String.valueOf(r.isOnlineProxyEnabled()));
        p.setProperty("proxy.gatewayUrl", r.getGatewayUrl());

        p.setProperty("archery.enabled", String.valueOf(r.isArcheryEnabled()));
        p.setProperty("archery.url", r.getArcheryUrl());
        p.setProperty("archery.headers", r.getArcheryHeaders());
        p.setProperty("archery.limit", String.valueOf(r.getArcheryLimit()));

        for (Map.Entry<String, Registry.ArcheryProject> e : r.getAllArcheryProjects().entrySet()) {
            String prefix = "archery.project." + e.getKey() + ".";
            p.setProperty(prefix + "instanceName", e.getValue().instanceName);
            p.setProperty(prefix + "dbName", e.getValue().dbName);
            p.setProperty(prefix + "enabled", String.valueOf(e.getValue().enabled));
        }

        try {
            Files.createDirectories(CONFIG_DIR);
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
                p.store(out, "local-registry config");
            }
        } catch (IOException ex) {
            System.err.println("[ConfigStore] 持久化失败: " + ex.getMessage());
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
