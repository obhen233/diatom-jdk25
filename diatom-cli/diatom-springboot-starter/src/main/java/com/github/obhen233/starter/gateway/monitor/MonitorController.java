package com.github.obhen233.starter.gateway.monitor;

import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.http.dto.TaskStateSummary;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;
import com.github.obhen233.starter.gateway.cloud.DiscoveryClientWorkerRegistry;
import com.github.obhen233.starter.gateway.cloud.GatewayNode;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Monitor 页面控制器。
 *
 * <p>提供与 standalone Gateway 功能对等的 Monitor 页面，包括仪表盘、工作区拓扑、
 * 路由规则管理、拓扑编辑器等。
 *
 * <p>路径前缀由 {@code diatom.monitor.prefix} 配置（默认 {@code monitor}）。</p>
 */
@RestController
@RequestMapping("${diatom.monitor.prefix:monitor}")
public class MonitorController {
    private static final Logger logger = LoggerFactory.getLogger(MonitorController.class);

    @Value("${diatom.monitor.prefix:monitor}")
    private String prefix;

    @Autowired(required = false)
    private WorkerRegistry workerRegistry;

    @Autowired(required = false)
    private TaskManager taskManager;

    @Autowired(required = false)
    private CapabilityRouter capabilityRouter;

    @Autowired(required = false)
    private ConfigManager configManager;

    @Autowired(required = false)
    private DatabaseManager databaseManager;

    @Autowired(required = false)
    private ResourceLockManager resourceLockManager;

    private DiatomMonitorProperties monitorProperties;
    private CommandRulesDao commandRulesDao;

    // Session tokens: token → expiry timestamp
    private final ConcurrentHashMap<String, Long> sessionTokens = new ConcurrentHashMap<>();

    // Load history for dashboard charts
    private final CopyOnWriteArrayList<Object[]> workerLoadHistory = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Object[]> pendingHistory = new CopyOnWriteArrayList<>();

    public MonitorController(DiatomMonitorProperties monitorProperties) {
        this.monitorProperties = monitorProperties;
    }

    @PostConstruct
    public void init() {
        if (databaseManager != null) {
            try {
                this.commandRulesDao = new CommandRulesDao(databaseManager);
            } catch (Exception e) {
                logger.warn("Failed to create CommandRulesDao: {}", e.getMessage());
            }
        }
        if (!monitorProperties.isEnabled()) {
            logger.info("Monitor page is disabled");
        } else {
            logger.info("Monitor page enabled at /{}/", prefix);
        }
    }

    // ========================================================================
    // Auth
    // ========================================================================

    private boolean isAuthConfigured() {
        return monitorProperties.isAuthConfigured();
    }

    private String checkAuth(String authorization) {
        if (!isAuthConfigured()) return "ok"; // no auth required
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Long expiry = sessionTokens.get(token);
        if (expiry == null || System.currentTimeMillis() > expiry) {
            if (expiry != null) sessionTokens.remove(token);
            return null;
        }
        return "ok";
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
    }

