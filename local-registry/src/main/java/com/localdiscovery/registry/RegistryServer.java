package com.localdiscovery.registry;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 注册中心 HTTP 服务
 * 提供服务注册/注销/心跳/查询 API + 控制面板
 */
public class RegistryServer {

    private static String panelHtml;

    public static void start(int port) {
        try (InputStream is = RegistryServer.class.getResourceAsStream("/panel.html")) {
            if (is != null) {
                panelHtml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                panelHtml = "<h1>面板资源加载失败</h1>";
            }
        } catch (Exception e) {
            panelHtml = "<h1>面板资源加载失败: " + e.getMessage() + "</h1>";
        }

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new HttpServerCodec(),
                                    new HttpObjectAggregator(65536),
                                    new ApiHandler()
                            );
                        }
                    });
            System.out.println("[Registry] HTTP 服务启动在端口: " + port);
            b.bind(port).sync().channel().closeFuture().sync();
        } catch (Exception e) {
            System.err.println("[Registry] 启动失败: " + e.getMessage());
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    static class ApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            HttpMethod method = request.method();

            try {
                switch (path) {
                    // ====== 面板 ======
                    case "/", "/index.html" -> sendHtml(ctx, panelHtml);

                    // ====== 注册 API ======
                    case "/api/register" -> {
                        if (method == HttpMethod.POST) handleRegister(ctx, request);
                        else sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"POST only\"}");
                    }
                    case "/api/deregister" -> {
                        if (method == HttpMethod.POST) handleDeregister(ctx, request);
                        else sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"POST only\"}");
                    }
                    case "/api/heartbeat" -> {
                        if (method == HttpMethod.POST) handleHeartbeat(ctx, request);
                        else sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"POST only\"}");
                    }

                    // ====== 查询 API ======
                    case "/api/instances" -> handleGetInstances(ctx, uri);
                    case "/api/services" -> handleGetServices(ctx);
                    case "/api/all" -> handleGetAll(ctx);

                    // ====== 管理 API ======
                    case "/api/toggle" -> {
                        if (method == HttpMethod.POST) handleToggle(ctx, request);
                        else sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"POST only\"}");
                    }
                    case "/api/toggleMq" -> {
                        if (method == HttpMethod.POST) handleToggleMq(ctx, request);
                        else sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"POST only\"}");
                    }
                    case "/api/onlineProxy" -> {
                        if (method == HttpMethod.GET) handleGetOnlineProxy(ctx);
                        else if (method == HttpMethod.POST) handleUpdateOnlineProxy(ctx, request);
                        else sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"GET/POST only\"}");
                    }

                    // ====== Archery 全局配置 ======
                    case "/api/archery" -> {
                        if (method == HttpMethod.GET) handleGetArchery(ctx);
                        else if (method == HttpMethod.POST) handleUpdateArchery(ctx, request);
                        else sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"GET/POST only\"}");
                    }
                    // ====== Archery 每服务项目配置 ======
                    case "/api/archeryProject" -> {
                        if (method == HttpMethod.POST) handleUpdateArcheryProject(ctx, request);
                        else if (method == HttpMethod.DELETE) handleDeleteArcheryProject(ctx, request);
                        else sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"POST/DELETE only\"}");
                    }
                    case "/api/archeryProjects" -> handleListArcheryProjects(ctx);

                    default -> sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"not found\"}");
                }
            } catch (Exception e) {
                sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }

        // ====== 注册 ======
        private void handleRegister(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, String> params = parseBody(request);
            String serviceId = params.get("serviceId");
            String host = params.get("host");
            String portStr = params.get("port");
            if (serviceId == null || host == null || portStr == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"缺少 serviceId/host/port\"}");
                return;
            }
            int port = Integer.parseInt(portStr);
            // metadata: 除 serviceId/host/port 外的字段都当 metadata
            Map<String, String> metadata = new HashMap<>(params);
            metadata.remove("serviceId");
            metadata.remove("host");
            metadata.remove("port");

            Registry.ServiceInstance inst = Registry.getInstance().register(serviceId, host, port, metadata);
            sendJson(ctx, HttpResponseStatus.OK,
                    "{\"success\":true,\"instanceId\":\"" + inst.getInstanceId() + "\"}");
        }

        // ====== 注销 ======
        private void handleDeregister(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, String> params = parseBody(request);
            String serviceId = params.get("serviceId");
            String host = params.get("host");
            String portStr = params.get("port");
            if (serviceId == null || host == null || portStr == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"缺少 serviceId/host/port\"}");
                return;
            }
            boolean ok = Registry.getInstance().deregister(serviceId, host, Integer.parseInt(portStr));
            sendJson(ctx, HttpResponseStatus.OK, "{\"success\":" + ok + "}");
        }

        // ====== 心跳 ======
        private void handleHeartbeat(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, String> params = parseBody(request);
            String serviceId = params.get("serviceId");
            String host = params.get("host");
            String portStr = params.get("port");
            if (serviceId == null || host == null || portStr == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"缺少 serviceId/host/port\"}");
                return;
            }
            int port = Integer.parseInt(portStr);
            boolean ok = Registry.getInstance().heartbeat(serviceId, host, port);
            // 心跳响应带回 mqEnabled + 网关代理配置，让 Agent 感知变化
            Boolean mqEnabled = Registry.getInstance().isMqEnabled(serviceId, host, port);
            String mqField = mqEnabled != null ? ",\"mqEnabled\":" + mqEnabled : "";
            String proxyField = ",\"onlineProxyEnabled\":" + Registry.getInstance().isOnlineProxyEnabled()
                    + ",\"gatewayUrl\":\"" + escapeJson(Registry.getInstance().getGatewayUrl()) + "\"";
            // Archery：全局开关 && 项目已配置 && 项目开关，任一为假 → archeryEnabled=false
            Registry.ArcheryProject proj = Registry.getInstance().getArcheryProject(serviceId);
            boolean archeryOn = Registry.getInstance().isArcheryEnabled()
                    && proj != null && proj.enabled
                    && !Registry.getInstance().getArcheryUrl().isEmpty()
                    && !proj.instanceName.isEmpty();
            String archeryField = ",\"archeryEnabled\":" + archeryOn
                    + ",\"archeryUrl\":\"" + escapeJson(Registry.getInstance().getArcheryUrl()) + "\""
                    + ",\"archeryInstance\":\"" + escapeJson(proj != null ? proj.instanceName : "") + "\""
                    + ",\"archeryDatabase\":\"" + escapeJson(proj != null ? proj.dbName : "") + "\""
                    + ",\"archeryHeaders\":\"" + escapeJson(Registry.getInstance().getArcheryHeaders()) + "\""
                    + ",\"archeryLimit\":" + Registry.getInstance().getArcheryLimit();
            sendJson(ctx, HttpResponseStatus.OK, "{\"success\":" + ok + mqField + proxyField + archeryField + "}");
        }

        // ====== 查询某服务的实例 ======
        private void handleGetInstances(ChannelHandlerContext ctx, String uri) {
            String serviceId = getQueryParam(uri, "serviceId");
            if (serviceId == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"缺少 serviceId 参数\"}");
                return;
            }
            List<Registry.ServiceInstance> instances = Registry.getInstance().getInstances(serviceId);
            sendJson(ctx, HttpResponseStatus.OK, instancesToJson(instances));
        }

        // ====== 查询所有服务名 ======
        private void handleGetServices(ChannelHandlerContext ctx) {
            Set<String> services = Registry.getInstance().getServiceIds();
            String json = "[" + services.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]";
            sendJson(ctx, HttpResponseStatus.OK, json);
        }

        // ====== 查询所有实例 ======
        private void handleGetAll(ChannelHandlerContext ctx) {
            List<Registry.ServiceInstance> all = Registry.getInstance().getAllInstances();
            String json = "{\"totalServices\":" + Registry.getInstance().totalServices()
                    + ",\"totalInstances\":" + Registry.getInstance().totalInstances()
                    + ",\"instances\":" + instancesToJson(all) + "}";
            sendJson(ctx, HttpResponseStatus.OK, json);
        }

        // ====== 启用/停用 ======
        private void handleToggle(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, String> params = parseBody(request);
            String serviceId = params.get("serviceId");
            String host = params.get("host");
            String portStr = params.get("port");
            String enabledStr = params.get("enabled");
            if (serviceId == null || host == null || portStr == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"缺少参数\"}");
                return;
            }
            boolean enabled = "true".equals(enabledStr);
            boolean ok = Registry.getInstance().toggleInstance(serviceId, host, Integer.parseInt(portStr), enabled);
            sendJson(ctx, HttpResponseStatus.OK, "{\"success\":" + ok + "}");
        }

        // ====== 启用/停用 MQ 消费 ======
        private void handleToggleMq(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, String> params = parseBody(request);
            String serviceId = params.get("serviceId");
            String host = params.get("host");
            String portStr = params.get("port");
            String mqEnabledStr = params.get("mqEnabled");
            if (serviceId == null || host == null || portStr == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"缺少参数\"}");
                return;
            }
            boolean mqEnabled = "true".equals(mqEnabledStr);
            boolean ok = Registry.getInstance().toggleMq(serviceId, host, Integer.parseInt(portStr), mqEnabled);
            sendJson(ctx, HttpResponseStatus.OK, "{\"success\":" + ok + "}");
        }

        // ====== 查询网关代理配置 ======
        private void handleGetOnlineProxy(ChannelHandlerContext ctx) {
            sendJson(ctx, HttpResponseStatus.OK,
                    "{\"enabled\":" + Registry.getInstance().isOnlineProxyEnabled()
                            + ",\"gatewayUrl\":\"" + escapeJson(Registry.getInstance().getGatewayUrl()) + "\"}");
        }

        // ====== 更新网关代理配置 ======
        private void handleUpdateOnlineProxy(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, String> params = parseBody(request);
            String enabledStr = params.get("enabled");
            String gatewayUrl = params.get("gatewayUrl");
            boolean enabled = "true".equals(enabledStr);
            Registry.getInstance().updateOnlineProxy(enabled, gatewayUrl);
            sendJson(ctx, HttpResponseStatus.OK,
                    "{\"success\":true,\"enabled\":" + Registry.getInstance().isOnlineProxyEnabled()
                            + ",\"gatewayUrl\":\"" + escapeJson(Registry.getInstance().getGatewayUrl()) + "\"}");
        }

        // ====== Archery 全局配置：查询 ======
        private void handleGetArchery(ChannelHandlerContext ctx) {
            Registry r = Registry.getInstance();
            sendJson(ctx, HttpResponseStatus.OK,
                    "{\"enabled\":" + r.isArcheryEnabled()
                            + ",\"url\":\"" + escapeJson(r.getArcheryUrl()) + "\""
                            + ",\"headers\":\"" + escapeJson(r.getArcheryHeaders()) + "\""
                            + ",\"limit\":" + r.getArcheryLimit() + "}");
        }

        // ====== Archery 全局配置：更新 ======
        private void handleUpdateArchery(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, String> params = parseBody(request);
            Boolean enabled = params.containsKey("enabled") ? "true".equals(params.get("enabled")) : null;
            String url = params.get("url");
            String headers = params.get("headers");
            Integer limit = null;
            if (params.get("limit") != null && !params.get("limit").isBlank()) {
                try { limit = Integer.parseInt(params.get("limit").trim()); } catch (NumberFormatException ignore) {}
            }
            Registry.getInstance().updateArcheryGlobal(enabled, url, headers, limit);
            handleGetArchery(ctx);
        }

        // ====== Archery 项目配置：新增/更新 ======
        private void handleUpdateArcheryProject(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, String> params = parseBody(request);
            String serviceId = params.get("serviceId");
            if (serviceId == null || serviceId.isBlank()) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"缺少 serviceId\"}");
                return;
            }
            String instanceName = params.get("instanceName");
            String dbName = params.get("dbName");
            Boolean enabled = params.containsKey("enabled") ? "true".equals(params.get("enabled")) : null;
            Registry.getInstance().updateArcheryProject(serviceId, instanceName, dbName, enabled);
            sendJson(ctx, HttpResponseStatus.OK, "{\"success\":true}");
        }

        // ====== Archery 项目配置：删除 ======
        private void handleDeleteArcheryProject(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, String> params = parseBody(request);
            String serviceId = params.get("serviceId");
            if (serviceId == null || serviceId.isBlank()) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"缺少 serviceId\"}");
                return;
            }
            boolean ok = Registry.getInstance().removeArcheryProject(serviceId);
            sendJson(ctx, HttpResponseStatus.OK, "{\"success\":" + ok + "}");
        }

        // ====== Archery 项目配置：列表 ======
        private void handleListArcheryProjects(ChannelHandlerContext ctx) {
            Map<String, Registry.ArcheryProject> all = Registry.getInstance().getAllArcheryProjects();
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Registry.ArcheryProject> e : all.entrySet()) {
                if (!first) json.append(",");
                first = false;
                Registry.ArcheryProject p = e.getValue();
                json.append("\"").append(escapeJson(e.getKey())).append("\":{")
                        .append("\"instanceName\":\"").append(escapeJson(p.instanceName)).append("\",")
                        .append("\"dbName\":\"").append(escapeJson(p.dbName)).append("\",")
                        .append("\"enabled\":").append(p.enabled)
                        .append("}");
            }
            json.append("}");
            sendJson(ctx, HttpResponseStatus.OK, json.toString());
        }

        // ====== 工具方法 ======

        private String instancesToJson(List<Registry.ServiceInstance> instances) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Registry.ServiceInstance inst : instances) {
                if (!first) json.append(",");
                first = false;
                json.append("{\"serviceId\":\"").append(inst.getServiceId())
                        .append("\",\"host\":\"").append(inst.getHost())
                        .append("\",\"port\":").append(inst.getPort())
                        .append(",\"instanceId\":\"").append(inst.getInstanceId())
                        .append("\",\"uri\":\"").append(inst.getUri())
                        .append("\",\"enabled\":").append(inst.isEnabled())
                        .append(",\"mqEnabled\":").append(inst.isMqEnabled())
                        .append(",\"lastHeartbeat\":").append(inst.getLastHeartbeat())
                        .append(",\"expired\":").append(inst.isExpired())
                        .append("}");
            }
            json.append("]");
            return json.toString();
        }

        private String getQueryParam(String uri, String name) {
            int idx = uri.indexOf('?');
            if (idx < 0) return null;
            String query = uri.substring(idx + 1);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        private Map<String, String> parseBody(FullHttpRequest request) {
            String body = request.content().toString(StandardCharsets.UTF_8);
            Map<String, String> params = new HashMap<>();
            if (body.isBlank()) return params;

            if (body.startsWith("{")) {
                body = body.replaceAll("[{}\"]", "");
                for (String pair : body.split(",")) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        params.put(kv[0].trim(), kv[1].trim());
                    }
                }
            } else {
                for (String pair : body.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        params.put(
                                URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                                URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                        );
                    }
                }
            }
            return params;
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private void sendHtml(ChannelHandlerContext ctx, String html) {
            ByteBuf buf = Unpooled.copiedBuffer(html, StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
            ByteBuf buf = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
