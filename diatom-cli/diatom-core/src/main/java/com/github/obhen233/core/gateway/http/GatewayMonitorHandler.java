package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.SystemConfigDao.SystemConfig;
import com.github.obhen233.core.gateway.checkpoint.CheckpointReport;
import com.github.obhen233.core.gateway.http.dto.ConcurrencyInfo;
import com.github.obhen233.core.gateway.http.dto.MonitorStatusResponse;
import com.github.obhen233.core.gateway.http.dto.TaskStateSummary;
import com.github.obhen233.core.gateway.http.dto.WorkerInfoWithActiveRequests;
import com.github.obhen233.core.gateway.http.dto.TopologyData;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.security.SecurityHeadersInjector;
import com.github.obhen233.core.gateway.security.SecurityProviderLoader;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;
import com.github.obhen233.core.gateway.topology.TopologyDef;
import com.github.obhen233.core.gateway.topology.TopologyService;
import com.github.obhen233.core.gateway.topology.TopologyVersion;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Extracted monitor-related HTTP handler methods from GatewayHttpServer.
 * All field access goes through server.getXxx() getters.
 */
public class GatewayMonitorHandler {

    private static final Logger logger = LoggerFactory.getLogger(GatewayMonitorHandler.class);

    private final GatewayHttpServer server;
    private ScheduledExecutorService tokenCleanupScheduler;

    public GatewayMonitorHandler(GatewayHttpServer server) {
        this.server = server;
    }

    // ─────────────────────────────────────────────────────────────────
    // Monitor enable check
    // ─────────────────────────────────────────────────────────────────

    boolean isMonitorEnabled() {
        ConfigManager cm = server.getConfigManager();
        if (cm == null) return false;
        String val = cm.get("monitor.enabled");
        return val == null || "true".equalsIgnoreCase(val.trim());
    }

    // ─────────────────────────────────────────────────────────────────
    // Main monitor router
    // ─────────────────────────────────────────────────────────────────