    // ========================================================================
    // POST /{prefix}/api/login
    // ========================================================================

    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (isAuthConfigured()) {
            if (username == null || password == null
                    || !monitorProperties.getUsername().equals(username)
                    || !monitorProperties.getPassword().equals(password)) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "Invalid username or password");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
            }
        }

        String token = UUID.randomUUID().toString();
        long expiry = System.currentTimeMillis() + monitorProperties.getTokenExpireSeconds() * 1000L;
        sessionTokens.put(token, expiry);

        // Clean expired tokens
        long now = System.currentTimeMillis();
        sessionTokens.entrySet().removeIf(e -> e.getValue() < now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("expiresIn", monitorProperties.getTokenExpireSeconds());
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // GET /{prefix}/api/status
    // ========================================================================

    @GetMapping("/api/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (checkAuth(auth) == null) return unauthorized();

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
            if (s.getStatus() == TaskStatus.PENDING || s.getStatus() == TaskStatus.ASSIGNED) pendingTasks++;
            if (s.getStatus().isActive()) activeTasksVal++;
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

            // Collect load history
            workerLoadHistory.add(new Object[]{now, w.getWorkerId(), w.getMetrics().getCurrentLoad()});
        }
        // Trim load history
        int maxHistory = 300;
        while (workerLoadHistory.size() > maxHistory) workerLoadHistory.removeFirst();

        pendingHistory.add(new Object[]{now, pendingTasks});
        while (pendingHistory.size() > maxHistory) pendingHistory.removeFirst();

        List<Object[]> loadHist = new ArrayList<>(workerLoadHistory);
        List<Object[]> pendHist = new ArrayList<>(pendingHistory);

        int totalTasks = allTasks.size();
        int onlineWorkers = workers.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onlineWorkers", onlineWorkers);
        result.put("activeTasks", activeTasksVal);
        result.put("pendingTasks", pendingTasks);
        result.put("totalTasks", totalTasks);
        result.put("authConfigured", isAuthConfigured());
        result.put("workers", workerList);
        result.put("tasks", taskList);
        result.put("workerLoadHistory", loadHist);
        result.put("pendingHistory", pendHist);
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // GET /{prefix}/api/i18n
    // ========================================================================

    @GetMapping("/api/i18n")
    public ResponseEntity<?> i18n(
            @RequestParam(value = "lang", required = false) String lang) {
        if (lang == null || lang.isEmpty()) {
            lang = configManager != null ? configManager.get("monitor.language") : "";
        }
        if (lang == null || lang.isEmpty()) {
            lang = configManager != null ? configManager.get("agent.language") : "";
        }
        if (lang == null || lang.isEmpty()) {
            lang = "zh";
        }

        String resourcePath = "monitor/i18n_" + lang + ".json";
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            resourcePath = "monitor/i18n_en.json";
            is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        }
        if (is == null) {
            return ResponseEntity.ok(Collections.emptyMap());
        }

        String json = readStream(is);
        try {
            Map<String, Object> map = JsonUtils.fromJson(json, Map.class);
            return ResponseEntity.ok(map != null ? map : Collections.emptyMap());
        } catch (Exception e) {
            return ResponseEntity.ok(json);
        }
    }

    // ========================================================================
    // GET /{prefix}/api/rules
    // ========================================================================

    @GetMapping("/api/rules")
    public ResponseEntity<Map<String, Object>> rules(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(value = "target", defaultValue = "gateway") String target,
            @RequestParam(value = "workerId", required = false) String workerId) {
        if (checkAuth(auth) == null) return unauthorized();

        if ("gateway".equals(target)) {
            if (commandRulesDao == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(errorMap("Gateway database not available"));
            }
            List<CommandRulesDao.CommandRule> rules = commandRulesDao.findAll();
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
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("target", "gateway");
            result.put("rules", ruleList);
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(errorMap("Unknown target: " + target));
        }
    }

    // ========================================================================
    // POST /{prefix}/api/rules
    // ========================================================================

    @PostMapping("/api/rules")
    public ResponseEntity<Map<String, Object>> rulesSave(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, Object> body) {
        if (checkAuth(auth) == null) return unauthorized();

        String target = (String) body.getOrDefault("target", "gateway");
        String action = (String) body.getOrDefault("action", "list");

        if ("gateway".equals(target)) {
            if (commandRulesDao == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(errorMap("Gateway database not available"));
            }
            Map<String, Object> result = executeGatewayRulesAction(body, action);
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(errorMap("Unknown target: " + target));
        }
    }

    private Map<String, Object> executeGatewayRulesAction(Map<String, Object> body, String action) {
        switch (action) {
            case "list": {
                List<CommandRulesDao.CommandRule> rules = commandRulesDao.findAll();
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
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                result.put("target", "gateway");
                result.put("rules", ruleList);
                return result;
            }
            case "add": {
                String mode = (String) body.get("mode");
                String type = (String) body.get("type");
                String pattern = (String) body.get("pattern");
                if (mode == null || type == null || pattern == null) {
                    return errorMap("Missing required fields: mode, type, pattern");
                }
                CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule();
                rule.mode = mode;
                rule.type = type;
                rule.pattern = pattern;
                rule.source = "manual";
                rule.enabled = true;
                rule.createdAt = System.currentTimeMillis();
                rule.updatedAt = rule.createdAt;
                commandRulesDao.insert(rule);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                result.put("id", 0);
                return result;
            }
            case "delete": {
                Object idObj = body.get("id");
                if (idObj == null) return errorMap("Missing id");
                long id = ((Number) idObj).longValue();
                commandRulesDao.delete(id);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                return result;
            }
            case "enable": {
                Object idObj = body.get("id");
                if (idObj == null) return errorMap("Missing id");
                long id = ((Number) idObj).longValue();
                commandRulesDao.updateEnabled(id, true);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ok");
                return result;
            }
            case "disable": {
                Object idObj = body.get("id");
                if (idObj == null) return errorMap("Missing id");
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

    // ========================================================================
    // POST /{prefix}/api/config
    // ========================================================================

    @PostMapping("/api/config")
    public ResponseEntity<Map<String, Object>> config(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, Object> body) {
        if (checkAuth(auth) == null) return unauthorized();

        String workerId = (String) body.get("workerId");
        String command = (String) body.get("command");
        if (workerId == null || workerId.isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap("Missing workerId"));
        }
        if (command == null || command.isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap("Missing command"));
        }

        if ("__gateway__".equals(workerId)) {
            return handleGatewayLocalConfig(command);
        }
        // Proxy to worker (future: use SpringGatewayTransport)
        return ResponseEntity.ok(errorMap("Worker command proxy not yet implemented"));
    }

    private ResponseEntity<Map<String, Object>> handleGatewayLocalConfig(String command) {
        if (configManager == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(errorMap("ConfigManager not available on this gateway"));
        }
        String[] parts = command.split(" ", 3);
        if (parts.length < 2 || !"config".equals(parts[0])) {
            return ResponseEntity.badRequest().body(errorMap("Invalid command. Usage: config list|set <key> <value>|reset <key>"));
        }
        String action = parts[1];
        try {
            switch (action) {
                case "list": {
                    StringBuilder sb = new StringBuilder();
                    configManager.getAll();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "ok");
                    result.put("output", sb.toString());
                    return ResponseEntity.ok(result);
                }
                case "set": {
                    if (parts.length < 3) {
                        return ResponseEntity.badRequest().body(errorMap("Missing key and value. Usage: config set <key> <value>"));
                    }
                    String kv = parts[2];
                    int spaceIdx = kv.indexOf(' ');
                    if (spaceIdx <= 0) {
                        return ResponseEntity.badRequest().body(errorMap("Missing value. Usage: config set <key> <value>"));
                    }
                    String cmResult = configManager.set(kv.substring(0, spaceIdx), kv.substring(spaceIdx + 1));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "ok");
                    result.put("output", cmResult != null ? cmResult : "Config updated: " + kv.substring(0, spaceIdx));
                    return ResponseEntity.ok(result);
                }
                case "reset": {
                    if (parts.length < 3) {
                        return ResponseEntity.badRequest().body(errorMap("Missing key. Usage: config reset <key>"));
                    }
                    String resetKey = parts[2].trim();
                    String cmResult = configManager.reset(resetKey);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "ok");
                    result.put("output", cmResult != null ? cmResult : "Config reset: " + resetKey);
                    return ResponseEntity.ok(result);
                }
                default:
                    return ResponseEntity.badRequest().body(errorMap("Unknown action: " + action + ". Use: list, set, reset"));
            }
        } catch (Exception e) {
            logger.error("Failed to handle gateway config command", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorMap(e.getMessage()));
        }
    }

    // ========================================================================
    // Workspace API
    // ========================================================================

    @GetMapping("/workspace/api/topology")
    public ResponseEntity<Map<String, Object>> workspaceTopology(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (checkAuth(auth) == null) return unauthorized();

        long now = System.currentTimeMillis();
        List<WorkerInfo> workers = workerRegistry != null
                ? workerRegistry.availableWorkers() : Collections.emptyList();

        // 注册中心直读模式（Path B）：多 gateway 共享同一 worker 集合，无 gateway↔worker 连线
        boolean registryMode = isDirectReadRegistry();

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

            // 直读模式下 gateway 与 worker 都只对注册中心通信，不生成 gateway→worker 连线
            if (!registryMode) {
                Map<String, Object> conn = new LinkedHashMap<>();
                conn.put("from", "gateway");
                conn.put("to", w.getWorkerId());
                conn.put("type", w.isUseSsl() ? "https" : "http");
                conn.put("activeRequests", capabilityRouter != null
                        ? capabilityRouter.getActiveRequests(w.getWorkerId()) : 0);
                conn.put("status", w.isAvailable() ? "active" : "inactive");
                connections.add(conn);
            }
        }

        List<Map<String, Object>> taskList = new ArrayList<>();
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

        List<GatewayNode> gateways = null;
        if (registryMode) {
            try {
                gateways = ((DiscoveryClientWorkerRegistry) workerRegistry).gatewayNodes();
            } catch (LinkageError | RuntimeException e) {
                logger.debug("Failed to read gateway nodes from discovery: {}", e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateway", gwInfo);
        result.put("gateways", gateways);
        result.put("workers", workerList);
        result.put("connections", connections);
        result.put("tasks", taskList);
        return ResponseEntity.ok(result);
    }

    /**
     * 判断当前 WorkerRegistry 是否为注册中心直读实现。
     * 无 spring-cloud-commons（DiscoveryClientWorkerRegistry 类不可加载）时安全回退 false。
     */
    private boolean isDirectReadRegistry() {
        if (workerRegistry == null) return false;
        try {
            return workerRegistry instanceof DiscoveryClientWorkerRegistry;
        } catch (LinkageError e) {
            logger.debug("DiscoveryClientWorkerRegistry not loadable ({}), using legacy topology", e.getMessage());
            return false;
        }
    }

    @GetMapping("/workspace/api/tasks")
    public ResponseEntity<List<Map<String, Object>>> workspaceTasks(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(value = "status", required = false) String filterStatus,
            @RequestParam(value = "workerId", required = false) String filterWorker) {
        if (checkAuth(auth) == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);

        List<TaskState> allTasks = taskManager != null
                ? taskManager.getAllTasks() : Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (TaskState s : allTasks) {
            if (filterStatus != null && !filterStatus.isEmpty()
                    && !s.getStatus().name().equalsIgnoreCase(filterStatus)) continue;
            if (filterWorker != null && !filterWorker.isEmpty()
                    && !filterWorker.equals(s.getWorkerId())) continue;
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
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // Static page serving
    // ========================================================================

    /**
     * Serve dashboard or login page at /{prefix}/ or /{prefix}.
     */
    @GetMapping({"", "/"})
    public ResponseEntity<String> index(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (isAuthConfigured()) {
            // If not authenticated, show login page
            if (checkAuth(auth) == null) {
                return serveHtml("monitor/login.html");
            }
        }
        return serveHtml("monitor/dashboard.html");
    }

    @GetMapping("/login")
    public ResponseEntity<String> loginPage() {
        return serveHtml("monitor/login.html");
    }

    @GetMapping("/workspace")
    public ResponseEntity<Void> workspaceRedirect() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/" + prefix + "/workspace/")
                .build();
    }

    @GetMapping("/workspace/")
    public ResponseEntity<String> workspacePage(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (checkAuth(auth) == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
        return serveHtml("monitor/workspace.html");
    }

    @GetMapping("/routing")
    public ResponseEntity<Void> routingRedirect() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/" + prefix + "/routing/")
                .build();
    }

    @GetMapping("/routing/")
    public ResponseEntity<String> routingPage(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (checkAuth(auth) == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
        return serveHtml("monitor/routing.html");
    }

    @GetMapping("/topology")
    public ResponseEntity<Void> topologyRedirect() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/" + prefix + "/topology/")
                .build();
    }

    @GetMapping("/topology/")
    public ResponseEntity<String> topologyPage(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (checkAuth(auth) == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
        return serveHtml("monitor/topology-editor.html");
    }

    // ========================================================================
    // Static resource serving (JS, CSS)
    // ========================================================================

    @GetMapping("/echarts.min.js")
    public ResponseEntity<String> echartsJs() {
        return serveJs("monitor/echarts.min.js");
    }

    @GetMapping("/vis-network.min.js")
    public ResponseEntity<String> visNetworkJs() {
        return serveJs("monitor/vis-network.min.js");
    }

    @GetMapping("/jsplumb.min.js")
    public ResponseEntity<String> jsplumbJs() {
        return serveJs("monitor/jsplumb.min.js");
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private ResponseEntity<String> serveHtml(String classpath) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(classpath);
        if (is == null) {
            return ResponseEntity.notFound().build();
        }
        String content = readStream(is);
        // Replace hardcoded /monitor/ prefix with configured prefix
        if (!"monitor".equals(prefix)) {
            content = content.replace("/monitor/", "/" + prefix + "/");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(content);
    }

    private ResponseEntity<String> serveJs(String classpath) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(classpath);
        if (is == null) {
            return ResponseEntity.notFound().build();
        }
        String content = readStream(is);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/javascript"))
                .body(content);
    }

    private static String readStream(InputStream is) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            logger.warn("Failed to read resource stream: {}", e.getMessage());
        }
        return sb.toString();
    }

    private static Map<String, Object> errorMap(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        return result;
    }
}
