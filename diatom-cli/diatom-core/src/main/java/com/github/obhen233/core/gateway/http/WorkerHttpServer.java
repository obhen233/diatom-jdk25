package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.agent.ToolConfirmationException;
import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.pipeline.PipelineCallback;
import com.github.obhen233.core.pipeline.PipelineService;
import com.github.obhen233.core.pipeline.RunnerRegistry;
import com.github.obhen233.core.session.SessionTracker;
import com.github.obhen233.spi.CoreCommandRegistry;
import com.github.obhen233.spi.command.CommandOutput;
import com.github.obhen233.core.gateway.http.dto.ApiError;
import com.github.obhen233.core.gateway.http.dto.AuditEntry;
import com.github.obhen233.core.gateway.http.dto.FileChanges;
import com.github.obhen233.core.gateway.http.dto.SseEvent;
import com.github.obhen233.core.gateway.http.dto.WorkerChatResponse;
import com.github.obhen233.core.gateway.http.dto.WorkerMeta;
import com.github.obhen233.core.gateway.sync.FileDiffResult;
import com.github.obhen233.core.gateway.sync.ProjectSyncService;
import com.github.obhen233.core.tool.builtin.FileTools;
import com.github.obhen233.core.gateway.security.SecurityHeadersInjector;
import com.github.obhen233.core.gateway.security.SecurityProviderLoader;
import com.github.obhen233.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.obhen233.core.gateway.http.docs.GatewayApi;
import com.github.obhen233.core.spi.http.HttpServerSpi;
import com.github.obhen233.core.spi.http.ServerHandler;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ServiceLoader;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

/**
 * Worker HTTP 服务器
 * 提供 /worker/v1/* 所有端点
 */
