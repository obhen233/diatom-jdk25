package com.github.obhen233.core.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway 级联路径映射。
 * <p>
 * 维护 gatewayId → workspacePath 映射表，支持多级 Gateway 级联场景。
 * 子 Gateway 向父 Gateway 注册自己的路径，父 Gateway 可据此构建全局路径上下文链。
 * </p>
 *
 * <pre>
 * 用法：
 *   // 本 Gateway 注册自己
 *   GatewayPathMapper.register("gateway-a", "/data/workspace", "http://gateway-a:8080", null);
 *
 *   // 子 Gateway 注册（在子 Gateway 上注册父 Gateway）
 *   GatewayPathMapper.register("gateway-parent", "/data/parent-ws", "http://parent:8080", "gateway-a");
 * </pre>
 */
public class GatewayPathMapper {
    private static final Logger logger = LoggerFactory.getLogger(GatewayPathMapper.class);

    private static final ConcurrentHashMap<String, GatewayPathEntry> pathMap = new ConcurrentHashMap<>();

    private GatewayPathMapper() {}

    /**
     * 注册一个 Gateway 的路径映射。
     *
     * @param gatewayId   Gateway 唯一标识
     * @param workspacePath 该 Gateway 的工作目录
     * @param baseUrl     Gateway 的访问地址
     * @param parentGatewayId 父 Gateway ID（级联场景），无则为 null
     */
    public static void register(String gatewayId, String workspacePath, String baseUrl, String parentGatewayId) {
        if (gatewayId == null || gatewayId.isEmpty()) {
            logger.warn("GatewayPathMapper: cannot register with null/empty gatewayId");
            return;
        }
        GatewayPathEntry entry = new GatewayPathEntry(gatewayId, workspacePath, baseUrl, parentGatewayId);
        pathMap.put(gatewayId, entry);
        logger.info("GatewayPathMapper registered: id={}, workspace={}, parent={}", gatewayId, workspacePath, parentGatewayId);
    }

    /**
     * 注销一个 Gateway 的路径映射。
     */
    public static void unregister(String gatewayId) {
        if (gatewayId != null) {
            pathMap.remove(gatewayId);
            logger.info("GatewayPathMapper unregistered: id={}", gatewayId);
        }
    }

    /**
     * 根据 gatewayId 获取 workspacePath。
     */
    public static String getWorkspacePath(String gatewayId) {
        GatewayPathEntry entry = pathMap.get(gatewayId);
        return entry != null ? entry.workspacePath : null;
    }

    /**
     * 根据 gatewayId 获取 Gateway 的 baseUrl。
     */
    public static String getBaseUrl(String gatewayId) {
        GatewayPathEntry entry = pathMap.get(gatewayId);
        return entry != null ? entry.baseUrl : null;
    }

    /**
     * 获取指定 gatewayId 的父 Gateway ID。
     */
    public static String getParentGatewayId(String gatewayId) {
        GatewayPathEntry entry = pathMap.get(gatewayId);
        return entry != null ? entry.parentGatewayId : null;
    }

    /**
     * 构建从当前 Gateway 到根 Gateway 的完整路径链。
     * 返回的数组第一个元素是最上层（根）Gateway，最后一个元素是当前 Gateway。
     * <p>
     * 例如：["root-gateway", "sub-gateway", "leaf-gateway"]
     *
     * @param leafGatewayId 最下层的 Gateway ID
     * @return 从根到叶的 Gateway ID 数组，如果找不到则返回仅包含 leafGatewayId 的数组
     */
    public static String[] buildPathChain(String leafGatewayId) {
        java.util.ArrayList<String> chain = new java.util.ArrayList<>();
        String current = leafGatewayId;
        while (current != null && pathMap.containsKey(current)) {
            chain.add(0, current); // prepend
            GatewayPathEntry entry = pathMap.get(current);
            current = entry.parentGatewayId;
        }
        return chain.toArray(new String[0]);
    }

    /**
     * 获取所有已注册的 Gateway 路径映射表（只读快照）。
     */
    public static Map<String, GatewayPathEntry> getAllEntries() {
        return new ConcurrentHashMap<>(pathMap);
    }

    /**
     * 清除所有注册信息（主要用于测试或重启场景）。
     */
    public static void clear() {
        pathMap.clear();
        logger.info("GatewayPathMapper cleared");
    }

    /**
     * Gateway 路径条目
     */
    public static class GatewayPathEntry {
        private final String gatewayId;
        private final String workspacePath;
        private final String baseUrl;
        private final String parentGatewayId;

        public GatewayPathEntry(String gatewayId, String workspacePath, String baseUrl, String parentGatewayId) {
            this.gatewayId = gatewayId;
            this.workspacePath = workspacePath;
            this.baseUrl = baseUrl;
            this.parentGatewayId = parentGatewayId;
        }

        public String getGatewayId() { return gatewayId; }
        public String getWorkspacePath() { return workspacePath; }
        public String getBaseUrl() { return baseUrl; }
        public String getParentGatewayId() { return parentGatewayId; }
    }
}
