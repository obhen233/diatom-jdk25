package com.github.obhen233.starter.worker;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.agent.ToolConfirmationException;
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
 * Worker 模式 REST 控制器。
 *
 * <p>当 {@code diatam.mode=worker} 且 Spring Web 可用时激活。
 * 接收 Gateway 下发的任务，通过 {@link ReActAgent} 执行 LLM 调用和工具操作。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /worker/v1/chat} — 执行任务</li>
 *   <li>{@code POST /worker/v1/cancel} — 取消任务</li>
 *   <li>{@code GET /worker/v1/health} — 健康检查</li>
 * </ul>
 */
@RestController
@RequestMapping("/worker/v1")
public class WorkerRestController {
    private static final Logger logger = LoggerFactory.getLogger(WorkerRestController.class);

    private final ReActAgent agent;
    private final GatewayRegistrationService registrationService;
    private final WorkerLoadState loadState;
    private volatile String currentTaskId;

    public WorkerRestController(ReActAgent agent, GatewayRegistrationService registrationService,
                                WorkerLoadState loadState) {
        this.agent = agent;
        this.registrationService = registrationService;
        this.loadState = loadState;
    }

    /**
     * 接收 Gateway 下发的任务，通过 ReActAgent 执行。
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
            logger.warn("Worker at capacity ({} active/{} max), rejecting task {}",
                    loadState.getActiveTasks(), loadState.getMaxConcurrency(), taskId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(overloaded(taskId));
        }

        currentTaskId = taskId;
        logger.info("Worker executing task: {}, message: {}", taskId, truncate(message, 200));

        try {
            // ReActAgent 为共享单例且非线程安全，必须在锁内串行执行。
            // 锁用 agent 实例本身，与 IDE 本地 AI 通道（executeAiChat/AiChatController）
            // 的 synchronized(agent) 共享同一监视器，避免并发调用同一非线程安全 Agent。
            String result;
            synchronized (agent) {
                result = agent.run(message);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("taskId", taskId != null ? taskId : "unknown");
            response.put("result", result);
            return ResponseEntity.ok(response);
        } catch (ToolConfirmationException e) {
            logger.warn("Task cancelled (confirmation rejected): {}", taskId);
            return ResponseEntity.ok(cancelled(taskId, "Confirmation rejected"));
        } catch (Exception e) {
            logger.error("Task execution failed: {}", taskId, e);
            return ResponseEntity.status(500).body(error(taskId, e.getMessage()));
        } finally {
            currentTaskId = null;
            loadState.release();
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> handleCancel(@RequestBody Map<String, Object> request) {
        String taskId = (String) request.get("taskId");
        logger.info("Cancel request received for task: {}", taskId);
        // Note: full cancellation would require agent interrupt support
        Map<String, Object> response = new HashMap<>();
        response.put("status", "cancelling");
        response.put("taskId", taskId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> handleHealth() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("workerId", registrationService.getWorkerId());
        response.put("host", registrationService.getExternalHost());
        response.put("port", registrationService.getExternalPort());
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

    private static Map<String, Object> cancelled(String taskId, String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "cancelled");
        m.put("taskId", taskId);
        m.put("error", reason);
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
