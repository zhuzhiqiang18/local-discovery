package com.localdiscovery.agent;

import java.util.List;
import java.util.function.Function;

/**
 * 桥接器：Advice 内联代码通过此类查询注册中心
 * 只使用 JDK 类型，避免 ClassLoader 问题
 *
 * 返回 List<String[]>（每个元素是 {host, port, secure}），空 List 表示放行到 Nacos
 */
public class DiscoveryBridge {

    public static volatile Function<String, List<String[]>> instanceLookup;

    /**
     * 查询本地注册中心的服务实例
     * @return null 或空 List 表示放行到原始 DiscoveryClient（Nacos）
     */
    public static List<String[]> lookup(String serviceId) {
        Function<String, List<String[]>> fn = instanceLookup;
        if (fn == null) return null;
        List<String[]> result = fn.apply(serviceId);
        if (result == null || result.isEmpty()) return null;
        return result;
    }
}
