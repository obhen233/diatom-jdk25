package com.github.obhen233.core.gateway.http.docs;

import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Runtime registry that scans {@link GatewayApi} annotations from {@code GatewayHttpServer}
 * and builds OpenAPI 3.0 specification JSON.
 * <p>
 * Scans handler methods at initialization, collects all API metadata, and
 * serves the OpenAPI JSON via {@link #toOpenApiJson()}.
 */
public class ApiDocRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ApiDocRegistry.class);

    private final Map<String, Map<String, Map<String, Object>>> paths = new LinkedHashMap<>();
    private final List<Map<String, Object>> tagsList = new ArrayList<>();
    private final Map<String, List<Map<String, Object>>> tagGroups = new LinkedHashMap<>();
    private volatile String cachedOpenApiJson;

    /**
     * Initialize by scanning the given instance for methods annotated with {@link GatewayApi}.
     * Clears any previously scanned data.
     */
    public void scan(Object instance) {
        paths.clear();
        tagsList.clear();
        tagGroups.clear();
        cachedOpenApiJson = null;
        doScan(instance.getClass());
    }

    /**
     * Scan a class directly (without instance) for {@link GatewayApi} annotations.
     * Clears any previously scanned data.
     */
    public void scanClass(Class<?> clazz) {
        paths.clear();
        tagsList.clear();
        tagGroups.clear();
        cachedOpenApiJson = null;
        doScan(clazz);
    }

    /**
     * Scan additional instance without clearing existing data.
     */
    public void scanAdditional(Object instance) {
        cachedOpenApiJson = null;
        doScan(instance.getClass());
        rebuildTags();
    }

    /**
     * Scan additional class without clearing existing data.
     */
    public void scanAdditionalClass(Class<?> clazz) {
        cachedOpenApiJson = null;
        doScan(clazz);
        rebuildTags();
    }

    private void doScan(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            GatewayApi api = method.getAnnotation(GatewayApi.class);
            if (api == null) continue;

            String path = api.path();
            for (String httpMethod : api.methods()) {
                String methodLower = httpMethod.toLowerCase();

                Map<String, Object> operation = new LinkedHashMap<>();
                operation.put("summary", api.summary());
                operation.put("description", api.description());
                operation.put("tags", Arrays.asList(api.tags()));

                // Parameters - extract path params like {workerId} from path
                List<Map<String, Object>> params = new ArrayList<>();
                String[] segments = path.split("/");
                for (String seg : segments) {
                    if (seg.startsWith("{") && seg.endsWith("}")) {
                        String paramName = seg.substring(1, seg.length() - 1);
                        Map<String, Object> param = new LinkedHashMap<>();
                        param.put("name", paramName);
                        param.put("in", "path");
                        param.put("required", true);
                        param.put("schema", Collections.singletonMap("type", "string"));
                        param.put("description", paramName);
                        params.add(param);
                    }
                }
                if (!params.isEmpty()) {
                    operation.put("parameters", params);
                }

                // Auth requirement
                operation.put("x-auth-required", api.authRequired());

                // Request body
                if (!api.requestBody().isEmpty()) {
                    Map<String, Object> requestBody = new LinkedHashMap<>();
                    requestBody.put("required", true);
                    Map<String, Object> content = new LinkedHashMap<>();
                    Map<String, Object> mediaType = new LinkedHashMap<>();
                    mediaType.put("schema", Collections.singletonMap("type", "string"));
                    mediaType.put("example", api.requestBody());
                    content.put(api.contentType(), mediaType);
                    requestBody.put("content", content);
                    operation.put("requestBody", requestBody);
                }

                // Responses
                Map<String, Object> responses = new LinkedHashMap<>();
                Map<String, Object> okResponse = new LinkedHashMap<>();
                okResponse.put("description", "OK");
                if (!api.responseBody().isEmpty()) {
                    Map<String, Object> content = new LinkedHashMap<>();
                    Map<String, Object> mediaType = new LinkedHashMap<>();
                    mediaType.put("schema", Collections.singletonMap("type", "string"));
                    mediaType.put("example", api.responseBody());
                    content.put(api.contentType(), mediaType);
                    okResponse.put("content", content);
                }
                responses.put("200", okResponse);
                operation.put("responses", responses);

                // Store
                paths.computeIfAbsent(path, k -> new LinkedHashMap<>())
                        .put(methodLower, operation);

                // Collect tags
                for (String tag : api.tags()) {
                    tagGroups.computeIfAbsent(tag, k -> new ArrayList<>());
                }
            }
        }

        rebuildTags();

        logger.info("ApiDocRegistry scanned {} methods, {} paths, {} tags",
                countMethods(), paths.size(), tagsList.size());
    }

    /**
     * Get the OpenAPI 3.0 specification as a JSON string (alias for toOpenApiJson).
     */
    public String getOpenApiJson() {
        return toOpenApiJson();
    }

    /**
     * Get the OpenAPI 3.0 specification as a JSON string.
     */
    public String toOpenApiJson() {
        if (cachedOpenApiJson != null) return cachedOpenApiJson;

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", "Diatom Gateway API");
        info.put("version", "1.0.0");
        info.put("description", buildInfoDescription());
        spec.put("info", info);

        spec.put("tags", tagsList);

        // Build paths with pathItems
        Map<String, Object> pathsObj = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<String, Object>>> pathEntry : paths.entrySet()) {
            Map<String, Object> pathItem = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> methodEntry : pathEntry.getValue().entrySet()) {
                pathItem.put(methodEntry.getKey(), methodEntry.getValue());
            }
            pathsObj.put(pathEntry.getKey(), pathItem);
        }
        spec.put("paths", pathsObj);

        cachedOpenApiJson = JsonUtils.toJson(spec);
        return cachedOpenApiJson;
    }

    /**
     * Get all unique tags in order.
     */
    public List<String> getTags() {
        return new ArrayList<>(tagGroups.keySet());
    }

    /**
     * Get the raw paths map for custom rendering.
     */
    public Map<String, Map<String, Map<String, Object>>> getPaths() {
        return paths;
    }

    /**
     * Check if any APIs have been registered.
     */
    public boolean isEmpty() {
        return paths.isEmpty();
    }

    private void rebuildTags() {
        tagsList.clear();
        for (String tagName : tagGroups.keySet()) {
            Map<String, Object> tagObj = new LinkedHashMap<>();
            tagObj.put("name", tagName);
            tagsList.add(tagObj);
        }
    }

    private String buildInfoDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Diatom Gateway — API 文档 / API Documentation</h2>");
        sb.append("<p>Gateway 与 Worker 之间通过 HTTP 通信，Worker 可用任何语言实现。");
        sb.append("Communication between Gateway and Worker is via HTTP. Workers can be implemented in any language.</p>");

        // Authentication
        sb.append("<h3 id=\"auth\">鉴权 / Authentication</h3>");
        sb.append("<p>所有 <code>POST</code>/<code>PUT</code>/<code>DELETE</code> 请求均需携带以下安全头（健康检查免鉴权）。");
        sb.append("All <code>POST</code>/<code>PUT</code>/<code>DELETE</code> requests MUST carry security headers (health check exempt).</p>");
        sb.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;margin:8px 0;font-size:13px;\">");
        sb.append("<tr style=\"background:#1e293b;\"><th>Header</th><th>Required</th><th>Description</th></tr>");
        sb.append("<tr><td>X-Diatom-Auth</td><td><strong>必需 / Required</strong></td>");
        sb.append("<td>共享 Token。Gateway 与 Worker 配置相同的 <code>diatom.auth.token</code>。Shared token.</td></tr>");
        sb.append("<tr><td>X-Diatom-Instance-Id</td><td><strong>必需 / Required</strong></td>");
        sb.append("<td>发送方实例 ID，用于日志追踪。Sender instance ID for tracing.</td></tr>");
        sb.append("<tr><td>X-Diatom-Encryption</td><td>可选 / Optional</td>");
        sb.append("<td>Body 加密算法标识，如 AES-256。Body encryption algorithm.</td></tr>");
        sb.append("<tr><td>X-Diatom-Timestamp</td><td>可选 / Optional</td>");
        sb.append("<td>请求时间戳，防重放。Request timestamp for anti-replay.</td></tr>");
        sb.append("</table>");

        // Gateway to Worker
        sb.append("<h3>Gateway → Worker 接口</h3>");
        sb.append("<p>由 Worker 端实现 HTTP 端点，Gateway 作为客户端调用。<br>");
        sb.append("These endpoints are implemented by the Worker. Gateway calls them as an HTTP client.</p>");
        sb.append("<p><strong>必需实现 / Required:</strong> <code>POST /worker/v1/chat</code> 和 <code>GET /worker/v1/health</code>，其余可选。</p>");

        // Worker to Gateway
        sb.append("<h3>Worker → Gateway 接口</h3>");
        sb.append("<p>Worker 主动向 Gateway 发起的请求。Worker 启动后应先注册，然后周期性心跳。<br>");
        sb.append("Requests from Worker to Gateway: register first, then heartbeat periodically.</p>");

        // User to Gateway
        sb.append("<h3>用户 → Gateway 接口 / User to Gateway</h3>");
        sb.append("<p>面向终端用户和第三方集成的 RESTful API。<br>");
        sb.append("RESTful APIs for end users and third-party integration.</p>");

        // Monitor
        sb.append("<h3>Monitor API — 仪表板 / Dashboard</h3>");
        sb.append("<p>Monitor 仪表板的 HTTP API，提供状态监控、配置管理、规则管理和认证等接口。<br>");
        sb.append("HTTP APIs for the Monitor dashboard.</p>");

        // Data Models
        sb.append("<h3 id=\"models\">数据模型 / Data Models</h3>");
        sb.append("<p>所有接口的请求体和响应体均为 JSON 格式。All request and response bodies are in JSON format.</p>");

        sb.append("<h4>WorkerCapabilities</h4>");
        sb.append("<p>Worker 注册时声明自身能力，Gateway 据此进行任务路由。Declares Worker capabilities at registration.</p>");
        sb.append("<pre style=\"background:#0d1117;padding:12px;border-radius:6px;font-size:13px;overflow-x:auto;\">");
        sb.append("{\n");
        sb.append("  \"languages\": [\"java\", \"python\", \"javascript\"],\n");
        sb.append("  \"frameworks\": [\"spring\", \"react\"],\n");
        sb.append("  \"maxContextTokens\": 200000,\n");
        sb.append("  \"supportsStreaming\": true,\n");
        sb.append("  \"models\": [\"claude-sonnet-4-6\"],\n");
        sb.append("  \"features\": [\"thinking\", \"tool_use\", \"file_edit\"]\n");
        sb.append("}</pre>");

        sb.append("<h4>Task</h4>");
        sb.append("<p>Gateway 分发任务时发给 Worker 的完整任务描述。Complete task description sent to Worker.</p>");
        sb.append("<pre style=\"background:#0d1117;padding:12px;border-radius:6px;font-size:13px;overflow-x:auto;\">");
        sb.append("{\n");
        sb.append("  \"taskId\": \"task-123\",\n");
        sb.append("  \"type\": \"coding\",\n");
        sb.append("  \"instruction\": \"add pagination\",\n");
        sb.append("  \"project\": { \"workDir\": \"/workspace/task-123\" },\n");
        sb.append("  \"context\": {\n");
        sb.append("    \"files\": [{\"path\": \"UserController.java\", \"content\": \"...\"}],\n");
        sb.append("    \"constraints\": [\"use Spring Data JPA\"]\n");
        sb.append("  },\n");
        sb.append("  \"timeoutSeconds\": 300\n");
        sb.append("}</pre>");

        sb.append("<h4>TaskResult</h4>");
        sb.append("<p>Worker 执行完成后回调 Gateway 上报的结果。Result reported by Worker.</p>");
        sb.append("<pre style=\"background:#0d1117;padding:12px;border-radius:6px;font-size:13px;overflow-x:auto;\">");
        sb.append("{\n");
        sb.append("  \"taskId\": \"task-123\",\n");
        sb.append("  \"status\": \"success\",\n");
        sb.append("  \"summary\": \"added pagination\",\n");
        sb.append("  \"changes\": [{\n");
        sb.append("    \"file\": \"UserController.java\",\n");
        sb.append("    \"diff\": \"@@ -10,0 +10,28 @@ ...\",\n");
        sb.append("    \"action\": \"modified\"\n");
        sb.append("  }],\n");
        sb.append("  \"elapsedSeconds\": 45,\n");
        sb.append("  \"tokenUsage\": {\"input\": 15000, \"output\": 3200}\n");
        sb.append("}</pre>");

        sb.append("<h4>WorkerEvent (SSE)</h4>");
        sb.append("<p>流式事件，用于实时推送任务进度。Streaming events for real-time task progress.</p>");
        sb.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;margin:8px 0;font-size:13px;\">");
        sb.append("<tr style=\"background:#1e293b;\"><th>Event</th><th>Data Fields</th><th>Description</th></tr>");
        sb.append("<tr><td>thinking</td><td>message</td><td>Agent 思考中 / Agent is thinking</td></tr>");
        sb.append("<tr><td>progress</td><td>phase, progress(0~1)</td><td>执行进度 / Execution progress</td></tr>");
        sb.append("<tr><td>tool_call</td><td>tool, args</td><td>工具调用 / Tool call</td></tr>");
        sb.append("<tr><td>diff</td><td>file, diff</td><td>文件变更 / File change</td></tr>");
        sb.append("<tr><td>error</td><td>code, message</td><td>错误信息 / Error info</td></tr>");
        sb.append("<tr><td>complete</td><td>status, summary</td><td>任务完成 / Task complete</td></tr>");
        sb.append("</table>");

        // Worker Integration Guide
        sb.append("<h3>Worker 接入指南 / Worker Integration Guide</h3>");
        sb.append("<p><strong>接入一个 Worker 只需实现 2 个必需接口：</strong></p>");
        sb.append("<ol>");
        sb.append("<li><code>POST /worker/v1/chat</code> — 接收任务并执行 / Receive and execute tasks</li>");
        sb.append("<li><code>GET /worker/v1/health</code> — 健康检查 / Health check</li>");
        sb.append("</ol>");
        sb.append("<p><strong>完整接入流程 / Full Integration Flow:</strong></p>");
        sb.append("<ol>");
        sb.append("<li>启动 Worker HTTP Server，实现必需接口 / Start Worker HTTP Server, implement required endpoints</li>");
        sb.append("<li>向 Gateway 注册：<code>POST /gateway/v1/workers</code> 携带 WorkerInfo / Register with Gateway</li>");
        sb.append("<li>周期性发送心跳：<code>POST /gateway/v1/workers/{workerId}/heartbeat</code> / Send periodic heartbeats</li>");
        sb.append("<li>Gateway 通过 <code>POST /worker/v1/chat</code> 分发任务 / Gateway dispatches tasks</li>");
        sb.append("<li>可选实现流式输出 <code>GET /worker/v1/chat/stream</code> 支持 SSE / Optional SSE streaming</li>");
        sb.append("<li>任务完成后通过 <code>POST /gateway/v1/chat</code> 回调上报结果 / Report results via callback</li>");
        sb.append("</ol>");
        sb.append("<p>详细端点说明见下方 API 文档。See API documentation below for endpoint details.</p>");
        return sb.toString();
    }

    private int countMethods() {
        int count = 0;
        for (Map<String, Map<String, Object>> pathItem : paths.values()) {
            count += pathItem.size();
        }
        return count;
    }
}
