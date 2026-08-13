package com.github.obhen233.starter.gateway.child;

import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.starter.gateway.SpringGatewayTransport;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 子 Gateway 的 Worker 代理端点。
 *
 * <p>当 {@code diatom.mode=gateway:child} 时，父 Gateway 通过本端点把任务转发到
 * 子 Gateway（当作 Worker 调用）。镜像核心 {@code GatewayChatHandler.handleWorkerChat}：
 * 只做同步转发，不走队列。</p>
 *
 * <p>流程：解析 {@code message} → {@code gatewayAgent.analyzeRequest} →
 * {@code capabilityRouter.routeWithLLMSuggestion} → {@code transport.sendChatRequestResult}
 * 转发到下挂 worker → 返回 {@code {"status":"ok","taskId","response"}}。</p>
 */
@RestController
public class ChildGatewayWorkerChatController {
    private static final Logger logger = LoggerFactory.getLogger(ChildGatewayWorkerChatController.class);

    private final GatewayAgent gatewayAgent;
    private final CapabilityRouter capabilityRouter;
    private final SpringGatewayTransport transport;
    private final ChildGatewayProperties properties;
    private final Environment environment;

    public ChildGatewayWorkerChatController(GatewayAgent gatewayAgent,
                                            CapabilityRouter capabilityRouter,
                                            SpringGatewayTransport transport,
                                            ChildGatewayProperties properties,
                                            Environment environment) {
        this.gatewayAgent = gatewayAgent;
        this.capabilityRouter = capabilityRouter;
        this.transport = transport;
        this.properties = properties;
        this.environment = environment;
    }

    @PostMapping("/worker/v1/chat")
    public ResponseEntity<Map<String, Object>> handleWorkerChat(@RequestBody(required = false) Map<String, Object> body) {
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
            logger.info("Child gateway worker proxy executing task={}, message={}",
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

            logger.info("Child gateway routed to worker {} (type={}, reasoning={})",
                    workerId, requirement.getTaskType(), requirement.getReasoning());

            // 3. 构造转发 body：原 body + taskId/sessionId/workspacePath/syncStrategy，
            //    gatewayUrl 改写为子节点自身外部 URL（让下挂 worker 回连到子而非父）
            String forwardBody = buildForwardBody(body, taskId, sessionId, requirement);
            SpringGatewayTransport.HttpResult result = transport.sendChatRequestResult(workerId, forwardBody);

            // 4. Worker 失败（含过载 503）→ 向上游返回 503，父 Gateway 换 worker 重试
            if (result == null || result.getBody() == null) {
                logger.warn("Downstream worker {} did not respond", workerId);
                return error(503, taskId, "Worker did not respond");
            }
            if (result.isError()) {
                logger.warn("Downstream worker {} returned HTTP {}: {}",
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
            logger.info("Child gateway worker proxy completed via worker {}: task={}", workerId, taskId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Child gateway worker proxy failed for task={}", taskId, e);
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
        String externalHost = properties.getExternalHost();
        if (externalHost == null || externalHost.isEmpty()) {
            externalHost = com.github.obhen233.util.NetworkUtils.getRealLocalIP();
        }
        String externalPort = properties.getExternalPort();
        if (externalPort == null || externalPort.isEmpty()) {
            externalPort = environment.getProperty("server.port", "8080");
        }
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

    private static ResponseEntity<Map<String, Object>> error(int status, String taskId, String errMsg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "error");
        result.put("taskId", taskId != null ? taskId : "");
        result.put("error", errMsg);
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(result);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
