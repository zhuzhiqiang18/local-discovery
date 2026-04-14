package com.localdiscovery.agent;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 端口管理器
 * 运行时通过反射增删 Tomcat Connector 实现端口热切换
 */
public class PortManager {

    private static final PortManager INSTANCE = new PortManager();

    private volatile Object tomcatService;
    private volatile ClassLoader appClassLoader;
    private volatile int primaryPort;
    private final ConcurrentHashMap<Integer, Object> extraConnectors = new ConcurrentHashMap<>();

    private PortManager() {}

    public static PortManager getInstance() {
        return INSTANCE;
    }

    /**
     * 通过反射注册 TomcatWebServer
     */
    public void registerTomcat(Object webServer) {
        try {
            this.appClassLoader = webServer.getClass().getClassLoader();
            Method getTomcat = webServer.getClass().getMethod("getTomcat");
            Object tomcat = getTomcat.invoke(webServer);
            Method getService = tomcat.getClass().getMethod("getService");
            this.tomcatService = getService.invoke(tomcat);

            // 获取主端口
            Method findConnectors = tomcatService.getClass().getMethod("findConnectors");
            Object[] connectors = (Object[]) findConnectors.invoke(tomcatService);
            if (connectors.length > 0) {
                Method getPort = connectors[0].getClass().getMethod("getPort");
                this.primaryPort = (int) getPort.invoke(connectors[0]);
            }

            System.out.println("[LocalDiscovery] Tomcat 已注册，主端口: " + primaryPort);
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] Tomcat 注册失败: " + e.getMessage());
        }
    }

    public synchronized boolean addPort(int port) {
        if (tomcatService == null) {
            System.err.println("[LocalDiscovery] Tomcat 尚未就绪");
            return false;
        }
        if (port == primaryPort || extraConnectors.containsKey(port)) {
            return false;
        }

        try {
            Class<?> connectorClass = Class.forName(
                    "org.apache.catalina.connector.Connector", true, appClassLoader);
            Object connector = connectorClass.getConstructor(String.class)
                    .newInstance("org.apache.coyote.http11.Http11NioProtocol");

            connectorClass.getMethod("setPort", int.class).invoke(connector, port);
            connectorClass.getMethod("setScheme", String.class).invoke(connector, "http");
            connectorClass.getMethod("setSecure", boolean.class).invoke(connector, false);

            tomcatService.getClass().getMethod("addConnector", connectorClass)
                    .invoke(tomcatService, connector);

            connectorClass.getMethod("start").invoke(connector);

            extraConnectors.put(port, connector);
            System.out.println("[LocalDiscovery] 新增监听端口: " + port);
            return true;
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] 添加端口失败: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean removePort(int port) {
        if (tomcatService == null) return false;

        Object connector = extraConnectors.remove(port);
        if (connector == null) return false;

        try {
            Class<?> connectorClass = Class.forName(
                    "org.apache.catalina.connector.Connector", true, appClassLoader);

            connector.getClass().getMethod("stop").invoke(connector);
            connector.getClass().getMethod("destroy").invoke(connector);
            tomcatService.getClass().getMethod("removeConnector", connectorClass)
                    .invoke(tomcatService, connector);

            System.out.println("[LocalDiscovery] 已移除端口: " + port);
            return true;
        } catch (Exception e) {
            System.err.println("[LocalDiscovery] 移除端口失败: " + e.getMessage());
            return false;
        }
    }

    public int getPrimaryPort() { return primaryPort; }
    public Set<Integer> getExtraPorts() { return Collections.unmodifiableSet(extraConnectors.keySet()); }
    public boolean isReady() { return tomcatService != null; }

    public List<Integer> getAllPorts() {
        List<Integer> ports = new ArrayList<>();
        if (primaryPort > 0) ports.add(primaryPort);
        ports.addAll(extraConnectors.keySet());
        Collections.sort(ports);
        return ports;
    }
}
