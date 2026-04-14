# Local Discovery

**零侵入的本地微服务注册发现工具** —— 不改代码、不改 POM、不改配置，本地开发微服务像单体一样简单。

## 解决什么问题

微服务开发中，你经常遇到这种场景：

> 我在本地同时开发 service-a 和 service-b，它们之间有 Feign 调用。但注册中心在远端（Nacos），我每次改完代码都要**发布到预发环境**才能验证调用是否正常。

Local Discovery 让你 **本地启动的服务自动互相发现**，找不到的服务自动穿透到远端 Nacos，**零配置、零侵入、热生效**。

## 工作原理

```
                     ┌─────────────────────────┐
                     │   Local Registry        │
                     │   http://localhost:9527  │
                     │                         │
                     │   service-a → :8081  ♥  │ ← 自动注册 + 心跳
                     │   service-b → :8082  ♥  │ ← 自动注册 + 心跳
                     └──────┬──────────┬───────┘
                        查询│          │查询
              ┌─────────────┘          └──────────────┐
              ▼                                        ▼
    ┌─────────────────┐                     ┌─────────────────┐
    │ service-a :8081 │   Feign / RestTpl   │ service-b :8082 │
    │ + Agent (无侵入) │ ─────────────────→  │ + Agent (无侵入) │
    └────────┬────────┘    localhost:8082    └─────────────────┘
             │
             │ 调用 service-c（本地没启动）
             └──→ 注册中心没有 → 放行 → Nacos → 预发环境 service-c
```

**Agent 通过 Java 字节码增强拦截 `DiscoveryClient.getInstances()`**：
1. 先查本地注册中心 → 有就返回本地实例
2. 没有 → 原方法继续执行 → 走 Nacos

你的代码**完全不知道** Agent 的存在。Feign、RestTemplate、Gateway 全部正常工作。

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- Spring Cloud 项目（任意注册中心：Nacos / Eureka / Consul 均可）

### 构建

```bash
git clone https://github.com/yourname/local-discovery.git
cd local-discovery
mvn clean package -DskipTests
```

产出两个 jar：

| 文件 | 说明 |
|------|------|
| `local-registry/target/local-registry-1.0.0.jar` | 本地注册中心（独立进程） |
| `local-discovery-agent/target/local-discovery-agent-1.0.0.jar` | Java Agent（挂到每个服务） |

### 1. 启动注册中心

```bash
java -jar local-registry-1.0.0.jar
```

打开浏览器访问 **http://localhost:9527** 查看控制面板。

自定义端口：

```bash
java -jar local-registry-1.0.0.jar 19527
```

### 2. 给微服务挂 Agent

**IDEA**：Run Configuration → VM Options 加一行：

```
-javaagent:D:/tools/local-discovery-agent-1.0.0.jar
```

**命令行**：

```bash
java -javaagent:./local-discovery-agent-1.0.0.jar -jar your-service.jar
```

注册中心不在默认端口时：

```bash
-javaagent:./local-discovery-agent-1.0.0.jar=http://localhost:19527
```

### 3. 完事了

启动你的微服务，观察控制台输出：

```
[LocalDiscovery] Agent 启动中...
[LocalDiscovery] Agent 初始化完成
[LocalDiscovery] 注册中心: http://localhost:9527
[LocalDiscovery] 检测到应用: service-a, 端口: 8081
[LocalDiscovery] 已注册到本地注册中心: service-a -> localhost:8081
```

控制面板自动显示已注册的服务：

```
┌────────────────────────────────────────────────────┐
│  Local Discovery Registry          服务: 2  实例: 2│
├──────────┬──────────────────┬──────┬───────────────┤
│ 服务名    │ 地址              │ 状态  │ 心跳          │
├──────────┼──────────────────┼──────┼───────────────┤
│ service-a│ localhost:8081   │ 在线  │ 2秒前 ████░   │
│ service-b│ localhost:8082   │ 在线  │ 1秒前 █████   │
└──────────┴──────────────────┴──────┴───────────────┘
```

## 核心特性

### 零侵入

| 维度 | 是否需要改动 |
|------|-------------|
| 业务代码 | **不需要** |
| pom.xml | **不需要** |
| 配置文件 | **不需要** |
| 启动类 | **不需要** |
| Feign 注解 | **不需要** |

只需在 JVM 启动参数加一行 `-javaagent`。删掉这行参数，一切恢复原样。

### 自动注册 & 发现

- Agent 自动读取 `spring.application.name` 和 `server.port`
- 启动后自动注册到本地注册中心
- 每 5 秒心跳，15 秒无心跳自动下线
- JVM 关闭时自动注销（ShutdownHook）

### Nacos 穿透

本地注册中心没有的服务，**自动穿透到远端注册中心**（Nacos / Eureka / Consul）。

```
service-a 调用 service-b → 本地注册中心有 → localhost:8082 ✅
service-a 调用 service-c → 本地注册中心没有 → Nacos 接管   ✅
```

你只需要本地启动正在开发的服务，其余服务照常走远端。

### 端口热切换

Agent 内置 `PortManager`，支持运行时给 Tomcat 动态增删监听端口。无需重启即可让同一服务在多个端口提供服务。

