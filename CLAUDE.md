# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

零侵入本地微服务注册发现工具。通过 Java Agent + ByteBuddy 字节码增强拦截 Spring Cloud `DiscoveryClient.getInstances()`，本地服务优先走本地注册中心，找不到则穿透到远端注册中心（Nacos/Eureka/Consul）。无需修改业务代码、POM 或配置文件。

## 构建

```bash
mvn clean package -DskipTests
```

产出：
- `local-registry/target/local-registry-1.0.0.jar` — 本地注册中心（独立进程）
- `local-discovery-agent/target/local-discovery-agent-1.0.0.jar` — Java Agent

## 运行

```bash
# 启动注册中心（默认 9527 端口）
java -jar local-registry/target/local-registry-1.0.0.jar

# 给微服务挂 Agent
java -javaagent:./local-discovery-agent/target/local-discovery-agent-1.0.0.jar -jar your-service.jar

# 自定义注册中心地址
java -javaagent:./local-discovery-agent-1.0.0.jar=http://localhost:19527 -jar your-service.jar
```

控制面板：http://localhost:9527

## 技术栈

- Java 17, Maven 多模块
- Netty 4.1（注册中心 HTTP 服务器）
- ByteBuddy 1.14（字节码增强）
- SnakeYAML 2.2
- spring-cloud-commons 4.1.4（仅 `provided` 作用域，编译时类型匹配用）
- Agent HTTP 客户端使用纯 JDK `HttpURLConnection`，零外部依赖

## 架构

两个模块：

**local-registry** — 独立进程，Netty HTTP 服务器 + 内存注册表（`ConcurrentHashMap`）。5 秒驱逐定时器，15 秒无心跳自动下线。REST API + Web 控制面板（`panel.html`）。

**local-discovery-agent** — Java Agent（`premain` 入口）。通过 ByteBuddy 拦截 `SpringApplication.run()` 和 `TomcatWebServer.start()`。核心机制：Spring 上下文就绪后，创建 JDK 动态代理包装 `DiscoveryClient` bean，`getInstances()` 先查本地注册中心，没有则走原始 DiscoveryClient。

### ClassLoader 隔离方案（Bridge 模式）

Agent 类由 AppClassLoader 加载，Spring Cloud 类由应用 ClassLoader 加载。ByteBuddy Advice 代码内联到目标类，直接引用 Agent 类会 `ClassNotFoundException`。

解决方案：`DiscoveryBridge` / `TomcatBridge` / `EnvironmentBridge` 仅暴露 JDK 类型（`Function<String, List<String[]>>`、`Consumer`），Advice 调用桥接器静态方法，通过反射创建 `DefaultServiceInstance`。

### 依赖隔离

maven-shade-plugin 将 `net.bytebuddy` 重定位到 `com.localdiscovery.shaded.bytebuddy`，避免与宿主应用冲突。

## 注册中心 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/register` | 注册实例 |
| POST | `/api/deregister` | 注销实例 |
| POST | `/api/heartbeat` | 心跳续约 |
| GET | `/api/instances?serviceId=X` | 查询服务实例 |
| GET | `/api/services` | 所有服务名 |
| GET | `/api/all` | 全部实例+统计 |
| POST | `/api/toggle` | 启用/停用实例 |

## 注意事项

- 项目无测试用例
- `DiscoveryClientInterceptor.java` 是遗留的直接拦截方案，当前架构使用 `SpringApplicationInterceptor` 中的 JDK 动态代理方案
- Agent 心跳间隔 5 秒，注册中心过期阈值 15 秒
- `RegistryClient` 使用 2 秒连接/读取超时
