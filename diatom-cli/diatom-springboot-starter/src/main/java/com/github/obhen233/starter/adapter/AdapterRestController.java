package com.github.obhen233.starter.adapter;

import com.github.obhen233.starter.adapter.AdapterDriverPlugin.AdapterRequest;
import com.github.obhen233.starter.adapter.AdapterDriverPlugin.AdapterResponse;
import com.github.obhen233.starter.worker.GatewayRegistrationService;
import com.github.obhen233.starter.worker.WorkerLoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapter 模式 REST 控制器。
 *
 * <p>当 {@code diatam.mode=adapter} 且 Spring Web 可用时激活。
 * 接收 Gateway 下发的任务，通过 {@link AdapterDriverPlugin} 转发到外部 AI Agent
 * （如 Claude Code、Cursor 等）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /worker/v1/chat} — 转发任务到外部 Agent</li>
 *   <li>{@code GET /worker/v1/health} — 健康检查</li>
 * </ul>
 */
@RestController
@RequestMapping("/worker/v1")
public class AdapterRestController {
    private static final Logger logger = LoggerFactory.getLogger(AdapterRestController.class);

    private final AdapterDriverPlugin driver;
    private final GatewayRegistrationService registrationService;
    private final WorkerLoadState loadState;

    public AdapterRestController(AdapterDriverPlugin driver,
                                  GatewayRegistrationService registrationService,
                                  WorkerLoadState loadState) {
        this.driver = driver;
        this.registrationService = registrationService;
        this.loadState = loadState;
    }

    /**
     * 接收 Gateway 下发的任务，通过 AdapterDriverPlugin 转发到外部 AI Agent。
     *
     * <p>请求体格式：
     * <pre>
     * {
     *   "taskId": "task-001",
     *   "message": "Implement feature X",
     *   "sessionId": "sess-abc",
     *   "gatewayUrl": "http://gateway:8080"
     * }
     * </pre>
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handleChat(@RequestBody Map<String, Object> request) {
        String taskId = (String) request.get("taskId");
        String message = (String) request.get("message");

        if (message == null || message.isEmpty()) {
            return ResponseEntity.badRequest().body(error("Missing message field"));
        }

        // 准入控制：达到 maxConcurrency 立即返回 503，让 Gateway 转排队或路由到其他 Worker
        if (!loadState.tryAcquire()) {
            logger.warn("Adapter at capacity ({} active/{} max), rejecting task {}",
                    loadState.getActiveTasks(), loadState.getMaxConcurrency(), taskId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(overloaded(taskId));
        }

        logger.info("Adapter forwarding task: {}, message: {}", taskId, truncate(message, 200));

        try {
            AdapterRequest adapterReq = new AdapterRequest();
            adapterReq.setAgentId(registrationService.getWorkerId());
            adapterReq.setMessage(message);
            @SuppressWarnings("unchecked")
            Map<String, String> metadata = (Map<String, String>) request.get("metadata");
            adapterReq.setMetadata(metadata);

            AdapterResponse adapterResp = driver.handleRequest(adapterReq);

            Map<String, Object> response = new HashMap<>();
            response.put("status", adapterResp.isSuccess() ? "success" : "error");
            response.put("taskId", taskId != null ? taskId : "unknown");
            response.put("result", adapterResp.getContent());
            if (adapterResp.getError() != null) {
                response.put("error", adapterResp.getError());
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Adapter task execution failed: {}", taskId, e);
            return ResponseEntity.status(500).body(error(taskId, e.getMessage()));
        } finally {
            loadState.release();
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> handleHealth() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", driver.isHealthy() ? "UP" : "DOWN");
        response.put("workerId", registrationService.getWorkerId());
        response.put("driverType", driver.getDriverType());
        response.put("driverName", driver.getDriverName());
        return ResponseEntity.ok(response);
    }

    // ===== 工具方法 =====

    private static Map<String, Object> error(String message) {
        return Collections.singletonMap("error", message);
    }

    private static Map<String, Object> error(String taskId, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "error");
        m.put("taskId", taskId);
        m.put("error", message);
        return m;
    }

    private static Map<String, Object> overloaded(String taskId) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "overloaded");
        m.put("taskId", taskId);
        m.put("error", "Worker at capacity, please retry later");
        return m;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