public class WorkerHttpServer {
    private static final Logger logger = LoggerFactory.getLogger(WorkerHttpServer.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();
    private static final int MAX_CONFIRM_DEPTH = 3;

    private volatile HttpServerSpi serverSpi;
    private final int port;
    private final ReActAgent agent;
    private final CoreCommandRegistry commandRegistry;
    private final ConfigManager configManager;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile String currentTaskId;
    private volatile CancellationListener cancelListener;
    private volatile String gatewayUrl;
    private volatile String instanceId;
    private volatile ConfirmationCallback confirmationCallback;
    private volatile String rulesPath;

    /** 项目文件同步服务 */
    private final ProjectSyncService projectSyncService = new ProjectSyncService();

    /** TaskId → 文件快照 (相对路径 → MD5)。支持 "taskId:subTaskId" 双 key */
    private static final int MAX_SNAPSHOTS = 500;
    private final ConcurrentHashMap<String, Map<String, String>> projectSnapshots = new ConcurrentHashMap<String, Map<String, String>>();

    /** 白名单：Worker 允许的 workspace 路径前缀（从 diatom.worker.allowed.workspaces 系统属性读取，逗号分隔） */
    private static final Set<String> allowedWorkspacePrefixes = loadAllowedWorkspaces();

    private static Set<String> loadAllowedWorkspaces() {
        String val = System.getProperty("diatom.worker.allowed.workspaces");
        if (val == null || val.trim().isEmpty()) return null; // null = 不限制
        String[] parts = val.split(",");
        Set<String> set = new HashSet<String>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed.replace("/", "\\").toLowerCase());
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * 校验 workspacePath 是否在白名单内。
     * 如果白名单为 null（未配置），则允许所有路径。
     */
    private static boolean isWorkspaceAllowed(String workspacePath) {
        if (allowedWorkspacePrefixes == null) return true;
        if (workspacePath == null || workspacePath.isEmpty()) return true;
        String normalized = workspacePath.replace("/", "\\").toLowerCase();
        for (String prefix : allowedWorkspacePrefixes) {
            if (normalized.startsWith(prefix)) return true;
        }
        return false;
    }

    public interface CancellationListener {
        void onCancel(String taskId);
    }

    @FunctionalInterface
    public interface ConfirmationCallback {
        String requestConfirmation(ToolConfirmationException ex);
    }

    public WorkerHttpServer(int port, ReActAgent agent) throws IOException {
        this(port, agent, null, null, null);
    }

    public WorkerHttpServer(int port, ReActAgent agent,
                            CoreCommandRegistry commandRegistry,
                            ConfigManager configManager) throws IOException {
        this(port, agent, commandRegistry, configManager, null);
    }

    public WorkerHttpServer(int port, ReActAgent agent,
                            CoreCommandRegistry commandRegistry,
                            ConfigManager configManager,
                            HttpServerSpi serverSpi) throws IOException {
        this.port = port;
        this.agent = agent;
        this.commandRegistry = commandRegistry;
        this.configManager = configManager;
        this.serverSpi = resolveHttpServerSpi(port, serverSpi);
        registerRoutes();
    }

    /**
     * Resolve the HttpServerSpi instance: prefer explicitly provided, then ServiceLoader,
     * then fall back to default JdkHttpServer.
     */
    private static HttpServerSpi resolveHttpServerSpi(int port, HttpServerSpi provided) throws IOException {
        if (provided != null) {
            return provided;
        }
        try {
            ServiceLoader<HttpServerSpi> loader = ServiceLoader.load(HttpServerSpi.class);
            for (HttpServerSpi spi : loader) {
                logger.info("Using custom HttpServerSpi from ServiceLoader: {}", spi.getClass().getName());
                return spi;
            }
        } catch (Exception e) {
            logger.warn("Failed to load HttpServerSpi via ServiceLoader, falling back to JdkHttpServer: {}", e.getMessage());
        }
        return new JdkHttpServer(port, Executors.newVirtualThreadPerTaskExecutor());
    }

    public void setCancelListener(CancellationListener listener) {
        this.cancelListener = listener;
    }

    public void setCurrentTaskId(String taskId) {
        this.currentTaskId = taskId;
        this.cancelled.set(false);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void setConfirmationCallback(ConfirmationCallback callback) {
        this.confirmationCallback = callback;
    }

    /**
     * Get the callback base URL for this Worker (used as callbackUrl in async confirm requests).
     * Format: http://127.0.0.1:{port}
     */
    public String getCallbackBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /**
     * Get the pending confirm callbacks map for async confirmation pattern.
     * Used by ServerModeLauncher to wire the GatewayConfirmationCallback.
     */
    public ConcurrentHashMap<String, CompletableFuture<String>> getPendingConfirmCallbacks() {
        return pendingConfirmCallbacks;
    }

    public void setGatewayUrl(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public void setRulesPath(String rulesPath) {
        this.rulesPath = rulesPath;
    }

    /**
     * Dynamically upgrade the HTTP server to HTTPS on the same port,
     * without restarting the process.
     *
     * <p>Delegates to the underlying {@link HttpServerSpi} implementation.</p>
     *
     * @param certPem  the signed certificate in PEM format
     * @param keyPem   the private key in PEM format (PKCS#8)
     * @param caCertPem the CA certificate in PEM format (for trust chain)
     * @param password password for the key (may be empty string)
     */
    public void upgradeToHttps(String certPem, String keyPem, String caCertPem, String password) {
        serverSpi.upgradeToHttps(certPem, keyPem, caCertPem, password);
    }

    private void registerRoutes() {
        serverSpi.addHandler("GET", "/worker/v1/health", this::handleHealth);
        serverSpi.addHandler("POST", "/worker/v1/cancel", this::handleCancel);
        serverSpi.addHandler("POST", "/worker/v1/chat", this::handleChat);
        serverSpi.addHandler("POST", "/worker/v1/chat/stream", this::handleChatStream);
        serverSpi.addHandler("POST", "/worker/v1/migrate", this::handleMigrate);
        serverSpi.addHandler("POST", "/worker/v1/shutdown-notice", this::handleShutdownNotice);
        serverSpi.addHandler("POST", "/worker/v1/command", this::handleCommand);
        serverSpi.addHandler("GET", "/worker/v1/rules", this::handleRules);
        serverSpi.addHandler("POST", "/worker/v1/rules", this::handleRules);
        serverSpi.addHandler("POST", "/worker/v1/resume", this::handleResume);
        serverSpi.addHandler("POST", "/worker/v1/deploy", this::handleDeploy);
        serverSpi.addHandler("POST", "/worker/v1/project/push", this::handleProjectPush);
        serverSpi.addHandler("POST", "/worker/v1/sandbox/setup", this::handleSandboxSetup);
        serverSpi.addHandler("POST", "/worker/v1/sandbox/cleanup", this::handleSandboxCleanup);
        serverSpi.addHandler("POST", "/worker/v1/confirm-callback", this::handleConfirmCallback);
        logger.info("All worker endpoints registered");
    }

    // ========== Worker-side pending confirm callbacks (async pattern) ==========
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingConfirmCallbacks = new ConcurrentHashMap<String, CompletableFuture<String>>();

    @GatewayApi(path = "/worker/v1/confirm-callback", methods = {"POST"},
            summary = "Receive confirmation callback",
            description = "Receive the user's approval/rejection decision for a pending confirmation request.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "Body fields: requestId (required), decision (required, 'approved' or 'rejected').",
            tags = {"审批 / Approval"})
    /**
     * POST /worker/v1/confirm-callback
     * Receives the decision for an async confirmation request from the Gateway.
     * Resolves the pending CompletableFuture so the waiting requestConfirmation() unblocks.
     */
    private void handleConfirmCallback(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        String requestId = extractJsonValue(body, "requestId");
        String decision = extractJsonValue(body, "decision");

        if (requestId == null || decision == null) {
            sendJson(response, 400, ApiError.of("Missing requestId or decision"));
            return;
        }

        CompletableFuture<String> future = pendingConfirmCallbacks.remove(requestId);
        if (future != null) {
            future.complete(decision);
            logger.info("Confirm callback received: requestId={}, decision={}", requestId, decision);
            sendJson(response, 200, Collections.singletonMap("status", "ok"));
        } else {
            logger.warn("Confirm callback for unknown requestId: {}", requestId);
            sendJson(response, 404, ApiError.of("Unknown requestId"));
        }
    }

    @GatewayApi(path = "/worker/v1/project/push", methods = {"POST"},
            summary = "Receive project files zip",
            description = "Receive and unpack a project zip file pushed from the Gateway. Headers: X-Workspace-Path, X-Task-Id.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "Body: binary zip data. Headers: X-Workspace-Path (required), X-Task-Id, X-Sub-Task-Id.",
            tags = {"项目同步 / Project Sync"})
    /**
     * 接收 Gateway 推送的项目 zip 包并解压。
     * POST /worker/v1/project/push
     * Headers: X-Workspace-Path, X-Task-Id
     * Body: zip bytes
     */
    private void handleProjectPush(ServerRequest request, ServerResponse response) throws IOException {
        // Authenticate from headers (binary body, not JSON)
        if (!authenticateRequest(request, response)) {
            return;
        }

        String workspacePath = request.getHeader("X-Workspace-Path");
        String taskId = request.getHeader("X-Task-Id");
        String subTaskId = request.getHeader("X-Sub-Task-Id");

        if (workspacePath == null || workspacePath.isEmpty()) {
            sendError(response, 400, "Missing X-Workspace-Path header");
            return;
        }

        try {
            // Read zip body as raw bytes
            byte[] zipData = request.getBodyBytes();

            // Validate workspace directory
            Path targetDir = Paths.get(workspacePath);
            Files.createDirectories(targetDir);

            // Unpack project zip
            projectSyncService.unpackProject(zipData, targetDir);
            logger.info("Project push unpacked to {} ({} bytes zip)", workspacePath, zipData.length);

            // Snapshot for diff tracking (supports taskId:subTaskId dual key)
            Map<String, String> snapshot = projectSyncService.snapshotProject(targetDir);
            String snapshotKey = buildSnapshotKey(taskId, subTaskId);
            if (snapshotKey != null) {
                putSnapshot(snapshotKey, snapshot);
            }

            Map<String, Object> pushResp = new HashMap<String, Object>();
            pushResp.put("status", "ok");
            pushResp.put("files", snapshot.size());
            sendJson(response, 200, pushResp);
        } catch (Exception e) {
            logger.error("Failed to handle project push: {}", e.getMessage());
            sendError(response, 500, "Failed to unpack project: " + e.getMessage());
        }
    }

    @GatewayApi(path = "/worker/v1/sandbox/setup", methods = {"POST"},
            summary = "Setup sandbox workspace",
            description = "Setup an isolated sandbox workspace. Headers: X-Workspace-Path, X-Task-Id, X-Push-Type (full/incremental).\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "Body: binary zip data. X-Push-Type supports 'full' (clear + extract) or 'incremental' (update by manifest).",
            tags = {"项目同步 / Project Sync"})
    /**
     * 接收 Gateway 推送的项目 zip 包并解压到沙箱目录。
     * POST /worker/v1/sandbox/setup
     * Headers: X-Workspace-Path, X-Task-Id, X-Sub-Task-Id, X-Push-Type
     * Body: zip bytes
     *
     * X-Push-Type 支持两种模式：
     * <ul>
     *   <li>{@code full} - 全量推送：清空沙箱目录后完整解压</li>
     *   <li>{@code incremental} - 增量推送：根据 manifest 增/改/删文件</li>
     * </ul>
     */
    private void handleSandboxSetup(ServerRequest request, ServerResponse response) throws IOException {
        // Authenticate from headers (binary body, not JSON)
        if (!authenticateRequest(request, response)) {
            return;
        }

        String workspacePath = request.getHeader("X-Workspace-Path");
        String taskId = request.getHeader("X-Task-Id");
        String subTaskId = request.getHeader("X-Sub-Task-Id");
        String pushType = request.getHeader("X-Push-Type");
        if (pushType == null || pushType.isEmpty()) {
            pushType = "full"; // 默认全量（兼容旧版 Gateway）
        }

        if (workspacePath == null || workspacePath.isEmpty()) {
            sendError(response, 400, "Missing X-Workspace-Path header");
            return;
        }

        // 验证沙箱路径是否在白名单内
        if (!isWorkspaceAllowed(workspacePath)) {
            sendError(response, 403, "Sandbox path not allowed: " + escapeJson(workspacePath));
            return;
        }

        try {
            // Read zip body as raw bytes
            byte[] zipData = request.getBodyBytes();

            // Create sandbox directory
            Path sandboxDir = Paths.get(workspacePath);
            Files.createDirectories(sandboxDir);

            if (zipData.length > 0) {
                if ("incremental".equals(pushType)) {
                    applyIncrementalPush(zipData, sandboxDir);
                    logger.info("Incremental sandbox update applied to {} ({} bytes zip)",
                            workspacePath, zipData.length);
                } else {
                    // Full push: clear existing sandbox and unpack full project
                    clearSandboxDirectory(sandboxDir);
                    Files.createDirectories(sandboxDir);
                    projectSyncService.unpackProject(zipData, sandboxDir);
                    logger.info("Full sandbox setup unpacked to {} ({} bytes zip)",
                            workspacePath, zipData.length);
                }
            }

            // Snapshot for diff tracking (key = taskId:subTaskId)
            Map<String, String> snapshot = projectSyncService.snapshotProject(sandboxDir);
            String snapshotKey = buildSnapshotKey(taskId, subTaskId);
            if (snapshotKey != null) {
                putSnapshot(snapshotKey, snapshot);
            }

            int fileCount = snapshot.size();
            String respJson = "{\"sandboxPath\":\"" + escapeJson(workspacePath)
                    + "\",\"fileCount\":" + fileCount + ",\"status\":\"ok\"}";
            sendJson(response, 200, respJson);
        } catch (Exception e) {
            logger.error("Failed to handle sandbox setup: {}", e.getMessage());
            sendError(response, 500, "Sandbox setup failed: " + e.getMessage());
        }
    }

    /**
     * 处理增量推送：读取 zip 中的 manifest，按操作类型增/改/删沙箱中的文件。
     */
    private void applyIncrementalPush(byte[] zipData, Path sandboxDir) throws IOException {
        // 第一遍扫描：读取 manifest
        String manifestContent = null;
        Map<String, byte[]> fileContents = new HashMap<String, byte[]>();

        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(zipData), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/');
                ByteArrayOutputStream entryBaos = new ByteArrayOutputStream((int) Math.max(entry.getSize(), 0));
                byte[] chunk = new byte[8192];
                int n;
                while ((n = zis.read(chunk)) != -1) {
                    entryBaos.write(chunk, 0, n);
                }
                byte[] content = entryBaos.toByteArray();

                if (".diatom-sync-manifest.json".equals(name)) {
                    manifestContent = new String(content, StandardCharsets.UTF_8);
                } else {
                    fileContents.put(name, content);
                }
                zis.closeEntry();
            }
        }

        if (manifestContent == null) {
            logger.warn("Incremental push missing manifest, treating as full unpack");
            projectSyncService.unpackProject(zipData, sandboxDir);
            return;
        }

        // 解析 manifest JSON
        List<IncrementalFileEntry> files = parseIncrementalManifest(manifestContent);

        for (IncrementalFileEntry file : files) {
            Path targetFile = sandboxDir.resolve(file.path.replace('/', java.io.File.separatorChar));
            switch (file.operation) {
                case "MODIFIED":
                case "CREATED":
                    byte[] content = fileContents.get(file.path);
                    if (content != null) {
                        Files.createDirectories(targetFile.getParent());
                        Files.write(targetFile, content);
                        logger.debug("Incremental {}: {}", file.operation.toLowerCase(), file.path);
                    } else {
                        logger.warn("Incremental push: content not found for {} file {}", file.operation, file.path);
                    }
                    break;
                case "DELETED":
                    if (Files.exists(targetFile)) {
                        Files.delete(targetFile);
                        logger.debug("Incremental deleted: {}", file.path);
                        // 尝试删除空父目录
                        deleteEmptyParentDirs(targetFile, sandboxDir);
                    }
                    break;
                default:
                    logger.warn("Unknown incremental operation: {} for file {}", file.operation, file.path);
            }
        }
    }

    /**
     * 删除文件后，递归删除空父目录（直到 sandboxDir）。
     */
    private static void deleteEmptyParentDirs(Path file, Path sandboxDir) throws IOException {
        Path parent = file.getParent();
        while (parent != null && !parent.equals(sandboxDir)) {
            if (Files.isDirectory(parent) && !Files.list(parent).findAny().isPresent()) {
                Files.delete(parent);
                parent = parent.getParent();
            } else {
                break;
            }
        }
    }

    /**
     * 清空沙箱目录（删除所有子文件和子目录，保留目录本身）。
     */
    private static void clearSandboxDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            logger.warn("Failed to delete {} during sandbox clear: {}", p, e.getMessage());
                        }
                    });
        }
    }

    /**
     * 增量 manifest 中的文件条目。
     */
    private static class IncrementalFileEntry {
        final String path;
        final String operation;

        IncrementalFileEntry(String path, String operation) {
            this.path = path;
            this.operation = operation;
        }
    }

    /**
     * 解析增量 manifest JSON。
     * 格式: {"type":"incremental","files":[{"path":"...","operation":"MODIFIED"},...]}
     */
    private static List<IncrementalFileEntry> parseIncrementalManifest(String json) {
        List<IncrementalFileEntry> result = new ArrayList<IncrementalFileEntry>();
        if (json == null || json.isEmpty()) return result;

        // 定位 "files": 数组
        String filesKey = "\"files\":";
        int filesStart = json.indexOf(filesKey);
        if (filesStart < 0) return result;
        filesStart += filesKey.length();
        while (filesStart < json.length() && json.charAt(filesStart) == ' ') filesStart++;

        if (filesStart >= json.length() || json.charAt(filesStart) != '[') return result;

        // 逐对象解析
        int i = filesStart + 1;
        while (i < json.length()) {
            // 跳过空白和逗号
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ',' || json.charAt(i) == '\n' || json.charAt(i) == '\r' || json.charAt(i) == '\t')) i++;
            if (i >= json.length() || json.charAt(i) != '{') break;

            int objEnd = json.indexOf('}', i);
            if (objEnd < 0) break;
            String obj = json.substring(i, objEnd + 1);
            i = objEnd + 1;

            String path = extractSimpleJsonValue(obj, "path");
            String operation = extractSimpleJsonValue(obj, "operation");
            if (path != null && operation != null) {
                result.add(new IncrementalFileEntry(path, operation));
            }
        }

        return result;
    }

    /**
     * 从简单 JSON 对象中提取字符串字段值（非嵌套，不处理转义）。
     */
    private static String extractSimpleJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('\"', start);
        return end > start ? json.substring(start, end) : null;
    }

    @GatewayApi(path = "/worker/v1/sandbox/cleanup", methods = {"POST"},
            summary = "Cleanup sandbox workspace",
            description = "Remove a sandbox workspace after task completion.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.",
            tags = {"项目同步 / Project Sync"})
    /**
     * 清理 Worker 上的沙箱目录。
     * POST /worker/v1/sandbox/cleanup
     * Body: {"sandboxPath":"..."}
     */
    private void handleSandboxCleanup(ServerRequest request, ServerResponse response) throws IOException {
        if (!authenticateRequest(request, response)) {
            return;
        }

        String body = readBody(request);
        String sandboxPath = extractJsonValue(body, "sandboxPath");

        if (sandboxPath == null || sandboxPath.isEmpty()) {
            sendError(response, 400, "Missing sandboxPath");
            return;
        }

        try {
            Path sandboxDir = Paths.get(sandboxPath);
            if (Files.exists(sandboxDir)) {
                // 递归删除沙箱目录
                Files.walk(sandboxDir)
                        .sorted((a, b) -> -a.compareTo(b)) // 先删子文件
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                logger.warn("Failed to delete sandbox file: {}", p);
                            }
                        });
                logger.info("Sandbox cleaned up: {}", sandboxPath);
            }

            // 清理相关 snapshot
            for (Map.Entry<String, Map<String, String>> entry : projectSnapshots.entrySet()) {
                if (entry.getKey().contains(sandboxPath)) {
                    projectSnapshots.remove(entry.getKey());
                }
            }

            sendJson(response, 200, Collections.singletonMap("status", "ok"));
        } catch (Exception e) {
            logger.error("Failed to handle sandbox cleanup: {}", e.getMessage());
            sendError(response, 500, "Sandbox cleanup failed: " + e.getMessage());
        }
    }

    /**
     * 构建快照 key："taskId:subTaskId"
     */
    private static String buildSnapshotKey(String taskId, String subTaskId) {
        if (taskId == null || taskId.isEmpty()) return null;
        if (subTaskId != null && !subTaskId.isEmpty()) {
            return taskId + ":" + subTaskId;
        }
        return taskId;
    }

    private void putSnapshot(String key, Map<String, String> snapshot) {
        projectSnapshots.put(key, snapshot);
        // Trim oldest entries if cache exceeds limit
        if (projectSnapshots.size() > MAX_SNAPSHOTS) {
            String eldest = null;
            for (String k : projectSnapshots.keySet()) {
                eldest = k;
                break;
            }
            if (eldest != null) {
                projectSnapshots.remove(eldest);
            }
        }
    }

    @GatewayApi(path = "/worker/v1/health", methods = {"GET"},
            summary = "Worker health check",
            description = "Worker health check endpoint. Returns UP status and worker ID.\n\n"
                    + "【免鉴权 / No Auth Required】\n"
                    + "Health check endpoints are exempt from authentication.",
            tags = {"Worker 代理 / Worker Proxy"},
            authRequired = false,
            responseBody = "{\"status\":\"UP\",\"workerId\":\"w1\"}")
    private void handleHealth(ServerRequest request, ServerResponse response) throws IOException {
        String json = "{\"status\":\"UP\",\"load\":0.0,\"activeTasks\":"
                + (currentTaskId != null ? 1 : 0) + "}";
        sendJson(response, 200, json);
    }

    @GatewayApi(path = "/worker/v1/cancel", methods = {"POST"},
            summary = "Cancel a running task",
            description = "Cancel a task that is currently running on this Worker.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【取消流程 / Cancel Flow】\n"
                    + "1. Gateway requests Worker to cancel running task via POST /worker/v1/cancel\n"
                    + "2. Worker sets cancellation flag and notifies the executing agent\n"
                    + "3. Agent stops execution and returns partial results\n"
                    + "4. Worker returns previous and new task status\n\n"
                    + "Body fields: taskId (required).",
            tags = {"任务管理 / Task Management"},
            requestBody = "{\"taskId\":\"task-001\"}")
    private void handleCancel(ServerRequest request, ServerResponse response) throws IOException {
        if (!authenticateRequest(request, response)) {
            return;
        }
        String body = readBody(request);
        String taskId = extractJsonValue(body, "taskId");
        if (taskId != null) {
            cancelled.set(true);
            if (cancelListener != null) {
                cancelListener.onCancel(taskId);
            }
            logger.info("Cancel request received for task: {}", taskId);
        }
        sendJson(response, 200, "{\"status\":\"ok\"}");
    }

    @GatewayApi(path = "/worker/v1/chat", methods = {"POST"},
            summary = "Receive task from Gateway",
            description = "Gateway 将任务分发给 Worker。支持同步和异步两种模式。Receive and execute a chat task dispatched by the Gateway.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【请求流程 / Request Flow】\n"
                    + "1. Gateway dispatches task to Worker via POST /worker/v1/chat\n"
                    + "2. Worker acknowledges and begins execution\n"
                    + "3. For sync mode: Worker processes and returns complete result in HTTP response\n"
                    + "4. For async (SSE) mode: Worker returns 202 Accepted, then streams progress via /chat/stream\n"
                    + "5. On completion, Worker may callback Gateway via POST /gateway/v1/chat with TaskResult\n\n"
                    + "Body fields: taskId (required), message (required), sessionId, workspacePath, fileManifest, gatewayUrl, syncStrategy.",
            tags = {"Worker 代理 / Worker Proxy"},
            requestBody = "{\"taskId\":\"task-001\",\"message\":\"Implement feature X\"}")
    private void handleChat(ServerRequest request, ServerResponse response) throws IOException {
        if (!authenticateRequest(request, response)) {
            return;
        }
        String body = readBody(request);
        com.fasterxml.jackson.databind.JsonNode root = parseJsonBody(body);
        String taskId = getJsonText(root, "taskId");
        String message = getJsonText(root, "message");
        String workspacePath = getJsonText(root, "workspacePath");
        String fileManifest = getJsonText(root, "fileManifest");
        String gatewayUrl = getJsonText(root, "gatewayUrl");
        String syncStrategy = getJsonText(root, "syncStrategy");
        String subTaskId = getJsonText(root, "subTaskId");
        boolean projectPushed = "full_sync".equals(syncStrategy) || "remote_sandbox".equals(syncStrategy);
        if (workspacePath != null && !workspacePath.isEmpty()) {
            if (!isWorkspaceAllowed(workspacePath)) {
                String json = "{\"status\":\"error\",\"error\":\"Workspace path not allowed: "
                        + escapeJson(workspacePath) + "\",\"taskId\":\"" + safe(taskId) + "\"}";
                sendJson(response, 403, json);
                return;
            }
            if (projectPushed) {
                logger.debug("Project already pushed, set request workspace to {} for task {}", workspacePath, taskId);
            } else {
                ensureSandboxDirectory(workspacePath, fileManifest, gatewayUrl);
                logger.debug("Set request workspace to {} for task {}", workspacePath, taskId);
            }
            FileTools.setRequestWorkspace(workspacePath);
        }
        if (taskId != null) {
            setCurrentTaskId(taskId);
        }
        if (agent == null || message == null || message.isEmpty()) {
            String json = "{\"status\":\"error\",\"error\":\"No agent or message\",\"taskId\":\""
                    + (taskId != null ? taskId : "unknown") + "\"}";
            sendJson(response, 400, json);
            return;
        }
        try {
            logger.info("Worker executing task: {}, message: {}", taskId, truncate(message, 200));
            String resp = executeWithConfirmations(agent, message, 0);

            // Collect file diffs if project was pushed
            String fileDiffsJson = null;
            if (projectPushed && workspacePath != null && taskId != null) {
                fileDiffsJson = collectFileDiffsJson(workspacePath, taskId, subTaskId);
            }

            String json = buildChatResponse(taskId, resp, fileDiffsJson);
            sendJson(response, 200, json);
            logger.info("Worker completed task: {}", taskId);
        } catch (ToolConfirmationException e) {
            // No confirmation callback configured or all retries exhausted — return cancel
            logger.warn("Unresolved confirmation for task {}: {}", taskId, e.getAction());
            if (taskId != null) reportCheckpointToGateway(taskId, FileTools.getEffectiveWorkspaceStatic(), "failed");
            String json = "{\"status\":\"cancelled\",\"taskId\":\"" + safe(taskId)
                    + "\",\"error\":\"Confirmation cancelled by user." + "\"}";
            sendJson(response, 200, json);
        } catch (Exception e) {
            logger.error("Agent execution failed for task: " + taskId, e);
            if (taskId != null) reportCheckpointToGateway(taskId, FileTools.getEffectiveWorkspaceStatic(), "failed");
            String json = "{\"status\":\"error\",\"taskId\":\"" + safe(taskId)
                    + "\",\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            sendJson(response, 500, json);
        } finally {
            FileTools.clearRequestWorkspace();
        }
    }

    @GatewayApi(path = "/worker/v1/chat/stream", methods = {"POST"},
            summary = "Streaming task execution (SSE)",
            description = "Receive a task and stream the response as Server-Sent Events.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【SSE 事件流 / Event Stream】\n"
                    + "Gateway requests POST /worker/v1/chat/stream with task body, Worker returns:\n"
                    + "- Content-Type: text/event-stream\n"
                    + "- Transfer-Encoding: chunked\n\n"
                    + "SSE event types:\n"
                    + "- event: token — data: {\"text\":\"partial response chunk\"}\n"
                    + "- event: thinking — data: {\"message\":\"Agent is thinking...\"}\n"
                    + "- event: progress — data: {\"phase\":\"writing\",\"progress\":0.3}\n"
                    + "- event: tool_call — data: {\"tool\":\"read_file\",\"args\":{...}}\n"
                    + "- event: diff — data: {\"file\":\"Foo.java\",\"diff\":\"@@ -1,3 +1,5 @@...\"}\n"
                    + "- event: error — data: {\"code\":\"timeout\",\"message\":\"...\"}\n"
                    + "- event: complete — data: {\"status\":\"success\",\"summary\":\"done\",\"taskId\":\"...\"}",
            tags = {"Worker 代理 / Worker Proxy"},
            requestBody = "{\"taskId\":\"task-001\",\"message\":\"Implement feature X\"}")
    /**
     * SSE 流式 Chat 响应
     * Worker 端通过 SSE 推送流式响应给 Gateway
     */
    private void handleChatStream(ServerRequest request, ServerResponse response) throws IOException {
        if (!authenticateRequest(request, response)) {
            return;
        }
        String body = readBody(request);
        com.fasterxml.jackson.databind.JsonNode root = parseJsonBody(body);
        String taskId = getJsonText(root, "taskId");
        String message = getJsonText(root, "message");
        String workspacePath = getJsonText(root, "workspacePath");
        String fileManifest = getJsonText(root, "fileManifest");
        String gatewayUrl = getJsonText(root, "gatewayUrl");
        String syncStrategy = getJsonText(root, "syncStrategy");
        String subTaskId = getJsonText(root, "subTaskId");
        boolean projectPushed = "full_sync".equals(syncStrategy) || "remote_sandbox".equals(syncStrategy);
        if (workspacePath != null && !workspacePath.isEmpty()) {
            if (!isWorkspaceAllowed(workspacePath)) {
                String json = "{\"status\":\"error\",\"error\":\"Workspace path not allowed: "
                        + escapeJson(workspacePath) + "\",\"taskId\":\"" + safe(taskId) + "\"}";
                sendJson(response, 403, json);
                return;
            }
            if (projectPushed) {
                logger.debug("Project already pushed, set request workspace to {} for streaming task {}", workspacePath, taskId);
            } else {
                ensureSandboxDirectory(workspacePath, fileManifest, gatewayUrl);
                logger.debug("Set request workspace to {} for streaming task {}", workspacePath, taskId);
            }
            FileTools.setRequestWorkspace(workspacePath);
        }
        if (taskId != null) {
            setCurrentTaskId(taskId);
        }

        // SSE response headers
        response.setHeader("Content-Type", "text/event-stream; charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setStatus(200);
        OutputStream os = response.getOutputStream();
        try {
            // Start event
            SseEvent startSse = SseEvent.start(taskId != null ? taskId : "unknown");
            startSse.writeTo(os);

            if (agent == null || message == null || message.isEmpty()) {
                SseEvent errorSse = SseEvent.error("No agent or message");
                errorSse.writeTo(os);
                os.flush();
            } else {
                logger.info("Worker streaming task: {}, message: {}", taskId, truncate(message, 200));

                // Set up streaming consumer for real-time token output via SSE
                agent.setStreamingConsumer(new AiHttpClient.StreamConsumer() {
                    @Override
                    public void onToken(String token) {
                        try {
                            SseEvent tokenSse = new SseEvent("token", null);
                            tokenSse.content = token;
                            tokenSse.writeTo(os);
                            os.flush();
                        } catch (IOException e) {
                            logger.error("Failed to write SSE token event", e);
                        }
                    }
                    @Override
                    public void onComplete(String fullResponse) {
                        // Individual LLM call completed — no action needed at SSE level
                    }
                    @Override
                    public void onData(String data) {
                        // Raw SSE data — not forwarded to client
                    }
                    @Override
                    public void onError(Throwable e) {
                        logger.error("Streaming error during LLM call", e);
                    }
                });

                // Run agent — tokens will be streamed in real-time via the consumer above
                String agentResponse = agent.run(message);

                // Collect file diffs if project was pushed (after run() completes)
                String fileDiffsJson = null;
                if (projectPushed && workspacePath != null && taskId != null) {
                    fileDiffsJson = collectFileDiffsJson(workspacePath, taskId, subTaskId);
                }

                // Complete event with optional file diffs
                SseEvent completeSse = SseEvent.complete(taskId != null ? taskId : "unknown");
                if (fileDiffsJson != null) {
                    completeSse.fileDiffs = mapper.readValue(fileDiffsJson,
                            mapper.getTypeFactory().constructCollectionType(List.class, FileDiffResult.class));
                }
                completeSse.writeTo(os);
            }
        } catch (Exception e) {
            logger.error("Agent streaming execution failed for task: " + taskId, e);
            reportCheckpointToGateway(taskId, workspacePath, "failed");
            try {
                SseEvent errorSse = SseEvent.error(e.getMessage());
                errorSse.writeTo(os);
            } catch (Exception ignored) {}
        } finally {
            os.close();
            FileTools.clearRequestWorkspace();
        }
        logger.info("Worker SSE stream completed for task: {}", taskId);
    }

    // ===== Remote Sandbox: Auto-fetch files from Gateway =====

    /**
     * 确保沙箱目录存在。
     * 如果本地不存在指定路径，尝试从 Gateway 按需拉取文件。
     *
     * @param workspacePath 沙箱目录路径
     * @param fileManifest  JSON 文件清单数组字符串 ["path1","path2",...]
     * @param gatewayUrl    Gateway 地址
     */
    private void ensureSandboxDirectory(String workspacePath, String fileManifest, String gatewayUrl) {
        if (workspacePath == null || workspacePath.isEmpty()) return;

        Path sandboxDir = Paths.get(workspacePath);
        if (Files.exists(sandboxDir)) {
            logger.debug("Sandbox directory already exists: {}", workspacePath);
            return; // 本地已存在，使用共享文件系统
        }

        // 需要从 Gateway 拉取文件
        if (fileManifest == null || fileManifest.trim().isEmpty()
                || "null".equals(fileManifest)) {
            logger.warn("Sandbox {} does not exist and no file manifest provided", workspacePath);
            // 创建空目录，至少让 worker 能切换进去
            try {
                Files.createDirectories(sandboxDir);
            } catch (IOException e) {
                logger.warn("Failed to create sandbox directory {}: {}", workspacePath, e.getMessage());
            }
            return;
        }

        // 确定 Gateway URL
        String gwUrl = resolveGatewayUrl(gatewayUrl);
        if (gwUrl == null) {
            logger.warn("Cannot fetch files: no gateway URL available");
            try {
                Files.createDirectories(sandboxDir);
            } catch (IOException e) {
                logger.warn("Failed to create sandbox directory: {}", e.getMessage());
            }
            return;
        }

        logger.info("Fetching files from Gateway {} to sandbox {}", gwUrl, workspacePath);
        fetchFilesFromGateway(gwUrl, fileManifest, sandboxDir);
    }

    /**
     * 从 Gateway 批量拉取文件到本地沙箱目录。
     * 使用 POST /gateway/v1/file/batch 端点，支持 gzip 压缩。
     */
    private void fetchFilesFromGateway(String gwUrl, String fileManifest, Path sandboxDir) {
        try {
            URL url = new URL(gwUrl + "/gateway/v1/file/batch");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            // Build request body: {"files":[...],"compress":true}
            String requestBody = "{\"files\":" + fileManifest + ",\"compress\":true}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                logger.warn("Gateway file batch returned HTTP {}", responseCode);
                return;
            }

            // Read response (supports gzip)
            byte[] responseData;
            String contentEncoding = conn.getHeaderField("Content-Encoding");
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                responseData = baos.toByteArray();
            }

            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(responseData));
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = gzis.read(buf)) != -1) {
                        baos.write(buf, 0, len);
                    }
                    responseData = baos.toByteArray();
                }
            }

            // Parse response and write files
            String responseJson = new String(responseData, StandardCharsets.UTF_8);
            writeFilesFromBatchResponse(responseJson, sandboxDir);
            conn.disconnect();

        } catch (Exception e) {
            logger.warn("Failed to fetch files from Gateway: {}", e.getMessage());
        }
    }

    /**
     * 解析 Gateway /file/batch 的响应，将文件写入沙箱目录。
     *
     * 响应格式: {"files":{"path1":"base64content","path2":"base64content"}}
     */
    private void writeFilesFromBatchResponse(String responseJson, Path sandboxDir) {
        try {
            // Find the "files" object in the JSON
            int filesStart = responseJson.indexOf("\"files\":{");
            if (filesStart < 0) {
                logger.warn("No files in Gateway batch response");
                return;
            }
            filesStart += 9; // skip past "files":{

            // Find matching braces
            int depth = 1;
            int i = filesStart;
            while (i < responseJson.length() && depth > 0) {
                char c = responseJson.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                i++;
            }
            String filesContent = responseJson.substring(filesStart, i - 1);

            // Parse key-value pairs: "path":"base64",...
            boolean inKey = false;
            boolean inValue = false;
            StringBuilder key = new StringBuilder();
            StringBuilder value = new StringBuilder();
            boolean readingKey = true;

            for (int pos = 0; pos < filesContent.length(); pos++) {
                char c = filesContent.charAt(pos);

                if (readingKey) {
                    if (c == '"') {
                        if (inKey) {
                            // End of key
                            inKey = false;
                            readingKey = false;
                        } else {
                            inKey = true;
                        }
                    } else if (inKey) {
                        key.append(c);
                    } else if (c == ':') {
                        readingKey = false;
                    }
                } else {
                    if (c == '"') {
                        if (inValue) {
                            // End of value — write file
                            writeBatchFile(sandboxDir, key.toString(), value.toString());
                            key.setLength(0);
                            value.setLength(0);
                            readingKey = true;
                            inValue = false;
                        } else {
                            inValue = true;
                        }
                    } else if (inValue) {
                        value.append(c);
                    } else if (c == ',') {
                        // Next entry
                        readingKey = true;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse Gateway batch response: {}", e.getMessage());
        }
    }

    /**
     * 将 base64 编码的文件内容写入沙箱目录。
     */
    private void writeBatchFile(Path sandboxDir, String filePath, String base64Content) {
        try {
            byte[] content = Base64.getDecoder().decode(base64Content);
            Path targetFile = sandboxDir.resolve(filePath.replace('/', java.io.File.separatorChar));
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, content);
            logger.debug("Wrote file from Gateway: {} ({} bytes)", filePath, content.length);
        } catch (Exception e) {
            logger.warn("Failed to write file {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * 确定 Gateway 的 URL。
     * 优先级：请求中指定的 gatewayUrl > 系统属性 diatom.gateway.url
     */
    private static String resolveGatewayUrl(String requestGatewayUrl) {
        if (requestGatewayUrl != null && !requestGatewayUrl.isEmpty()
                && !"null".equals(requestGatewayUrl)) {
            return requestGatewayUrl;
        }
        return System.getProperty("diatom.gateway.url");
    }

    @GatewayApi(path = "/worker/v1/migrate", methods = {"POST"},
            summary = "Migrate task to this Worker",
            description = "Receive a task migration from another Worker. Used in HA mode for task redistribution.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.",
            tags = {"Worker 代理 / Worker Proxy"})
    private void handleMigrate(ServerRequest request, ServerResponse response) throws IOException {
        if (!authenticateRequest(request, response)) {
            return;
        }
        String body = readBody(request);
        String taskId = extractJsonValue(body, "taskId");
        String checkpointStep = extractJsonValue(body, "checkpointStep");
        String workspacePath = extractJsonValue(body, "workspacePath");
        logger.info("Migration request received: task={}, checkpointStep={}, workspacePath={}",
                taskId, checkpointStep, workspacePath);
        // Store workspace path for subsequent handleChat call
        if (workspacePath != null && !workspacePath.isEmpty()) {
            FileTools.setRequestWorkspace(workspacePath);
        }
        String json = "{\"status\":\"accepted\",\"taskId\":\"" + (taskId != null ? taskId : "unknown") + "\"}";
        sendJson(response, 200, json);
    }

    @GatewayApi(path = "/worker/v1/shutdown-notice", methods = {"POST"},
            summary = "Receive shutdown notice",
            description = "Receive notice from Gateway that it is shutting down. Worker should prepare for disconnection.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.",
            tags = {"Worker 代理 / Worker Proxy"})
    /**
     * Gateway 关闭通知
     * Worker 收到后切换到备用 Gateway 地址
     */
    private void handleShutdownNotice(ServerRequest request, ServerResponse response) throws IOException {
        if (!authenticateRequest(request, response)) {
            return;
        }
        String body = readBody(request);
        String gatewayId = extractJsonValue(body, "gatewayId");
        logger.warn("Gateway shutdown notice received from {}: switching to standby", gatewayId);

        // 切换 gateway.url 到备用地址
        String currentUrl = System.getProperty("gateway.url", "http://127.0.0.1:8080");
        String[] urls = currentUrl.split(",");
        if (urls.length > 1) {
            // 切换到下一个地址
            String standbyUrl = urls[1].trim();
            System.setProperty("gateway.url", standbyUrl);
            logger.info("Switched to standby gateway: {}", standbyUrl);
        } else {
            logger.warn("No standby gateway configured, will retry primary on next heartbeat");
        }

        String json = "{\"status\":\"acknowledged\",\"gatewayId\":\"" + gatewayId + "\"}";
        sendJson(response, 200, json);
    }

    @GatewayApi(path = "/worker/v1/command", methods = {"POST"},
            summary = "Execute command on Worker",
            description = "Execute a CLI command on this Worker. Used by Gateway for config/rules management.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "Body fields: command (required).",
            tags = {"Worker 代理 / Worker Proxy"},
            requestBody = "{\"command\":\"rules list --json\"}")
    /**
     * POST /worker/v1/command
     * Execute a CLI command on the worker (e.g. "config list") and return the output.
     * Body: {"command":"config list"}
     * Response: {"status":"ok","output":"..."} or {"status":"error","error":"..."}
     */
    private void handleCommand(ServerRequest request, ServerResponse response) throws IOException {
        if (!authenticateRequest(request, response)) {
            return;
        }
        if (commandRegistry == null) {
            sendError(response, 503, "Command registry not available on this worker");
            return;
        }
        String body = readBody(request);
        String command = extractJsonValue(body, "command");
        if (command == null || command.isEmpty()) {
            sendError(response, 400, "Missing 'command' field in request body");
            return;
        }

        CommandOutput output = new CommandOutput() {
            private final StringBuilder sb = new StringBuilder();
            @Override public void print(String text) { sb.append(text); }
            @Override public void printSuccess(String text) { sb.append(text); }
            @Override public void printError(String text) { sb.append(text); }
            @Override public void printInfo(String text) { sb.append(text); }
            @Override public void printDim(String text) { sb.append(text); }
            @Override public void printWarning(String text) { sb.append(text); }
            @Override public void printBold(String text) { sb.append(text); }
            @Override public void printColored(String text, String ansiColor) { sb.append(text); }
            @Override public StringBuilder getBuffer() { return sb; }
        };

        try {
            String result = commandRegistry.execute(command, output);
            String outputText = output.getBuffer().toString();
            if (result != null && outputText.isEmpty()) {
                outputText = result;
            }
            if (result == null && outputText.isEmpty()) {
                outputText = "No provider registered for command: " + command;
                String json = "{\"status\":\"error\",\"error\":\"" + escapeJson(outputText) + "\"}";
                sendJson(response, 404, json);
                logger.warn("Worker command not registered: {}", command);
                return;
            }
            String json = "{\"status\":\"ok\",\"output\":\"" + escapeJson(outputText) + "\"}";
            sendJson(response, 200, json);
            logger.info("Worker command executed: {}", command);
        } catch (Exception e) {
            logger.error("Worker command failed: " + command, e);
            String json = "{\"status\":\"error\",\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            sendJson(response, 500, json);
        }
    }

    @GatewayApi(path = "/worker/v1/rules", methods = {"GET", "POST"},
            summary = "Worker rules management",
            description = "GET /rules?type=capability: read capability rules. POST /rules: save rules.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "Used by Monitor dashboard via proxyWorkerCommand to manage Worker command_rules DB table.",
            tags = {"Worker 代理 / Worker Proxy"})
    /**
     * GET /worker/v1/rules?type=capability
     * POST /worker/v1/rules
     * Body: {"type":"capability","content":"..."}
     * Read or update the worker's rules file (capability.md).
     */
    private void handleRules(ServerRequest request, ServerResponse response) throws IOException {
        String method = request.getMethod();
        if ("GET".equals(method)) {
            String type = request.getQueryParam("type");
            if (type == null) type = "capability";

            String filePath = resolveRulesPath(type);
            String content = "";
            if (filePath != null) {
                Path path = Paths.get(filePath);
                if (Files.exists(path)) {
                    content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                }
            }
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("status", "ok");
            result.put("type", type);
            result.put("path", filePath != null ? filePath : "");
            result.put("content", content);
            sendJson(response, 200, result);
        } else if ("POST".equals(method)) {
            if (!authenticateRequest(request, response)) {
                return;
            }
            String body = readBody(request);
            String type = extractJsonValue(body, "type");
            String content = extractJsonValue(body, "content");
            if (type == null) type = "capability";
            if (content == null) content = "";

            String filePath = resolveRulesPath(type);
            if (filePath == null) {
                sendJson(response, 400, ApiError.of("Unknown rules type: " + type));
                return;
            }
            try {
                Path path = Paths.get(filePath);
                Files.createDirectories(path.getParent());
                Files.write(path, content.getBytes(StandardCharsets.UTF_8));
                logger.info("Worker rules updated: {}", filePath);
                Map<String, String> successResp = new HashMap<String, String>();
                successResp.put("status", "ok");
                successResp.put("path", filePath);
                sendJson(response, 200, successResp);
            } catch (Exception e) {
                sendJson(response, 500, ApiError.of(e.getMessage()));
            }
        } else {
            sendError(response, 405, "Method not allowed");
        }
    }

    private String resolveRulesPath(String type) {
        if ("capability".equals(type)) {
            if (rulesPath != null) return rulesPath;
            // Fallback to default capability path in JAR directory (worker's "home")
            String jarDir = System.getProperty("diatom.jar.dir");
            String baseDir = (jarDir != null && !jarDir.isEmpty()) ? jarDir : System.getProperty("user.dir");
            return Paths.get(baseDir, ".diatom", "capability.md").toString();
        }
        return null;
    }

    @GatewayApi(path = "/worker/v1/resume", methods = {"POST"},
            summary = "Resume a paused task",
            description = "Resume a previously checkpointed task on this Worker from its last saved checkpoint.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【恢复流程 / Resume Flow】\n"
                    + "1. Gateway sends POST /worker/v1/resume with saved checkpoint data\n"
                    + "2. Worker loads conversation history and agent state\n"
                    + "3. Worker continues execution from checkpointed step\n"
                    + "4. Returns result on completion\n\n"
                    + "Body fields: taskId (required), checkpointStep, originalRequest, conversationHistory, agentState.",
            tags = {"任务管理 / Task Management"})
    /**
     * Resume a task from a saved checkpoint.
     * Body: {"taskId":"...","checkpointStep":15,"originalRequest":"...",
     *        "conversationHistory":["..."],"agentState":"..."}
     */
    private void handleResume(ServerRequest request, ServerResponse response) throws IOException {
        if (!authenticateRequest(request, response)) {
            return;
        }
        String body = readBody(request);
        String taskId = extractJsonValue(body, "taskId");
        String checkpointStepStr = extractJsonValue(body, "checkpointStep");
        String originalRequest = extractJsonValue(body, "originalRequest");
        if (taskId == null || taskId.isEmpty()) {
            sendError(response, 400, "{\"status\":\"error\",\"error\":\"Missing taskId\"}");
            return;
        }
        setCurrentTaskId(taskId);
        try {
            logger.info("Worker resume requested: task={}, checkpointStep={}", taskId, checkpointStepStr);
            boolean resumed = false;
            // Priority 1: Use local SQLite checkpoint
            if (agent != null) {
                resumed = agent.resumeFromCheckpoint(taskId);
            }
            if (!resumed) {
                // Fallback: use Gateway-provided conversation history
                String convHistoryRaw = extractFullJsonValue(body, "conversationHistory");
                if (convHistoryRaw != null && !convHistoryRaw.isEmpty() && agent != null) {
                    try {
                        List<ChatMessage> restoredHistory = mapper.readValue(convHistoryRaw,
                                mapper.getTypeFactory().constructCollectionType(List.class, ChatMessage.class));
                        agent.setHistory(restoredHistory);
                        resumed = true;
                        logger.info("Resumed task {} from Gateway-provided history ({} messages)",
                                taskId, restoredHistory.size());
                    } catch (Exception e) {
                        logger.warn("Failed to deserialize conversation history for resume: {}", e.getMessage());
                    }
                }
            }
            if (!resumed) {
                String json = "{\"status\":\"error\",\"taskId\":\"" + safe(taskId)
                        + "\",\"error\":\"No checkpoint found for task\"}";
                sendJson(response, 404, json);
                return;
            }
            // Run the agent in resume mode (empty prompt continues from restored history)
            String resp = executeWithConfirmations(agent, "", 0);
            String json = buildChatResponse(taskId, resp);
            sendJson(response, 200, json);
            logger.info("Worker resume completed: {}", taskId);
        } catch (ToolConfirmationException e) {
            logger.warn("Unresolved confirmation during resume for task {}: {}", taskId, e.getAction());
            String json = "{\"status\":\"cancelled\",\"taskId\":\"" + safe(taskId)
                    + "\",\"error\":\"Confirmation cancelled by user.\"}";
            sendJson(response, 200, json);
        } catch (Exception e) {
            logger.error("Worker resume failed for task: " + taskId, e);
            String json = "{\"status\":\"error\",\"taskId\":\"" + safe(taskId)
                    + "\",\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            sendJson(response, 500, json);
        }
    }

    @GatewayApi(path = "/worker/v1/deploy", methods = {"POST"},
            summary = "Execute deployment on Worker",
            description = "Execute a deployment pipeline on this Worker. Runs build, SCP, SSH commands.\n\n"
                    + "【鉴权 / Authentication】\n"
                    + "Requires X-Diatom-Auth header.\n\n"
                    + "【部署流程 / Deploy Flow】\n"
                    + "1. Gateway forwards deployment request to Worker with deploy capability\n"
                    + "2. Worker runs build commands based on project profile\n"
                    + "3. Worker transfers artifacts (SCP) and executes remote commands (SSH)\n"
                    + "4. Returns deployment output and status\n\n"
                    + "Worker 需实现此端点以支持代码部署。Workers must implement this for deployment support.\n\n"
                    + "Body fields: profile (production/staging/testing, optional), workspaceHint (required), projectName (optional).",
            tags = {"部署 / Deploy"})
    /**
     * Handle deploy request from Gateway.
     * POST /worker/v1/deploy
     * Body: { "profile": "test", "workspaceHint": "/mnt/nas/project-x" }
     *
     * Creates a PipelineService and executes the deploy pipeline,
     * returning the output and status as JSON.
     */
    private void handleDeploy(ServerRequest request, ServerResponse response) throws IOException {
        if (!authenticateRequest(request, response)) {
            return;
        }
        String body = readBody(request);
        String profile = extractJsonValue(body, "profile");
        String workspaceHint = extractJsonValue(body, "workspaceHint");
        String projectName = extractJsonValue(body, "projectName");

        if (workspaceHint == null || workspaceHint.isEmpty()) {
            String json = "{\"status\":\"error\",\"error\":\"Missing workspaceHint\"}";
            sendJson(response, 400, json);
            return;
        }

        // Derive project name from workspaceHint if not provided
        if (projectName == null || projectName.isEmpty()) {
            Path wsPath = Paths.get(workspaceHint);
            projectName = wsPath.getFileName().toString();
        }

        String originalUserDir = null;
        try {
            // Switch to workspace directory
            Path workspaceDir = Paths.get(workspaceHint);
            if (!Files.exists(workspaceDir)) {
                String json = "{\"status\":\"error\",\"error\":\"Workspace path does not exist: "
                        + escapeJson(workspaceHint) + "\"}";
                sendJson(response, 400, json);
                return;
            }
            originalUserDir = System.setProperty("user.dir", workspaceHint);
            logger.info("Deploy: switched to workspace {}, profile={}, project={}",
                    workspaceHint, profile, projectName);

            // Create PipelineService and execute
            RunnerRegistry runnerRegistry = new RunnerRegistry();
            PipelineService pipelineService = new PipelineService(runnerRegistry, workspaceHint);

            // Collect pipeline output
            StringBuilder output = new StringBuilder();
            final boolean[] pipelineSuccess = {false};

            PipelineCallback callback = new PipelineCallback() {
                @Override
                public void onOutput(String text) {
                    output.append(text);
                }
                @Override
                public void onStepComplete(String stepName, boolean success) {
                    output.append("[STEP ").append(stepName).append(": ")
                          .append(success ? "OK" : "FAILED").append("]\n");
                }
                @Override
                public void onPipelineComplete(boolean success) {
                    pipelineSuccess[0] = success;
                }
                @Override
                public void onError(String message) {
                    output.append("[ERROR] ").append(message).append("\n");
                }
            };

            pipelineService.execute(projectName, callback, profile);

            // Build response
            String json = "{\"status\":\"" + (pipelineSuccess[0] ? "ok" : "error")
                    + "\",\"project\":\"" + escapeJson(projectName)
                    + "\",\"profile\":\"" + (profile != null ? escapeJson(profile) : "default")
                    + "\",\"workspace\":\"" + escapeJson(workspaceHint)
                    + "\",\"output\":\"" + escapeJson(output.toString()) + "\"}";
            sendJson(response, 200, json);
            logger.info("Deploy completed: project={}, profile={}, success={}",
                    projectName, profile, pipelineSuccess[0]);
        } catch (Exception e) {
            logger.error("Deploy failed for project: " + projectName, e);
            String json = "{\"status\":\"error\",\"project\":\"" + escapeJson(projectName)
                    + "\",\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            sendJson(response, 500, json);
        } finally {
            if (originalUserDir != null) {
                System.setProperty("user.dir", originalUserDir);
            }
        }
    }

    /**
     * Execute agent.run() with recursive confirmation handling.
     * When ToolConfirmationException is caught, delegates to the callback
     * to get user decision, then resumes or skips accordingly.
     * After successful run, reports checkpoint to Gateway.
     */
    private String executeWithConfirmations(ReActAgent agent, String message, int depth) {
        if (depth >= MAX_CONFIRM_DEPTH) {
            logger.warn("Max confirmation depth ({}) reached, cancelling", MAX_CONFIRM_DEPTH);
            return "Task cancelled: too many confirmation requests.";
        }
        try {
            String result = agent.run(message);
            // Report checkpoint after successful run
            if (currentTaskId != null) {
                String wsPath = FileTools.getEffectiveWorkspaceStatic();
                reportCheckpointToGateway(currentTaskId, wsPath);
            }
            return result;
        } catch (ToolConfirmationException e) {
            if (confirmationCallback == null) {
                throw e; // Let the outer handler return cancellation
            }
            String decision = confirmationCallback.requestConfirmation(e);
            if (decision == null || decision.isEmpty()) {
                decision = "c";
            }

            String decisionLower = decision.toLowerCase().trim();
            switch (decisionLower) {
                case "y":
                    // Resume with existing messages
                    if (e.getMessages() != null) {
                        agent.setHistory(e.getMessages());
                    }
                    return executeWithConfirmations(agent, "", depth + 1);
                case "n":
                    // Skip this tool: inject a tool result message and resume
                    if (e.getMessages() != null && e.getToolCallId() != null) {
                        List<ChatMessage> skipMessages = new ArrayList<ChatMessage>(e.getMessages());
                        skipMessages.add(new ChatMessage("tool", "[User skipped this action]", e.getToolCallId()));
                        agent.setHistory(skipMessages);
                    } else if (e.getMessages() != null) {
                        agent.setHistory(e.getMessages());
                    }
                    return executeWithConfirmations(agent, "", depth + 1);
                case "a":
                    // Auto-approve all writes for this session
                    agent.setAutoApproveWrite(true);
                    if (e.getMessages() != null) {
                        agent.setHistory(e.getMessages());
                    }
                    return executeWithConfirmations(agent, "", depth + 1);
                case "aw":
                    // Auto-approve just this worker (handled at Gateway level)
                    if (e.getMessages() != null) {
                        agent.setHistory(e.getMessages());
                    }
                    return executeWithConfirmations(agent, "", depth + 1);
                case "s":
                    // Approve shell command
                    agent.addApprovedCommand(e.getArguments());
                    if (e.getMessages() != null) {
                        agent.setHistory(e.getMessages());
                    }
                    return executeWithConfirmations(agent, "", depth + 1);
                case "t":
                    // Temporarily approve shell command
                    agent.addApprovedCommand(e.getArguments());
                    if (e.getMessages() != null) {
                        agent.setHistory(e.getMessages());
                    }
                    return executeWithConfirmations(agent, "", depth + 1);
                case "c":
                default:
                    // Cancel — rethrow to outer handler
                    throw e;
            }
        }
    }

    /**
     * Build a chat response JSON with workerMeta containing audit log and file changes.
     */
    private String buildChatResponse(String taskId, String responseText) {
        return buildChatResponse(taskId, responseText, null);
    }

    /**
     * Build a chat response JSON with optional fileDiffs from pushed project sync.
     */
    private String buildChatResponse(String taskId, String responseText, String fileDiffsJson) {
        WorkerChatResponse resp = new WorkerChatResponse("ok", taskId, responseText);

        // Build workerMeta if SessionTracker is available
        if (agent != null) {
            SessionTracker tracker = agent.getSessionTracker();
            if (tracker != null) {
                WorkerMeta meta = new WorkerMeta();
                meta.instanceId = instanceId;
                meta.auditEnabled = tracker.isAuditLogEnabled();

                // Audit entries
                List<AuditEntry> auditEntries = new ArrayList<AuditEntry>();
                for (SessionTracker.AuditEntry entry : tracker.getAuditEntries()) {
                    AuditEntry ae = new AuditEntry();
                    ae.timestamp = entry.getTimestamp();
                    ae.operation = entry.getOperation();
                    ae.path = entry.getPath();
                    auditEntries.add(ae);
                }
                meta.auditEntries = auditEntries;

                // File changes summary
                FileChanges fc = new FileChanges();
                fc.created = new ArrayList<String>(tracker.getCreatedFiles());
                fc.modified = new ArrayList<String>(tracker.getModifiedFiles());
                fc.deleted = new ArrayList<String>(tracker.getDeletedFiles());
                meta.fileChanges = fc;

                resp.workerMeta = meta;
            }
        }

        // Append fileDiffs if present (pushed project sync)
        if (fileDiffsJson != null && !fileDiffsJson.isEmpty()) {
            try {
                resp.fileDiffs = mapper.readValue(fileDiffsJson,
                        mapper.getTypeFactory().constructCollectionType(List.class, FileDiffResult.class));
            } catch (Exception e) {
                logger.warn("Failed to deserialize fileDiffs for buildChatResponse: {}", e.getMessage());
            }
        }

        return JsonUtils.toJson(resp);
    }

    /**
     * Collect file diffs for a pushed project by comparing current state against the pre-execution snapshot.
     * Supports both simple taskId and "taskId:subTaskId" dual-key lookup.
     * Returns the JSON array string of diffs, or null if no snapshot exists.
     */
    private String collectFileDiffsJson(String workspacePath, String taskId) {
        return collectFileDiffsJson(workspacePath, taskId, null);
    }

    /**
     * Collect file diffs with optional subTaskId for sandbox diff collection.
     */
    private String collectFileDiffsJson(String workspacePath, String taskId, String subTaskId) {
        // Try dual-key first, then fall back to simple taskId
        Map<String, String> preSnapshot = null;
        if (subTaskId != null && !subTaskId.isEmpty()) {
            preSnapshot = projectSnapshots.get(taskId + ":" + subTaskId);
        }
        if (preSnapshot == null) {
            preSnapshot = projectSnapshots.get(taskId);
        }
        if (preSnapshot == null) {
            logger.debug("No pre-snapshot found for task {}, skipping diff collection", taskId);
            return null;
        }
        try {
            List<FileDiffResult> diffs = projectSyncService.collectDiffs(Paths.get(workspacePath), preSnapshot);
            if (diffs.isEmpty()) return null;
            // Clean up snapshot after collection
            projectSnapshots.remove(taskId);
            return JsonUtils.toJson(diffs);
        } catch (Exception e) {
            logger.warn("Failed to collect file diffs for task {}: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * Report task checkpoint to Gateway after successful execution.
     * Sends agent state, conversation history, and summary data to the Gateway's
     * /gateway/v1/checkpoint endpoint for persistence and potential task migration.
     * Runs asynchronously; failures are logged but do not affect the response.
     */
    private void reportCheckpointToGateway(String taskId, String workspacePath) {
        reportCheckpointToGateway(taskId, workspacePath, "completed");
    }

    private void reportCheckpointToGateway(String taskId, String workspacePath, String status) {
        String gwUrl = gatewayUrl;
        if (gwUrl == null || gwUrl.isEmpty() || taskId == null) return;
        // Run in separate thread, don't block response
        new Thread(() -> {
            try {
                ObjectNode root = mapper.createObjectNode();
                root.put("taskId", taskId);
                root.put("stepCount", agent.getCurrentStepCount());
                root.put("messageCount", agent.getConversationHistory().size());
                if (agent.getCheckpointManager() != null) {
                    String llmSummary = agent.getCheckpointManager().getCurrentLlmSummary();
                    if (llmSummary != null) root.put("llmSummary", llmSummary);
                }
                if (agent.getSessionTracker() != null) {
                    String fileSummary = agent.getSessionTracker().buildFileChangeSummary();
                    if (fileSummary != null) root.put("fileChangeSummary", fileSummary);
                }
                // Serialize agent state
                Map<String, Object> agentState = agent.buildAgentState();
                try {
                    String agentStateJson = mapper.writeValueAsString(agentState);
                    root.put("agentState", agentStateJson);
                } catch (Exception e) {
                    logger.warn("Failed to serialize agentState: {}", e.getMessage());
                }
                // Serialize conversation history (last 50 messages for efficiency)
                List<ChatMessage> history = agent.getHistory();
                if (history != null && !history.isEmpty()) {
                    int fromIdx = Math.max(0, history.size() - 50);
                    List<ChatMessage> recentHistory = history.subList(fromIdx, history.size());
                    try {
                        String historyJson = mapper.writeValueAsString(recentHistory);
                        root.put("conversationHistory", historyJson);
                    } catch (Exception e) {
                        logger.warn("Failed to serialize history: {}", e.getMessage());
                    }
                }
                // Serialize tool results (from history)
                if (history != null) {
                    List<String> toolMsgs = new ArrayList<String>();
                    for (ChatMessage msg : history) {
                        if ("tool".equals(msg.getRole())) {
                            try {
                                toolMsgs.add(mapper.writeValueAsString(msg));
                            } catch (Exception ignored) {}
                        }
                    }
                    if (!toolMsgs.isEmpty()) {
                        try {
                            root.put("toolResults", mapper.writeValueAsString(toolMsgs));
                        } catch (Exception ignored) {}
                    }
                }
                // Include workspace path and status in checkpoint
                if (workspacePath != null) root.put("workspacePath", workspacePath);
                root.put("status", status != null ? status : "running");
                // POST to Gateway
                String targetUrl = gwUrl + "/gateway/v1/checkpoint";
                HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                byte[] requestBytes = root.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(requestBytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBytes);
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                logger.debug("Checkpoint reported for task {} (HTTP {})", taskId, code);
            } catch (Exception e) {
                logger.debug("Failed to report checkpoint to Gateway: {}", e.getMessage());
            }
        }, "checkpoint-reporter").start();
    }

    private void sendJson(ServerResponse response, int code, String json) throws IOException {
        response.setStatus(code);
        response.send(json);
    }

    private void sendJson(ServerResponse response, int code, Object obj) throws IOException {
        sendJson(response, code, JsonUtils.toJson(obj));
    }

    private void sendError(ServerResponse response, int code, String message) throws IOException {
        Map<String, String> error = new HashMap<String, String>();
        error.put("error", message);
        sendJson(response, code, JsonUtils.toJson(error));
    }

    private String readBody(ServerRequest request) throws IOException {
        byte[] rawBytes = request.getBodyBytes();
        String sourceId = request.getHeader(SecurityHeadersInjector.HEADER_INSTANCE_ID);
        String encryptionAlgo = request.getHeader(SecurityHeadersInjector.HEADER_ENCRYPTION);
        // Build headers map for SecurityHeadersInjector.decryptBody
        Map<String, String> headers = new HashMap<String, String>();
        if (encryptionAlgo != null) {
            headers.put(SecurityHeadersInjector.HEADER_ENCRYPTION, encryptionAlgo);
        }
        byte[] decrypted = SecurityHeadersInjector.decryptBody(rawBytes, sourceId, headers);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * Authenticate incoming request using SecurityHeadersInjector.
     * Returns true if authenticated, false if rejected (response sent).
     */
    private boolean authenticateRequest(ServerRequest request, ServerResponse response) throws IOException {
        String authHeader = request.getHeader(SecurityHeadersInjector.HEADER_AUTH);
        String sourceInstanceId = request.getHeader(SecurityHeadersInjector.HEADER_INSTANCE_ID);
        Map<String, String> headers = new HashMap<String, String>();
        if (authHeader != null) headers.put(SecurityHeadersInjector.HEADER_AUTH, authHeader);
        if (sourceInstanceId != null) headers.put(SecurityHeadersInjector.HEADER_INSTANCE_ID, sourceInstanceId);
        SecurityHeadersInjector injector = new SecurityHeadersInjector(
                SecurityProviderLoader.getAuthProvider(),
                SecurityProviderLoader.getEncryptionProvider());
        if (!injector.authenticateRequest(headers)) {
            sendError(response, 401, "Unauthorized");
            return false;
        }
        return true;
    }

    /** 用 Jackson 解析 JSON body，返回 JsonNode（null 表示解析失败） */
    private static com.fasterxml.jackson.databind.JsonNode parseJsonBody(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            logger.debug("Failed to parse JSON body: {}", e.getMessage());
            return null;
        }
    }

    /** 从 JsonNode 中安全获取文本字段值 */
    private static String getJsonText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }

    /** 使用 Jackson 从 JSON 字符串中提取指定 key 的文本值（兼容 string/number/boolean） */
    private static String extractJsonValue(String json, String key) {
        if (json == null) return null;
        try {
            return getJsonText(mapper.readTree(json), key);
        } catch (Exception e) {
            return null;
        }
    }

    /** 使用 Jackson 从 JSON 字符串中提取指定 key 的完整 JSON 值（数组/对象/原始值） */
    private static String extractFullJsonValue(String json, String key) {
        if (json == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode val = root.get(key);
            if (val != null && !val.isNull()) {
                return val.toString();
            }
        } catch (Exception e) {
            // fallback to null
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public void start() {
        serverSpi.start();
        logger.info("Worker HTTP server started on 127.0.0.1:{}", port);
    }

    public void stop(int delaySeconds) {
        if (serverSpi != null) {
            serverSpi.stop(delaySeconds);
        }
        logger.info("Worker HTTP server stopped");
    }

    /**
     * GatewayConfirmationCallback — async callback implementation of ConfirmationCallback.
     * Sends the confirmation request to the Gateway's confirm-request endpoint with a
     * callbackUrl, then waits for the decision to arrive via POST /worker/v1/confirm-callback.
     * This enables Gateway-to-Gateway forwarding without blocking the chain.
     */
    public static class GatewayConfirmationCallback implements ConfirmationCallback {
        private final String gatewayUrl;
        private final String workerId;
        private final String callbackBaseUrl;
        private final ConcurrentHashMap<String, CompletableFuture<String>> pendingMap;

        public GatewayConfirmationCallback(String gatewayUrl, String workerId,
                                           String callbackBaseUrl,
                                           ConcurrentHashMap<String, CompletableFuture<String>> pendingMap) {
            this.gatewayUrl = gatewayUrl;
            this.workerId = workerId;
            this.callbackBaseUrl = callbackBaseUrl;
            this.pendingMap = pendingMap;
        }

        @Override
        public String requestConfirmation(ToolConfirmationException ex) {
            // Generate a unique requestId for callback correlation
            String requestId = UUID.randomUUID().toString();

            // Create a CompletableFuture and store it before sending the request
            // (avoids race condition where callback arrives before we start waiting)
            CompletableFuture<String> decisionFuture = new CompletableFuture<String>();
            pendingMap.put(requestId, decisionFuture);

            try {
                String targetUrl = gatewayUrl + "/gateway/v1/confirm-request";
                URL url = new URL(targetUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                // Use a short read timeout for the 202 response (actual decision comes via callback)
                conn.setReadTimeout(30000);

                // Build request body with callbackUrl and requestId
                String callbackUrl = callbackBaseUrl + "/worker/v1/confirm-callback";
                Map<String, Object> requestBody = new HashMap<String, Object>();
                requestBody.put("requestId", requestId);
                requestBody.put("workerId", workerId);
                requestBody.put("toolName", ex.getToolName());
                requestBody.put("action", ex.getAction());
                requestBody.put("arguments", ex.getArguments());
                requestBody.put("toolCallId", ex.getToolCallId());
                requestBody.put("callbackUrl", callbackUrl);

                // Serialize messages if present
                if (ex.getMessages() != null && !ex.getMessages().isEmpty()) {
                    requestBody.put("messages", ex.getMessages());
                } else {
                    requestBody.put("messages", Collections.emptyList());
                }

                byte[] requestBytes = JsonUtils.toJson(requestBody).getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(requestBytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBytes);
                }

                int code = conn.getResponseCode();
                String responseBody = readConnBody(conn, code);
                conn.disconnect();

                if (code == 202) {
                    // Async pattern: Gateway accepted, decision will arrive via callback.
                    // Wait for the callback with 10-minute timeout.
                    logger.info("Confirm request sent (async): requestId={}, waiting for callback...", requestId);
                    try {
                        return decisionFuture.get(600, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        logger.warn("Confirm request {} timed out after 10 min, cancelling", requestId);
                        return "c";
                    } catch (Exception ie) {
                        logger.error("Confirm request {} interrupted: {}", requestId, ie.getMessage());
                        return "c";
                    }
                } else {
                    // Sync fallback: Gateway returned decision directly (old-style or auto-approved)
                    logger.info("Confirm request returned HTTP {} (sync fallback)", code);
                    String decision = extractJsonValue(responseBody, "decision");
                    if (decision != null && !decision.isEmpty()) {
                        return decision;
                    }
                    logger.warn("No decision in confirm response (HTTP {}): {}", code, truncate(responseBody, 200));
                }
            } catch (Exception e) {
                logger.error("Failed to send confirmation request to Gateway", e);
            } finally {
                pendingMap.remove(requestId);
            }
            return "c"; // Default to cancel on error
        }

        private static String readConnBody(HttpURLConnection conn, int code) throws IOException {
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf, 0, buf.length)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }
}
