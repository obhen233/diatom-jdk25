package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.checkpoint.CheckpointService;
import com.github.obhen233.core.gateway.http.docs.GatewayApi;
import com.github.obhen233.core.gateway.collaboration.CompositeTaskManager;
import com.github.obhen233.core.gateway.collaboration.Pipeline;
import com.github.obhen233.core.gateway.collaboration.PipelineOrchestrator;
import com.github.obhen233.core.gateway.http.dto.SseEvent;
import com.github.obhen233.core.gateway.model.ChatRequest;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.core.gateway.sync.ProjectSyncService;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.transport.AsyncTaskTransport;
import com.github.obhen233.core.gateway.transport.TransportCallback;
import com.github.obhen233.core.gateway.transport.TransportResponse;
import com.github.obhen233.spi.ClusterCoordinator;
import com.github.obhen233.spi.TaskQueueProvider;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.net.HttpURLConnection;
import java.net.URL;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Handles chat, stream, worker proxy, command, collab, and deploy endpoints.
 */
class GatewayChatHandler {

    private final GatewayHttpServer server;

    GatewayChatHandler(GatewayHttpServer server) {
        this.server = server;
    }

    void registerRoutes() {
        // Chat (user entry point) — only in daemon mode
        if (server.isDaemonize()) {
            server.getServerSpi().addHandler("POST", "/gateway/v1/chat", this::handleChat);
            server.getServerSpi().addHandler("POST", "/gateway/v1/chat/stream", this::handleChatStream);
            LoggerFactory.getLogger(GatewayChatHandler.class).info("Chat endpoints enabled (daemon mode)");

            // Collaborative task endpoint — only in daemon mode when CompositeTaskManager available
            if (server.getCompositeTaskManager() != null) {
                server.getServerSpi().addHandler("POST", "/gateway/v1/collab", this::handleCollab);
                LoggerFactory.getLogger(GatewayChatHandler.class).info("Collab endpoint enabled (daemon mode)");
            } else {
                LoggerFactory.getLogger(GatewayChatHandler.class).info("Collab endpoint disabled (no CompositeTaskManager)");
            }

            // Deploy endpoint — route deploy requests to capable workers
            server.getServerSpi().addHandler("POST", "/gateway/v1/deploy", this::handleDeploy);
            LoggerFactory.getLogger(GatewayChatHandler.class).info("Deploy endpoint enabled (daemon mode)");
        } else {
            LoggerFactory.getLogger(GatewayChatHandler.class).info("Chat endpoints disabled (CLI mode)");
        }

        // Worker proxy endpoint — allows upstream Gateway to use this Gateway as a Worker
        server.getServerSpi().addHandler("POST", "/worker/v1/chat", this::handleWorkerChat);
        server.getServerSpi().addHandler("POST", "/worker/v1/command", this::handleWorkerCommand);
        LoggerFactory.getLogger(GatewayChatHandler.class).info("Worker proxy endpoint enabled at /worker/v1/chat");
    }

    private GatewayAgent getGatewayAgent() { return server.getGatewayAgent(); }
    private CapabilityRouter getCapabilityRouter() { return server.getCapabilityRouter(); }
    private TaskManager getTaskManager() { return server.getTaskManager(); }
    private WorkerRegistry getRegistry() { return server.getRegistry(); }
    private AsyncTaskTransport getTransport() { return server.getTransport(); }
    private CompositeTaskManager getCompositeTaskManager() { return server.getCompositeTaskManager(); }
    private PipelineOrchestrator getPipelineOrchestrator() { return server.getPipelineOrchestrator(); }
    private Pipeline getAnalysisExecPipeline() { return server.getAnalysisExecPipeline(); }
    private ProjectSyncService getProjectSyncService() { return server.getProjectSyncService(); }
    private ClusterCoordinator getClusterCoordinator() { return server.getClusterCoordinator(); }
    private CheckpointService getCheckpointService() { return server.getCheckpointService(); }
    private GatewayAdmissionControl getAdmissionControl() { return server.getAdmissionControl(); }
    private boolean isQueueEnabled() { return server.isQueueEnabled(); }
    private TaskQueueProvider getTaskQueueProvider() { return server.getTaskQueueProvider(); }

