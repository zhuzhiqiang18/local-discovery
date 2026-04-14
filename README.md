# Local Discovery

**零侵入的本地微服务注册发现工具** —— 不改代码、不改 POM、不改配置，本地开发微服务像单体一样简单。

## 解决什么痛点

微服务本地开发最大的痛苦：

> 我在本地同时开发 service-a 和 service-b，它们之间有 Feign 调用。但注册中心在远端（Nacos），每次改完代码都要**发布到预发环境**才能验证调用是否正常。

更具体地说：

- **调试链路断裂**：本地改了接口，要推到远端才能被其他服务调到，断点调试根本不可能
- **环境抢占**：多人开发同一服务时互相覆盖预发环境，排队等部署
- **反馈周期长**：改一行代码 → 提交 → CI → 部署 → 验证，最快也要几分钟
- **配置侵入**：手动改 Nacos 地址、硬编码 localhost、加 Profile 区分环境，改完忘恢复就炸

Local Discovery 一行 JVM 参数解决所有问题：**本地启动的服务自动互相发现，找不到的服务自动穿透到远端注册中心，零配置、零侵入、热生效**。

## 适用版本与架构

### 环境要求

| 依赖 | 最低版本 | 说明 |
|------|----------|------|
| JDK | 17+ | Agent 和 Registry 均需要 |
| Maven | 3.6+ | 仅构建时需要 |
| Spring Boot | 2.4+ | 被挂载的目标服务 |
| Spring Cloud | 2020.0+ | 使用 Spring Cloud LoadBalancer（非 Ribbon） |

### 注册中心兼容性

与远端注册中心类型**无关**，只要目标服务使用 Spring Cloud 的 `DiscoveryClient` 体系即可：

| 注册中心 | 兼容 |
|----------|------|
| Nacos | ✅ |
| Eureka | ✅ |
| Consul | ✅ |
| Zookeeper | ✅ |

### 调用方式覆盖

| 调用方式 | 拦截点 | 状态 |
|----------|--------|------|
| Feign（`@FeignClient`） | `BlockingLoadBalancerClient.choose()` | ✅ 已支持 |
| `@LoadBalanced` RestTemplate | `BlockingLoadBalancerClient.choose()` | ✅ 已支持 |
| Spring Cloud Gateway | `RoundRobinLoadBalancer.choose()` / `RandomLoadBalancer.choose()` | ✅ 已支持 |
| WebClient（响应式） | `RoundRobinLoadBalancer.choose()` / `RandomLoadBalancer.choose()` | ✅ 已支持 |

> **注意**：Spring Cloud Netflix Ribbon（Spring Cloud 2020.0 之前）使用 `RibbonLoadBalancerClient`，不在拦截范围内。如果你的项目还在用 Ribbon，需要升级到 Spring Cloud LoadBalancer。

## 工作原理

### 整体架构

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
    │ service-a :8081 │   Feign / Gateway   │ service-b :8082 │
    │ + Agent (无侵入) │ ─────────────────→  │ + Agent (无侵入) │
    └────────┬────────┘    localhost:8082    └─────────────────┘
             │
             │ 调用 service-c（本地没启动）
             └──→ 本地注册中心没有 → 放行 → Nacos → 预发环境 service-c
```

### 拦截原理

Agent 通过 ByteBuddy 字节码增强在 **LoadBalancer 层** 拦截服务选择，而非 DiscoveryClient 层（后者有缓存，无法实时响应）。

**阻塞式路径**（Feign / RestTemplate）：

```
Feign.execute()
  → BlockingLoadBalancerClient.choose(serviceId)  ← Agent 在此拦截
      → 查本地注册中心
          → 有本地实例 → 返回 DefaultServiceInstance(localhost:8082)，跳过原方法
          → 无本地实例 → 放行原方法 → 走 Nacos/Eureka/Consul
```

**响应式路径**（Gateway / WebClient）：

```
ReactiveLoadBalancer.choose(request)
  → RoundRobinLoadBalancer.choose()  ← Agent 在此拦截
      → 查本地注册中心
          → 有本地实例 → 返回 Mono.just(DefaultResponse(localhost:8082))，跳过原方法
          → 无本地实例 → 放行原方法 → 走原有负载均衡逻辑
