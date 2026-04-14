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
 * 1. 拦截 SpringApplication.run() 获取服务名和端口
 * 2. 自动注册到本地注册中心 + 心跳
 * 3. 拦截 DiscoveryClient.getInstances() → 先查注册中心，没有则穿透 Nacos
 * 4. 拦截 TomcatWebServer.start() → 端口管理
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

        // 设置桥接器：DiscoveryClient 拦截 → 查注册中心
        DiscoveryBridge.instanceLookup = serviceId -> registryClient.getInstances(serviceId);

        // 设置桥接器：Tomcat 启动完成 → 注册 PortManager
        TomcatBridge.tomcatCallback = tomcatWebServer -> {
            PortManager.getInstance().registerTomcat(tomcatWebServer);
        };

        // 设置桥接器：Spring 应用就绪 → 自动注册到注册中心
        EnvironmentBridge.onApplicationReady = info -> {
            String appName = info[0];
            int port = Integer.parseInt(info[1]);
            System.out.println("[LocalDiscovery] 检测到应用: " + appName + ", 端口: " + port);
            registrar.register(appName, port);
        };

        // ====== ByteBuddy 拦截 ======

        // 1. 拦截 SpringApplication.run() → 获取应用名和端口
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(ElementMatchers.nameStartsWith("com.localdiscovery"))
                .type(ElementMatchers.named("org.springframework.boot.SpringApplication"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(SpringApplicationInterceptor.class)
                                .on(ElementMatchers.named("run")
                                        .and(ElementMatchers.takesArguments(String[].class))
                                        .and(ElementMatchers.isStatic().or(ElementMatchers.not(ElementMatchers.isStatic())))))
                )
                .installOn(inst);

        // 2. 拦截 DiscoveryClient.getInstances() → 查注册中心
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(ElementMatchers.nameStartsWith("com.localdiscovery"))
                .type(ElementMatchers.hasSuperType(
                                ElementMatchers.named("org.springframework.cloud.client.discovery.DiscoveryClient"))
                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.localdiscovery")))
                        .and(ElementMatchers.not(ElementMatchers.isInterface()))
                )
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(DiscoveryClientInterceptor.class)
                                .on(ElementMatchers.named("getInstances")
                                        .and(ElementMatchers.takesArguments(1))
                                        .and(ElementMatchers.takesArgument(0, String.class))))
                )
                .installOn(inst);

        // 3. 拦截 TomcatWebServer.start() → 端口管理
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("org.springframework.boot.web.embedded.tomcat.TomcatWebServer"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(TomcatInterceptor.class)
                                .on(ElementMatchers.named("start")
                                        .and(ElementMatchers.takesArguments(0))))
                )
                .installOn(inst);

        System.out.println("[LocalDiscovery] Agent 初始化完成");
        System.out.println("[LocalDiscovery] 注册中心: " + registryUrl);
    }
}
