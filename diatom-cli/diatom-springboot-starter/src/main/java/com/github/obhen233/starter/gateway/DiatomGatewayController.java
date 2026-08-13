package com.github.obhen233.starter.gateway;

import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.spi.IsolationContext;
import com.github.obhen233.spi.TaskQueueProvider;
import com.github.obhen233.spi.TaskQueueProvider.QueuedTask;
import com.github.obhen233.starter.gateway.cloud.NoopRegistryService;
import com.github.obhen233.starter.gateway.cloud.RegistryService;
import com.github.obhen233.starter.gateway.dto.LockRequest;
import com.github.obhen233.starter.gateway.dto.MetricsPayload;
import com.github.obhen233.starter.gateway.dto.WorkerRegisterRequest;
import com.github.obhen233.starter.gateway.queue.TaskResultStore;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gateway REST 控制器。
 *
 * <p>通过 Spring MVC {@code @RestController} 暴露 Gateway API 端点，
 * 复用 Spring Boot 内嵌 Web 容器（Tomcat/Jetty/Undertow），
 * 替代原来的 JDK {@code HttpServer} 实现。
 *
 * <p>端点映射与独立 Gateway 功能对等。
 */
@RestController
public class DiatomGatewayController {
    private static final Logger logger = LoggerFactory.getLogger(DiatomGatewayController.class);

    private final DiatomGatewayService service;
    private final SpringGatewayTransport transport;
    private final ResourceLockManager lockManager;
    private final GatewayAgent gatewayAgent;
    private final CapabilityRouter capabilityRouter;
    private final RegistryService registryService;
    private final TaskManager taskManager;
    private final TaskQueueProvider taskQueueProvider;
    private final TaskResultStore taskResultStore;
    private final boolean queueEnabled;

    /** Gateway 自身 URL，由启动参数或配置注入，用于 Worker 回连 */
    private final String gatewayUrl;

    /** Worker 返回 503/无响应时，同步路径最多换 worker 重试次数 */
    private static final int MAX_ROUTING_RETRIES = 3;

    public DiatomGatewayController(DiatomGatewayService service,
                                    SpringGatewayTransport transport,
                                    ResourceLockManager lockManager,
                                    GatewayAgent gatewayAgent,
                                    CapabilityRouter capabilityRouter,
                                    RegistryService registryService,
                                    TaskManager taskManager,
                                    TaskQueueProvider taskQueueProvider,
                                    TaskResultStore taskResultStore,
                                    boolean queueEnabled) {
        this.service = service;
        this.transport = transport;
        this.lockManager = lockManager;
        this.gatewayAgent = gatewayAgent;
        this.capabilityRouter = capabilityRouter;
        this.registryService = registryService;
        this.taskManager = taskManager;
        this.taskQueueProvider = taskQueueProvider;
        this.taskResultStore = taskResultStore;
        this.queueEnabled = queueEnabled;
        this.gatewayUrl = System.getProperty("gateway.url", "");
    }

    // ========================================================================
    // /gateway/v1/workers — Worker 注册/心跳/列表
    // ========================================================================

    /**
     * 注册模式检查：注册中心模式（gateway:nacos/eureka/consul）下，
     * Worker 生命周期由注册中心管理，不接收直连注册/心跳/注销。
     */
    private boolean isRegistryMode() {
        return !(registryService instanceof NoopRegistryService);
    }

