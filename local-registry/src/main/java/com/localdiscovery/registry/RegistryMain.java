package com.localdiscovery.registry;

/**
 * 注册中心启动入口
 * 用法: java -jar local-registry.jar [端口号]
 * 默认端口: 9527
 */
public class RegistryMain {

    public static void main(String[] args) {
        int port = 9527;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("无效端口: " + args[0] + "，使用默认端口 9527");
            }
        }

        System.out.println("========================================");
        System.out.println("  Local Discovery Registry v1.0.0");
        System.out.println("========================================");
        System.out.println("  控制面板: http://localhost:" + port);
        System.out.println("  API 地址: http://localhost:" + port + "/api");
        System.out.println("========================================");

        // 启动过期检测
        Registry.getInstance().startEviction();

        // 加载持久化配置（Archery / 网关代理）
        ConfigStore.load();

        // 启动 HTTP 服务（阻塞）
        RegistryServer.start(port);
    }
}
