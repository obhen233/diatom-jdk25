package com.github.obhen233.starter.gateway.remote;

import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.starter.gateway.SpringGatewayTransport;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * ChatService implementation for GATEWAY mode.
 *
 * <p>Injects a unified {@link ChatService} into the IDE for gateway mode.
 * The gateway's local capability is routing: it classifies the request via LLM
 * ({@link GatewayAgent#analyzeRequest}), selects a registered Worker via
 * {@link CapabilityRouter}, then forwards the request to that Worker through
 * {@link SpringGatewayTransport} and returns the Worker's response.</p>
 */
public class GatewayChatService implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(GatewayChatService.class);

    private final GatewayAgent gatewayAgent;
    private final CapabilityRouter capabilityRouter;
    private final SpringGatewayTransport transport;

    public GatewayChatService(GatewayAgent gatewayAgent,
                              CapabilityRouter capabilityRouter,
                              SpringGatewayTransport transport) {
        this.gatewayAgent = gatewayAgent;
        this.capabilityRouter = capabilityRouter;
        this.transport = transport;
    }

    /** 单个 Worker 返回 503/无响应时，最多换 worker 重试次数 */
    private static final int MAX_ROUTING_RETRIES = 3;

    @Override
    public String chat(String message, String sessionId, String taskId) {
        // 1. LLM analyze request → task requirement (routing decision)
        TaskRequirement requirement = gatewayAgent.analyzeRequest(message);

        // 2. Route with 503/无响应重试：过载或失败的 Worker 加入排除集合重新路由，
        //    避免多 Gateway 并发时单 Worker 热点导致整体失败。
        Set<String> excluded = new HashSet<>();
        for (int attempt = 0; attempt < MAX_ROUTING_RETRIES; attempt++) {
            WorkerInfo target = capabilityRouter.routeWithLLMSuggestion(requirement, excluded);
            if (target == null) {
                throw new IllegalStateException("No available workers to route the request");
            }
            String workerId = target.getWorkerId();
            log.info("Gateway chat routed to worker: {} (type={}, complexity={})",
                    workerId, requirement.getTaskType(), requirement.getComplexity());

            // 3. Forward to worker and get response (with status code)
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", message);
            if (sessionId != null) body.put("sessionId", sessionId);
            if (taskId != null) body.put("taskId", taskId);
            body.put("workspacePath", System.getProperty("user.dir", "."));
            String requestBody = JsonUtils.toJson(body);

            SpringGatewayTransport.HttpResult result = transport.sendChatRequestResult(workerId, requestBody);
            if (result != null && result.getBody() != null && result.isSuccess()) {
                return extractResult(result.getBody());
            }
            if (result != null && result.isOverloaded()) {
                log.warn("Worker {} overloaded (HTTP {}), excluding and retrying (attempt {})",
                        workerId, result.getStatusCode(), attempt + 1);
            } else {
                log.warn("Worker {} failed (http={}), excluding and retrying (attempt {})",
                        workerId, result != null ? result.getStatusCode() : -1, attempt + 1);
            }
            excluded.add(workerId);
        }
        throw new IllegalStateException("All workers failed or overloaded");
    }

    @Override
    public void chatStream(String message, String sessionId, String taskId,
                           StreamHandler handler) {
        try {
            String result = chat(message, sessionId, taskId);
            // 底层传输为同步阻塞转发，返回完整结果。分块经 onToken 下发，
            // 让前端获得渐进式输出体验（最终 onComplete 仍回传全文）。
            emitChunked(result, handler);
        } catch (Exception e) {
            log.error("GatewayChatService.chatStream() failed", e);
            handler.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /**
     * 将完整结果按固定块大小经 {@link StreamHandler#onToken} 逐段下发，最后回传全文。
     */
    private static void emitChunked(String result, StreamHandler handler) {
        if (result == null || result.isEmpty()) {
            handler.onComplete(result);
            return;
        }
        int chunkSize = 100;
        for (int i = 0; i < result.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, result.length());
            handler.onToken(result.substring(i, end));
        }
        handler.onComplete(result);
    }

    /**
     * Extract the AI result text from a Worker's JSON response.
     * Worker/Adapter controllers return {"status": "success", "result": "..."}.
     */
    private static String extractResult(String workerBody) {
        try {
            Map<String, Object> parsed = JsonUtils.fromJson(workerBody, Map.class);
            if (parsed != null && parsed.get("result") != null) {
                return String.valueOf(parsed.get("result"));
            }
            if (parsed != null && parsed.get("response") != null) {
                return String.valueOf(parsed.get("response"));
            }
            if (parsed != null && parsed.get("error") != null) {
                throw new IllegalStateException(String.valueOf(parsed.get("error")));
            }
            return workerBody;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // Not JSON — return raw body
            return workerBody;
        }
    }
}
