package com.localdiscovery.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * Java Agent 入口
 *
 * 用法: -javaagent:local-discovery-agent.jar[=registryUrl]
 * 默认注册中心地址: http://localhost:9527
 *
 * 启动后自动完成:
 * 1. 拦截 SpringApplication.run() → Spring 启动完成后提取应用信息并自动注册
 * 2. 拦截 TomcatWebServer.start() → 端口管理
 * 3. 拦截 BlockingLoadBalancerClient.choose() → Feign/RestTemplate 本地优先
 * 4. 拦截 RoundRobinLoadBalancer.choose() → Gateway/WebClient 响应式路径本地优先
 */
public class AgentMain {

    private static RegistryClient registryClient;
    private static AgentRegistrar registrar;

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[LocalDiscovery] Agent 启动中...");

        // 解析参数：注册中心地址，默认 http://localhost:9527
        String registryUrl = "http://localhost:9527";
        if (agentArgs != null && !agentArgs.isBlank()) {
            registryUrl = agentArgs.trim();
        }

        registryClient = new RegistryClient(registryUrl);
        registrar = new AgentRegistrar(registryClient);

        // 设置桥接器：查注册中心
        DiscoveryBridge.instanceLookup = serviceId -> registryClient.getInstances(serviceId);

        // 设置桥接器：Tomcat 启动完成 → 注册 PortManager
        TomcatBridge.tomcatCallback = tomcatWebServer -> {
            PortManager.getInstance().registerTomcat(tomcatWebServer);
        };

        // 设置桥接器：Spring 应用就绪 → 自动注册
        EnvironmentBridge.onApplicationReady = info -> {
            // info = {appName, port, applicationContext 对象的类名（仅用于日志）}
            String appName = info[0];
            int port = Integer.parseInt(info[1]);
            System.out.println("[LocalDiscovery] 检测到应用: " + appName + ", 端口: " + port);
            registrar.register(appName, port);
        };

        // ====== ByteBuddy 拦截 ======

        AgentBuilder.Listener safeListener = new AgentBuilder.Listener.Adapter() {
            @Override
            public void onError(String typeName, ClassLoader classLoader,
                                net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable throwable) {
                System.err.println("[LocalDiscovery] 拦截失败: " + typeName + " -> " + throwable.getMessage());
                throwable.printStackTrace();
            }
        };

        // 1. 拦截 SpringApplication.run() → Spring 启动完成后提取应用信息并自动注册
        new AgentBuilder.Default()
                .with(safeListener)
                .ignore(ElementMatchers.nameStartsWith("com.localdiscovery"))
                .type(ElementMatchers.named("org.springframework.boot.SpringApplication"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(SpringApplicationInterceptor.class)
                                .on(ElementMatchers.named("run")
                                        .and(ElementMatchers.takesArguments(String[].class))))
                )
                .installOn(inst);

        // 2. 拦截 TomcatWebServer.start() → 端口管理
        new AgentBuilder.Default()
                .with(safeListener)
                .type(ElementMatchers.named("org.springframework.boot.web.embedded.tomcat.TomcatWebServer"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(TomcatInterceptor.class)
                                .on(ElementMatchers.named("start")
                                        .and(ElementMatchers.takesArguments(0))))
                )
                .installOn(inst);

        // 3. 拦截 BlockingLoadBalancerClient.choose() → Feign/RestTemplate 本地优先
        new AgentBuilder.Default()
                .with(safeListener)
                .type(ElementMatchers.named("org.springframework.cloud.loadbalancer.blocking.client.BlockingLoadBalancerClient"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(LoadBalancerInterceptor.class)
                                .on(ElementMatchers.named("choose")
                                        .and(ElementMatchers.takesArguments(2))
                                        .and(ElementMatchers.takesArgument(0, String.class))))
                )
                .installOn(inst);

        // 4. 拦截 RoundRobinLoadBalancer.choose() → Gateway/WebClient 响应式路径本地优先
        // 自适应：类不存在时 safeListener 静默处理，不影响阻塞式路径
        new AgentBuilder.Default()
                .with(safeListener)
                .type(ElementMatchers.named("org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ReactiveLoadBalancerInterceptor.class)
                                .on(ElementMatchers.named("choose")))
                )
                .installOn(inst);

        new AgentBuilder.Default()
                .with(safeListener)
                .type(ElementMatchers.named("org.springframework.cloud.loadbalancer.core.RandomLoadBalancer"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(ReactiveLoadBalancerInterceptor.class)
                                .on(ElementMatchers.named("choose")))
                )
                .installOn(inst);

        // 6. 拦截 DefaultRocketMQListenerContainer.start() → 捕获 container 引用，用于 MQ 消费控制
        new AgentBuilder.Default()
                .with(safeListener)
                .type(ElementMatchers.named("org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RocketMqInterceptor.class)
                                .on(ElementMatchers.named("start")
                                        .and(ElementMatchers.takesArguments(0))))
                )
                .installOn(inst);

        // 8. JDBC 查询转发到 Archery
        // 拦截器无条件挂载；实际是否转发由 ArcheryConfig.isEnabled() 运行时判定
        // （由 AgentRegistrar 心跳从注册中心下发）
        installJdbcInterceptors(inst, safeListener);

