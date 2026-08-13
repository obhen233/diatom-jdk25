package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;
import com.github.obhen233.quarkus.runtime.components.DiatomRuntimeContext;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import com.github.obhen233.util.JsonUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Monitor 管理 UI（对齐 starter {@code MonitorController}）。
 *
 * <p>提供仪表盘 / 工作区拓扑 / 路由规则管理 / 拓扑编辑等页面与 API。静态资源
 * （HTML/JS/i18n）复用 diatom-core 的 {@code monitor/*} classpath 资源。
 *
 * <p>路径前缀固定为 {@code /monitor}（v1 简化：JAX-RS @Path 需常量；自定义 prefix
 * 由部署期构建固定，默认 monitor）。认证用内存 session token 表（镜像 starter）。
 * 数据源均为可空服务（WorkerRegistry/TaskManager/CapabilityRouter/ConfigManager/
 * DatabaseManager 走 {@link DiatomRuntimeContext} 门面），缺省时优雅降级。
 */
@ApplicationScoped
@Path("/monitor")
public class MonitorResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorResource.class);

    /** 静态资源在 diatom-core 中的 classpath 根。 */
    private static final String RESOURCE_ROOT = "monitor/";

    private final DiatomRuntimeContext context;
    private final DiatomRuntimeConfig config;
    private final String prefix;

    private CommandRulesDao commandRulesDao;

    // Session tokens: token → expiry timestamp
    private final ConcurrentHashMap<String, Long> sessionTokens = new ConcurrentHashMap<>();

    // Load history for dashboard charts
    private final CopyOnWriteArrayList<Object[]> workerLoadHistory = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Object[]> pendingHistory = new CopyOnWriteArrayList<>();

    @Inject
    public MonitorResource(DiatomRuntimeContext context, DiatomRuntimeConfig config) {
        this.context = context;
        this.config = config;
        this.prefix = config.monitor().prefix();
        if (context.databaseManager() != null) {
            try {
                this.commandRulesDao = new CommandRulesDao(context.databaseManager());
            } catch (Exception e) {
                LOGGER.warn("Failed to create CommandRulesDao: {}", e.getMessage());
            }
        }
        LOGGER.info("Monitor page {} at /{}/", config.monitor().enabled() ? "enabled" : "disabled", prefix);
    }

    // ========================================================================
    // Auth
    // ========================================================================

    private boolean isAuthConfigured() {
        return config.monitor().username().map(u -> !u.isEmpty()).orElse(false)
                && config.monitor().password().map(p -> !p.isEmpty()).orElse(false);
    }

    private boolean checkAuth(String authorization) {
        if (!isAuthConfigured()) {
            return true;
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Long expiry = sessionTokens.get(token);
        if (expiry == null || System.currentTimeMillis() > expiry) {
            if (expiry != null) {
                sessionTokens.remove(token);
            }
            return false;
        }
        return true;
    }

    private Response unauthorized() {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "Unauthorized");
        return Response.status(Response.Status.UNAUTHORIZED).entity(err).build();
    }

    private boolean monitorEnabled() {
        return config.monitor().enabled();
    }

    // ========================================================================
    // POST /monitor/api/login
    // ========================================================================

    @POST
    @Path("/api/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(Map<String, String> body) {
        String username = body != null ? body.get("username") : null;
        String password = body != null ? body.get("password") : null;

        if (isAuthConfigured()) {
            if (username == null || password == null
                    || !config.monitor().username().orElse("").equals(username)
                    || !config.monitor().password().orElse("").equals(password)) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "Invalid username or password");
                return Response.status(Response.Status.UNAUTHORIZED).entity(err).build();
            }
        }

        String token = UUID.randomUUID().toString();
        long expiry = System.currentTimeMillis() + config.monitor().tokenExpireSeconds() * 1000L;
        sessionTokens.put(token, expiry);

        long now = System.currentTimeMillis();
        sessionTokens.entrySet().removeIf(e -> e.getValue() < now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("expiresIn", config.monitor().tokenExpireSeconds());
        return Response.ok(result).build();
    }

    // ========================================================================
    // GET /monitor/api/status
    // ========================================================================

    @GET
    @Path("/api/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@HeaderParam("Authorization") String auth) {
        if (!checkAuth(auth)) {
            return unauthorized();
        }

        WorkerRegistry workerRegistry = context.workerRegistry();
        TaskManager taskManager = context.taskManager();
        CapabilityRouter capabilityRouter = context.capabilityRouter();

        List<WorkerInfo> workers = workerRegistry != null
                ? workerRegistry.availableWorkers() : Collections.emptyList();
        List<TaskState> allTasks = taskManager != null
                ? taskManager.getAllTasks() : Collections.emptyList();

        int activeTasksVal = 0;
        int pendingTasks = 0;
        List<Map<String, Object>> taskList = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (TaskState s : allTasks) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("taskId", s.getTaskId());
            t.put("status", s.getStatus().name());
            t.put("workerId", s.getWorkerId());
            t.put("currentStep", s.getCurrentStep());
            t.put("totalTokens", s.getTotalTokens());
            t.put("createdAt", s.getCreatedAt());
            t.put("updatedAt", s.getUpdatedAt());
            taskList.add(t);
            if (s.getStatus() == TaskStatus.PENDING || s.getStatus() == TaskStatus.ASSIGNED) {
                pendingTasks++;
            }
            if (s.getStatus().isActive()) {
                activeTasksVal++;
            }
        }

        List<Map<String, Object>> workerList = new ArrayList<>();
        for (WorkerInfo w : workers) {
            Map<String, Object> wi = new LinkedHashMap<>();
            wi.put("workerId", w.getWorkerId());
            wi.put("host", w.getHost());
            wi.put("port", w.getPort());
            wi.put("model", w.getModel());
            wi.put("group", w.getGroup() != null ? w.getGroup() : "");
            wi.put("tier", w.getTier() != null ? w.getTier() : "worker");
            wi.put("status", w.getStatus().name());
            wi.put("currentLoad", w.getMetrics().getCurrentLoad());
            wi.put("maxConcurrency", w.getMaxConcurrency());
            wi.put("activeTasks", w.getMetrics().getActiveTasks());
            wi.put("gatewayActiveRequests", capabilityRouter != null
                    ? capabilityRouter.getActiveRequests(w.getWorkerId()) : 0);
            wi.put("gatewayId", w.getGatewayId() != null ? w.getGatewayId() : "");
            wi.put("heartbeatAge", now - w.getMetrics().getLastHeartbeat());
            workerList.add(wi);

            workerLoadHistory.add(new Object[]{now, w.getWorkerId(), w.getMetrics().getCurrentLoad()});
        }
        trimHistory(workerLoadHistory);
        pendingHistory.add(new Object[]{now, pendingTasks});
        trimHistory(pendingHistory);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onlineWorkers", workers.size());
        result.put("activeTasks", activeTasksVal);
        result.put("pendingTasks", pendingTasks);
        result.put("totalTasks", allTasks.size());
        result.put("authConfigured", isAuthConfigured());
        result.put("workers", workerList);
        result.put("tasks", taskList);
        result.put("workerLoadHistory", new ArrayList<>(workerLoadHistory));
        result.put("pendingHistory", new ArrayList<>(pendingHistory));
        return Response.ok(result).build();
    }

    private static void trimHistory(List<Object[]> history) {
        int maxHistory = 300;
        while (history.size() > maxHistory) {
            history.remove(0);
        }
    }

    // ========================================================================
    // GET /monitor/api/i18n
    // ========================================================================

    @GET
    @Path("/api/i18n")
    @Produces(MediaType.APPLICATION_JSON)
    public Response i18n(@QueryParam("lang") String lang) {
        ConfigManager configManager = context.configManager();
        if (lang == null || lang.isEmpty()) {
            lang = configManager != null ? configManager.get("monitor.language") : "";
        }
        if (lang == null || lang.isEmpty()) {
            lang = configManager != null ? configManager.get("agent.language") : "";
        }
        if (lang == null || lang.isEmpty()) {
            lang = "zh";
        }

        String resourcePath = RESOURCE_ROOT + "i18n_" + lang + ".json";
        String json = readResource(resourcePath);
        if (json == null) {
            json = readResource(RESOURCE_ROOT + "i18n_en.json");
        }
        if (json == null) {
            return Response.ok(Collections.emptyMap()).build();
        }
        try {
            Map<String, Object> map = JsonUtils.fromJson(json, Map.class);
            return Response.ok(map != null ? map : Collections.emptyMap()).build();
        } catch (Exception e) {
            return Response.ok(json).build();
        }
    }

    // ========================================================================
    // GET/POST /monitor/api/rules
    // ========================================================================

    @GET
    @Path("/api/rules")
    @Produces(MediaType.APPLICATION_JSON)
    public Response rules(@HeaderParam("Authorization") String auth,
                          @QueryParam("target") String target) {
        if (!checkAuth(auth)) {
            return unauthorized();
        }
        if (target == null || target.isEmpty()) {
            target = "gateway";
        }
        if (!"gateway".equals(target)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorMap("Unknown target: " + target)).build();
        }
        if (commandRulesDao == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorMap("Gateway database not available")).build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("target", "gateway");
        result.put("rules", ruleList(commandRulesDao.findAll()));
        return Response.ok(result).build();
    }

    @POST
    @Path("/api/rules")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response rulesSave(@HeaderParam("Authorization") String auth,
                              Map<String, Object> body) {
        if (!checkAuth(auth)) {
            return unauthorized();
        }
        String target = (String) body.getOrDefault("target", "gateway");
        String action = (String) body.getOrDefault("action", "list");
        if (!"gateway".equals(target)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorMap("Unknown target: " + target)).build();
        }
        if (commandRulesDao == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorMap("Gateway database not available")).build();
        }
        return Response.ok(executeRulesAction(body, action)).build();
    }

    private Map<String, Object> executeRulesAction(Map<String, Object> body, String action) {
        switch (action) {
            case "list": {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                result.put("target", "gateway");
                result.put("rules", ruleList(commandRulesDao.findAll()));
                return result;
            }
            case "add": {
                String mode = (String) body.get("mode");
                String type = (String) body.get("type");
                String pattern = (String) body.get("pattern");
                if (mode == null || type == null || pattern == null) {
                    return errorMap("Missing required fields: mode, type, pattern");
                }
                CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule(mode, type, pattern, "manual");
                commandRulesDao.insert(rule);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                result.put("id", 0);
                return result;
            }
            case "delete": {
                Object idObj = body.get("id");
                if (idObj == null) {
                    return errorMap("Missing id");
                }
                long id = ((Number) idObj).longValue();
                commandRulesDao.delete(id);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                return result;
            }
            case "enable": {
                Object idObj = body.get("id");
                if (idObj == null) {
                    return errorMap("Missing id");
                }
                long id = ((Number) idObj).longValue();
                commandRulesDao.updateEnabled(id, true);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                return result;
            }
            case "disable": {
                Object idObj = body.get("id");
                if (idObj == null) {
                    return errorMap("Missing id");
                }
                long id = ((Number) idObj).longValue();
                commandRulesDao.updateEnabled(id, false);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                return result;
            }
            default:
                return errorMap("Unknown action: " + action);
        }
    }

    private static List<Map<String, Object>> ruleList(List<CommandRulesDao.CommandRule> rules) {
        List<Map<String, Object>> ruleList = new ArrayList<>();
        for (CommandRulesDao.CommandRule rule : rules) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rule.id);
            r.put("mode", rule.mode);
            r.put("type", rule.type);
            r.put("pattern", rule.pattern);
            r.put("source", rule.source);
            r.put("enabled", rule.enabled);
            r.put("createdAt", rule.createdAt);
            r.put("updatedAt", rule.updatedAt);
            ruleList.add(r);
        }
        return ruleList;
    }

    // ========================================================================
    // POST /monitor/api/config
    // ========================================================================

    @POST
    @Path("/api/config")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response config(@HeaderParam("Authorization") String auth,
                           Map<String, Object> body) {
        if (!checkAuth(auth)) {
            return unauthorized();
        }
        String workerId = (String) body.get("workerId");
        String command = (String) body.get("command");
        if (workerId == null || workerId.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(errorMap("Missing workerId")).build();
        }
        if (command == null || command.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(errorMap("Missing command")).build();
        }
        if ("__gateway__".equals(workerId)) {
            return handleGatewayLocalConfig(command);
        }
        return Response.ok(errorMap("Worker command proxy not yet implemented")).build();
    }

    private Response handleGatewayLocalConfig(String command) {
        ConfigManager configManager = context.configManager();
        if (configManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorMap("ConfigManager not available on this gateway")).build();
        }
        String[] parts = command.split(" ", 3);
        if (parts.length < 2 || !"config".equals(parts[0])) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorMap("Invalid command. Usage: config list|set <key> <value>|reset <key>")).build();
        }
        String action = parts[1];
        try {
            switch (action) {
                case "list": {
                    StringBuilder sb = new StringBuilder();
                    for (com.github.obhen233.core.database.SystemConfigDao.SystemConfig c : configManager.getAll()) {
                        sb.append(c.configKey).append('=').append(c.configValue).append('\n');
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "ok");
                    result.put("output", sb.toString());
                    return Response.ok(result).build();
                }
                case "set": {
                    if (parts.length < 3) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(errorMap("Missing key and value. Usage: config set <key> <value>")).build();
                    }
                    String kv = parts[2];
                    int spaceIdx = kv.indexOf(' ');
                    if (spaceIdx <= 0) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(errorMap("Missing value. Usage: config set <key> <value>")).build();
                    }
                    String cmResult = configManager.set(kv.substring(0, spaceIdx), kv.substring(spaceIdx + 1));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "ok");
                    result.put("output", cmResult != null ? cmResult : "Config updated: " + kv.substring(0, spaceIdx));
                    return Response.ok(result).build();
                }
                case "reset": {
                    if (parts.length < 3) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(errorMap("Missing key. Usage: config reset <key>")).build();
                    }
                    String resetKey = parts[2].trim();
                    String cmResult = configManager.reset(resetKey);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "ok");
                    result.put("output", cmResult != null ? cmResult : "Config reset: " + resetKey);
                    return Response.ok(result).build();
                }
                default:
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(errorMap("Unknown action: " + action + ". Use: list, set, reset")).build();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to handle gateway config command", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(errorMap(e.getMessage())).build();
        }
    }

    // ========================================================================
    // Workspace API
    // ========================================================================

    @GET
    @Path("/workspace/api/topology")
    @Produces(MediaType.APPLICATION_JSON)
    public Response workspaceTopology(@HeaderParam("Authorization") String auth) {
        if (!checkAuth(auth)) {
            return unauthorized();
        }
        long now = System.currentTimeMillis();
        WorkerRegistry workerRegistry = context.workerRegistry();
        CapabilityRouter capabilityRouter = context.capabilityRouter();
        List<WorkerInfo> workers = workerRegistry != null
                ? workerRegistry.availableWorkers() : Collections.emptyList();

        Map<String, Object> gwInfo = new LinkedHashMap<>();
        gwInfo.put("id", "gateway");
        gwInfo.put("host", "localhost");
        gwInfo.put("port", 0);
        gwInfo.put("version", "1.0.0");
        gwInfo.put("uptime", now);
        gwInfo.put("workerCount", workers.size());

        List<Map<String, Object>> workerList = new ArrayList<>();
        List<Map<String, Object>> connections = new ArrayList<>();

        for (WorkerInfo w : workers) {
            Map<String, Object> wi = new LinkedHashMap<>();
            wi.put("workerId", w.getWorkerId());
            wi.put("host", w.getHost());
            wi.put("port", w.getPort());
            wi.put("model", w.getModel());
            wi.put("group", w.getGroup() != null ? w.getGroup() : "");
            wi.put("tier", w.getTier() != null ? w.getTier() : "worker");
            wi.put("status", w.getStatus().name());
            wi.put("currentLoad", w.getMetrics().getCurrentLoad());
            wi.put("activeTasks", w.getMetrics().getActiveTasks());
            wi.put("maxConcurrency", w.getMaxConcurrency());
            wi.put("heartbeatAge", now - w.getMetrics().getLastHeartbeat());
            wi.put("lastHeartbeat", w.getMetrics().getLastHeartbeat());
            wi.put("workspace", w.getWorkspace());
            wi.put("gatewayProfile", w.getGatewayProfile());
            workerList.add(wi);

            Map<String, Object> conn = new LinkedHashMap<>();
            conn.put("from", "gateway");
            conn.put("to", w.getWorkerId());
            conn.put("type", w.isUseSsl() ? "https" : "http");
            conn.put("activeRequests", capabilityRouter != null
                    ? capabilityRouter.getActiveRequests(w.getWorkerId()) : 0);
            conn.put("status", w.isAvailable() ? "active" : "inactive");
            connections.add(conn);
        }

        List<Map<String, Object>> taskList = new ArrayList<>();
        TaskManager taskManager = context.taskManager();
        if (taskManager != null) {
            for (TaskState s : taskManager.getAllTasks()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("taskId", s.getTaskId());
                t.put("status", s.getStatus().name());
                t.put("workerId", s.getWorkerId());
                t.put("currentStep", s.getCurrentStep());
                t.put("totalTokens", s.getTotalTokens());
                t.put("createdAt", s.getCreatedAt());
                t.put("updatedAt", s.getUpdatedAt());
                taskList.add(t);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateway", gwInfo);
        result.put("workers", workerList);
        result.put("connections", connections);
        result.put("tasks", taskList);
        return Response.ok(result).build();
    }

    @GET
    @Path("/workspace/api/tasks")
    @Produces(MediaType.APPLICATION_JSON)
    public Response workspaceTasks(@HeaderParam("Authorization") String auth,
                                   @QueryParam("status") String filterStatus,
                                   @QueryParam("workerId") String filterWorker) {
        if (!checkAuth(auth)) {
            return unauthorized();
        }
        TaskManager taskManager = context.taskManager();
        List<TaskState> allTasks = taskManager != null
                ? taskManager.getAllTasks() : Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskState s : allTasks) {
            if (filterStatus != null && !filterStatus.isEmpty()
                    && !s.getStatus().name().equalsIgnoreCase(filterStatus)) {
                continue;
            }
            if (filterWorker != null && !filterWorker.isEmpty()
                    && !filterWorker.equals(s.getWorkerId())) {
                continue;
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("taskId", s.getTaskId());
            t.put("status", s.getStatus().name());
            t.put("workerId", s.getWorkerId());
            t.put("currentStep", s.getCurrentStep());
            t.put("totalTokens", s.getTotalTokens());
            t.put("createdAt", s.getCreatedAt());
            t.put("updatedAt", s.getUpdatedAt());
            result.add(t);
        }
        return Response.ok(result).build();
    }

    // ========================================================================
    // Static pages
    // ========================================================================

    @GET
    @Path("")
    @Produces(MediaType.TEXT_HTML)
    public Response index(@HeaderParam("Authorization") String auth) {
        if (isAuthConfigured()) {
            if (!checkAuth(auth)) {
                return serveHtml("login.html");
            }
        }
        return serveHtml("dashboard.html");
    }

    @GET
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    public Response indexSlash(@HeaderParam("Authorization") String auth) {
        return index(auth);
    }

    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public Response loginPage() {
        return serveHtml("login.html");
    }

    @GET
    @Path("/workspace")
    public Response workspaceRedirect() {
        return Response.status(Response.Status.FOUND)
                .header("Location", "/" + prefix + "/workspace/")
                .build();
    }

    @GET
    @Path("/workspace/")
    @Produces(MediaType.TEXT_HTML)
    public Response workspacePage(@HeaderParam("Authorization") String auth) {
        if (!checkAuth(auth)) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("").build();
        }
        return serveHtml("workspace.html");
    }

    @GET
    @Path("/routing")
    public Response routingRedirect() {
        return Response.status(Response.Status.FOUND)
                .header("Location", "/" + prefix + "/routing/")
                .build();
    }

    @GET
    @Path("/routing/")
    @Produces(MediaType.TEXT_HTML)
    public Response routingPage(@HeaderParam("Authorization") String auth) {
        if (!checkAuth(auth)) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("").build();
        }
        return serveHtml("routing.html");
    }

    @GET
    @Path("/topology")
    public Response topologyRedirect() {
        return Response.status(Response.Status.FOUND)
                .header("Location", "/" + prefix + "/topology/")
                .build();
    }

    @GET
    @Path("/topology/")
    @Produces(MediaType.TEXT_HTML)
    public Response topologyPage(@HeaderParam("Authorization") String auth) {
        if (!checkAuth(auth)) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("").build();
        }
        return serveHtml("topology-editor.html");
    }

    // ========================================================================
    // Static JS resources
    // ========================================================================

    @GET
    @Path("/echarts.min.js")
    @Produces("application/javascript")
    public Response echartsJs() {
        return serveJs("echarts.min.js");
    }

    @GET
    @Path("/vis-network.min.js")
    @Produces("application/javascript")
    public Response visNetworkJs() {
        return serveJs("vis-network.min.js");
    }

    @GET
    @Path("/jsplumb.min.js")
    @Produces("application/javascript")
    public Response jsplumbJs() {
        return serveJs("jsplumb.min.js");
    }

    // ========================================================================
    // Static serving helpers
    // ========================================================================

    private Response serveHtml(String name) {
        String content = readResource(RESOURCE_ROOT + name);
        if (content == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!"monitor".equals(prefix)) {
            content = content.replace("/monitor/", "/" + prefix + "/");
        }
        return Response.ok().type(MediaType.TEXT_HTML).entity(content).build();
    }

    private Response serveJs(String name) {
        String content = readResource(RESOURCE_ROOT + name);
        if (content == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok().type("application/javascript").entity(content).build();
    }

    private static String readResource(String classpath) {
        InputStream is = null;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            is = ctx.getResourceAsStream(classpath);
        }
        if (is == null) {
            is = MonitorResource.class.getClassLoader().getResourceAsStream(classpath);
        }
        if (is == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read resource stream: {}", e.getMessage());
        }
        return sb.toString();
    }

    private static Map<String, Object> errorMap(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        return result;
    }
}