```

### 为什么在 LoadBalancer 层拦截

| 拦截层 | 问题 |
|--------|------|
| DiscoveryClient.getInstances() | 被 `CachingServiceInstanceListSupplier` 缓存（默认 35s），停用再启用无法实时生效 |
| NacosServiceDiscovery.getInstances() | 同上，且绑死 Nacos |
| 替换 BeanFactory 中的 Bean | Feign/LoadBalancer 启动时已持有原始引用，替换无效 |
| **BlockingLoadBalancerClient.choose()** | **每次调用都经过，无缓存，与注册中心类型无关** |
| **RoundRobinLoadBalancer.choose()** | **响应式路径每次调用都经过** |

### ClassLoader 隔离（Bridge 模式）

Agent 类由 AppClassLoader 加载，Spring Cloud 类由应用 ClassLoader 加载。ByteBuddy Advice 代码内联到目标类后，直接引用 Agent 类会导致 `ClassNotFoundException`。

解决方案：`DiscoveryBridge` / `TomcatBridge` / `EnvironmentBridge` 仅暴露 JDK 类型（`Function<String, List<String[]>>`、`Consumer`），Advice 代码通过桥接器静态方法回调 Agent，通过反射创建 `DefaultServiceInstance`。

### 依赖隔离

Agent 的 ByteBuddy 通过 maven-shade-plugin 重定位到 `com.localdiscovery.shaded.bytebuddy`，避免与宿主应用的 ByteBuddy 版本冲突。

## 快速开始

### 构建

```bash
git clone https://github.com/zhuzhiqiang18/local-discovery.git
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

发起 Feign 调用时，日志输出：

```
[LocalDiscovery] service-b → 本地 localhost:8082
```

本地没有的服务自动穿透到远端：

```
service-a 调用 service-b → 本地注册中心有 → localhost:8082 ✅
service-a 调用 service-c → 本地注册中心没有 → Nacos 接管   ✅
```

## 零侵入

| 维度 | 是否需要改动 |
|------|-------------|
| 业务代码 | **不需要** |
| pom.xml | **不需要** |
| 配置文件 | **不需要** |
| 启动类 | **不需要** |
| Feign 注解 | **不需要** |

只需在 JVM 启动参数加一行 `-javaagent`。删掉这行参数，一切恢复原样。

## 核心特性

### 自动注册 & 发现

- Agent 自动读取 `spring.application.name` 和 `server.port`
- 启动后自动注册到本地注册中心
- 每 5 秒心跳，15 秒无心跳自动下线
- JVM 关闭时自动注销（ShutdownHook）

### 远端穿透

本地注册中心没有的服务，**自动穿透到远端注册中心**（Nacos / Eureka / Consul）。不需要配置，不需要区分哪些服务走本地哪些走远端——有就走本地，没有就走远端。

### 控制面板

注册中心自带 Web 控制面板（`http://localhost:9527`）：

- 实时查看所有已注册的服务实例
- 心跳状态可视化（进度条 + 倒计时）
- 手动注册 / 注销 / 启用 / 停用实例
- 每 2 秒自动刷新

### 端口热切换

Agent 内置 `PortManager`，支持运行时给 Tomcat 动态增删监听端口，无需重启。

## 项目结构

```
local-discovery/
├── local-registry/                          ← 本地注册中心（独立进程）
│   └── src/main/java/.../registry/
│       ├── RegistryMain.java                # 启动入口，默认端口 9527
│       ├── Registry.java                    # 内存注册表 + 心跳检测 + 过期清理
│       └── RegistryServer.java              # Netty HTTP API + 控制面板
│
├── local-discovery-agent/                   ← Java Agent（挂到微服务上）
│   └── src/main/java/.../agent/
│       ├── AgentMain.java                   # premain 入口，安装所有 ByteBuddy 拦截
│       ├── AgentRegistrar.java              # 自动注册 + 心跳上报
│       ├── RegistryClient.java              # 注册中心 HTTP 客户端（纯 JDK）
│       ├── LoadBalancerInterceptor.java     # 拦截 BlockingLoadBalancerClient.choose()
│       ├── ReactiveLoadBalancerInterceptor.java # 拦截 RoundRobin/RandomLoadBalancer.choose()
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

## FAQ

### Q: 注册中心挂了怎么办？

Agent 会每 5 秒重试注册。LoadBalancer 拦截查询失败时返回空，自动穿透到远端注册中心，**不影响正常调用**。

### Q: 多人开发会冲突吗？

注册中心运行在你本机，每个人有自己独立的注册中心，不会互相干扰。

### Q: 性能影响？

LoadBalancer 拦截每次 Feign 调用增加一次 localhost HTTP 查询（<1ms）。对业务接口**零感知**。

### Q: 线上会不会误用？

不加 `-javaagent` 参数就完全没有 Agent 的存在。即使误加了，注册中心没启动时查询失败，自动穿透到远端，**不影响线上行为**。

### Q: 停用/启用实例能实时生效吗？

能。Agent 在 LoadBalancer 层拦截（每次调用都经过），不受 Spring Cloud 的服务发现缓存影响，停用/启用即时生效。

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 构建 | Maven | 多模块 |
| 注册中心 HTTP | Netty | 4.1.112 |
| 字节码增强 | ByteBuddy | 1.14.18 |
| YAML 解析 | SnakeYAML | 2.2 |
| Agent HTTP 客户端 | JDK HttpURLConnection | 零外部依赖 |
| 编译时类型参考 | spring-cloud-commons | 4.1.4（provided） |

## 开源协议

[Apache License 2.0](LICENSE)