### 控制面板

注册中心自带 Web 控制面板（`http://localhost:9527`）：

- 实时查看所有已注册的服务实例
- 心跳状态可视化（进度条 + 倒计时）
- 手动注册 / 注销 / 启用 / 停用实例
- 每 2 秒自动刷新

## 项目结构

```
local-discovery/
├── local-registry/                          ← 本地注册中心（独立进程）
│   └── src/main/java/.../registry/
│       ├── RegistryMain.java                # 启动入口
│       ├── Registry.java                    # 注册表 + 心跳检测 + 过期清理
│       └── RegistryServer.java              # Netty HTTP API + 控制面板
│
├── local-discovery-agent/                   ← Java Agent（挂到微服务上）
│   └── src/main/java/.../agent/
│       ├── AgentMain.java                   # premain 入口
│       ├── AgentRegistrar.java              # 自动注册 + 心跳上报
│       ├── RegistryClient.java              # 注册中心 HTTP 客户端
│       ├── DiscoveryClientInterceptor.java  # 拦截 DiscoveryClient
│       ├── SpringApplicationInterceptor.java# 拦截 SpringApplication.run()
│       ├── TomcatInterceptor.java           # 拦截 Tomcat 启动
│       ├── PortManager.java                 # 端口热切换
│       ├── DiscoveryBridge.java             # 桥接器（解决 ClassLoader 隔离）
│       ├── TomcatBridge.java                # 桥接器
│       └── EnvironmentBridge.java           # 桥接器
│
└── pom.xml                                  # 父 POM
```

## API 文档

注册中心暴露以下 HTTP API：

### 服务注册

```
POST /api/register
Content-Type: application/x-www-form-urlencoded

serviceId=service-a&host=localhost&port=8081
```

### 服务注销

```
POST /api/deregister
Content-Type: application/x-www-form-urlencoded

serviceId=service-a&host=localhost&port=8081
```

### 心跳

```
POST /api/heartbeat
Content-Type: application/x-www-form-urlencoded

serviceId=service-a&host=localhost&port=8081
```

### 查询服务实例

```
GET /api/instances?serviceId=service-a
```

响应：

```json
[
  {
    "serviceId": "service-a",
    "host": "localhost",
    "port": 8081,
    "instanceId": "service-a-localhost-8081",
    "uri": "http://localhost:8081",
    "enabled": true,
    "lastHeartbeat": 1713066891000,
    "expired": false
  }
]
```

### 查询所有服务

```
GET /api/services
```

### 查询全部实例

```
GET /api/all
```

### 启用/停用实例

```
POST /api/toggle
Content-Type: application/x-www-form-urlencoded

serviceId=service-a&host=localhost&port=8081&enabled=false
```

## 技术实现

### 为什么用 Java Agent 而不是 Starter？

| 方案 | 改代码 | 改 POM | 改配置 | 通用性 |
|------|--------|--------|--------|--------|
| Spring Boot Starter | 否 | **要** | **要** | 仅 Spring Cloud |
| **Java Agent** | **否** | **否** | **否** | **任意 Spring Cloud 项目** |

Agent 通过 ByteBuddy 字节码增强，在 JVM 层面拦截关键方法，对应用完全透明。

### ClassLoader 隔离

Agent 的类由 AppClassLoader 加载，而 Spring Cloud 的类由应用的 ClassLoader 加载。ByteBuddy Advice 代码会被内联到目标类中，直接引用 Agent 类会导致 `ClassNotFoundException`。

解决方案：**Bridge 模式**。Agent 核心逻辑通过 `DiscoveryBridge`、`TomcatBridge`、`EnvironmentBridge` 暴露为 `Function` / `Consumer`，Advice 代码只调用桥接器的静态方法（JDK 类型），通过反射创建 Spring Cloud 的 `DefaultServiceInstance`。

### 依赖隔离

Agent 的 ByteBuddy 和 Netty 依赖通过 maven-shade-plugin relocate，避免与宿主应用的同名依赖冲突：

```
net.bytebuddy → com.localdiscovery.shaded.bytebuddy
```

## FAQ

### Q: 注册中心挂了怎么办？

Agent 会每 5 秒重试注册。服务发现查询失败时返回空结果，自动穿透到 Nacos，**不影响正常调用**。

### Q: 支持哪些注册中心？

穿透机制与注册中心无关。只要你用的是 Spring Cloud 的 `DiscoveryClient` 体系（Nacos / Eureka / Consul / Zookeeper），都支持。

### Q: 多人开发会冲突吗？

注册中心运行在你本机，每个人有自己独立的注册中心。不会互相干扰。

### Q: 性能影响？

Agent 只拦截 `getInstances()` 方法，查询本地注册中心是 localhost HTTP 调用（<1ms）。对业务接口**零性能影响**。

### Q: 线上会不会误用？

不加 `-javaagent` 参数就完全没有 Agent 的存在。即使误加了，注册中心没启动时 Agent 查询失败，自动穿透到 Nacos，**不影响线上行为**。

## 开源协议

[Apache License 2.0](LICENSE)