    @GatewayApi(path = "/gateway/v1/chat", methods = {"POST"},
            summary = "Chat completion",
            description = "Send a chat message to the Gateway. Gateway routes to the best available Worker via CapabilityRouter.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header. All POST/PUT/DELETE requests must carry security headers:\n"
                    + "- X-Diatom-Auth: shared authentication token\n"
                    + "- X-Diatom-Instance-Id: sender instance ID for tracing\n\n"
                    + "【请求流程 / Request Flow】\n"
                    + "--- Sync Mode (default) ---\n"
                    + "1. User sends message to Gateway\n"
                    + "2. Gateway analyzes request via GatewayAgent\n"
                    + "3. CapabilityRouter selects best Worker\n"
                    + "4. Gateway forwards message to selected Worker\n"
                    + "5. Worker processes and returns response\n"
                    + "6. Gateway returns unified response (200)\n\n"
                    + "--- Async Queue Mode (--queue true) ---\n"
                    + "1. User sends message to Gateway\n"
                    + "2. Gateway creates task and enqueues it\n"
                    + "3. Gateway returns 202 Accepted with taskId immediately\n"
                    + "4. Background consumer dequeues and processes via Worker\n"
                    + "5. Poll /gateway/v1/tasks for completion status\n\n"
                    + "If pipeline is recommended and diverse workers are available, Gateway may execute via PipelineOrchestrator instead of single Worker routing.\n\n"
                    + "Body fields: message (required), sessionId (optional, auto-generated if omitted), taskId (optional, for queue mode), stream (optional, use /chat/stream for streaming).",
            tags = {"核心 / Core"},
            requestBody = "{\"message\":\"Hello\",\"sessionId\":\"sess-001\",\"stream\":false}",
            responseBody = "{\"taskId\":\"task-xxx\",\"status\":\"queued\",\"queueDepth\":0}  (queue mode 202)\n{\"taskId\":\"task-xxx\",\"response\":\"Hello! How can I help you?\",\"worker\":{\"id\":\"w1\",\"model\":\"claude-sonnet-4-6\"}}  (sync mode 200)")
    private void handleChat(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        String message = extractMessageText(body);
        String sessionId = extractJsonValue(body, "sessionId");

        if (message == null || message.isEmpty()) {
            sendError(response, 400, "Missing message");
            return;
        }

        // Queue mode: return 202 immediately, process asynchronously
        if (isQueueEnabled() && getTaskQueueProvider() != null) {
            String taskId = extractJsonValue(body, "taskId");
            if (taskId == null || taskId.isEmpty()) {
                taskId = getTaskManager().createTask(sessionId != null ? sessionId : "unknown", message);
            }
            try {
                getTaskQueueProvider().enqueue(new TaskQueueProvider.QueuedTask(taskId, sessionId, message, body));
                String json = "{\"taskId\":\"" + safe(taskId) + "\",\"status\":\"queued\",\"queueDepth\":" + getTaskQueueProvider().getQueueDepth() + "}";
                sendJson(response, 202, json);
                LoggerFactory.getLogger(GatewayChatHandler.class).info("Task queued: {} (queueDepth={})", taskId, getTaskQueueProvider().getQueueDepth());
            } catch (Exception e) {
                String json = "{\"error\":\"Queue full: " + escapeJson(e.getMessage()) + "\",\"taskId\":\"" + safe(taskId) + "\"}";
                sendJson(response, 503, json);
            }
            return;
        }

        // Admission control: reject if too many concurrent requests (sync mode only)
        if (request instanceof JdkServerRequest) {
            if (!getAdmissionControl().tryAcquirePermit(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        // Note: body, message, sessionId were already parsed above (shared with queue mode path)
        String taskId = extractJsonValue(body, "taskId");
        if (taskId == null || taskId.isEmpty()) {
            taskId = getTaskManager().createTask(sessionId != null ? sessionId : "unknown", message);
        }

        // Check for reduce/collab mode
        String reduceStr = extractJsonValue(body, "reduce");
        boolean reduceMode = "true".equalsIgnoreCase(reduceStr);
        if (reduceMode && getCompositeTaskManager() != null) {
            try {
                TaskRequirement requirement = buildRequirementFromJson(body);
                String result = getCompositeTaskManager().submitCollaborativeTask(
                        sessionId != null ? sessionId : "http-" + UUID.randomUUID().toString().substring(0, 8),
                        message, requirement, Paths.get(resolveWorkspaceDir()));
                String json = "{\"taskId\":\"" + safe(taskId) + "\",\"status\":\"completed\",\"mode\":\"collab\",\"result\":" + result + "}";
                sendJson(response, 200, json);
            } catch (Exception e) {
                LoggerFactory.getLogger(GatewayChatHandler.class).error("Collab request failed", e);
                String json = "{\"error\":\"" + escapeJson(e.getMessage()) + "\",\"taskId\":\"" + safe(taskId) + "\"}";
                sendJson(response, 500, json);
            } finally {
                getAdmissionControl().releasePermit(null);
            }
            return;
        }

        String workerId = null;
        try {
            // 1. Analyze request via GatewayAgent
            TaskRequirement requirement = getGatewayAgent().analyzeRequest(message);

            // 1b. Auto-detect pipeline (if LLM recommends + diverse workers + orchestrator available)
            if (requirement.isPipelineRecommended() && getPipelineOrchestrator() != null
                    && getAnalysisExecPipeline() != null && getAdmissionControl().hasDiverseWorkers()) {
                PipelineOrchestrator.PipelineResult pipelineResult =
                        getPipelineOrchestrator().execute(getAnalysisExecPipeline(), message);
                String stagesJson = getAdmissionControl().buildPipelineStagesJson(pipelineResult);
                String responseText = pipelineResult.getLastResponse();
                if (responseText == null) responseText = "";
                String json = "{\"taskId\":\"" + safe(taskId) + "\",\"status\":\"completed\""
                        + ",\"pipeline\":\"" + safe(getAnalysisExecPipeline().getName()) + "\""
                        + ",\"stages\":" + stagesJson
                        + ",\"response\":\"" + escapeJson(responseText) + "\"}";
                sendJson(response, 200, json);
                LoggerFactory.getLogger(GatewayChatHandler.class).info("Pipeline auto-routed: task={} pipeline={}", taskId, getAnalysisExecPipeline().getName());
                return;
            }

            // 1b. Check distributed session affinity (HA mode)
            if (sessionId != null && getClusterCoordinator() != null && getClusterCoordinator().isActive()) {
                String affinityWorkerId = getClusterCoordinator().retrieve("session", sessionId);
                if (affinityWorkerId != null && !affinityWorkerId.isEmpty()) {
                    WorkerInfo affinityWorker = getRegistry().getWorker(affinityWorkerId);
                    if (affinityWorker != null && affinityWorker.isAvailable()) {
                        requirement.setSuggestedWorkerId(affinityWorkerId);
                        requirement.setReasoning("session affinity (HA cluster)");
                        LoggerFactory.getLogger(GatewayChatHandler.class).debug("Session affinity: session={} -> worker={}", sessionId, affinityWorkerId);
                    } else {
                        // Worker no longer available, clear affinity
                        getClusterCoordinator().remove("session", sessionId);
                        LoggerFactory.getLogger(GatewayChatHandler.class).debug("Session affinity cleared: session={} worker={} unavailable", sessionId, affinityWorkerId);
                    }
                }
            }

            // 2. Route to best worker via CapabilityRouter
            WorkerInfo selected = getCapabilityRouter().routeWithLLMSuggestion(requirement);
            if (selected == null) {
                String json = "{\"error\":\"No available workers\",\"taskId\":\"" + safe(taskId) + "\"}";
                sendJson(response, 503, json);
                return;
            }

            // 2a. Write session affinity after routing (HA mode)
            if (sessionId != null && getClusterCoordinator() != null && getClusterCoordinator().isActive()) {
                getClusterCoordinator().store("session", sessionId, selected.getWorkerId(), 3600);
            }

            // Track active request for this worker
            workerId = selected.getWorkerId();
            getCapabilityRouter().incrementActive(workerId);

            getTaskManager().assignTask(taskId, workerId);
            getTaskManager().startTask(taskId);
            // Store workspace path on task state for migration context
            TaskState taskState = getTaskManager().getTask(taskId);
            if (taskState != null) {
                taskState.addAttribute("workspacePath", resolveWorkspaceDir());
            }

            // 2b. 工作区同步决策 — 在发送 chat 前判断是否需要推送项目文件
            String syncStrategy = requirement.getSyncStrategy();
            if ("full_sync".equals(syncStrategy)) {
                String gatewayWorkspace = resolveWorkspaceDir();
                try {
                    ProjectSyncService.ProjectSize size = getProjectSyncService().estimateProjectSize(Paths.get(gatewayWorkspace));
                    if (getAdmissionControl().canPushZip(size, selected, 0)) {
                        byte[] projectZip = getProjectSyncService().packProject(Paths.get(gatewayWorkspace));
                        if (getAdmissionControl().canPushZip(size, selected, projectZip.length)) {
                            LoggerFactory.getLogger(GatewayChatHandler.class).info("Pushing project to worker {}: {} files, {} MB, zip={} KB, est transfer time={}s",
                                    selected.getWorkerId(), size.fileCount, size.totalBytes / 1024 / 1024,
                                    projectZip.length / 1024,
                                    (projectZip.length * 8L) / estimateBandwidthBps(selected) * 1000);
                            getAdmissionControl().pushProjectToWorker(selected, gatewayWorkspace, taskId, projectZip);
                        } else {
                            LoggerFactory.getLogger(GatewayChatHandler.class).warn("After packing: insufficient memory for zip push ({} MB zip, {} MB available), skipping.",
                                    projectZip.length / 1024 / 1024, getAvailableMemory() / 1024 / 1024);
                            syncStrategy = "skip";
                        }
                    } else {
                        LoggerFactory.getLogger(GatewayChatHandler.class).warn("Project too large for zip push to worker {}: {} files, {} MB, latency={}ms. Recommend NAS/OSS.",
                                selected.getWorkerId(), size.fileCount, size.totalBytes / 1024 / 1024,
                                selected.getMetrics().getAvgLatencyMs());
                        syncStrategy = "skip";
                    }
                } catch (Exception e) {
                    LoggerFactory.getLogger(GatewayChatHandler.class).warn("Project sync failed for worker {}: {}. Falling back to skip.", selected.getWorkerId(), e.getMessage());
                    syncStrategy = "skip";
                }
            }

            // 3. Build ChatRequest and send via async transport
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setTaskId(taskId);
            chatRequest.setSessionId(sessionId);
            chatRequest.setMessage(message);
            chatRequest.setSyncStrategy(syncStrategy);
            chatRequest.setGatewayUrl(getAdmissionControl().getGatewayBaseUrl(selected));
            chatRequest.setWorkspacePath(resolveWorkspaceDir());

            CompletableFuture<TransportResponse> future = new CompletableFuture<>();
            getTransport().sendTaskAsync(selected, chatRequest, 600000, new TransportCallback() {
                @Override
                public void onSuccess(String workerId, TransportResponse transportResponse) {
                    future.complete(transportResponse);
                }
                @Override
                public void onFailure(String workerId, String error) {
                    future.completeExceptionally(new RuntimeException("Transport failed: " + error));
                }
                @Override
                public void onTimeout(String workerId) {
                    future.completeExceptionally(new TimeoutException("Request timed out for worker " + workerId));
                }
            });

            // 4. Wait for response
            TransportResponse transportResponse;
            try {
                transportResponse = future.get(600000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                String json = "{\"error\":\"" + safe(e.getMessage()) + "\",\"taskId\":\"" + safe(taskId) + "\"}";
                sendJson(response, 500, json);
                return;
            }

            // 5a. Extract and apply fileDiffs from worker response if project was synced
            if ("full_sync".equals(syncStrategy)) {
                String responseBodyStr = transportResponse.getBody();
                getAdmissionControl().applyFileDiffsFromResponse(responseBodyStr);
            }

            // 5b. Return unified response
            String json = buildUnifiedResponse(taskId, workerId, selected, transportResponse.getBody());
            sendJson(response, 200, json);
            LoggerFactory.getLogger(GatewayChatHandler.class).info("Chat request completed: task={} -> worker={}", taskId, workerId);
        } finally {
            getAdmissionControl().releasePermit(workerId);
        }
    }

    /**
     * SSE 流式 Chat 响应
     * 客户端通过 POST 发起请求，服务端通过 SSE 推送流式响应
     */
    @GatewayApi(path = "/gateway/v1/chat/stream", methods = {"POST"},
            summary = "Streaming chat completion (SSE)",
            description = "Send a chat message and receive a Server-Sent Events (SSE) stream response.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【SSE 事件格式 / SSE Event Format】\n"
                    + "The response is an SSE stream with the following event types:\n\n"
                    + "1. event: token — streaming text chunk\n"
                    + "   data: {\"content\":\"partial response text\"}\n\n"
                    + "2. event: complete — task completed\n"
                    + "   data: {\"taskId\":\"...\",\"workerMeta\":{...},\"fileDiffs\":[...]}\n\n"
                    + "3. event: error — error occurred\n"
                    + "   data: {\"taskId\":\"...\",\"error\":\"error message\"}\n\n"
                    + "The stream is terminated by the server with a \"data: [DONE]\" sentinel.",
            tags = {"核心 / Core"},
            requestBody = "{\"message\":\"Hello\",\"sessionId\":\"sess-001\",\"stream\":true}",
            responseBody = "event: token\ndata: {\"content\":\"Hello\"}\n\nevent: complete\ndata: {\"taskId\":\"task-xxx\"}\n\n")
    private void handleChatStream(ServerRequest request, ServerResponse response) throws IOException {
        if (!"POST".equals(request.getMethod())) {
            sendError(response, 405, "Method not allowed");
            return;
        }

        // Authenticate incoming request
        if (request instanceof JdkServerRequest) {
            if (!getAdmissionControl().authenticateRequest(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        // Admission control: reject if too many concurrent requests
        if (request instanceof JdkServerRequest) {
            if (!getAdmissionControl().tryAcquirePermit(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        String body = readBody(request);
        String taskId = extractJsonValue(body, "taskId");
        String message = extractMessageText(body);
        String sessionId = extractJsonValue(body, "sessionId");

        if (message == null || message.isEmpty()) {
            sendError(response, 400, "Missing message");
            getAdmissionControl().releasePermit(null);
            return;
        }

        if (taskId == null || taskId.isEmpty()) {
            taskId = getTaskManager().createTask(sessionId != null ? sessionId : "unknown", message);
        }

        // Check for reduce/collab mode
        String reduceStr = extractJsonValue(body, "reduce");
        boolean reduceMode = "true".equalsIgnoreCase(reduceStr);
        if (reduceMode && getCompositeTaskManager() != null) {
            try {
                TaskRequirement requirement = buildRequirementFromJson(body);
                String result = getCompositeTaskManager().submitCollaborativeTask(
                        sessionId != null ? sessionId : "http-" + UUID.randomUUID().toString().substring(0, 8),
                        message, requirement, Paths.get(resolveWorkspaceDir()));
                // SSE response
                response.setHeader("Content-Type", "text/event-stream; charset=UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("Connection", "keep-alive");
                OutputStream os = response.getOutputStream();
                SseEvent collabSse = new SseEvent("result", taskId);
                collabSse.mode = "collab";
                collabSse.result = GatewayConfirmHandler.confirmMapper.readTree(result);
                collabSse.writeTo(os);
                os.flush();
                os.close();
            } catch (Exception e) {
                LoggerFactory.getLogger(GatewayChatHandler.class).error("Collab request failed (streaming)", e);
                sendError(response, 500, "Collab failed: " + e.getMessage());
            } finally {
                getAdmissionControl().releasePermit(null);
            }
            return;
        }

        String workerId = null;
        try {
            // 1. Analyze request
            TaskRequirement requirement = getGatewayAgent().analyzeRequest(message);

            // 1b. Check distributed session affinity (HA mode)
            if (sessionId != null && getClusterCoordinator() != null && getClusterCoordinator().isActive()) {
                String affinityWorkerId = getClusterCoordinator().retrieve("session", sessionId);
                if (affinityWorkerId != null && !affinityWorkerId.isEmpty()) {
                    WorkerInfo affinityWorker = getRegistry().getWorker(affinityWorkerId);
                    if (affinityWorker != null && affinityWorker.isAvailable()) {
                        requirement.setSuggestedWorkerId(affinityWorkerId);
                        requirement.setReasoning("session affinity (HA cluster)");
                        LoggerFactory.getLogger(GatewayChatHandler.class).debug("Session affinity (stream): session={} -> worker={}", sessionId, affinityWorkerId);
                    } else {
                        getClusterCoordinator().remove("session", sessionId);
                    }
                }
            }

            // 2. Route to best worker
            WorkerInfo selected = getCapabilityRouter().routeWithLLMSuggestion(requirement);
            if (selected == null) {
                sendError(response, 503, "No available workers");
                return;
            }

            // 2a. Write session affinity (HA mode)
            if (sessionId != null && getClusterCoordinator() != null && getClusterCoordinator().isActive()) {
                getClusterCoordinator().store("session", sessionId, selected.getWorkerId(), 3600);
            }

            // Track active request for this worker
            workerId = selected.getWorkerId();
            getCapabilityRouter().incrementActive(workerId);

            getTaskManager().assignTask(taskId, workerId);
            getTaskManager().startTask(taskId);
            TaskState taskState = getTaskManager().getTask(taskId);
            if (taskState != null) {
                taskState.addAttribute("workspacePath", resolveWorkspaceDir());
            }

            // 2b. 工作区同步决策（Streaming 模式）
            String syncStrategy = requirement.getSyncStrategy();
            if ("full_sync".equals(syncStrategy)) {
                String gatewayWorkspace = resolveWorkspaceDir();
                try {
                    ProjectSyncService.ProjectSize size = getProjectSyncService().estimateProjectSize(Paths.get(gatewayWorkspace));
                    if (getAdmissionControl().canPushZip(size, selected, 0)) {
                        byte[] projectZip = getProjectSyncService().packProject(Paths.get(gatewayWorkspace));
                        if (getAdmissionControl().canPushZip(size, selected, projectZip.length)) {
                            LoggerFactory.getLogger(GatewayChatHandler.class).info("Pushing project to worker {} (streaming): {} files, {} MB, zip={} KB",
                                    selected.getWorkerId(), size.fileCount, size.totalBytes / 1024 / 1024,
                                    projectZip.length / 1024);
                            getAdmissionControl().pushProjectToWorker(selected, gatewayWorkspace, taskId, projectZip);
                        } else {
                            syncStrategy = "skip";
                        }
                    } else {
                        LoggerFactory.getLogger(GatewayChatHandler.class).warn("Project too large for zip push to worker {} (streaming): {} files, {} MB",
                                selected.getWorkerId(), size.fileCount, size.totalBytes / 1024 / 1024);
                        syncStrategy = "skip";
                    }
                } catch (Exception e) {
                    LoggerFactory.getLogger(GatewayChatHandler.class).warn("Project sync failed for worker {} (streaming): {}", selected.getWorkerId(), e.getMessage());
                    syncStrategy = "skip";
                }
            }

            // SSE response headers
            response.setHeader("Content-Type", "text/event-stream; charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");

            OutputStream os = response.getOutputStream();
            try {
                // Send initial event with routing info
                SseEvent routedSse = SseEvent.routedEvent(taskId);
                routedSse.worker = workerId;
                routedSse.writeTo(os);
                os.flush();

                // Build ChatRequest and send via async transport
                ChatRequest chatRequest = new ChatRequest();
                chatRequest.setTaskId(taskId);
                chatRequest.setSessionId(sessionId);
                chatRequest.setMessage(message);
                chatRequest.setSyncStrategy(syncStrategy);
                chatRequest.setGatewayUrl(getAdmissionControl().getGatewayBaseUrl(selected));
                chatRequest.setWorkspacePath(resolveWorkspaceDir());

                CompletableFuture<TransportResponse> future = new CompletableFuture<>();
                getTransport().sendTaskAsync(selected, chatRequest, 600000, new TransportCallback() {
                    @Override
                    public void onSuccess(String workerId, TransportResponse transportResponse) {
                        future.complete(transportResponse);
                    }
                    @Override
                    public void onFailure(String workerId, String error) {
                        future.completeExceptionally(new RuntimeException("Transport failed: " + error));
                    }
                    @Override
                    public void onTimeout(String workerId) {
                        future.completeExceptionally(new TimeoutException("Request timed out for worker " + workerId));
                    }
                });

                TransportResponse transportResponse;
                try {
                    transportResponse = future.get(600000, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    SseEvent errorSse = SseEvent.error(e.getMessage());
                    errorSse.writeTo(os);
                    return;
                }

                // Extract and apply fileDiffs if the project was synced
                String workerBody = transportResponse.getBody();
                if ("full_sync".equals(syncStrategy)) {
                    getAdmissionControl().applyFileDiffsFromResponse(workerBody);
                }

                // Parse worker response to extract text, meta, and diffs
                com.fasterxml.jackson.databind.JsonNode workerRoot = null;
                String textResponse = workerBody;
                com.fasterxml.jackson.databind.JsonNode workerMetaNode = null;
                com.fasterxml.jackson.databind.JsonNode fileDiffsNode = null;
                try {
                    workerRoot = GatewayConfirmHandler.confirmMapper.readTree(workerBody);
                    if (workerRoot.has("response") && !workerRoot.get("response").isNull()) {
                        textResponse = workerRoot.get("response").asText();
                    }
                    workerMetaNode = workerRoot.has("workerMeta") && !workerRoot.get("workerMeta").isNull()
                            ? workerRoot.get("workerMeta") : null;
                    fileDiffsNode = workerRoot.has("fileDiffs") && !workerRoot.get("fileDiffs").isNull()
                            && workerRoot.get("fileDiffs").isArray() && workerRoot.get("fileDiffs").size() > 0
                            ? workerRoot.get("fileDiffs") : null;
                } catch (Exception e) {
                    LoggerFactory.getLogger(GatewayChatHandler.class).warn("Failed to parse worker response for SSE: {}", e.getMessage());
                }

                // Send token event with plain text response
                SseEvent tokenSse = new SseEvent("token", null);
                tokenSse.content = textResponse;
                tokenSse.worker = workerId;
                tokenSse.writeTo(os);

                // Send completion event with metadata
                SseEvent completeSse = SseEvent.complete(taskId);
                completeSse.worker = workerId;
                if (workerMetaNode != null) {
                    completeSse.result = workerMetaNode;
                }
                if (fileDiffsNode != null) {
                    completeSse.fileDiffs = fileDiffsNode;
                }
                completeSse.writeTo(os);
            } catch (Exception e) {
                LoggerFactory.getLogger(GatewayChatHandler.class).error("Chat SSE stream failed for task: " + taskId, e);
                try {
                    SseEvent errorSse = SseEvent.error(e.getMessage());
                    errorSse.writeTo(os);
                } catch (Exception ignored) {}
            } finally {
                os.close();
            }
            LoggerFactory.getLogger(GatewayChatHandler.class).info("Chat SSE stream completed for task: {}", taskId);
        } finally {
            getAdmissionControl().releasePermit(workerId);
        }
    }

    /**
     * POST /gateway/v1/collab
     * 提交并行协同任务，将请求分解后分配到多个 Worker 并行执行
     * Body: {"message":"...","sessionId":"...","taskType":"...","capabilities":["..."]}
     */
    @GatewayApi(path = "/gateway/v1/collab", methods = {"POST"},
            summary = "Collaborative task execution",
            description = "Submit a collaborative task that may be split across multiple Workers and executed in parallel. Returns merged result from all Workers.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【协同流程 / Collaboration Flow】\n"
                    + "1. Gateway receives collaboration request\n"
                    + "2. CompositeTaskManager decomposes the task based on capabilities\n"
                    + "3. Multiple Workers execute sub-tasks in parallel via ParallelTaskExecutor\n"
                    + "4. Results are merged with file conflict resolution\n"
                    + "5. ResourceLockManager ensures exclusive access to shared resources\n\n"
                    + "Body fields: message (required), sessionId (optional), taskType, capabilities array.",
            tags = {"协同 / Collaboration"},
            requestBody = "{\"message\":\"Implement login page\",\"taskType\":\"development\",\"capabilities\":[\"frontend\",\"backend\"]}",
            responseBody = "{\"sessionId\":\"sess-001\",\"result\":\"...merged result...\"}")
    private void handleCollab(ServerRequest request, ServerResponse response) throws IOException {
        if (!"POST".equals(request.getMethod())) {
            sendError(response, 405, "Method not allowed");
            return;
        }

        if (getCompositeTaskManager() == null) {
            sendError(response, 503, "CompositeTaskManager not available");
            return;
        }

        // Authenticate incoming request
        if (request instanceof JdkServerRequest) {
            if (!getAdmissionControl().authenticateRequest(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        String body = readBody(request);
        String message = extractMessageText(body);
        String sessionId = extractJsonValue(body, "sessionId");

        if (message == null || message.isEmpty()) {
            sendError(response, 400, "Missing message");
            return;
        }

        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "http-" + UUID.randomUUID().toString().substring(0, 8);
        }

        try {
            // Build TaskRequirement from request JSON
            TaskRequirement requirement = buildRequirementFromJson(body);

            // Submit collaborative task
            String result = getCompositeTaskManager().submitCollaborativeTask(
                    sessionId, message, requirement, Paths.get(resolveWorkspaceDir()));

            // Return merged result
            String json = "{\"sessionId\":\"" + safe(sessionId) + "\",\"result\":" + result + "}";
            sendJson(response, 200, json);
            LoggerFactory.getLogger(GatewayChatHandler.class).info("Collab request completed: session={}", sessionId);
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayChatHandler.class).error("Collab request failed: session={}", sessionId, e);
            String json = "{\"error\":\"" + escapeJson(e.getMessage()) + "\",\"sessionId\":\"" + safe(sessionId) + "\"}";
            sendJson(response, 500, json);
        }
    }

    /**
     * Worker 代理模式：/worker/v1/chat
     * 使 Gateway 可以被上游 Gateway 当作 Worker 调用。
     * 接收 Worker 格式的请求，通过本地 Gateway 的 pipeline/路由处理，返回 Worker 格式响应。
     */
    @GatewayApi(path = "/worker/v1/chat", methods = {"POST"},
            summary = "Worker proxy chat (upstream Gateway to this Gateway as Worker)",
            description = "Allows an upstream Gateway to use this Gateway as a Worker. Routes to downstream Workers or executes pipeline.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【代理流程 / Proxy Flow】\n"
                    + "1. Upstream Gateway sends task to this Gateway as if it were a Worker\n"
                    + "2. This Gateway analyzes the request via its own GatewayAgent\n"
                    + "3. If pipeline recommended and diverse workers available, executes via PipelineOrchestrator\n"
                    + "4. Otherwise routes to a downstream Worker via CapabilityRouter\n"
                    + "5. Returns the result back to the upstream Gateway\n\n"
                    + "Body fields: taskId, message (required), sessionId.",
            tags = {"Worker 代理 / Worker Proxy"},
            requestBody = "{\"taskId\":\"task-001\",\"message\":\"Hello\"}",
            responseBody = "{\"taskId\":\"task-001\",\"response\":\"...\",\"workerMeta\":{}}")
    private void handleWorkerChat(ServerRequest request, ServerResponse response) throws IOException {
        if (!"POST".equals(request.getMethod())) {
            sendError(response, 405, "Method not allowed");
            return;
        }

        // Admission control: reject if too many concurrent requests
        if (request instanceof JdkServerRequest) {
            if (!getAdmissionControl().tryAcquirePermit(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        String body = readBody(request);
        String taskId = extractJsonValue(body, "taskId");
        String message = extractMessageText(body);
        if (message == null || message.isEmpty()) {
            String json = "{\"status\":\"error\",\"taskId\":\"" + safe(taskId)
                    + "\",\"error\":\"Missing message\"}";
            sendJson(response, 400, json);
            getAdmissionControl().releasePermit(null);
            return;
        }

        String workerId = null;
        try {
            LoggerFactory.getLogger(GatewayChatHandler.class).info("Worker proxy executing task={}, message={}", taskId, truncate(message, 200));

            // Analyze and route through local Gateway
            TaskRequirement requirement = getGatewayAgent().analyzeRequest(message);

            String respBody;
            if (requirement.isPipelineRecommended() && getPipelineOrchestrator() != null
                    && getAnalysisExecPipeline() != null && getAdmissionControl().hasDiverseWorkers()) {
                // Use local pipeline
                PipelineOrchestrator.PipelineResult pipelineResult =
                        getPipelineOrchestrator().execute(getAnalysisExecPipeline(), message);
                respBody = pipelineResult.getLastResponse();
                if (respBody == null) respBody = "";
                LoggerFactory.getLogger(GatewayChatHandler.class).info("Worker proxy completed via pipeline: task={}", taskId);
            } else {
                // Route to downstream worker
                WorkerInfo worker = getCapabilityRouter().routeWithLLMSuggestion(requirement);
                if (worker == null) {
                    String json = "{\"status\":\"error\",\"taskId\":\"" + safe(taskId)
                            + "\",\"error\":\"No available workers\"}";
                    sendJson(response, 503, json);
                    return;
                }
                workerId = worker.getWorkerId();
                getCapabilityRouter().incrementActive(workerId);
                String rawResponse = getAdmissionControl().proxyToWorker(worker, taskId, "", message);
                respBody = extractJsonValue(rawResponse, "response");
                if (respBody == null) respBody = rawResponse; // fallback
                LoggerFactory.getLogger(GatewayChatHandler.class).info("Worker proxy completed via worker {}: task={}", workerId, taskId);
            }

            String json = "{\"status\":\"ok\",\"taskId\":\"" + safe(taskId)
                    + "\",\"response\":\"" + escapeJson(respBody) + "\"}";
            sendJson(response, 200, json);
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayChatHandler.class).error("Worker proxy failed for task: " + taskId, e);
            String json = "{\"status\":\"error\",\"taskId\":\"" + safe(taskId)
                    + "\",\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            sendJson(response, 500, json);
        } finally {
            getAdmissionControl().releasePermit(workerId);
        }
    }

    /**
     * POST /worker/v1/command
     * Gateway-to-Gateway: handle commands when this Gateway acts as a Worker for an upstream Gateway.
     * Delegates to the same local config handler used by the monitor dashboard.
     */
    @GatewayApi(path = "/worker/v1/command", methods = {"POST"},
            summary = "Worker proxy command execution",
            description = "Execute a command on this Gateway (used by upstream Gateway for config/rules management).\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【用途 / Usage】\n"
                    + "Used by Monitor dashboard to proxy config management commands to Worker.\n"
                    + "Supported commands: rules list --json, rules add ..., config get/set, etc.\n"
                    + "The command is executed on the target Gateway/Worker and the output is returned as-is.",
            tags = {"Worker 代理 / Worker Proxy"},
            requestBody = "{\"command\":\"rules list --json\"}",
            responseBody = "{\"status\":\"ok\",\"output\":\"...\"}")
    private void handleWorkerCommand(ServerRequest request, ServerResponse response) throws IOException {
        if (!"POST".equals(request.getMethod())) {
            sendError(response, 405, "Method not allowed");
            return;
        }
        if (request instanceof JdkServerRequest) {
            if (!getAdmissionControl().authenticateRequest(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }
        String body = readBody(request);
        String command = extractJsonValue(body, "command");
        if (command == null || command.isEmpty()) {
            sendError(response, 400, "Missing 'command' field");
            return;
        }
        if (command.startsWith("config ")) {
            server.getMonitorHandler().handleGatewayLocalConfig(response, command);
        } else {
            Map<String, String> m = new HashMap<>();
            m.put("status", "error");
            m.put("error", "Unsupported command: " + command);
            sendJson(response, 400, m);
        }
    }

    /**
     * Deploy endpoint: /gateway/v1/deploy
     * Routes a deploy request to the best matching worker via CapabilityRouter,
     * then forwards the request to the worker's /worker/v1/deploy endpoint.
     *
     * Body: { "profile": "test", "workspaceHint": "/mnt/nas/project-x", "projectName": "my-project" }
     */
    @GatewayApi(path = "/gateway/v1/deploy", methods = {"POST"},
            summary = "Submit deployment task",
            description = "Submit a deployment task. Gateway routes to a Worker with deploy capability via CapabilityRouter.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【请求流程 / Request Flow】\n"
                    + "1. Gateway builds TaskRequirement with taskType=\"devops\", capabilities=[\"deploy\"]\n"
                    + "2. CapabilityRouter routes to best Worker with deploy capability\n"
                    + "3. Gateway sends deploy request to selected Worker\n"
                    + "4. Worker executes deployment (build, scp, ssh commands)\n"
                    + "5. Worker returns deployment result\n\n"
                    + "Body fields: profile (deploy profile, e.g. \"production\"), workspaceHint (required), projectName.",
            tags = {"部署 / Deploy"},
            requestBody = "{\"profile\":\"production\",\"workspaceHint\":\"/mnt/nas/project-x\",\"projectName\":\"my-app\"}",
            responseBody = "{\"status\":\"deployed\",\"workerId\":\"w1\",\"taskId\":\"task-xxx\"}")
    private void handleDeploy(ServerRequest request, ServerResponse response) throws IOException {
        if (!"POST".equals(request.getMethod())) {
            sendError(response, 405, "Method not allowed");
            return;
        }

        // Authenticate incoming request
        if (request instanceof JdkServerRequest) {
            if (!getAdmissionControl().authenticateRequest(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        // Admission control
        if (request instanceof JdkServerRequest) {
            if (!getAdmissionControl().tryAcquirePermit(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        String body = readBody(request);
        String profile = extractJsonValue(body, "profile");
        String workspaceHint = extractJsonValue(body, "workspaceHint");
        String projectName = extractJsonValue(body, "projectName");

        if (workspaceHint == null || workspaceHint.isEmpty()) {
            String json = "{\"status\":\"error\",\"error\":\"Missing workspaceHint\"}";
            sendJson(response, 400, json);
            getAdmissionControl().releasePermit(null);
            return;
        }

        String workerId = null;
        try {
            // 1. Build a TaskRequirement for deploy routing
            TaskRequirement requirement = new TaskRequirement();
            requirement.setTaskType("devops");
            java.util.List<String> caps = new java.util.ArrayList<>();
            caps.add("deploy");
            requirement.setRequiredCapabilities(caps);
            requirement.setComplexity(5);
            requirement.setWorkspaceHint(workspaceHint);
            requirement.setPipelineRecommended(false);

            // 2. Route to best worker via CapabilityRouter
            WorkerInfo selected = getCapabilityRouter().routeWithLLMSuggestion(requirement);
            if (selected == null) {
                String json = "{\"status\":\"error\",\"error\":\"No available workers with deploy capability\"}";
                sendJson(response, 503, json);
                return;
            }

            workerId = selected.getWorkerId();
            LoggerFactory.getLogger(GatewayChatHandler.class).info("Deploy routing: project={}, profile={}, workspace={}, worker={}",
                    projectName, profile, workspaceHint, workerId);

            // 3. Forward to Worker's /worker/v1/deploy via HTTP POST
            String targetUrl = selected.getBaseUrl() + "/worker/v1/deploy";
            String requestBody = "{"
                    + "\"profile\":\"" + (profile != null ? escapeJson(profile) : "")
                    + "\",\"workspaceHint\":\"" + escapeJson(workspaceHint)
                    + "\",\"projectName\":\"" + (projectName != null ? escapeJson(projectName) : "")
                    + "\"}";

            URL url = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(600000); // 10 min for deploy

            // Inject security headers
            com.github.obhen233.core.gateway.security.SecurityHeadersInjector deployInjector =
                    new com.github.obhen233.core.gateway.security.SecurityHeadersInjector(
                    com.github.obhen233.core.gateway.security.SecurityProviderLoader.getAuthProvider(),
                    com.github.obhen233.core.gateway.security.SecurityProviderLoader.getEncryptionProvider());
            deployInjector.injectIntoConnection(conn, selected.getWorkerId());

            byte[] deployBytes = requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.util.Map<String, String> deployEncHeaders = new java.util.HashMap<>();
            deployBytes = com.github.obhen233.core.gateway.security.SecurityHeadersInjector.encryptBody(deployBytes, selected.getWorkerId(), deployEncHeaders);
            for (java.util.Map.Entry<String, String> e : deployEncHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(deployBytes);
            }

            int responseCode = conn.getResponseCode();
            String responseBody;
            try (java.io.InputStream is = responseCode >= 200 && responseCode < 300
                    ? conn.getInputStream() : conn.getErrorStream()) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                responseBody = baos.toString("UTF-8");
            }
            conn.disconnect();

            // 4. Return combined response
            String json = "{\"status\":\"" + (responseCode == 200 ? "completed" : "error")
                    + "\",\"taskId\":\"deploy-" + safe(projectName) + "\""
                    + ",\"worker\":\"" + safe(workerId) + "\""
                    + ",\"workerUrl\":\"" + safe(selected.getBaseUrl()) + "\""
                    + ",\"workerResponse\":" + responseBody + "}";
            sendJson(response, responseCode == 200 ? 200 : 502, json);
            LoggerFactory.getLogger(GatewayChatHandler.class).info("Deploy request completed: project={}, profile={}, worker={}, http={}",
                    projectName, profile, workerId, responseCode);
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayChatHandler.class).error("Deploy request failed for project: " + projectName, e);
            String json = "{\"status\":\"error\",\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            sendJson(response, 500, json);
        } finally {
            getAdmissionControl().releasePermit(workerId);
        }
    }

    /**
     * 从请求 JSON 中构建 TaskRequirement
     */
    private TaskRequirement buildRequirementFromJson(String body) {
        TaskRequirement req = new TaskRequirement();

        String taskType = extractJsonValue(body, "taskType");
        if (taskType != null) {
            req.setTaskType(taskType);
        }

        // Parse capabilities array: "["cap1","cap2"]"
        String capsStr = extractJsonValue(body, "capabilities");
        if (capsStr != null && !capsStr.isEmpty()) {
            // Support both JSON array and comma-separated string
            if (capsStr.startsWith("[")) {
                java.util.List<String> caps = new java.util.ArrayList<>();
                String inner = capsStr.substring(1, capsStr.length() - 1);
                if (!inner.isEmpty()) {
                    String[] parts = inner.split(",");
                    for (String p : parts) {
                        String trimmed = p.trim();
                        if (trimmed.startsWith("\"")) trimmed = trimmed.substring(1);
                        if (trimmed.endsWith("\"")) trimmed = trimmed.substring(0, trimmed.length() - 1);
                        if (!trimmed.isEmpty()) caps.add(trimmed);
                    }
                }
                req.setRequiredCapabilities(caps);
            } else {
                java.util.List<String> caps = new java.util.ArrayList<>();
                caps.add(capsStr);
                req.setRequiredCapabilities(caps);
            }
        }

        String complexity = extractJsonValue(body, "complexity");
        if (complexity != null) {
            try {
                req.setComplexity(Integer.parseInt(complexity));
            } catch (NumberFormatException ignored) {}
        }

        return req;
    }

    // ---- Static helpers (duplicated from AdmissionControl for bandwidth estimation, etc.) ----

    /**
     * 根据 Worker 延迟估算带宽。
     * 简单模型：延迟越低 → 带宽越高。
     * 延迟 1ms → ~100MB/s，延迟 100ms → ~1MB/s
     */
    static long estimateBandwidthBps(WorkerInfo worker) {
        double latencyMs = worker.getMetrics().getAvgLatencyMs();
        if (latencyMs <= 0) latencyMs = 1;
        return (long) Math.max(1_000_000, 100_000_000L / latencyMs);
    }

    /**
     * 获取 JVM 当前可用内存（字节）
     */
    static long getAvailableMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.freeMemory() + (rt.maxMemory() - rt.totalMemory());
    }
}
