package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.quarkus.runtime.components.DiatomRuntimeContext;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import com.github.obhen233.util.JsonUtils;
import com.github.obhen233.util.NetworkUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 子 Gateway 的 Worker 代理端点（JAX-RS，注册到 Quarkus 原生 web 容器）。
 *
 * <p>镜像 starter {@code ChildGatewayWorkerChatController}：当 {@code diatom.mode}
 * 为 child（{@code gateway:child}）时，父 Gateway 通过本端点把任务转发到子 Gateway
 * （当作 Worker 调用）。流程：解析 {@code message} → {@code gatewayAgent.analyzeRequest}
 * → {@code capabilityRouter.routeWithLLMSuggestion} → transport 转发到下挂 worker →
 * 返回 {@code {"status":"ok","taskId","response"}}。gatewayUrl 改写为子节点自身外部 URL。</p>
 */
@Path("/worker/v1/chat")
@ApplicationScoped
public class ChildGatewayResource {

    private static final Logger LOGGER = Logger.getLogger(ChildGatewayResource.class);

    private final DiatomRuntimeContext context;
    private final DiatomRuntimeConfig config;

    @Inject
    public ChildGatewayResource(DiatomRuntimeContext context, DiatomRuntimeConfig config) {
        this.context = context;
        this.config = config;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleWorkerChat(Map<String, Object> body) {
        GatewayAgent gatewayAgent = context.gatewayAgent();
        CapabilityRouter capabilityRouter = context.capabilityRouter();
        WorkerRegistry registry = context.workerRegistry();
        if (gatewayAgent == null || capabilityRouter == null || registry == null) {
            return error(503, "", "Gateway components not available (not child mode)");
        }
        QuarkusGatewayTransport transport = new QuarkusGatewayTransport(registry);

        if (body == null) {
            return error(400, "", "Missing request body");
        }

        Object msgObj = body.get("message");
        String message = msgObj != null ? msgObj.toString() : null;
        String taskId = body.get("taskId") != null ? body.get("taskId").toString() : "";
        if (message == null || message.isEmpty()) {
            return error(400, taskId, "Missing message");
        }

        String sessionId = body.get("sessionId") != null ? body.get("sessionId").toString() : null;
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        }

        String workerId = null;
        try {
            LOGGER.infof("Child gateway worker proxy executing task=%s, message=%s",
                    taskId, truncate(message, 200));

            // 1. 分析请求 → 任务需求
            TaskRequirement requirement = gatewayAgent.analyzeRequest(message);

            // 2. 路由到下挂 worker
            WorkerInfo worker = capabilityRouter.routeWithLLMSuggestion(requirement);
            if (worker == null) {
                return error(503, taskId, "No available workers");
            }
            workerId = worker.getWorkerId();
            capabilityRouter.incrementActive(workerId);

            LOGGER.infof("Child gateway routed to worker %s (type=%s, reasoning=%s)",
                    workerId, requirement.getTaskType(), requirement.getReasoning());

            // 3. 构造转发 body：原 body + taskId/sessionId/workspacePath/syncStrategy，
            //    gatewayUrl 改写为子节点自身外部 URL（让下挂 worker 回连到子而非父）
            String forwardBody = buildForwardBody(body, taskId, sessionId, requirement);
            QuarkusGatewayTransport.HttpResult result = transport.sendChatRequestResult(workerId, forwardBody);

            // 4. Worker 失败（含过载 503）→ 向上游返回 503，父 Gateway 换 worker 重试
            if (result == null || result.getBody() == null) {
                LOGGER.warnf("Downstream worker %s did not respond", workerId);
                return error(503, taskId, "Worker did not respond");
            }
            if (result.isError()) {
                LOGGER.warnf("Downstream worker %s returned HTTP %d: %s",
                        workerId, result.getStatusCode(), truncate(result.getBody(), 200));
                return error(result.isOverloaded() ? 503 : 502, taskId,
                        "Worker returned HTTP " + result.getStatusCode());
            }

            // 5. 提取 response 文本并返回统一格式
            String responseText = extractResponse(result.getBody());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("taskId", taskId);
            response.put("response", responseText);
            LOGGER.infof("Child gateway worker proxy completed via worker %s: task=%s", workerId, taskId);
            return Response.ok(response).build();
        } catch (Exception e) {
            LOGGER.errorf(e, "Child gateway worker proxy failed for task=%s", taskId);
            return error(500, taskId, e.getMessage());
        } finally {
            if (workerId != null) {
                capabilityRouter.decrementActive(workerId);
            }
        }
    }

    /**
     * 构造发送给下挂 worker 的请求体，注入 taskId/sessionId/workspacePath/syncStrategy，
     * 并将 gatewayUrl 改写为子节点自身外部 URL。
     */
    private String buildForwardBody(Map<String, Object> body, String taskId, String sessionId,
                                    TaskRequirement requirement) {
        Map<String, Object> forward = new LinkedHashMap<>();
        if (body != null) {
            forward.putAll(body);
        }
        forward.put("taskId", taskId);
        forward.put("sessionId", sessionId);
        forward.put("workspacePath", System.getProperty("user.dir", "."));
        String syncStrategy = requirement.getSyncStrategy();
        forward.put("syncStrategy", syncStrategy != null ? syncStrategy : "skip");

        // 子节点自身外部 URL：worker 回连本子 Gateway
        String externalHost = NetworkUtils.getRealLocalIP();
        int externalPort = config.gateway().externalPort().orElse(config.gateway().port());
        forward.put("gatewayUrl", "http://" + externalHost + ":" + externalPort);
        return JsonUtils.toJson(forward);
    }

    /**
     * 从 worker 响应体提取文本：优先 {@code response}，其次 {@code result}，最后原样返回。
     */
    private String extractResponse(String workerBody) {
        try {
            Map<String, Object> parsed = JsonUtils.fromJson(workerBody, Map.class);
            if (parsed != null) {
                if (parsed.containsKey("response")) {
                    Object r = parsed.get("response");
                    return r != null ? r.toString() : "";
                }
                if (parsed.containsKey("result")) {
                    Object r = parsed.get("result");
                    return r != null ? r.toString() : "";
                }
            }
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return workerBody != null ? workerBody : "";
    }

    private static Response error(int status, String taskId, String errMsg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "error");
        result.put("taskId", taskId != null ? taskId : "");
        result.put("error", errMsg);
        return Response.status(status).entity(result).build();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
