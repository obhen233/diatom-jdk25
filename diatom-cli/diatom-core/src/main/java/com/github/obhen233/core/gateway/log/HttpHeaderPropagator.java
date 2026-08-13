package com.github.obhen233.core.gateway.log;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 请求头透传
 * 关联 ID 通过 HTTP 请求头在 Gateway 和 Worker 之间传递
 */
public class HttpHeaderPropagator {

    /** Gateway 入口注入的请求头 */
    public static final String HEADER_TASK_ID = "X-Diatom-Task-Id";
    public static final String HEADER_SESSION_ID = "X-Diatom-Session-Id";
    public static final String HEADER_INSTANCE_ID = "X-Diatom-Instance-Id";

    /**
     * 注入当前上下文的追踪头
     */
    public static Map<String, String> injectHeaders() {
        Map<String, String> headers = new HashMap<>();
        String instanceId = System.getProperty("diatom.instance.id", "unknown");
        headers.put(HEADER_INSTANCE_ID, instanceId);
        return headers;
    }

    /**
     * 从请求头中提取关联 ID
     */
    public static CorrelationContext extractFromHeaders(Map<String, String> headers) {
        CorrelationContext ctx = new CorrelationContext();
        ctx.taskId = headers.get(HEADER_TASK_ID);
        ctx.sessionId = headers.get(HEADER_SESSION_ID);
        ctx.instanceId = headers.get(HEADER_INSTANCE_ID);
        return ctx;
    }

    public static class CorrelationContext {
        public String taskId;
        public String sessionId;
        public String instanceId;
    }
}