    @PostMapping("/gateway/v1/workers")
    public ResponseEntity<Map<String, Object>> registerWorker(@RequestBody WorkerRegisterRequest req) {
        if (isRegistryMode()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                    errorMap("Registry mode: workers register via registry, not direct HTTP. Current: "
                            + registryService.getClass().getSimpleName()));
        }
        if (req.workerId() == null || req.workerId().isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap("Missing workerId in registration"));
        }

        WorkerInfo existing = service.getWorkerRaw(req.workerId());
        if (existing != null && existing.getStatus() == WorkerInfo.WorkerStatus.ONLINE) {
            String errMsg = "Worker ID '" + req.workerId()
                    + "' is already registered and online at "
                    + existing.getHost() + ":" + existing.getPort();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorMap(errMsg));
        }

        WorkerInfo worker = new WorkerInfo();
        worker.setWorkerId(req.workerId());
        worker.setHost(req.host() != null ? req.host() : "unknown");
        worker.setPort(req.port());
        worker.setModel(req.model());
        worker.setGroup(req.group());
        worker.setWorkspace(req.workspace());
        worker.setGatewayProfile(req.gatewayProfile());
        if (req.maxConcurrency() > 0) {
            worker.setMaxConcurrency(req.maxConcurrency());
        }
        // tier 标记子 Gateway 节点（tier="gateway-proxy"）或网关 tier，父 Gateway 据此在拓扑/路由中识别
        worker.setTier(req.tier());
        worker.setUseSsl(req.useSsl());
        service.registerWorker(worker);

        logger.info("Worker registered: {} at {}:{}", worker.getWorkerId(), worker.getHost(), worker.getPort());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "registered");
        result.put("workerId", req.workerId());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/gateway/v1/workers/{workerId}/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(
            @PathVariable String workerId,
            @RequestBody(required = false) MetricsPayload payload) {
        if (isRegistryMode()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                    errorMap("Registry mode: heartbeat managed by registry"));
        }

        WorkerInfo existing = service.getWorkerRaw(workerId);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap("Worker not found"));
        }

        if (payload != null) {
            if (payload.useSsl() != existing.isUseSsl()) {
                existing.setUseSsl(payload.useSsl());
                logger.info("Worker {} useSsl updated to {} via heartbeat", workerId, payload.useSsl());
            }
        }

        WorkerMetrics metrics = new WorkerMetrics();
        if (payload != null) {
            metrics.setCurrentLoad(payload.currentLoad());
        }
        metrics.updateHeartbeat();
        service.heartbeat(workerId, metrics);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/gateway/v1/workers/{workerId}")
    public ResponseEntity<Map<String, Object>> deregisterWorker(@PathVariable String workerId) {
        if (isRegistryMode()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                    errorMap("Registry mode: worker lifecycle managed by registry"));
        }
        service.deregisterWorker(workerId);
        logger.info("Worker deregistered: {}", workerId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "deregistered");
        result.put("workerId", workerId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/gateway/v1/workers")
    public ResponseEntity<List<Map<String, Object>>> listWorkers() {
        List<WorkerInfo> workers = service.listWorkersRaw();
        List<Map<String, Object>> result = workers.stream()
                .map(this::workerInfoToMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/gateway/v1/workers/{workerId}")
    public ResponseEntity<Map<String, Object>> getWorker(@PathVariable String workerId) {
        WorkerInfo worker = service.getWorkerRaw(workerId);
        if (worker != null) {
            return ResponseEntity.ok(workerInfoToMap(worker));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap("Worker not found"));
    }

    // ========================================================================
    // /gateway/v1/health — 健康检查
    // ========================================================================

    @GetMapping("/gateway/v1/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(service.health());
    }

    // ========================================================================
    // /gateway/v1/chat — 聊天任务提交
    // ========================================================================

    @PostMapping("/gateway/v1/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        Object msgObj = body.get("message");
        if (msgObj == null || msgObj.toString().isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap("Missing message field"));
        }
        String message = msgObj.toString();
        String sessionId = body.containsKey("sessionId") ? body.get("sessionId").toString() : null;
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // ===== 队列模式：立即返回 202，后台异步处理 =====
        if (queueEnabled) {
            String taskId = taskManager.createTask(sessionId, message);
            taskQueueProvider.enqueue(new QueuedTask(taskId, sessionId, message, JsonUtils.toJson(body)));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            result.put("status", "queued");
            result.put("queueDepth", taskQueueProvider.getQueueDepth());
            logger.info("Task queued: {} (queueDepth={}, session={})", taskId, taskQueueProvider.getQueueDepth(), sessionId);
            return ResponseEntity.accepted().body(result);
        }

        // ===== 同步模式：阻塞等待 Worker 响应 =====
        String workerId = null;
        String taskId = null;
        try {
            // 1. LLM 分析请求 → 任务需求 (Tier 1: LocalRouter SPI, Tier 2: LLM)
            TaskRequirement requirement = gatewayAgent.analyzeRequest(message);

            // 工作区路径：Spring Boot 下直接使用 user.dir
            String workspacePath = System.getProperty("user.dir", ".");

            // 2. 多维度评分路由 + 503/无响应自动换 worker 重试。
            //    某 Worker 返回 503（Worker 侧准入控制过载）或网络失败时，
            //    将其加入排除集合重新路由，避免多 Gateway 并发时单 Worker 热点。
            String workerResponse = null;
            WorkerInfo finalTarget = null;
            Set<String> excluded = new HashSet<>();

            for (int attempt = 0; attempt < MAX_ROUTING_RETRIES; attempt++) {
                WorkerInfo target = capabilityRouter.routeWithLLMSuggestion(requirement, excluded);
                if (target == null) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(errorMap("No available workers"));
                }
                workerId = target.getWorkerId();

                logger.info("Routed to worker: {} (type={}, complexity={}, reasoning={})",
                        workerId, requirement.getTaskType(),
                        requirement.getComplexity(), requirement.getReasoning());

                try {
                    // 活跃请求计数跟踪（内层 finally 确保每次尝试都配对释放）
                    capabilityRouter.incrementActive(workerId);

                    // 任务生命周期：创建 → 分配 → 开始（首次路由时创建一次）
                    if (taskId == null) {
                        taskId = taskManager.createTask(sessionId, message);
                    }
                    taskManager.assignTask(taskId, workerId);
                    taskManager.startTask(taskId);

                    com.github.obhen233.core.gateway.task.TaskState taskState = taskManager.getTask(taskId);
                    if (taskState != null) {
                        taskState.addAttribute("workspacePath", workspacePath);
                    }

                    // 构造完整的 ChatRequest（包含所有 Worker 需要的字段）
                    String requestBody = buildWorkerChatRequest(body, taskId, sessionId, workspacePath, requirement);
                    logger.debug("Forwarding chat request to worker {} at {}", workerId, target.getBaseUrl());

                    // 通过 SpringGatewayTransport 转发给 Worker（携带状态码）
                    SpringGatewayTransport.HttpResult result = transport.sendChatRequestResult(workerId, requestBody);
                    if (result == null || result.getBody() == null) {
                        logger.warn("Worker {} did not respond, excluding and retrying (attempt {})",
                                workerId, attempt + 1);
                        excluded.add(workerId);
                    } else if (result.isOverloaded()) {
                        logger.warn("Worker {} overloaded (HTTP {}), excluding and retrying (attempt {})",
                                workerId, result.getStatusCode(), attempt + 1);
                        excluded.add(workerId);
                    } else if (result.isError()) {
                        logger.warn("Worker {} returned HTTP {}, excluding and retrying (attempt {})",
                                workerId, result.getStatusCode(), attempt + 1);
                        excluded.add(workerId);
                    } else {
                        workerResponse = result.getBody();
                        finalTarget = target;
                        break;
                    }
                } finally {
                    capabilityRouter.decrementActive(workerId);
                    workerId = null;
                }
            }

            if (workerResponse == null) {
                if (taskId != null) {
                    try {
                        taskManager.failTask(taskId, "All workers failed or overloaded");
                    } catch (Exception inner) {
                        logger.warn("Failed to mark task {} as failed: {}", taskId, inner.getMessage());
                    }
                }
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(errorMap("All workers failed or overloaded"));
            }

            // 标记任务完成
            taskManager.completeTask(taskId);

            // 构建统一响应（含 taskId、worker 元信息等）
            Map<String, Object> responseMap = buildUnifiedResponse(taskId, finalTarget.getWorkerId(), finalTarget, workerResponse);
            logger.info("Chat request completed: task={} -> worker={}", taskId, finalTarget.getWorkerId());
            return ResponseEntity.ok(responseMap);
        } catch (Exception e) {
            logger.error("Error handling chat request: {}", e.getMessage(), e);
            if (taskId != null) {
                try {
                    taskManager.failTask(taskId, e.getMessage());
                } catch (Exception inner) {
                    logger.warn("Failed to mark task {} as failed: {}", taskId, inner.getMessage());
                }
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorMap("Chat processing failed: " + e.getMessage()));
        } finally {
            // 必须释放活跃计数，防止 Router 的 active counter 泄漏
            if (workerId != null) {
                capabilityRouter.decrementActive(workerId);
            }
        }
    }

    /**
     * SSE 流式 chat：{@code POST /gateway/v1/chat/stream}。
     *
     * <p>镜像独立 Gateway 的 {@code GatewayChatHandler.handleChatStream}：同步路由并转发，
     * 以 {@code text/event-stream} 依次输出 {@code routed} → {@code token}（整段结果）→
     * {@code complete} 事件，错误时输出 {@code error} 事件。与 {@link GatewayChatClient}
     * 的 SSE 解析（按 {@code type} 分发、{@code content} 承载内容）对齐。</p>
     */
    @PostMapping("/gateway/v1/chat/stream")
    public void chatStream(@RequestBody Map<String, Object> body,
                           HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream; charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        OutputStream os = response.getOutputStream();

        Object msgObj = body.get("message");
        if (msgObj == null || msgObj.toString().isEmpty()) {
            writeSseError(os, "Missing message field");
            return;
        }
        String message = msgObj.toString();
        String sessionId = body.containsKey("sessionId") ? body.get("sessionId").toString() : null;
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String taskId = body.containsKey("taskId") ? body.get("taskId").toString() : null;
        if (taskId == null || taskId.isEmpty()) {
            taskId = null;
        }

        String workerId = null;
        try {
            TaskRequirement requirement = gatewayAgent.analyzeRequest(message);
            String workspacePath = System.getProperty("user.dir", ".");

            WorkerInfo target = capabilityRouter.routeWithLLMSuggestion(requirement);
            if (target == null) {
                writeSseError(os, "No available workers");
                return;
            }
            workerId = target.getWorkerId();
            capabilityRouter.incrementActive(workerId);
            logger.info("Chat SSE stream routed to worker: {} (type={}, complexity={})",
                    workerId, requirement.getTaskType(), requirement.getComplexity());

            try {
                if (taskId == null) {
                    taskId = taskManager.createTask(sessionId, message);
                }
                taskManager.assignTask(taskId, workerId);
                taskManager.startTask(taskId);

                String requestBody = buildWorkerChatRequest(body, taskId, sessionId, workspacePath, requirement);
                SpringGatewayTransport.HttpResult result = transport.sendChatRequestResult(workerId, requestBody);
                if (result == null || result.getBody() == null) {
                    writeSseError(os, "Worker did not respond");
                    return;
                }
                if (result.isError()) {
                    writeSseError(os, "Worker returned HTTP " + result.getStatusCode());
                    return;
                }

                try {
                    taskManager.completeTask(taskId);
                } catch (Exception ignored) {
                    // 流式路径任务标记失败不影响已取到的结果
                }

                String textResponse = extractTextResponse(result.getBody());
                writeSse(os, "{\"type\":\"routed\",\"taskId\":\"" + escapeJson(taskId)
                        + "\",\"worker\":\"" + escapeJson(workerId) + "\"}");
                writeSse(os, "{\"type\":\"token\",\"content\":\"" + escapeJson(textResponse)
                        + "\",\"worker\":\"" + escapeJson(workerId) + "\"}");
                writeSse(os, "{\"type\":\"complete\",\"taskId\":\"" + escapeJson(taskId)
                        + "\",\"worker\":\"" + escapeJson(workerId) + "\"}");
            } finally {
                capabilityRouter.decrementActive(workerId);
                workerId = null;
            }
        } catch (Exception e) {
            logger.error("Chat SSE stream failed for message: {}", e.getMessage(), e);
            writeSseError(os, e.getMessage());
        } finally {
            if (workerId != null) {
                capabilityRouter.decrementActive(workerId);
            }
            try {
                os.close();
            } catch (IOException ignored) {
                // 客户端可能已断开
            }
        }
    }

    /**
     * 从 Worker 响应体提取文本：优先 {@code response}，其次 {@code result}，最后原样返回。
     */
    private static String extractTextResponse(String workerBody) {
        try {
            Map<String, Object> parsed = JsonUtils.fromJson(workerBody, Map.class);
            if (parsed != null) {
                if (parsed.containsKey("response") && parsed.get("response") != null) {
                    return parsed.get("response").toString();
                }
                if (parsed.containsKey("result") && parsed.get("result") != null) {
                    return parsed.get("result").toString();
                }
            }
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return workerBody != null ? workerBody : "";
    }

    private static void writeSse(OutputStream os, String json) throws IOException {
        os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void writeSseError(OutputStream os, String error) throws IOException {
        writeSse(os, "{\"type\":\"error\",\"content\":\"" + escapeJson(error != null ? error : "Unknown error") + "\"}");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 构造发送给 Worker 的请求体，注入 taskId、sessionId、workspacePath、syncStrategy、gatewayUrl。
     */
    private String buildWorkerChatRequest(Map<String, Object> body,
                                           String taskId, String sessionId,
                                           String workspacePath,
                                           TaskRequirement requirement) {
        Map<String, Object> workerBody = new LinkedHashMap<>(body.size() + 5);
        workerBody.putAll(body);
        workerBody.put("taskId", taskId);
        workerBody.put("sessionId", sessionId);
        workerBody.put("workspacePath", workspacePath);
        String syncStrategy = requirement.getSyncStrategy();
        workerBody.put("syncStrategy", syncStrategy != null ? syncStrategy : "skip");
        if (!gatewayUrl.isEmpty()) {
            workerBody.put("gatewayUrl", gatewayUrl);
        }
        return JsonUtils.toJson(workerBody);
    }

    /**
     * 构建统一响应：包含 taskId、status、worker 元信息、以及 Worker 返回的 response 内容。
     */
    private Map<String, Object> buildUnifiedResponse(String taskId, String workerId,
                                                      WorkerInfo worker, String workerBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", "completed");

        // Worker 元信息
        Map<String, Object> workerObj = new LinkedHashMap<>();
        workerObj.put("id", workerId);
        workerObj.put("url", worker.getBaseUrl());
        workerObj.put("model", worker.getModel() != null ? worker.getModel() : "");
        result.put("worker", workerObj);

        // 尝试从 Worker 响应中提取 response 文本
        try {
            Map<String, Object> parsed = JsonUtils.fromJson(workerBody, Map.class);
            if (parsed != null) {
                if (parsed.containsKey("response")) {
                    result.put("response", parsed.get("response"));
                }
                // 传递 Worker 的元信息（如果有）
                if (parsed.containsKey("workerMeta")) {
                    result.put("workerMeta", parsed.get("workerMeta"));
                }
            } else {
                result.put("response", workerBody);
            }
        } catch (Exception e) {
            result.put("response", workerBody);
        }
        return result;
    }

    // ========================================================================
    // /gateway/v1/tasks/{taskId} — 任务结果查询（队列模式轮询）
    // ========================================================================

    /**
     * 查询任务结果（队列模式使用，同步模式也可查询任务状态）。
     *
     * <p>队列模式下，任务完成后结果由 {@code TaskResultStore} 持有，
     * TTL 过期后自动清理。若结果已过期，回退到 {@code TaskManager} 查询任务状态。</p>
     */
    @GetMapping("/gateway/v1/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskResult(@PathVariable String taskId) {
        // 1. 检查 TaskResultStore（队列模式的结果缓存）
        TaskResultStore.Entry stored = taskResultStore.get(taskId);
        if (stored != null) {
            Map<String, Object> result = new LinkedHashMap<>(stored.getData());
            result.put("taskId", taskId);
            result.put("status", stored.getStatus());
            return ResponseEntity.ok(result);
        }

        // 2. 回退到 TaskManager 查询任务状态
        com.github.obhen233.core.gateway.task.TaskState state = taskManager.getTask(taskId);
        if (state != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            result.put("status", state.getStatus().name().toLowerCase());
            result.put("workerId", state.getWorkerId());
            result.put("createdAt", state.getCreatedAt());
            result.put("updatedAt", state.getUpdatedAt());
            return ResponseEntity.ok(result);
        }

        // 3. 任务不存在或结果已过期
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap("Task not found or expired"));
    }

    // ========================================================================
    // /gateway/v1/lock/* — 分布式锁管理
    // ========================================================================

    @PostMapping("/gateway/v1/lock/acquire")
    public ResponseEntity<Map<String, Object>> acquireLock(@RequestBody LockRequest req) {
        if (req.resourceId() == null || req.workerId() == null || req.mode() == null) {
            return ResponseEntity.badRequest().body(errorMap("Missing required fields: resourceId, workerId, mode"));
        }

        IsolationContext.LockMode mode = "READ".equalsIgnoreCase(req.mode())
                ? IsolationContext.LockMode.READ : IsolationContext.LockMode.WRITE;
        long leaseMs = req.leaseMs() > 0 ? req.leaseMs() : 30000;
        long waitMs = Math.max(req.waitMs(), 0);

        IsolationContext.LockToken token = lockManager.acquire(
                req.resourceId(), req.workerId(), mode, leaseMs, waitMs);

        if (token != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("token", token.getToken());
            result.put("resourceId", token.getResourceId());
            result.put("mode", token.getMode());
            result.put("expiresAt", token.getExpiresAt());
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorMap("Lock acquisition failed (timeout or conflict)"));
    }

    @PostMapping("/gateway/v1/lock/release")
    public ResponseEntity<Map<String, Object>> releaseLock(@RequestBody LockRequest req) {
        if (req.resourceId() == null || req.token() == null || req.workerId() == null) {
            return ResponseEntity.badRequest().body(errorMap("Missing required fields: resourceId, token, workerId"));
        }
        boolean released = lockManager.release(req.resourceId(), req.token(), req.workerId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", released);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/gateway/v1/lock/renew")
    public ResponseEntity<Map<String, Object>> renewLock(@RequestBody LockRequest req) {
        if (req.resourceId() == null || req.token() == null) {
            return ResponseEntity.badRequest().body(errorMap("Missing required fields: resourceId, token"));
        }
        long additionalMs = req.additionalMs() > 0 ? req.additionalMs() : 30000;
        boolean renewed = lockManager.renewLease(req.resourceId(), req.token(), additionalMs);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", renewed);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/gateway/v1/lock/status")
    public ResponseEntity<Map<String, Object>> lockStatus(
            @RequestParam(value = "resourceId", required = false) String resourceId) {
        List<IsolationContext.LockInfo> locks;
        if (resourceId != null && !resourceId.isEmpty()) {
            IsolationContext.LockInfo info = lockManager.getLockInfo(resourceId);
            locks = info != null ? Collections.singletonList(info) : Collections.emptyList();
        } else {
            locks = lockManager.getAllLocks();
        }

        List<Map<String, Object>> lockList = locks.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resourceId", l.getResourceId());
            m.put("holderWorkerId", l.getHolderWorkerId());
            m.put("mode", l.getMode());
            m.put("expiresAt", l.getExpiresAt());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("locks", lockList);
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // /api/diatom/* — 管理端点（向后兼容）
    // ========================================================================

    @GetMapping("/api/diatom/workers")
    public ResponseEntity<?> apiListWorkers() {
        return ResponseEntity.ok(service.listWorkers());
    }

    @GetMapping("/api/diatom/workers/{workerId}")
    public ResponseEntity<?> apiGetWorker(@PathVariable String workerId) {
        DiatomGatewayService.WorkerSummary worker = service.getWorker(workerId);
        if (worker != null) {
            return ResponseEntity.ok(worker);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap("worker not found"));
    }

    @GetMapping("/api/diatom/tasks")
    public ResponseEntity<?> apiListTasks(
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(service.listTasks(status));
    }

    @GetMapping("/api/diatom/tasks/{taskId}")
    public ResponseEntity<?> apiGetTask(@PathVariable String taskId) {
        DiatomGatewayService.TaskSummary task = service.getTask(taskId);
        if (task != null) {
            return ResponseEntity.ok(task);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap("task not found"));
    }

    @PostMapping("/api/diatom/tasks/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> apiCancelTask(@PathVariable String taskId) {
        boolean cancelled = service.cancelTask(taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", cancelled ? "cancelling" : "not_found");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/diatom/health")
    public ResponseEntity<Map<String, Object>> apiHealth() {
        return ResponseEntity.ok(service.health());
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private Map<String, Object> workerInfoToMap(WorkerInfo w) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workerId", w.getWorkerId());
        map.put("host", w.getHost());
        map.put("port", w.getPort());
        map.put("model", w.getModel());
        if (w.getWorkspace() != null && !w.getWorkspace().isEmpty()) {
            map.put("workspace", w.getWorkspace());
        }
        map.put("status", w.getStatus().name());
        map.put("currentLoad", w.getMetrics().getCurrentLoad());
        map.put("lastHeartbeat", w.getMetrics().getLastHeartbeat());
        if (w.getGatewayProfile() != null && !w.getGatewayProfile().isEmpty()) {
            map.put("gatewayProfile", w.getGatewayProfile());
        }
        if (w.getTier() != null && !w.getTier().isEmpty()) {
            map.put("tier", w.getTier());
        }
        return map;
    }

    private static Map<String, Object> errorMap(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        return result;
    }
}
