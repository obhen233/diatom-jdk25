package com.github.obhen233.core.gateway.log;

import org.slf4j.MDC;

/**
 * 日志关联（Log Correlation）
 * 跨 Gateway 和 Worker 调试问题时，通过 taskId 贯穿两个系统的日志
 *
 * 使用方式：
 * <pre>
 *   LogCorrelationFilter.put(request.getTaskId(), request.getSessionId(), "gateway");
 *   // ... do work ...
 *   LogCorrelationFilter.clear();
 * </pre>
 */
public class LogCorrelationFilter {

    private static final String TASK_ID_KEY = "taskId";
    private static final String SESSION_ID_KEY = "sessionId";
    private static final String ROLE_KEY = "role";
    private static final String INSTANCE_ID_KEY = "instanceId";

    /**
     * Gateway 侧：收到用户请求时注入 MDC
     */
    public static void putGatewayContext(String taskId, String sessionId) {
        MDC.put(TASK_ID_KEY, taskId != null ? taskId : "none");
        MDC.put(SESSION_ID_KEY, sessionId != null ? sessionId : "none");
        MDC.put(ROLE_KEY, "gateway");
        MDC.put(INSTANCE_ID_KEY, System.getProperty("diatom.instance.id", "unknown"));
    }

    /**
     * Worker 侧：收到 Gateway 转发请求时注入 MDC
     */
    public static void putWorkerContext(String taskId, String sessionId, String gatewayId) {
        MDC.put(TASK_ID_KEY, taskId != null ? taskId : "none");
        MDC.put(SESSION_ID_KEY, sessionId != null ? sessionId : "none");
        MDC.put(ROLE_KEY, "worker");
        MDC.put(INSTANCE_ID_KEY, System.getProperty("diatom.instance.id", "unknown"));
        if (gatewayId != null) {
            MDC.put("gatewayId", gatewayId);
        }
    }

    /**
     * 通用上下文注入
     */
    public static void put(String taskId, String sessionId, String role) {
        if (taskId != null) MDC.put(TASK_ID_KEY, taskId);
        if (sessionId != null) MDC.put(SESSION_ID_KEY, sessionId);
        if (role != null) MDC.put(ROLE_KEY, role);
        MDC.put(INSTANCE_ID_KEY, System.getProperty("diatom.instance.id", "unknown"));
    }

    /**
     * 清除 MDC 上下文
     */
    public static void clear() {
        MDC.clear();
    }
}