        // 7. 拦截 feign.Client.execute() → 走网关的请求注入 Authorization
        // 只匹配 feign.* 包下的具体 Client 实现，避开 Spring 的包装类（RetryableFeignBlockingLoadBalancerClient 等）
        // 这些包装类会引入 spring-retry 等可选依赖，若 classpath 里没有会导致 TypePool 解析失败
        new AgentBuilder.Default()
                .with(safeListener)
                .type(ElementMatchers.nameStartsWith("feign.")
                        .and(ElementMatchers.failSafe(ElementMatchers.hasSuperType(ElementMatchers.named("feign.Client"))))
                        .and(ElementMatchers.not(ElementMatchers.isInterface()))
                        .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(FeignClientInterceptor.class)
                                .on(ElementMatchers.named("execute")
                                        .and(ElementMatchers.takesArguments(2))))
                )
                .installOn(inst);

        System.out.println("[LocalDiscovery] Agent 初始化完成");
        System.out.println("[LocalDiscovery] 注册中心: " + registryUrl);
    }

    /**
     * 挂 JDBC 查询拦截：
     *   - Connection.prepareStatement(String,...) → 记录 SQL
     *   - PreparedStatement.setXxx(int, ?) → 记录参数
     *   - PreparedStatement.executeQuery() / Statement.executeQuery(String) → 转发到 Archery
     *
     * 通过 hasSuperType 匹配所有实现类（MySQL/PostgreSQL/Druid 包装类等）
     * SqlBridge 内 ThreadLocal 重入保护防止嵌套触发
     */
    private static void installJdbcInterceptors(Instrumentation inst, AgentBuilder.Listener listener) {
        // Connection.prepareStatement(String, ...) — 记录 SQL
        new AgentBuilder.Default()
                .with(listener)
                .ignore(ElementMatchers.nameStartsWith("com.localdiscovery"))
                .type(ElementMatchers.not(ElementMatchers.isInterface())
                        .and(ElementMatchers.failSafe(ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.Connection")))))
                .transform((builder, td, cl, m, pd) ->
                        builder.visit(Advice.to(JdbcInterceptor.PrepareStatementAdvice.class)
                                .on(ElementMatchers.named("prepareStatement")
                                        .and(ElementMatchers.takesArgument(0, String.class))
                                        .and(ElementMatchers.isPublic())))
                )
                .installOn(inst);

        // PreparedStatement.setXxx(int, ?) — 记录参数；execute() / executeQuery() — 拦截
        new AgentBuilder.Default()
                .with(listener)
                .ignore(ElementMatchers.nameStartsWith("com.localdiscovery"))
                .type(ElementMatchers.not(ElementMatchers.isInterface())
                        .and(ElementMatchers.failSafe(ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.PreparedStatement")))))
                .transform((builder, td, cl, m, pd) ->
                        builder
                                .visit(Advice.to(JdbcInterceptor.SetParamAdvice.class)
                                        .on(ElementMatchers.nameStartsWith("set")
                                                .and(ElementMatchers.takesArguments(2))
                                                .and(ElementMatchers.takesArgument(0, int.class))
                                                .and(ElementMatchers.isPublic())))
                                .visit(Advice.to(JdbcInterceptor.ExecuteQueryPreparedAdvice.class)
                                        .on(ElementMatchers.named("executeQuery")
                                                .and(ElementMatchers.takesArguments(0))
                                                .and(ElementMatchers.isPublic())))
                                .visit(Advice.to(JdbcInterceptor.ExecutePreparedAdvice.class)
                                        .on(ElementMatchers.named("execute")
                                                .and(ElementMatchers.takesArguments(0))
                                                .and(ElementMatchers.isPublic())))
                )
                .installOn(inst);

        // Statement.executeQuery(String) / execute(String) — 无占位符路径
        // Statement.getResultSet() — MyBatis/Hibernate 走 execute()+getResultSet() 时取暂存的 Archery ResultSet
        new AgentBuilder.Default()
                .with(listener)
                .ignore(ElementMatchers.nameStartsWith("com.localdiscovery"))
                .type(ElementMatchers.not(ElementMatchers.isInterface())
                        .and(ElementMatchers.failSafe(ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.Statement")))))
                .transform((builder, td, cl, m, pd) ->
                        builder
                                .visit(Advice.to(JdbcInterceptor.ExecuteQueryStatementAdvice.class)
                                        .on(ElementMatchers.named("executeQuery")
                                                .and(ElementMatchers.takesArguments(1))
                                                .and(ElementMatchers.takesArgument(0, String.class))
                                                .and(ElementMatchers.isPublic())))
                                .visit(Advice.to(JdbcInterceptor.ExecuteStatementAdvice.class)
                                        .on(ElementMatchers.named("execute")
                                                .and(ElementMatchers.takesArguments(1))
                                                .and(ElementMatchers.takesArgument(0, String.class))
                                                .and(ElementMatchers.isPublic())))
                                .visit(Advice.to(JdbcInterceptor.GetResultSetAdvice.class)
                                        .on(ElementMatchers.named("getResultSet")
                                                .and(ElementMatchers.takesArguments(0))
                                                .and(ElementMatchers.isPublic())))
                )
                .installOn(inst);

        System.out.println("[LocalDiscovery] JDBC 查询转发已挂载");
    }
}
