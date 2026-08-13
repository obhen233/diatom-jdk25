package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;
import com.github.obhen233.quarkus.runtime.components.DiatomRuntimeContext;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import com.github.obhen233.quarkus.runtime.queue.TaskResultStore;
import com.github.obhen233.quarkus.runtime.rest.dto.LockRequest;
import com.github.obhen233.quarkus.runtime.rest.dto.MetricsPayload;
import com.github.obhen233.quarkus.runtime.rest.dto.WorkerRegisterRequest;
import com.github.obhen233.spi.IsolationContext;
import com.github.obhen233.util.JsonUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gateway REST 资源（JAX-RS，注册到 Quarkus 原生 web 容器）。
 *
 * <p>镜像 starter {@code DiatomGatewayController}：通过 {@code /gateway/v1/*} 暴露
 * Worker 注册/心跳/列表、聊天（同步 + SSE 流式 + 202 队列）、任务查询、分布式锁等端点，
 * 复用 core 逻辑层（WorkerRegistry/GatewayAgent/CapabilityRouter/TaskManager/
 * ResourceLockManager），传输层用框架无关的 {@link QuarkusGatewayTransport}。
 * wire 协议与 starter/core 完全兼容。</p>
 */
@Path("/gateway/v1")
@ApplicationScoped
public class GatewayResource {

    private static final Logger LOGGER = Logger.getLogger(GatewayResource.class);

    /** Worker 返回 503/无响应时，同步路径最多换 worker 重试次数 */
    private static final int MAX_ROUTING_RETRIES = 3;

    private final DiatomRuntimeContext context;
    private final DiatomRuntimeConfig config;
    private final boolean queueEnabled;
    /** Gateway 自身 URL，由启动参数或配置注入，用于 Worker 回连 */
    private final String gatewayUrl;

    @Inject
    public GatewayResource(DiatomRuntimeContext context, DiatomRuntimeConfig config) {
        this.context = context;
        this.config = config;
        this.queueEnabled = config.gateway().queueEnabled();
        this.gatewayUrl = System.getProperty("gateway.url", "");
    }

    // ========================================================================
    // /gateway/v1/workers — Worker 注册/心跳/列表
    // ========================================================================

    @POST
    @Path("/workers")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response registerWorker(WorkerRegisterRequest req) {
        WorkerRegistry registry = context.workerRegistry();
        if (registry == null) {
            return unavailable("Worker registry not available (not gateway mode)");
        }
        if (req == null || req.workerId() == null || req.workerId().isEmpty()) {
            return error(400, "Missing workerId in registration");
        }

        WorkerInfo existing = registry.getWorker(req.workerId());
        if (existing != null && existing.getStatus() == WorkerInfo.WorkerStatus.ONLINE) {
            String errMsg = "Worker ID '" + req.workerId()
                    + "' is already registered and online at "
                    + existing.getHost() + ":" + existing.getPort();
            return error(409, errMsg);
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
        // tier 标记子 Gateway 节点（tier="gateway-proxy"）或网关 tier，父 Gateway 据此识别
        worker.setTier(req.tier());
        worker.setUseSsl(req.useSsl());
        registry.register(worker);

        LOGGER.infof("Worker registered: %s at %s:%d", worker.getWorkerId(), worker.getHost(), worker.getPort());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "registered");
        result.put("workerId", req.workerId());
        return Response.ok(result).build();
    }

    @PUT
    @Path("/workers/{workerId}/heartbeat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response heartbeat(@PathParam("workerId") String workerId, MetricsPayload payload) {
        WorkerRegistry registry = context.workerRegistry();
        if (registry == null) {
            return unavailable("Worker registry not available (not gateway mode)");
        }

        WorkerInfo existing = registry.getWorker(workerId);
        if (existing == null) {
            return error(404, "Worker not found");
        }

        if (payload != null && payload.useSsl() != existing.isUseSsl()) {
            existing.setUseSsl(payload.useSsl());
            LOGGER.infof("Worker %s useSsl updated to %s via heartbeat", workerId, payload.useSsl());
        }

        WorkerMetrics metrics = new WorkerMetrics();
        if (payload != null) {
            metrics.setCurrentLoad(payload.currentLoad());
        }
        metrics.updateHeartbeat();
        registry.heartbeat(workerId, metrics);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/workers/{workerId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deregisterWorker(@PathParam("workerId") String workerId) {
        WorkerRegistry registry = context.workerRegistry();
        if (registry == null) {
            return unavailable("Worker registry not available (not gateway mode)");
        }
        registry.deregister(workerId);
        LOGGER.infof("Worker deregistered: %s", workerId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "deregistered");
        result.put("workerId", workerId);
        return Response.ok(result).build();
    }

    @GET
    @Path("/workers")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listWorkers() {
        WorkerRegistry registry = context.workerRegistry();
        if (registry == null) {
            return unavailable("Worker registry not available (not gateway mode)");
        }
        List<WorkerInfo> workers = registry.availableWorkers();
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkerInfo w : workers) {
            result.add(workerInfoToMap(w));
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/workers/{workerId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getWorker(@PathParam("workerId") String workerId) {
        WorkerRegistry registry = context.workerRegistry();
        if (registry == null) {
            return unavailable("Worker registry not available (not gateway mode)");
        }
        WorkerInfo worker = registry.getWorker(workerId);
        if (worker != null) {
            return Response.ok(workerInfoToMap(worker)).build();
        }
        return error(404, "Worker not found");
    }

    // ========================================================================
    // /gateway/v1/health — 健康检查
    // ========================================================================

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        WorkerRegistry registry = context.workerRegistry();
        TaskManager taskManager = context.taskManager();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("onlineWorkers", registry != null ? registry.availableWorkers().size() : 0);
        result.put("activeTasks", taskManager != null ? taskManager.getActiveTaskCount() : 0);
        return Response.ok(result).build();
    }

    // ========================================================================
    // /gateway/v1/chat — 聊天任务提交
    // ========================================================================

    @POST
    @Path("/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response chat(Map<String, Object> body) {
        if (body == null) {
            return error(400, "Missing message field");
        }
        Object msgObj = body.get("message");
        if (msgObj == null || msgObj.toString().isEmpty()) {
            return error(400, "Missing message field");
        }
        String message = msgObj.toString();
        String sessionId = body.containsKey("sessionId") ? body.get("sessionId").toString() : null;
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // ===== 队列模式：立即返回 202，后台异步处理 =====
        if (queueEnabled) {
            return queueChat(body, message, sessionId);
        }

        GatewayAgent gatewayAgent = context.gatewayAgent();
        CapabilityRouter capabilityRouter = context.capabilityRouter();
        TaskManager taskManager = context.taskManager();
        WorkerRegistry registry = context.workerRegistry();
        if (gatewayAgent == null || capabilityRouter == null || taskManager == null || registry == null) {
            return unavailable("Gateway components not available (not gateway mode)");
        }
        QuarkusGatewayTransport transport = new QuarkusGatewayTransport(registry);

        // ===== 同步模式：阻塞等待 Worker 响应 =====
        String workerId = null;
        String taskId = null;
        try {
            // 1. LLM 分析请求 → 任务需求
            TaskRequirement requirement = gatewayAgent.analyzeRequest(message);

            // 工作区路径：Quarkus 下使用 user.dir
            String workspacePath = System.getProperty("user.dir", ".");

            // 2. 多维度评分路由 + 503/无响应自动换 worker 重试
            String workerResponse = null;
            WorkerInfo finalTarget = null;
            Set<String> excluded = new HashSet<>();

            for (int attempt = 0; attempt < MAX_ROUTING_RETRIES; attempt++) {
                WorkerInfo target = capabilityRouter.routeWithLLMSuggestion(requirement, excluded);
                if (target == null) {
                    return error(503, "No available workers");
                }
                workerId = target.getWorkerId();

                LOGGER.infof("Routed to worker: %s (type=%s, complexity=%s, reasoning=%s)",
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

                    TaskState taskState = taskManager.getTask(taskId);
                    if (taskState != null) {
                        taskState.addAttribute("workspacePath", workspacePath);
                    }

                    // 构造完整的 ChatRequest（包含所有 Worker 需要的字段）
                    String requestBody = buildWorkerChatRequest(body, taskId, sessionId, workspacePath, requirement);
                    LOGGER.debugf("Forwarding chat request to worker %s at %s", workerId, target.getBaseUrl());

                    // 通过 QuarkusGatewayTransport 转发给 Worker（携带状态码）
                    QuarkusGatewayTransport.HttpResult result = transport.sendChatRequestResult(workerId, requestBody);
                    if (result == null || result.getBody() == null) {
                        LOGGER.warnf("Worker %s did not respond, excluding and retrying (attempt %d)",
                                workerId, attempt + 1);
                        excluded.add(workerId);
                    } else if (result.isOverloaded()) {
                        LOGGER.warnf("Worker %s overloaded (HTTP %d), excluding and retrying (attempt %d)",
                                workerId, result.getStatusCode(), attempt + 1);
                        excluded.add(workerId);
                    } else if (result.isError()) {
                        LOGGER.warnf("Worker %s returned HTTP %d, excluding and retrying (attempt %d)",
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
                        LOGGER.warnf("Failed to mark task %s as failed: %s", taskId, inner.getMessage());
                    }
                }
                return error(503, "All workers failed or overloaded");
            }

            // 标记任务完成
            taskManager.completeTask(taskId);

            // 构建统一响应（含 taskId、worker 元信息等）
            Map<String, Object> responseMap = buildUnifiedResponse(taskId, finalTarget.getWorkerId(), finalTarget, workerResponse);
            LOGGER.infof("Chat request completed: task=%s -> worker=%s", taskId, finalTarget.getWorkerId());
            return Response.ok(responseMap).build();
        } catch (Exception e) {
            LOGGER.errorf(e, "Error handling chat request: %s", e.getMessage());
            if (taskId != null) {
                try {
                    taskManager.failTask(taskId, e.getMessage());
                } catch (Exception inner) {
                    LOGGER.warnf("Failed to mark task %s as failed: %s", taskId, inner.getMessage());
                }
            }
            return error(500, "Chat processing failed: " + e.getMessage());
        } finally {
            // 必须释放活跃计数，防止 Router 的 active counter 泄漏
            if (workerId != null && capabilityRouter != null) {
                capabilityRouter.decrementActive(workerId);
            }
        }
    }

    /**
     * 队列模式：创建任务入队，返回 202 + taskId。
     */
    private Response queueChat(Map<String, Object> body, String message, String sessionId) {
        TaskManager taskManager = context.taskManager();
        if (taskManager == null || context.kernel().taskQueueProvider() == null) {
            return error(503, "Task queue not available");
        }
        String taskId = taskManager.createTask(sessionId, message);
        context.kernel().taskQueueProvider().enqueue(
                new com.github.obhen233.spi.TaskQueueProvider.QueuedTask(
                        taskId, sessionId, message, JsonUtils.toJson(body)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", "queued");
        result.put("queueDepth", context.kernel().taskQueueProvider().getQueueDepth());
        LOGGER.infof("Task queued: %s (session=%s)", taskId, sessionId);
        return Response.status(Response.Status.ACCEPTED).entity(result).build();
    }

    // ========================================================================
    // /gateway/v1/chat/stream — SSE 流式 chat
    // ========================================================================

    @POST
    @Path("/chat/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Response chatStream(Map<String, Object> body) {
        return Response.ok((StreamingOutput) os -> {
            try {
                handleChatStream(body, os);
            } finally {
                try {
                    os.flush();
                } catch (IOException ignored) {
                    // 客户端可能已断开
                }
            }
        }).header("Cache-Control", "no-cache").header("Connection", "keep-alive").build();
    }

    /**
     * SSE 流式 chat：同步路由并转发，以 {@code text/event-stream} 依次输出
     * {@code routed} → {@code token}（整段结果）→ {@code complete} 事件，错误时输出
     * {@code error} 事件。与 starter/core wire 协议一致。
     */
    private void handleChatStream(Map<String, Object> body, OutputStream os) throws IOException {
        if (body == null) {
            writeSseError(os, "Missing message field");
            return;
        }
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

        GatewayAgent gatewayAgent = context.gatewayAgent();
        CapabilityRouter capabilityRouter = context.capabilityRouter();
        TaskManager taskManager = context.taskManager();
        WorkerRegistry registry = context.workerRegistry();
        if (gatewayAgent == null || capabilityRouter == null || taskManager == null || registry == null) {
            writeSseError(os, "Gateway components not available (not gateway mode)");
            return;
        }
        QuarkusGatewayTransport transport = new QuarkusGatewayTransport(registry);

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
            LOGGER.infof("Chat SSE stream routed to worker: %s (type=%s, complexity=%s)",
                    workerId, requirement.getTaskType(), requirement.getComplexity());

            try {
                if (taskId == null) {
                    taskId = taskManager.createTask(sessionId, message);
                }
                taskManager.assignTask(taskId, workerId);
                taskManager.startTask(taskId);

                String requestBody = buildWorkerChatRequest(body, taskId, sessionId, workspacePath, requirement);
                QuarkusGatewayTransport.HttpResult result = transport.sendChatRequestResult(workerId, requestBody);
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
            LOGGER.errorf(e, "Chat SSE stream failed for message: %s", e.getMessage());
            writeSseError(os, e.getMessage());
        } finally {
            if (workerId != null && capabilityRouter != null) {
                capabilityRouter.decrementActive(workerId);
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
        writeSse(os, "{\"type\":\"error\",\"content\":\""
                + escapeJson(error != null ? error : "Unknown error") + "\"}");
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

        Map<String, Object> workerObj = new LinkedHashMap<>();
        workerObj.put("id", workerId);
        workerObj.put("url", worker.getBaseUrl());
        workerObj.put("model", worker.getModel() != null ? worker.getModel() : "");
        result.put("worker", workerObj);

        try {
            Map<String, Object> parsed = JsonUtils.fromJson(workerBody, Map.class);
            if (parsed != null) {
                if (parsed.containsKey("response")) {
                    result.put("response", parsed.get("response"));
                }
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

    @GET
    @Path("/tasks/{taskId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTaskResult(@PathParam("taskId") String taskId) {
        TaskResultStore store = context.kernel().taskResultStore();
        TaskManager taskManager = context.taskManager();

        // 1. 检查 TaskResultStore（队列模式的结果缓存）
        if (store != null) {
            TaskResultStore.Entry stored = store.get(taskId);
            if (stored != null) {
                Map<String, Object> result = new LinkedHashMap<>(stored.getData());
                result.put("taskId", taskId);
                result.put("status", stored.getStatus());
                return Response.ok(result).build();
            }
        }

        // 2. 回退到 TaskManager 查询任务状态
        if (taskManager != null) {
            TaskState state = taskManager.getTask(taskId);
            if (state != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("taskId", taskId);
                result.put("status", state.getStatus().name().toLowerCase());
                result.put("workerId", state.getWorkerId());
                result.put("createdAt", state.getCreatedAt());
                result.put("updatedAt", state.getUpdatedAt());
                return Response.ok(result).build();
            }
        }

        // 3. 任务不存在或结果已过期
        return error(404, "Task not found or expired");
    }

    // ========================================================================
    // /gateway/v1/lock/* — 分布式锁管理
    // ========================================================================

    @POST
    @Path("/lock/acquire")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response acquireLock(LockRequest req) {
        ResourceLockManager lockManager = context.lockManager();
        if (lockManager == null) {
            return unavailable("Lock manager not available (not gateway mode)");
        }
        if (req == null || req.resourceId() == null || req.workerId() == null || req.mode() == null) {
            return error(400, "Missing required fields: resourceId, workerId, mode");
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
            return Response.ok(result).build();
        }
        return error(409, "Lock acquisition failed (timeout or conflict)");
    }

    @POST
    @Path("/lock/release")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response releaseLock(LockRequest req) {
        ResourceLockManager lockManager = context.lockManager();
        if (lockManager == null) {
            return unavailable("Lock manager not available (not gateway mode)");
        }
        if (req == null || req.resourceId() == null || req.token() == null || req.workerId() == null) {
            return error(400, "Missing required fields: resourceId, token, workerId");
        }
        boolean released = lockManager.release(req.resourceId(), req.token(), req.workerId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", released);
        return Response.ok(result).build();
    }

    @POST
    @Path("/lock/renew")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response renewLock(LockRequest req) {
        ResourceLockManager lockManager = context.lockManager();
        if (lockManager == null) {
            return unavailable("Lock manager not available (not gateway mode)");
        }
        if (req == null || req.resourceId() == null || req.token() == null) {
            return error(400, "Missing required fields: resourceId, token");
        }
        long additionalMs = req.additionalMs() > 0 ? req.additionalMs() : 30000;
        boolean renewed = lockManager.renewLease(req.resourceId(), req.token(), additionalMs);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", renewed);
        return Response.ok(result).build();
    }

    @GET
    @Path("/lock/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response lockStatus(@QueryParam("resourceId") String resourceId) {
        ResourceLockManager lockManager = context.lockManager();
        if (lockManager == null) {
            return unavailable("Lock manager not available (not gateway mode)");
        }
        List<IsolationContext.LockInfo> locks;
        if (resourceId != null && !resourceId.isEmpty()) {
            IsolationContext.LockInfo info = lockManager.getLockInfo(resourceId);
            locks = info != null ? Collections.singletonList(info) : Collections.emptyList();
        } else {
            locks = lockManager.getAllLocks();
        }

        List<Map<String, Object>> lockList = new ArrayList<>();
        for (IsolationContext.LockInfo l : locks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resourceId", l.getResourceId());
            m.put("holderWorkerId", l.getHolderWorkerId());
            m.put("mode", l.getMode());
            m.put("expiresAt", l.getExpiresAt());
            lockList.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("locks", lockList);
        return Response.ok(result).build();
    }

    // ========================================================================
    // /api/diatom/* — 管理端点（向后兼容）
    // ========================================================================

    @GET
    @Path("/api/diatom/workers")
    @Produces(MediaType.APPLICATION_JSON)
    public Response apiListWorkers() {
        WorkerRegistry registry = context.workerRegistry();
        if (registry == null) {
            return unavailable("Worker registry not available (not gateway mode)");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkerInfo w : registry.availableWorkers()) {
            result.add(workerSummary(w));
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/api/diatom/workers/{workerId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response apiGetWorker(@PathParam("workerId") String workerId) {
        WorkerRegistry registry = context.workerRegistry();
        if (registry == null) {
            return unavailable("Worker registry not available (not gateway mode)");
        }
        WorkerInfo w = registry.getWorker(workerId);
        if (w != null) {
            return Response.ok(workerSummary(w)).build();
        }
        return error(404, "worker not found");
    }

    @GET
    @Path("/api/diatom/tasks")
    @Produces(MediaType.APPLICATION_JSON)
    public Response apiListTasks(@QueryParam("status") String status) {
        TaskManager taskManager = context.taskManager();
        if (taskManager == null) {
            return unavailable("Task manager not available (not gateway mode)");
        }
        List<TaskState> taskList;
        if (status != null && !status.isEmpty()) {
            try {
                TaskStatus filter = TaskStatus.valueOf(status.toUpperCase());
                taskList = taskManager.getTasksByStatus(filter);
            } catch (IllegalArgumentException e) {
                taskList = taskManager.getAllTasks();
            }
        } else {
            taskList = taskManager.getAllTasks();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskState t : taskList) {
            result.add(taskSummary(t));
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/api/diatom/tasks/{taskId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response apiGetTask(@PathParam("taskId") String taskId) {
        TaskManager taskManager = context.taskManager();
        if (taskManager == null) {
            return unavailable("Task manager not available (not gateway mode)");
        }
        TaskState state = taskManager.getTask(taskId);
        if (state != null) {
            return Response.ok(taskSummary(state)).build();
        }
        return error(404, "task not found");
    }

    @POST
    @Path("/api/diatom/tasks/{taskId}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response apiCancelTask(@PathParam("taskId") String taskId) {
        TaskManager taskManager = context.taskManager();
        if (taskManager == null) {
            return unavailable("Task manager not available (not gateway mode)");
        }
        boolean cancelled = taskManager.cancelTask(taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", cancelled ? "cancelling" : "not_found");
        return Response.ok(result).build();
    }

    @GET
    @Path("/api/diatom/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response apiHealth() {
        return health();
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

    private Map<String, Object> workerSummary(WorkerInfo w) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("workerId", w.getWorkerId());
        s.put("host", w.getHost());
        s.put("port", w.getPort());
        s.put("model", w.getModel());
        s.put("status", w.getStatus().name());
        s.put("currentLoad", w.getMetrics().getCurrentLoad());
        s.put("lastHeartbeat", w.getMetrics().getLastHeartbeat());
        return s;
    }

    private Map<String, Object> taskSummary(TaskState t) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("taskId", t.getTaskId());
        s.put("status", t.getStatus().name());
        s.put("workerId", t.getWorkerId());
        s.put("currentStep", t.getCurrentStep());
        s.put("totalTokens", t.getTotalTokens());
        s.put("createdAt", t.getCreatedAt());
        s.put("updatedAt", t.getUpdatedAt());
        return s;
    }

    private static Map<String, Object> errorMap(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        return result;
    }

    private static Response error(int status, String message) {
        return Response.status(status).entity(errorMap(message)).build();
    }

    private static Response unavailable(String message) {
        return Response.status(503).entity(errorMap(message)).build();
    }
}