    void handleMonitorRouter(ServerRequest request, ServerResponse response) throws IOException {
        String path = ((JdkServerRequest) request).getExchange().getRequestURI().getPath();
        String method = request.getMethod();
        String p = "/" + server.getMonitorPrefix();

        // API routes
        if (path.equals(p + "/api/login")) {
            if (!"POST".equals(method)) { sendError(response, 405, "Method not allowed"); return; }
            handleMonitorApiLogin(request, response);
            return;
        }
        if (path.equals(p + "/api/status")) {
            if (!"GET".equals(method)) { sendError(response, 405, "Method not allowed"); return; }
            if (!requireMonitorAuth(request, response)) return;
            handleMonitorApiStatus(request, response);
            return;
        }
        if (path.equals(p + "/api/config")) {
            if (!"POST".equals(method)) { sendError(response, 405, "Method not allowed"); return; }
            if (!requireMonitorAuth(request, response)) return;
            handleMonitorApiConfig(request, response);
            return;
        }
        if (path.equals(p + "/api/i18n")) {
            if (!"GET".equals(method)) { sendError(response, 405, "Method not allowed"); return; }
            handleMonitorApiI18n(request, response);
            return;
        }
        if (path.equals(p + "/api/rules")) {
            if (!requireMonitorAuth(request, response)) return;
            if ("GET".equals(method)) { handleMonitorApiRules(request, response); return; }
            else if ("POST".equals(method)) { handleMonitorApiRulesSave(request, response); return; }
            else { sendError(response, 405, "Method not allowed"); return; }
        }

        boolean authConfigured = isMonitorAuthConfigured();
        if (path.equals(p + "/") || path.equals(p)) {
            if (authConfigured) server.serveStaticResource(request, response, "monitor/login.html");
            else server.serveStaticResource(request, response, "monitor/dashboard.html");
        } else if (path.equals(p + "/login")) {
            server.serveStaticResource(request, response, "monitor/login.html");
        } else if (path.endsWith(".js")) {
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if ("vis-network.min.js".equals(filename)) server.serveStaticResource(request, response, "monitor/vis-network.min.js");
            else if ("echarts.min.js".equals(filename)) server.serveStaticResource(request, response, "monitor/echarts.min.js");
            else sendError(response, 404, "Not found");
        } else {
            sendError(response, 404, "Not found");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Workspace router
    // ─────────────────────────────────────────────────────────────────

    void handleWorkspaceRouter(ServerRequest request, ServerResponse response) throws IOException {
        String path = ((JdkServerRequest) request).getExchange().getRequestURI().getPath();
        String method = request.getMethod();
        String p = "/" + server.getMonitorPrefix();

        if (path.equals(p + "/workspace/api/topology") || path.equals(p + "/routing/api/topology")) {
            if (!"GET".equals(method)) { sendError(response, 405, "Method not allowed"); return; }
            if (!requireMonitorAuth(request, response)) return;
            handleTopologyApi(request, response);
            return;
        }
        if (path.equals(p + "/workspace/api/tasks")) {
            if (!"GET".equals(method)) { sendError(response, 405, "Method not allowed"); return; }
            if (!requireMonitorAuth(request, response)) return;
            handleWorkspaceApiTasks(request, response);
            return;
        }
        if (path.matches(".*/workspace/api/task/[^/]+/cancel$")) {
            if (!"POST".equals(method)) { sendError(response, 405, "Method not allowed"); return; }
            if (!requireMonitorAuth(request, response)) return;
            String apiPrefix = p + "/workspace/api/task/";
            String taskId = path.substring(apiPrefix.length(), path.length() - "/cancel".length());
            TaskManager tm = server.getTaskManager();
            boolean cancelled = tm != null && tm.cancelTask(taskId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", cancelled ? "cancelled" : "not_found");
            sendJson(response, cancelled ? 200 : 404, resp);
            return;
        }
        if (path.equals(p + "/workspace")) {
            response.setHeader("Location", path + "/");
            response.setHeader("Content-Type", "text/plain; charset=UTF-8");
            response.setStatus(302);
            response.send("");
        } else if (path.equals(p + "/workspace/")) {
            server.serveStaticResource(request, response, "monitor/workspace.html");
        } else if (path.equals(p + "/routing")) {
            response.setHeader("Location", path + "/");
            response.setHeader("Content-Type", "text/plain; charset=UTF-8");
            response.setStatus(302);
            response.send("");
        } else if (path.equals(p + "/routing/")) {
            server.serveStaticResource(request, response, "monitor/routing.html");
        } else if (path.endsWith("vis-network.min.js") && !path.contains("/api/")) {
            server.serveStaticResource(request, response, "monitor/vis-network.min.js");
        } else if (path.endsWith("jsplumb.min.js") && !path.contains("/api/")) {
            server.serveStaticResource(request, response, "monitor/jsplumb.min.js");
        } else if (path.endsWith("echarts.min.js") && !path.contains("/api/")) {
            server.serveStaticResource(request, response, "monitor/vis-network.min.css");
        } else {
            sendError(response, 404, "Not found");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Topology API (live topology data)
    // ─────────────────────────────────────────────────────────────────

    private void handleTopologyApi(ServerRequest request, ServerResponse response) throws IOException {
        long now = System.currentTimeMillis();
        TopologyData.GatewayInfo gwInfo = new TopologyData.GatewayInfo();
        gwInfo.id = "gateway";
        gwInfo.host = "localhost";
        gwInfo.port = server.getPort();
        gwInfo.version = "1.0.0";
        gwInfo.uptime = now;

        WorkerRegistry registry = server.getRegistry();
        List<WorkerInfo> workers = registry != null ? registry.localWorkers() : new ArrayList<WorkerInfo>();
        gwInfo.workerCount = workers.size();

        List<TopologyData.WorkerTopologyInfo> workerList = new ArrayList<TopologyData.WorkerTopologyInfo>();
        List<TopologyData.ConnectionInfo> connections = new ArrayList<TopologyData.ConnectionInfo>();
        CapabilityRouter capabilityRouter = server.getCapabilityRouter();

        for (WorkerInfo w : workers) {
            TopologyData.WorkerTopologyInfo wi = new TopologyData.WorkerTopologyInfo();
            wi.workerId = w.getWorkerId();
            wi.host = w.getHost();
            wi.port = w.getPort();
            wi.model = w.getModel();
            wi.group = w.getGroup() != null ? w.getGroup() : "";
            wi.tier = w.getTier() != null ? w.getTier() : "worker";
            wi.status = w.getStatus().name();
            wi.currentLoad = w.getMetrics().getCurrentLoad();
            wi.activeTasks = w.getMetrics().getActiveTasks();
            wi.maxConcurrency = w.getMaxConcurrency();
            wi.heartbeatAge = now - w.getMetrics().getLastHeartbeat();
            wi.lastHeartbeat = w.getMetrics().getLastHeartbeat();
            wi.workspace = w.getWorkspace();
            wi.boundaries = w.getBoundaries();
            wi.maxTokens = w.getMaxTokens();
            wi.successRate = w.getMetrics().getSuccessRate();
            wi.avgLatencyMs = w.getMetrics().getAvgLatencyMs();
            wi.traits = w.getTraits();
            wi.gatewayProfile = w.getGatewayProfile();
            workerList.add(wi);

            TopologyData.ConnectionInfo conn = new TopologyData.ConnectionInfo();
            conn.from = "gateway";
            conn.to = w.getWorkerId();
            conn.type = w.isUseSsl() ? "https" : "http";
            conn.activeRequests = capabilityRouter != null ? capabilityRouter.getActiveRequests(w.getWorkerId()) : 0;
            conn.status = w.isAvailable() ? "active" : "inactive";
            connections.add(conn);
        }

        TaskManager taskManager = server.getTaskManager();
        List<TaskState> allTasks = taskManager != null ? taskManager.getAllTasks() : new ArrayList<TaskState>();
        List<TaskStateSummary> taskList = new ArrayList<TaskStateSummary>();
        for (TaskState s : allTasks) {
            TaskStateSummary tss = new TaskStateSummary();
            tss.taskId = s.getTaskId();
            tss.status = s.getStatus().name();
            tss.workerId = s.getWorkerId();
            tss.currentStep = s.getCurrentStep();
            tss.totalTokens = s.getTotalTokens();
            tss.createdAt = s.getCreatedAt();
            tss.updatedAt = s.getUpdatedAt();
            taskList.add(tss);
        }

        TopologyData data = new TopologyData();
        data.gateway = gwInfo;
        data.workers = workerList;
        data.connections = connections;
        data.tasks = taskList;
        sendJson(response, 200, data);
    }

    // ─────────────────────────────────────────────────────────────────
    // Topology editor router
    // ─────────────────────────────────────────────────────────────────

    void handleTopologyEditorRouter(ServerRequest request, ServerResponse response) throws IOException {
        String path = ((JdkServerRequest) request).getExchange().getRequestURI().getPath();
        String method = request.getMethod();
        String p = "/" + server.getMonitorPrefix() + "/topology";

        if (path.startsWith(p + "/api/")) {
            if (!requireMonitorAuth(request, response)) return;
            handleTopologyApiRoute(request, response, path, method, p);
            return;
        }
        if (path.equals(p) || path.equals(p + "/")) {
            server.serveStaticResource(request, response, "monitor/topology-editor.html");
        } else if (path.endsWith("jsplumb.min.js")) {
            server.serveStaticResource(request, response, "monitor/jsplumb.min.js");
        } else {
            sendError(response, 404, "Not found");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Topology API route handler (CRUD for topology definitions)
    // ─────────────────────────────────────────────────────────────────

    private void handleTopologyApiRoute(ServerRequest request, ServerResponse response, String path, String method, String prefix) throws IOException {
        String apiPath = path.substring(prefix.length());
        TopologyService topologyService = server.getTopologyService();

        try {
            if (apiPath.equals("/api/definitions") && "GET".equals(method)) {
                List<TopologyDef> defs = topologyService.listDefinitions();
                sendJson(response, 200, defs);
                return;
            }
            if (apiPath.equals("/api/definitions") && "POST".equals(method)) {
                String body = readBody(request);
                Map<String, Object> map = com.github.obhen233.util.JsonUtils.getMapper().readValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                String name = (String) map.get("name");
                if (name == null || name.trim().isEmpty()) { sendError(response, 400, "name is required"); return; }
                String description = (String) map.get("description");
                long id = topologyService.createDefinition(name.trim(), description);
                if (id < 0) { sendError(response, 500, "Failed to create topology definition"); return; }
                sendJson(response, 201, topologyService.getDefinition(id));
                return;
            }
            if (apiPath.matches("/api/definitions/\\d+$") && "GET".equals(method)) {
                long id = Long.parseLong(apiPath.substring("/api/definitions/".length()));
                TopologyDef def = topologyService.getDefinition(id);
                if (def == null) { sendError(response, 404, "Not found"); return; }
                sendJson(response, 200, def);
                return;
            }
            if (apiPath.matches("/api/definitions/\\d+$") && "PUT".equals(method)) {
                long id = Long.parseLong(apiPath.substring("/api/definitions/".length()));
                String body = readBody(request);
                Map<String, Object> map = com.github.obhen233.util.JsonUtils.getMapper().readValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                String name = (String) map.get("name");
                String description = (String) map.get("description");
                String draftDefinition = (String) map.get("draftDefinition");
                if (name == null || name.trim().isEmpty()) { sendError(response, 400, "name is required"); return; }
                boolean ok = topologyService.updateDefinition(id, name.trim(), description, draftDefinition);
                if (!ok) { sendError(response, 404, "Not found"); return; }
                sendJson(response, 200, topologyService.getDefinition(id));
                return;
            }
            if (apiPath.matches("/api/definitions/\\d+$") && "DELETE".equals(method)) {
                long id = Long.parseLong(apiPath.substring("/api/definitions/".length()));
                boolean ok = topologyService.deleteDefinition(id);
                if (!ok) { sendError(response, 400, "Cannot delete -- may be published or not found"); return; }
                sendJson(response, 200, Collections.singletonMap("status", "deleted"));
                return;
            }
            if (apiPath.matches("/api/definitions/\\d+/publish") && "POST".equals(method)) {
                String apiPrefix = "/api/definitions/";
                String remaining = apiPath.substring(apiPrefix.length());
                long id = Long.parseLong(remaining.substring(0, remaining.indexOf("/publish")));
                String body = readBody(request);
                Map<String, Object> map = com.github.obhen233.util.JsonUtils.getMapper().readValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                String definitionJson = (String) map.get("definition");
                if (definitionJson == null || definitionJson.trim().isEmpty()) { sendError(response, 400, "definition is required"); return; }
                boolean ok = topologyService.publishDefinition(id, definitionJson);
                if (!ok) { sendError(response, 500, "Failed to publish"); return; }
                sendJson(response, 200, topologyService.getDefinition(id));
                return;
            }
            if (apiPath.matches("/api/definitions/\\d+/versions/?$") && "GET".equals(method)) {
                String apiPrefix = "/api/definitions/";
                String remaining = apiPath.substring(apiPrefix.length());
                long id = Long.parseLong(remaining.substring(0, remaining.indexOf("/versions")));
                sendJson(response, 200, topologyService.listVersions(id));
                return;
            }
            if (apiPath.matches("/api/definitions/\\d+/versions/\\d+$") && "GET".equals(method)) {
                String[] parts = apiPath.split("/");
                long id = Long.parseLong(parts[3]);
                int ver = Integer.parseInt(parts[5]);
                TopologyVersion tv = topologyService.getVersion(id, ver);
                if (tv == null) { sendError(response, 404, "Version not found"); return; }
                sendJson(response, 200, tv);
                return;
            }
            if (apiPath.matches("/api/definitions/\\d+/versions/\\d+$") && "DELETE".equals(method)) {
                String[] parts = apiPath.split("/");
                long id = Long.parseLong(parts[3]);
                int ver = Integer.parseInt(parts[5]);
                boolean ok = topologyService.deleteVersion(id, ver);
                if (!ok) { sendError(response, 400, "Cannot delete -- may be active/published or not found"); return; }
                sendJson(response, 200, Collections.singletonMap("status", "deleted"));
                return;
            }
            if (apiPath.matches("/api/definitions/\\d+/rollback/\\d+$") && "POST".equals(method)) {
                String[] parts = apiPath.split("/");
                long id = Long.parseLong(parts[3]);
                int ver = Integer.parseInt(parts[5]);
                boolean ok = topologyService.rollback(id, ver);
                if (!ok) { sendError(response, 404, "Rollback target not found"); return; }
                sendJson(response, 200, topologyService.getDefinition(id));
                return;
            }
            if (apiPath.equals("/api/active") && "GET".equals(method)) {
                com.github.obhen233.core.gateway.topology.model.TopologyDefinition activeDef = topologyService.getActiveDefinition();
                if (activeDef == null) { sendError(response, 404, "No active topology"); return; }
                sendJson(response, 200, activeDef);
                return;
            }
            if (apiPath.equals("/api/history") && "GET".equals(method)) {
                sendJson(response, 200, topologyService.listAllHistory());
                return;
            }
            sendError(response, 404, "API endpoint not found: " + method + " " + apiPath);
        } catch (NumberFormatException e) {
            sendError(response, 400, "Invalid ID format");
        } catch (Exception e) {
            logger.error("Topology API error: {} {}: {}", method, apiPath, e.getMessage(), e);
            sendError(response, 500, "Internal error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Workspace API tasks
    // ─────────────────────────────────────────────────────────────────

    private void handleWorkspaceApiTasks(ServerRequest request, ServerResponse response) throws IOException {
        String filterStatus = request.getQueryParam("status");
        String filterWorker = request.getQueryParam("workerId");
        TaskManager taskManager = server.getTaskManager();

        List<TaskState> allTasks = taskManager != null ? taskManager.getAllTasks() : new ArrayList<TaskState>();
        List<TaskStateSummary> result = new ArrayList<TaskStateSummary>();
        for (TaskState s : allTasks) {
            if (filterStatus != null && !filterStatus.isEmpty() && !s.getStatus().name().equalsIgnoreCase(filterStatus)) continue;
            if (filterWorker != null && !filterWorker.isEmpty() && !filterWorker.equals(s.getWorkerId())) continue;
            TaskStateSummary tss = new TaskStateSummary();
            tss.taskId = s.getTaskId();
            tss.status = s.getStatus().name();
            tss.workerId = s.getWorkerId();
            tss.currentStep = s.getCurrentStep();
            tss.totalTokens = s.getTotalTokens();
            tss.createdAt = s.getCreatedAt();
            tss.updatedAt = s.getUpdatedAt();
            result.add(tss);
        }
        sendJson(response, 200, result);
    }

    // ─────────────────────────────────────────────────────────────────
    // Monitor auth check
    // ─────────────────────────────────────────────────────────────────

    private boolean isMonitorAuthConfigured() {
        ConfigManager cm = server.getConfigManager();
        if (cm == null) return false;
        String configUsername = cm.get("monitor.login.username");
        String configPassword = cm.get("monitor.login.password");
        return configUsername != null && !configUsername.isEmpty() && configPassword != null && !configPassword.isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────
    // Monitor API login
    // ─────────────────────────────────────────────────────────────────

    private void handleMonitorApiLogin(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        String username = extractJsonValue(body, "username");
        String password = extractJsonValue(body, "password");
        ConfigManager configManager = server.getConfigManager();
        ConcurrentHashMap<String, Long> sessionTokens = server.getSessionTokens();

        String configUsername = configManager != null ? configManager.get("monitor.login.username") : "";
        String configPassword = configManager != null ? configManager.get("monitor.login.password") : "";

        boolean authConfigured = isMonitorAuthConfigured();
        if (authConfigured) {
            if (username == null || password == null || !configUsername.equals(username) || !configPassword.equals(password)) {
                sendError(response, 401, "Invalid username or password");
                return;
            }
        }
        String token = UUID.randomUUID().toString();
        sessionTokens.put(token, System.currentTimeMillis() + 86400000L);
        sendJson(response, 200, "{\"token\":\"" + token + "\",\"expiresIn\":86400}");
    }

    // ─────────────────────────────────────────────────────────────────
    // Monitor API status
    // ─────────────────────────────────────────────────────────────────

    private void handleMonitorApiStatus(ServerRequest request, ServerResponse response) throws IOException {
        WorkerRegistry registry = server.getRegistry();
        TaskManager taskManager = server.getTaskManager();
        CapabilityRouter capabilityRouter = server.getCapabilityRouter();
        CopyOnWriteArrayList<Object[]> workerLoadHistory = server.getWorkerLoadHistory();
        CopyOnWriteArrayList<Object[]> pendingHistory = server.getPendingHistory();
        java.util.concurrent.Semaphore requestSemaphore = server.getRequestSemaphore();

        List<WorkerInfo> workers = registry != null ? registry.availableWorkers() : new ArrayList<WorkerInfo>();
        List<TaskState> allTasks = taskManager != null ? taskManager.getAllTasks() : new ArrayList<TaskState>();

        int activeTasksVal = 0;
        int pendingTasks = 0;
        List<TaskStateSummary> taskList = new ArrayList<TaskStateSummary>();
        for (TaskState s : allTasks) {
            TaskStateSummary tss = new TaskStateSummary();
            tss.taskId = s.getTaskId();
            tss.status = s.getStatus().name();
            tss.workerId = s.getWorkerId();
            tss.currentStep = s.getCurrentStep();
            tss.totalTokens = s.getTotalTokens();
            tss.createdAt = s.getCreatedAt();
            tss.updatedAt = s.getUpdatedAt();
            taskList.add(tss);
            if (s.getStatus() == TaskStatus.PENDING || s.getStatus() == TaskStatus.ASSIGNED) pendingTasks++;
            if (s.getStatus().isActive()) activeTasksVal++;
        }

        List<WorkerInfoWithActiveRequests> workerList = new ArrayList<WorkerInfoWithActiveRequests>();
        long now = System.currentTimeMillis();
        for (WorkerInfo w : workers) {
            WorkerInfoWithActiveRequests wi = new WorkerInfoWithActiveRequests();
            wi.workerId = w.getWorkerId();
            wi.host = w.getHost();
            wi.port = w.getPort();
            wi.model = w.getModel();
            wi.group = w.getGroup() != null ? w.getGroup() : "";
            wi.tier = w.getTier() != null ? w.getTier() : "worker";
            wi.status = w.getStatus().name();
            wi.currentLoad = w.getMetrics().getCurrentLoad();
            wi.maxConcurrency = w.getMaxConcurrency();
            wi.activeTasks = w.getMetrics().getActiveTasks();
            wi.gatewayActiveRequests = capabilityRouter != null ? capabilityRouter.getActiveRequests(w.getWorkerId()) : 0;
            wi.gatewayId = w.getGatewayId() != null ? w.getGatewayId() : "";
            wi.heartbeatAge = now - w.getMetrics().getLastHeartbeat();
            workerList.add(wi);
        }

        for (WorkerInfo w : workers) {
            workerLoadHistory.add(new Object[]{System.currentTimeMillis(), w.getWorkerId(), w.getMetrics().getCurrentLoad()});
        }
        while (workerLoadHistory.size() > GatewayHttpServer.MAX_LOAD_HISTORY) {
            workerLoadHistory.removeFirst();
        }

        pendingHistory.add(new Object[]{System.currentTimeMillis(), pendingTasks});
        while (pendingHistory.size() > GatewayHttpServer.MAX_PENDING_HISTORY) {
            pendingHistory.removeFirst();
        }

        List<Object[]> loadHist = new ArrayList<Object[]>(workerLoadHistory);
        List<Object[]> pendHist = new ArrayList<Object[]>(pendingHistory);

        int totalTasks = allTasks.size();
        int onlineWorkers = workers.size();
        int queueDepth = server.getQueueDepth();
        boolean authConfigured = isMonitorAuthConfigured();

        int maxConcurrent = requestSemaphore.availablePermits()
            + (GatewayHttpServer.DEFAULT_MAX_CONCURRENT_REQUESTS - requestSemaphore.availablePermits());
        int availablePermits = requestSemaphore.availablePermits();
        int queuedRequests = requestSemaphore.getQueueLength();

        MonitorStatusResponse resp = new MonitorStatusResponse();
        resp.onlineWorkers = onlineWorkers;
        resp.activeTasks = activeTasksVal;
        resp.pendingTasks = pendingTasks;
        resp.totalTasks = totalTasks;
        resp.queueDepth = queueDepth;
        resp.authConfigured = authConfigured;
        ConcurrencyInfo ci = new ConcurrencyInfo();
        ci.max = maxConcurrent;
        ci.available = availablePermits;
        ci.queued = queuedRequests;
        resp.concurrency = ci;
        resp.workers = workerList;
        resp.tasks = taskList;
        resp.workerLoadHistory = loadHist;
        resp.pendingHistory = pendHist;
        sendJson(response, 200, resp);
    }

    // ─────────────────────────────────────────────────────────────────
    // Monitor API config (proxied command)
    // ─────────────────────────────────────────────────────────────────

    private void handleMonitorApiConfig(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        String workerId = extractJsonValue(body, "workerId");
        String command = extractJsonValue(body, "command");
        if (workerId == null || workerId.isEmpty()) { sendError(response, 400, "Missing workerId"); return; }
        if (command == null || command.isEmpty()) { sendError(response, 400, "Missing command"); return; }
        if ("__gateway__".equals(workerId)) { handleGatewayLocalConfig(response, command); return; }
        proxyWorkerCommand(response, workerId, command);
    }

    // ─────────────────────────────────────────────────────────────────
    // Gateway local config (config list|set|reset)
    // ─────────────────────────────────────────────────────────────────

    void handleGatewayLocalConfig(ServerResponse response, String command) throws IOException {
        ConfigManager configManager = server.getConfigManager();
        if (configManager == null) {
            Map<String, String> err = new HashMap<>();
            err.put("status", "error");
            err.put("error", "ConfigManager not available on this gateway");
            sendJson(response, 503, err);
            return;
        }
        String[] parts = command.split(" ", 3);
        if (parts.length < 2 || !"config".equals(parts[0])) {
            Map<String, String> inv = new HashMap<>();
            inv.put("status", "error");
            inv.put("error", "Invalid command. Usage: config list|set <key> <value>|reset <key>");
            sendJson(response, 400, inv);
            return;
        }
        String action = parts[1];
        try {
            switch (action) {
                case "list": {
                    StringBuilder sb = new StringBuilder();
                    List<SystemConfig> configs = configManager.getAll();
                    String currentCategory = null;
                    for (SystemConfig cfg : configs) {
                        if (!cfg.category.equals(currentCategory)) {
                            currentCategory = cfg.category;
                            sb.append("[").append(cfg.category).append("]\n");
                        }
                        String value = cfg.configValue != null ? cfg.configValue : "(default: " + (cfg.defaultValue != null ? cfg.defaultValue : "") + ")";
                        sb.append("  ").append(cfg.configKey).append(" = ").append(value).append("\n");
                    }
                    sendJson(response, 200, "{\"status\":\"ok\",\"output\":\"" + escapeJson(sb.toString()) + "\"}");
                    break;
                }
                case "set": {
                    if (parts.length < 3) {
                        Map<String, String> kvMissing = new HashMap<>();
                        kvMissing.put("status", "error");
                        kvMissing.put("error", "Missing key and value. Usage: config set <key> <value>");
                        sendJson(response, 400, kvMissing);
                        return;
                    }
                    String kv = parts[2];
                    int spaceIdx = kv.indexOf(' ');
                    if (spaceIdx <= 0) {
                        Map<String, String> valMissing = new HashMap<>();
                        valMissing.put("status", "error");
                        valMissing.put("error", "Missing value. Usage: config set <key> <value>");
                        sendJson(response, 400, valMissing);
                        return;
                    }
                    String result = configManager.set(kv.substring(0, spaceIdx), kv.substring(spaceIdx + 1));
                    Map<String, String> setResult = new HashMap<>();
                    setResult.put("status", "ok");
                    setResult.put("output", result != null ? result : "Config updated: " + kv.substring(0, spaceIdx) + " = " + kv.substring(spaceIdx + 1));
                    sendJson(response, 200, setResult);
                    break;
                }
                case "reset": {
                    if (parts.length < 3) { sendError(response, 400, "Missing key. Usage: config reset <key>"); return; }
                    String resetKey = parts[2].trim();
                    Map<String, String> resetResult = new HashMap<>();
                    resetResult.put("status", "ok");
                    String cmResult = configManager.reset(resetKey);
                    resetResult.put("output", cmResult != null ? cmResult : "Config reset: " + resetKey);
                    sendJson(response, 200, resetResult);
                    break;
                }
                default: {
                    Map<String, String> unknown = new HashMap<>();
                    unknown.put("status", "error");
                    unknown.put("error", "Unknown action: " + action + ". Use: list, set, reset");
                    sendJson(response, 400, unknown);
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to handle gateway config command", e);
            Map<String, String> excErr = new HashMap<>();
            excErr.put("status", "error");
            excErr.put("error", e.getMessage());
            sendJson(response, 500, excErr);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Monitor API i18n
    // ─────────────────────────────────────────────────────────────────

    private void handleMonitorApiI18n(ServerRequest request, ServerResponse response) throws IOException {
        String lang = request.getQueryParam("lang");
        ConfigManager configManager = server.getConfigManager();

        if (lang == null || lang.isEmpty()) lang = configManager != null ? configManager.get("monitor.language") : "";
        if (lang == null || lang.isEmpty()) lang = configManager != null ? configManager.get("agent.language") : "";
        if (lang == null || lang.isEmpty()) lang = "zh";

        String resourcePath = "monitor/i18n_" + lang + ".json";
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            resourcePath = "monitor/i18n_en.json";
            is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        }
        if (is == null) { sendJson(response, 200, Collections.emptyMap()); return; }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        sendJson(response, 200, sb.toString());
    }

    // ─────────────────────────────────────────────────────────────────
    // Monitor API rules (GET)
    // ─────────────────────────────────────────────────────────────────

    private void handleMonitorApiRules(ServerRequest request, ServerResponse response) throws IOException {
        String target = request.getQueryParam("target");
        if (target == null) target = "gateway";
        CommandRulesDao commandRulesDao = server.getCommandRulesDao();

        try {
            if ("gateway".equals(target)) {
                if (commandRulesDao == null) { sendError(response, 500, "Gateway database not available"); return; }
                List<CommandRulesDao.CommandRule> rules = commandRulesDao.findAll();
                StringBuilder json = new StringBuilder("{\"status\":\"ok\",\"target\":\"gateway\",\"rules\":[");
                boolean first = true;
                for (CommandRulesDao.CommandRule rule : rules) {
                    if (!first) json.append(",");
                    first = false;
                    json.append("{\"id\":").append(rule.id)
                        .append(",\"mode\":\"").append(escapeJson(rule.mode))
                        .append("\",\"type\":\"").append(escapeJson(rule.type))
                        .append("\",\"pattern\":\"").append(escapeJson(rule.pattern))
                        .append("\",\"source\":\"").append(escapeJson(rule.source))
                        .append("\",\"enabled\":").append(rule.enabled)
                        .append(",\"createdAt\":").append(rule.createdAt)
                        .append(",\"updatedAt\":").append(rule.updatedAt)
                        .append("}");
                }
                json.append("]}");
                sendJson(response, 200, json.toString());
            } else if ("worker".equals(target)) {
                String workerId = request.getQueryParam("workerId");
                if (workerId == null || workerId.isEmpty()) { sendError(response, 400, "Missing workerId for worker target"); return; }
                proxyRulesAsJson(response, workerId, "rules list --json");
            } else {
                sendError(response, 400, "Unknown target: " + safe(target));
            }
        } catch (Exception e) {
            logger.error("Failed to read rules", e);
            sendError(response, 500, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Monitor API rules save (POST)
    // ─────────────────────────────────────────────────────────────────

    private void handleMonitorApiRulesSave(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        String target = extractJsonValue(body, "target");
        String action = extractJsonValue(body, "action");
        if (action == null || action.isEmpty()) action = "list";
        if (target == null) target = "gateway";
        CommandRulesDao commandRulesDao = server.getCommandRulesDao();

        try {
            if ("gateway".equals(target)) {
                if (commandRulesDao == null) { sendError(response, 500, "Gateway database not available"); return; }
                sendJson(response, 200, executeGatewayRulesAction(body, action));
            } else if ("worker".equals(target)) {
                String workerId = extractJsonValue(body, "workerId");
                if (workerId == null || workerId.isEmpty()) { sendError(response, 400, "Missing workerId for worker target"); return; }
                proxyWorkerCommand(response, workerId, buildWorkerRulesCommand(body, action));
            } else {
                sendError(response, 400, "Unknown target: " + safe(target));
            }
        } catch (Exception e) {
            logger.error("Failed to save rules", e);
            sendError(response, 500, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Execute gateway rules action (list/add/delete/enable/disable/reset)
    // ─────────────────────────────────────────────────────────────────

    private String executeGatewayRulesAction(String body, String action) {
        CommandRulesDao commandRulesDao = server.getCommandRulesDao();
        switch (action) {
            case "list": {
                List<CommandRulesDao.CommandRule> rules = commandRulesDao.findAll();
                StringBuilder json = new StringBuilder("{\"status\":\"ok\",\"target\":\"gateway\",\"rules\":[");
                boolean first = true;
                for (CommandRulesDao.CommandRule rule : rules) {
                    if (!first) json.append(",");
                    first = false;
                    json.append("{\"id\":").append(rule.id)
                        .append(",\"mode\":\"").append(escapeJson(rule.mode))
                        .append("\",\"type\":\"").append(escapeJson(rule.type))
                        .append("\",\"pattern\":\"").append(escapeJson(rule.pattern))
                        .append("\",\"source\":\"").append(escapeJson(rule.source))
                        .append("\",\"enabled\":").append(rule.enabled)
                        .append(",\"createdAt\":").append(rule.createdAt)
                        .append(",\"updatedAt\":").append(rule.updatedAt)
                        .append("}");
                }
                json.append("]}");
                return json.toString();
            }
            case "add": {
                String mode = extractJsonValue(body, "mode");
                String type = extractJsonValue(body, "type");
                String pattern = extractJsonValue(body, "pattern");
                if (mode == null || type == null || pattern == null || pattern.isEmpty()) {
                    return "{\"status\":\"error\",\"error\":\"Missing mode, type, or pattern\"}";
                }
                commandRulesDao.insert(new CommandRulesDao.CommandRule(mode, type, pattern, "manual"));
                return "{\"status\":\"ok\",\"output\":\"Rule added: " + escapeJson(mode + " " + type + " " + pattern) + "\"}";
            }
            case "remove":
            case "delete": {
                String idStr = extractJsonValue(body, "id");
                if (idStr == null || idStr.isEmpty()) return "{\"status\":\"error\",\"error\":\"Missing id\"}";
                try { commandRulesDao.delete(Long.parseLong(idStr)); return "{\"status\":\"ok\",\"output\":\"Rule deleted: " + idStr + "\"}"; }
                catch (NumberFormatException e) { return "{\"status\":\"error\",\"error\":\"Invalid id: " + escapeJson(idStr) + "\"}"; }
            }
            case "enable": {
                String idStr = extractJsonValue(body, "id");
                if (idStr == null || idStr.isEmpty()) return "{\"status\":\"error\",\"error\":\"Missing id\"}";
                try { commandRulesDao.updateEnabled(Long.parseLong(idStr), true); return "{\"status\":\"ok\",\"output\":\"Rule enabled: " + idStr + "\"}"; }
                catch (NumberFormatException e) { return "{\"status\":\"error\",\"error\":\"Invalid id: " + escapeJson(idStr) + "\"}"; }
            }
            case "disable": {
                String idStr = extractJsonValue(body, "id");
                if (idStr == null || idStr.isEmpty()) return "{\"status\":\"error\",\"error\":\"Missing id\"}";
                try { commandRulesDao.updateEnabled(Long.parseLong(idStr), false); return "{\"status\":\"ok\",\"output\":\"Rule disabled: " + idStr + "\"}"; }
                catch (NumberFormatException e) { return "{\"status\":\"error\",\"error\":\"Invalid id: " + escapeJson(idStr) + "\"}"; }
            }
            case "reset": {
                commandRulesDao.deleteNonBuiltin();
                List<CommandRulesDao.CommandRule> builtinRules = com.github.obhen233.cli.provider.RulesCommandProvider.getBuiltinRules();
                for (CommandRulesDao.CommandRule rule : builtinRules) commandRulesDao.insertIfNotExists(rule);
                return "{\"status\":\"ok\",\"output\":\"Rules reset to " + builtinRules.size() + " built-in defaults\"}";
            }
            default:
                return "{\"status\":\"error\",\"error\":\"Unknown action: " + escapeJson(action) + "\"}";
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Build worker rules command string
    // ─────────────────────────────────────────────────────────────────

    private String buildWorkerRulesCommand(String body, String action) {
        switch (action) {
            case "list": return "rules list";
            case "add": {
                String mode = extractJsonValue(body, "mode");
                String type = extractJsonValue(body, "type");
                String pattern = extractJsonValue(body, "pattern");
                if (mode == null || type == null || pattern == null) return "rules list";
                return "rules add " + mode + " " + type + " " + pattern;
            }
            case "remove":
            case "delete": {
                String idStr = extractJsonValue(body, "id");
                if (idStr == null || idStr.isEmpty()) return "rules list";
                return "rules delete " + idStr;
            }
            case "enable": {
                String idStr = extractJsonValue(body, "id");
                if (idStr == null || idStr.isEmpty()) return "rules list";
                return "rules enable " + idStr;
            }
            case "disable": {
                String idStr = extractJsonValue(body, "id");
                if (idStr == null || idStr.isEmpty()) return "rules list";
                return "rules disable " + idStr;
            }
            case "reset": return "rules reset";
            default: return "rules list";
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Proxy a command to a worker
    // ─────────────────────────────────────────────────────────────────

    private void proxyWorkerCommand(ServerResponse response, String workerId, String command) throws IOException {
        WorkerRegistry registry = server.getRegistry();
        WorkerInfo worker = registry != null ? registry.getWorker(workerId) : null;
        if (worker == null) { sendError(response, 404, "Worker not found: " + safe(workerId)); return; }
        try {
            URL url = new URL(worker.getBaseUrl() + "/worker/v1/command");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            SecurityHeadersInjector injector = new SecurityHeadersInjector(
                SecurityProviderLoader.getAuthProvider(), SecurityProviderLoader.getEncryptionProvider());
            injector.injectIntoConnection(conn, worker.getWorkerId());

            String requestBody = "{\"command\":\"" + escapeJson(command) + "\"}";
            byte[] cmdBytes = requestBody.getBytes(StandardCharsets.UTF_8);
            Map<String, String> encHeaders = new HashMap<>();
            cmdBytes = SecurityHeadersInjector.encryptBody(cmdBytes, worker.getWorkerId(), encHeaders);
            for (Map.Entry<String, String> e : encHeaders.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            try (OutputStream os = conn.getOutputStream()) { os.write(cmdBytes); }

            int code = conn.getResponseCode();
            String responseBody = readConnectionBody(conn, code);
            conn.disconnect();
            if (code == 200) { sendJson(response, 200, responseBody); }
            else {
                Map<String, String> proxyErr = new HashMap<>();
                proxyErr.put("status", "error");
                proxyErr.put("error", "Worker returned HTTP " + code + ": " + truncate(responseBody, 500));
                sendJson(response, 502, proxyErr);
            }
        } catch (Exception e) { sendError(response, 502, e.getMessage()); }
    }

    // ─────────────────────────────────────────────────────────────────
    // Proxy rules as JSON from a worker
    // ─────────────────────────────────────────────────────────────────

    private void proxyRulesAsJson(ServerResponse response, String workerId, String command) throws IOException {
        WorkerRegistry registry = server.getRegistry();
        WorkerInfo worker = registry != null ? registry.getWorker(workerId) : null;
        if (worker == null) { sendError(response, 404, "Worker not found: " + safe(workerId)); return; }
        try {
            URL url = new URL(worker.getBaseUrl() + "/worker/v1/command");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            SecurityHeadersInjector injector = new SecurityHeadersInjector(
                SecurityProviderLoader.getAuthProvider(), SecurityProviderLoader.getEncryptionProvider());
            injector.injectIntoConnection(conn, worker.getWorkerId());

            String requestBody = "{\"command\":\"" + escapeJson(command) + "\"}";
            byte[] cmdBytes = requestBody.getBytes(StandardCharsets.UTF_8);
            Map<String, String> encHeaders = new HashMap<>();
            cmdBytes = SecurityHeadersInjector.encryptBody(cmdBytes, worker.getWorkerId(), encHeaders);
            for (Map.Entry<String, String> e : encHeaders.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            try (OutputStream os = conn.getOutputStream()) { os.write(cmdBytes); }

            int code = conn.getResponseCode();
            String responseBody = readConnectionBody(conn, code);
            conn.disconnect();

            if (code == 200) {
                try {
                    com.fasterxml.jackson.databind.JsonNode root = GatewayConfirmHandler.confirmMapper.readTree(responseBody);
                    String output = root.has("output") ? root.get("output").asText() : "[]";
                    com.fasterxml.jackson.databind.JsonNode rulesNode = GatewayConfirmHandler.confirmMapper.readTree(output);
                    if (rulesNode.isArray()) { sendJson(response, 200, "{\"status\":\"ok\",\"target\":\"worker\",\"rules\":" + output + "}"); }
                    else {
                        Map<String, Object> rulesOk = new HashMap<>();
                        rulesOk.put("status", "ok"); rulesOk.put("target", "worker"); rulesOk.put("rules", new ArrayList<>());
                        sendJson(response, 200, rulesOk);
                    }
                } catch (Exception e) {
                    Map<String, Object> rulesEmpty = new HashMap<>();
                    rulesEmpty.put("status", "ok"); rulesEmpty.put("target", "worker"); rulesEmpty.put("rules", new ArrayList<>());
                    sendJson(response, 200, rulesEmpty);
                }
            } else {
                Map<String, String> proxyErr = new HashMap<>();
                proxyErr.put("status", "error"); proxyErr.put("error", "Worker returned HTTP " + code);
                sendJson(response, 502, proxyErr);
            }
        } catch (Exception e) { sendError(response, 502, e.getMessage()); }
    }

    // ─────────────────────────────────────────────────────────────────
    // Require monitor authentication
    // ─────────────────────────────────────────────────────────────────

    private boolean requireMonitorAuth(ServerRequest request, ServerResponse response) throws IOException {
        if (!isMonitorAuthConfigured()) return true;
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) { sendError(response, 401, "Unauthorized"); return false; }
        String token = authHeader.substring(7).trim();
        ConcurrentHashMap<String, Long> sessionTokens = server.getSessionTokens();
        Long expiry = sessionTokens.get(token);
        if (expiry == null || System.currentTimeMillis() > expiry) {
            if (expiry != null) sessionTokens.remove(token);
            sendError(response, 401, "Unauthorized");
            return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────
    // Token cleanup scheduler
    // ─────────────────────────────────────────────────────────────────

    void startTokenCleanup() {
        ConcurrentHashMap<String, Long> sessionTokens = server.getSessionTokens();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-token-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.tokenCleanupScheduler = scheduler;
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> entry : sessionTokens.entrySet()) {
                if (now > entry.getValue()) {
                    sessionTokens.remove(entry.getKey());
                }
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    ScheduledExecutorService getTokenCleanupScheduler() {
        return tokenCleanupScheduler;
    }

    void stopTokenCleanup() {
        if (tokenCleanupScheduler != null) {
            tokenCleanupScheduler.shutdown();
            tokenCleanupScheduler = null;
        }
    }
}
