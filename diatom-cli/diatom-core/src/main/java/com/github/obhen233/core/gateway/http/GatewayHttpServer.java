package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.checkpoint.CheckpointService;
import com.github.obhen233.core.gateway.collaboration.CompositeTaskManager;
import com.github.obhen233.core.gateway.collaboration.Pipeline;
import com.github.obhen233.core.gateway.collaboration.PipelineOrchestrator;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.http.WorkerHttpServer;
import com.github.obhen233.core.gateway.http.docs.ApiDocRegistry;
import com.github.obhen233.core.gateway.http.docs.GatewayApi;
import com.github.obhen233.core.gateway.model.ChatRequest;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.core.gateway.sync.ProjectSyncService;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.topology.TopologyService;
import com.github.obhen233.core.gateway.transport.AsyncTaskTransport;
import com.github.obhen233.core.gateway.transport.DefaultHttpTransport;
import com.github.obhen233.core.gateway.transport.TransportCallback;
import com.github.obhen233.core.gateway.transport.TransportResponse;
import com.github.obhen233.core.gateway.GatewayHttpServerCallback;
import com.github.obhen233.core.gateway.queue.AsyncTaskConsumer;
import com.github.obhen233.core.spi.http.HttpServerSpi;
import com.github.obhen233.core.spi.http.ServerHandler;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;
import com.github.obhen233.spi.ClusterCoordinator;
import com.github.obhen233.spi.ConcurrencyControlProvider;
import com.github.obhen233.spi.GatewayCertProvider;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.TaskQueueProvider;
import java.util.ServiceLoader;
import com.github.obhen233.util.InstallPaths;
import com.github.obhen233.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;
import static com.github.obhen233.core.gateway.http.GatewayHttpSslUtil.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gateway HTTP 服务器
 * 提供 /gateway/v1/* 所有端点
 */
public class GatewayHttpServer implements GatewayHttpServerCallback {
    private static final Logger logger = LoggerFactory.getLogger(GatewayHttpServer.class);

    private HttpServerSpi serverSpi;
    private final int port;
    private final TaskManager taskManager;
    private final WorkerRegistry registry;
    private final CheckpointService checkpointService;
    private final GatewayAgent gatewayAgent;
    private final CapabilityRouter capabilityRouter;
    private final boolean daemonize;
    private final AsyncTaskTransport transport;
    private final ConfigManager configManager;
    private final CompositeTaskManager compositeTaskManager;
    private final PipelineOrchestrator pipelineOrchestrator;
    private final Pipeline analysisExecPipeline;
    private final String monitorPrefix;
    private final DatabaseManager gatewayDb;
    private final CommandRulesDao commandRulesDao;

    // Monitor session store
    private final ConcurrentHashMap<String, Long> sessionTokens = new ConcurrentHashMap<>();

    // Monitor data history for charts
    private final CopyOnWriteArrayList<Object[]> workerLoadHistory = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Object[]> pendingHistory = new CopyOnWriteArrayList<>();
    static final int MAX_LOAD_HISTORY = 200;
    static final int MAX_PENDING_HISTORY = 30;

    /** 全局并发控制信号量，限制同时处理的请求数 */
    private final Semaphore requestSemaphore;
    static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 20;

    /** SPI: 自定义并发控制策略 */
    private final ConcurrencyControlProvider concurrencyControl;

    /** 分布式锁管理器（可选，用于锁策略协作） */
    private ResourceLockManager lockManager;

    /** 集群协调器（可选，HA 模式下用于 session 亲和性等） */
    private ClusterCoordinator clusterCoordinator;

    /** 项目文件同步服务 */
    private final ProjectSyncService projectSyncService = new ProjectSyncService();

    /** 队列模式开关 */
    private final boolean queueEnabled;

    /** 任务队列提供者（队列模式时启用） */
    private final TaskQueueProvider taskQueueProvider;

    /** 后台任务消费者（队列模式时启用） */
    private volatile AsyncTaskConsumer asyncConsumer;

    /** 拓扑编辑器服务 */
    private TopologyService topologyService;
    private ApiDocRegistry apiDocRegistry;

    /** Monitor handler for dashboard and workspace UI */
    private final GatewayMonitorHandler monitorHandler;

    /** 证书提供商（用于签发 Worker HTTPS 证书） */
    private final GatewayCertProvider certProvider;

    // ========== 项目推送阈值常量 ==========
    static final int PROJECT_PUSH_MAX_FILES = 20_000;
    static final long MAX_TRANSFER_TIME_MS = 60_000; // 60s
    static final double MAX_CPU_LOAD = 0.7;          // CPU > 0.7 时不推
    static final double MEMORY_BUFFER_FACTOR = 3.0;   // 需要 3x zip 大小的空闲内存

    // ========== Handler 实例 ==========
    private GatewayDocHandler docHandler;
    private GatewayLockHandler lockHandler;
    private GatewayChatHandler chatHandler;
    private GatewayWorkerHandler workerHandler;
    private GatewayTaskHandler taskHandler;
    private GatewayFileHandler fileHandler;
    private GatewayConfirmHandler confirmHandler;
    private GatewayCertHandler certHandler;
    private GatewayAdmissionControl admissionControl;

    // ==================== Package-private getters ====================

    int getPort() { return port; }
    ConfigManager getConfigManager() { return configManager; }
    WorkerRegistry getRegistry() { return registry; }
    CapabilityRouter getCapabilityRouter() { return capabilityRouter; }
    String getMonitorPrefix() { return monitorPrefix; }
    CommandRulesDao getCommandRulesDao() { return commandRulesDao; }
    ConcurrentHashMap<String, Long> getSessionTokens() { return sessionTokens; }
    CopyOnWriteArrayList<Object[]> getWorkerLoadHistory() { return workerLoadHistory; }
    CopyOnWriteArrayList<Object[]> getPendingHistory() { return pendingHistory; }
    Semaphore getRequestSemaphore() { return requestSemaphore; }

    // ==================== Public getters for handlers ====================

    /**
     * @deprecated Use {@link #getServerSpi()} instead. This method returns
     * the underlying JDK HttpServer if the current SPI is a JdkHttpServer.
     */
    @Deprecated
    public com.sun.net.httpserver.HttpServer getServer() {
        if (serverSpi instanceof JdkHttpServer) {
            return ((JdkHttpServer) serverSpi).getServer();
        }
        throw new UnsupportedOperationException("Not a JDK HttpServer: " + serverSpi.getClass().getName());
    }

    /**
     * Get the HttpServerSpi instance for route registration.
     */
    public HttpServerSpi getServerSpi() { return serverSpi; }
    public GatewayAgent getGatewayAgent() { return gatewayAgent; }
    public TaskManager getTaskManager() { return taskManager; }
    public CheckpointService getCheckpointService() { return checkpointService; }
    public AsyncTaskTransport getTransport() { return transport; }
    public CompositeTaskManager getCompositeTaskManager() { return compositeTaskManager; }
    public PipelineOrchestrator getPipelineOrchestrator() { return pipelineOrchestrator; }
    public Pipeline getAnalysisExecPipeline() { return analysisExecPipeline; }
    public ClusterCoordinator getClusterCoordinator() { return clusterCoordinator; }
    public ProjectSyncService getProjectSyncService() { return projectSyncService; }
    public GatewayCertProvider getCertProvider() { return certProvider; }
    public boolean isDaemonize() { return daemonize; }
    public TaskQueueProvider getTaskQueueProvider() { return taskQueueProvider; }
    public boolean isQueueEnabled() { return queueEnabled; }
    public int getQueueDepth() { return taskQueueProvider != null ? taskQueueProvider.getQueueDepth() : 0; }
    public ConcurrencyControlProvider getConcurrencyControl() { return concurrencyControl; }
    public GatewayAdmissionControl getAdmissionControl() { return admissionControl; }
    public GatewayMonitorHandler getMonitorHandler() { return monitorHandler; }
    public ResourceLockManager getLockManager() { return lockManager; }
    public ApiDocRegistry getApiDocRegistry() { return apiDocRegistry; }

    /**
     * Delegate to GatewayDocHandler for serving static resources.
     * Called by GatewayMonitorHandler.
     */
    void serveStaticResource(ServerRequest request, ServerResponse response, String resourcePath) throws IOException {
        if (docHandler != null) {
            docHandler.serveStaticResource(request, response, resourcePath);
        }
    }

    // ==================== Constructors ====================

    public GatewayHttpServer(int port, TaskManager taskManager, WorkerRegistry registry,
                             GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                             boolean daemonize, ConfigManager configManager) throws IOException {
        this(port, taskManager, registry, gatewayAgent, capabilityRouter, daemonize,
                SpiLoader.getFirst(AsyncTaskTransport.class, new DefaultHttpTransport()),
                configManager, null, null, null);
    }

    public GatewayHttpServer(int port, TaskManager taskManager, WorkerRegistry registry,
                             GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                             boolean daemonize, AsyncTaskTransport transport,
                             ConfigManager configManager) throws IOException {
        this(port, taskManager, registry, gatewayAgent, capabilityRouter, daemonize,
                transport, configManager, null, null, null);
    }

    public GatewayHttpServer(int port, TaskManager taskManager, WorkerRegistry registry,
                             GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                             boolean daemonize, AsyncTaskTransport transport,
                             ConfigManager configManager,
                             PipelineOrchestrator pipelineOrchestrator,
                             Pipeline analysisExecPipeline) throws IOException {
        this(port, taskManager, registry, gatewayAgent, capabilityRouter, daemonize,
                transport, configManager, pipelineOrchestrator, analysisExecPipeline, null);
    }

    public GatewayHttpServer(int port, TaskManager taskManager, WorkerRegistry registry,
                             GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                             boolean daemonize, AsyncTaskTransport transport,
                             ConfigManager configManager,
                             PipelineOrchestrator pipelineOrchestrator,
                             Pipeline analysisExecPipeline,
                             DatabaseManager gatewayDb) throws IOException {
        this(port, taskManager, registry, gatewayAgent, capabilityRouter, daemonize,
                transport, configManager, pipelineOrchestrator, analysisExecPipeline, gatewayDb, null);
    }

    public GatewayHttpServer(int port, TaskManager taskManager, WorkerRegistry registry,
                             GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                             boolean daemonize, AsyncTaskTransport transport,
                             ConfigManager configManager,
                             PipelineOrchestrator pipelineOrchestrator,
                             Pipeline analysisExecPipeline,
                             DatabaseManager gatewayDb,
                             TaskQueueProvider taskQueueProvider) throws IOException {
        this(port, taskManager, registry, gatewayAgent, capabilityRouter, daemonize,
                transport, configManager, pipelineOrchestrator, analysisExecPipeline, gatewayDb,
                taskQueueProvider, null);
    }

    public GatewayHttpServer(int port, TaskManager taskManager, WorkerRegistry registry,
                             GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                             boolean daemonize, AsyncTaskTransport transport,
                             ConfigManager configManager,
                             PipelineOrchestrator pipelineOrchestrator,
                             Pipeline analysisExecPipeline,
                             DatabaseManager gatewayDb,
                             TaskQueueProvider taskQueueProvider,
                             HttpServerSpi serverSpi) throws IOException {
        this.port = port;
        this.taskManager = taskManager;
        this.registry = registry;
        this.gatewayAgent = gatewayAgent;
        this.capabilityRouter = capabilityRouter;
        this.daemonize = daemonize;
        this.transport = transport;
        this.configManager = configManager;
        this.monitorPrefix = computeMonitorPrefix(configManager);
        this.gatewayDb = gatewayDb;
        this.commandRulesDao = gatewayDb != null ? new CommandRulesDao(gatewayDb) : null;
        this.checkpointService = new CheckpointService(taskManager, gatewayDb);
        this.topologyService = gatewayDb != null ? new TopologyService(gatewayDb) : null;
        this.compositeTaskManager = taskManager instanceof CompositeTaskManager
                ? (CompositeTaskManager) taskManager : null;
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.analysisExecPipeline = analysisExecPipeline;
        this.taskQueueProvider = taskQueueProvider;
        this.queueEnabled = taskQueueProvider != null;

        // 初始化 SPI 并发控制策略
        this.concurrencyControl = SpiLoader.getFirst(ConcurrencyControlProvider.class, null);

        // 初始化并发控制信号量
        int maxConcurrent = parseMaxConcurrentRequestsFallback();
        this.requestSemaphore = new Semaphore(maxConcurrent, true);
        logger.info("Admission control: max concurrent requests = {}, custom SPI={}",
                maxConcurrent, concurrencyControl != null ? concurrencyControl.getClass().getSimpleName() : "none");

        // 初始化证书分发提供商
        this.certProvider = initCertProvider();

        // Initialize HttpServerSpi
        if (serverSpi != null) {
            this.serverSpi = serverSpi;
            logger.info("Using custom HttpServerSpi: {}", serverSpi.getClass().getName());
        } else {
            this.serverSpi = createDefaultHttpServer();
        }

        this.monitorHandler = new GatewayMonitorHandler(this);

        initHandlers();
        registerRoutes();
    }

    /**
     * Create the default JdkHttpServer based on SSL config.
     * Tries ServiceLoader&lt;HttpServerSpi&gt; first; falls back to JdkHttpServer.
     */
    private HttpServerSpi createDefaultHttpServer() throws IOException {
        // 1. Try ServiceLoader to discover custom HttpServerSpi implementations
        try {
            ServiceLoader<HttpServerSpi> loader = ServiceLoader.load(HttpServerSpi.class);
            for (HttpServerSpi spi : loader) {
                logger.info("Using custom HttpServerSpi from ServiceLoader: {}", spi.getClass().getName());
                return spi;
            }
        } catch (Exception e) {
            logger.warn("Failed to load HttpServerSpi via ServiceLoader, falling back to JdkHttpServer: {}", e.getMessage());
        }

        boolean sslEnabled = sslEnabled(configManager);
        javax.net.ssl.SSLContext sslCtx = null;

        if (sslEnabled) {
            String keystorePassword = readPassword(configManager, "gateway.ssl.keystore.password");
            String keyPassword = readPassword(configManager, "gateway.ssl.key.password");
            if (keyPassword == null) keyPassword = keystorePassword;

            String keystorePath = configManager.get("gateway.ssl.keystore-path");
            if (keystorePath != null && !keystorePath.isEmpty()) {
                String lower = keystorePath.toLowerCase();
                if (lower.endsWith(".crt") || lower.endsWith(".pem")) {
                    String keystoreKey = configManager.get("gateway.ssl.keystore-key");
                    if (keystoreKey == null || keystoreKey.isEmpty()) {
                        logger.warn("SSL PEM mode requires ssl-keystore-key for: {}", keystorePath);
                        sslEnabled = false;
                    } else {
                        sslCtx = createSSLContextFromPem(keystorePath, keystoreKey, keystorePassword, keyPassword);
                        logger.info("Gateway HTTPS server enabled (PEM: {}, key: {})", keystorePath, keystoreKey);
                    }
                } else {
                    sslCtx = createSSLContext(keystorePath, keystorePassword, keyPassword);
                    logger.info("Gateway HTTPS server enabled (keystore: {})", keystorePath);
                }
            } else {
                logger.warn("SSL enabled but ssl-keystore-path not configured; falling back to HTTP");
                sslEnabled = false;
            }
        }

        return new JdkHttpServer(port, sslEnabled, sslCtx, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Initialize all handler instances after the server is created.
     */
    private void initHandlers() {
        this.admissionControl = new GatewayAdmissionControl(this);
        this.docHandler = new GatewayDocHandler(this);
        this.lockHandler = new GatewayLockHandler(this);
        this.chatHandler = new GatewayChatHandler(this);
        this.workerHandler = new GatewayWorkerHandler(this);
        this.taskHandler = new GatewayTaskHandler(this);
        this.fileHandler = new GatewayFileHandler(this);
        this.confirmHandler = new GatewayConfirmHandler(this);
        this.certHandler = new GatewayCertHandler(this);
    }

    private void registerRoutes() {
        // Health — keep in GatewayHttpServer
        serverSpi.addHandler("GET", "/gateway/v1/health", this::handleHealth);

        // API docs — handled by GatewayDocHandler
        if (docHandler != null) {
            docHandler.registerRoutes();
        }

        // Lock endpoints — handled by GatewayLockHandler (only when lockManager is configured)
        if (lockHandler != null) {
            lockHandler.registerRoutes();
        }

        // Chat, stream, collab, deploy, worker proxy — handled by GatewayChatHandler
        if (chatHandler != null) {
            chatHandler.registerRoutes();
        }

        // Worker registration — handled by GatewayWorkerHandler
        if (workerHandler != null) {
            workerHandler.registerRoutes();
        }

        // Checkpoint, Tasks — handled by GatewayTaskHandler
        if (taskHandler != null) {
            taskHandler.registerRoutes();
        }

        // File, workspace, config sync, project push, sandbox setup — handled by GatewayFileHandler
        if (fileHandler != null) {
            fileHandler.registerRoutes();
        }

        // Confirmation endpoints — handled by GatewayConfirmHandler
        if (confirmHandler != null) {
            confirmHandler.registerRoutes();
        }

        // Certificate issue — handled by GatewayCertHandler (checks internally if enabled)
        if (certHandler != null) {
            certHandler.registerRoutes();
        }

        // Monitor dashboard — handled by GatewayMonitorHandler
        if (monitorHandler != null && monitorHandler.isMonitorEnabled()) {
            String monitorPath = "/" + monitorPrefix + "/";
            serverSpi.addHandler("GET", monitorPath, monitorHandler::handleMonitorRouter);
            monitorHandler.startTokenCleanup();
            logger.info("Monitor dashboard enabled at {} (prefix={})", monitorPath, monitorPrefix);
        }

        // Workspace UI (topology, routing) — shares monitor prefix
        if (monitorHandler != null && monitorHandler.isMonitorEnabled()) {
            String workspacePath = "/" + monitorPrefix + "/workspace";
            serverSpi.addHandler("GET", workspacePath, monitorHandler::handleWorkspaceRouter);
            String routingPath = "/" + monitorPrefix + "/routing";
            serverSpi.addHandler("GET", routingPath, monitorHandler::handleWorkspaceRouter);
            logger.info("Workspace UI enabled under /{}/workspace and /{}/routing", monitorPrefix, monitorPrefix);
        }

        // Topology editor — shares monitor prefix
        if (monitorHandler != null && monitorHandler.isMonitorEnabled() && topologyService != null) {
            String topologyApiPath = "/" + monitorPrefix + "/topology";
            serverSpi.addHandler("GET", topologyApiPath, monitorHandler::handleTopologyEditorRouter);
            serverSpi.addHandler("POST", topologyApiPath, monitorHandler::handleTopologyEditorRouter);
            serverSpi.addHandler("PUT", topologyApiPath, monitorHandler::handleTopologyEditorRouter);
            serverSpi.addHandler("DELETE", topologyApiPath, monitorHandler::handleTopologyEditorRouter);
            logger.info("Topology editor enabled under /{}/topology", monitorPrefix);
        } else {
            if (monitorHandler == null || !monitorHandler.isMonitorEnabled()) {
                logger.info("Topology editor disabled (monitor.enabled=false)");
            } else {
                logger.info("Topology editor disabled (no database)");
            }
        }

        // Initialize API doc registry (scan all @GatewayApi annotations)
        apiDocRegistry = new ApiDocRegistry();
        apiDocRegistry.scan(this);
        apiDocRegistry.scanAdditionalClass(WorkerHttpServer.class);
        apiDocRegistry.scanAdditionalClass(GatewayChatHandler.class);
        apiDocRegistry.scanAdditionalClass(GatewayWorkerHandler.class);
        apiDocRegistry.scanAdditionalClass(GatewayTaskHandler.class);
        apiDocRegistry.scanAdditionalClass(GatewayFileHandler.class);
        apiDocRegistry.scanAdditionalClass(GatewayConfirmHandler.class);
        apiDocRegistry.scanAdditionalClass(GatewayCertHandler.class);
        logger.info("API doc registry initialized with {} endpoints",
                apiDocRegistry.isEmpty() ? "no" : "scanned");
    }

    @GatewayApi(path = "/gateway/v1/health", methods = {"GET"},
            summary = "Health check",
            description = "Gateway health check endpoint. Returns status, online worker count, active tasks, and concurrency info.\n\n"
                    + "【免鉴权 / No Auth Required】\n"
                    + "Health check endpoints are exempt from authentication.\n\n"
                    + "Response fields:\n"
                    + "- status: \"UP\" or \"DOWN\"\n"
                    + "- onlineWorkers: number of currently connected workers\n"
                    + "- activeTasks: number of active task executions\n"
                    + "- queue.enabled: whether async queue mode is active (--queue)\n"
                    + "- queue.depth: number of tasks pending in the async queue\n"
                    + "- concurrency.max: maximum concurrent request limit\n"
                    + "- concurrency.available: remaining available permits\n"
                    + "- concurrency.queued: number of requests waiting in queue",
            tags = {"核心 / Core"},
            authRequired = false,
            responseBody = "{\"status\":\"UP\",\"onlineWorkers\":3,\"activeTasks\":5,\"queue\":{\"enabled\":true,\"depth\":2},\"concurrency\":{\"max\":20,\"available\":15,\"queued\":0}}")
    private void handleHealth(ServerRequest request, ServerResponse response) throws IOException {
        int onlineCount = registry.localWorkers().size();
        int activeTasks = taskManager.getActiveTaskCount();
        int maxConcurrent = requestSemaphore.availablePermits()
                + (DEFAULT_MAX_CONCURRENT_REQUESTS - requestSemaphore.availablePermits());
        int availablePermits = requestSemaphore.availablePermits();
        int queued = requestSemaphore.getQueueLength();
        int queueDepth = getQueueDepth();
        String queueInfo = queueEnabled
            ? ",\"queue\":{\"enabled\":true,\"depth\":" + queueDepth + "}"
            : "";
        String json = "{\"status\":\"UP\",\"onlineWorkers\":" + onlineCount
                + ",\"activeTasks\":" + activeTasks
                + queueInfo
                + ",\"concurrency\":{\"max\":" + maxConcurrent
                + ",\"available\":" + availablePermits
                + ",\"queued\":" + queued + "}}";
        sendJson(response, 200, json);
    }

    /**
     * GET /gateway/v1/docs/openapi.json — returns OpenAPI 3.0 specification.
     */
    private void handleOpenApiJson(ServerRequest request, ServerResponse response) throws IOException {
        if (apiDocRegistry == null || apiDocRegistry.isEmpty()) {
            sendError(response, 503, "{\"error\":\"API docs not initialized\"}");
            return;
        }
        String json = apiDocRegistry.toOpenApiJson();
        response.setHeader("Content-Type", "application/json");
        response.setStatus(200);
        response.send(json);
    }

    // ==================== GatewayHttpServerCallback ====================

    @Override
    public void processTask(TaskQueueProvider.QueuedTask task) {
        String taskId = task.getTaskId();
        String message = task.getMessage();
        String sessionId = task.getSessionId();
        String body = task.getBody();
        String workerId = null;
        try {
            // 1. Analyze request via GatewayAgent
            TaskRequirement requirement = gatewayAgent.analyzeRequest(message);

            // 2. Route to best worker via CapabilityRouter
            WorkerInfo selected = capabilityRouter.routeWithLLMSuggestion(requirement);
            if (selected == null) {
                taskManager.failTask(taskId, "No available workers");
                logger.warn("Queued task {} failed: no available workers", taskId);
                return;
            }

            workerId = selected.getWorkerId();
            capabilityRouter.incrementActive(workerId);
            taskManager.assignTask(taskId, workerId);
            taskManager.startTask(taskId);
            TaskState taskState = taskManager.getTask(taskId);
            if (taskState != null) {
                taskState.addAttribute("workspacePath", resolveWorkspaceDir());
            }

            // 3. Build ChatRequest and send via transport
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setTaskId(taskId);
            chatRequest.setSessionId(sessionId);
            chatRequest.setMessage(message);
            chatRequest.setSyncStrategy("skip");
            chatRequest.setGatewayUrl(getGatewayBaseUrl(selected));
            chatRequest.setWorkspacePath(resolveWorkspaceDir());

            CompletableFuture<TransportResponse> future = new CompletableFuture<>();
            transport.sendTaskAsync(selected, chatRequest, 600000, new TransportCallback() {
                @Override
                public void onSuccess(String wid, TransportResponse response) {
                    future.complete(response);
                }
                @Override
                public void onFailure(String wid, String error) {
                    future.completeExceptionally(new RuntimeException("Transport failed: " + error));
                }
                @Override
                public void onTimeout(String wid) {
                    future.completeExceptionally(new java.util.concurrent.TimeoutException("Request timed out"));
                }
            });

            TransportResponse transportResponse;
            try {
                transportResponse = future.get(600000, java.util.concurrent.TimeUnit.MILLISECONDS);
                taskManager.completeTask(taskId);
                logger.info("Queued task completed: {} -> worker={}", taskId, workerId);
            } catch (Exception e) {
                taskManager.failTask(taskId, e.getMessage());
                logger.error("Queued task failed: {} -> worker={}: {}", taskId, workerId, e.getMessage());
            }
        } catch (Exception e) {
            logger.error("Queued task {} failed unexpectedly", taskId, e);
            try { taskManager.failTask(taskId, e.getMessage()); } catch (Exception ignored) {}
        } finally {
            if (workerId != null) {
                capabilityRouter.decrementActive(workerId);
            }
        }
    }

    private String getGatewayBaseUrl(WorkerInfo worker) {
        String host = worker.getHost();
        String gwHost = System.getProperty("gateway.host", "127.0.0.1");
        String gwPort = System.getProperty("gateway.port", String.valueOf(port));
        return "http://" + gwHost + ":" + gwPort;
    }

    // ========== 证书初始化 ==========

    /**
     * Initialize the certificate provider.
     * Loads SPI first, falls back to DefaultJavaCertProvider.
     */
    private GatewayCertProvider initCertProvider() {
        if (!sslCertDistributionEnabled()) {
            logger.info("Cert distribution is disabled (gateway.ssl.cert-distribution.enabled=false or not set)");
            return null;
        }

        java.nio.file.Path caDir = InstallPaths.getGatewayCaDir();

        // 1. Try ServiceLoader SPI
        java.util.ServiceLoader<GatewayCertProvider> loader = java.util.ServiceLoader.load(GatewayCertProvider.class);
        try {
            for (GatewayCertProvider provider : loader) {
                logger.info("Loaded GatewayCertProvider SPI: {}", provider.getClass().getName());
                provider.init(caDir, configManager);
                if (provider.isEnabled()) {
                    return provider;
                }
                logger.warn("GatewayCertProvider {} is not enabled, trying next", provider.getClass().getSimpleName());
            }
        } catch (Exception e) {
            logger.warn("Failed to load GatewayCertProvider SPI, falling back to default: {}", e.getMessage());
        }

        // 2. Fallback to DefaultJavaCertProvider
        try {
            DefaultJavaCertProvider defaultProvider = new DefaultJavaCertProvider();
            defaultProvider.init(caDir, configManager);
            if (defaultProvider.isEnabled()) {
                logger.info("Using default GatewayCertProvider: DefaultJavaCertProvider");
                return defaultProvider;
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize DefaultJavaCertProvider: {}", e.getMessage());
        }

        logger.warn("No GatewayCertProvider available, certificate issue endpoint will be disabled");
        return null;
    }

    /**
     * Check if certificate distribution is enabled via config.
     */
    private boolean sslCertDistributionEnabled() {
        if (configManager == null) return false;
        String val = configManager.get("gateway.ssl.cert-distribution.enabled");
        return "true".equalsIgnoreCase(val);
    }

    // ========== 并发控制（遗留 fallback，用于构造器初始化顺序） ==========

    private int parseMaxConcurrentRequestsFallback() {
        if (concurrencyControl != null) {
            int spiVal = concurrencyControl.getMaxConcurrentRequests();
            if (spiVal > 0) return spiVal;
        }
        String val = System.getProperty("gateway.max.concurrent.requests");
        if (val != null) {
            try {
                int parsed = Integer.parseInt(val);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException e) {
                logger.warn("Invalid gateway.max.concurrent.requests: {}", val);
            }
        }
        return DEFAULT_MAX_CONCURRENT_REQUESTS;
    }

    // ========== Public API ==========

    public void start() {
        serverSpi.start();
        if (queueEnabled && taskQueueProvider != null) {
            int concurrency = admissionControl != null
                    ? admissionControl.parseQueueConcurrency()
                    : Math.max(1, Runtime.getRuntime().availableProcessors());
            asyncConsumer = new AsyncTaskConsumer(taskQueueProvider, this, concurrency);
            asyncConsumer.start();
            logger.info("Async task consumer started: queue={}, concurrency={}", taskQueueProvider.getName(), concurrency);
        }
        logger.info("Gateway HTTP server started on 127.0.0.1:{}", port);
    }

    public void stop(int delaySeconds) {
        if (serverSpi != null) {
            serverSpi.stop(delaySeconds);
        }
        if (asyncConsumer != null) {
            asyncConsumer.shutdown();
        }
        if (taskQueueProvider != null) {
            taskQueueProvider.shutdown();
        }
        if (monitorHandler != null) {
            monitorHandler.stopTokenCleanup();
        }
        if (clusterCoordinator != null) {
            try {
                clusterCoordinator.shutdown();
                logger.info("Cluster coordinator shut down");
            } catch (Exception e) {
                logger.warn("Error shutting down cluster coordinator: {}", e.getMessage());
            }
        }
        // Cancel all pending confirmations
        if (confirmHandler != null) {
            confirmHandler.cancelAllPendingConfirmations();
        }
        logger.info("Gateway HTTP server stopped");
    }

    // ========== 分布式锁管理器 setter（外部注入） ==========

    public void setLockManager(ResourceLockManager lockManager) {
        this.lockManager = lockManager;
    }

    public void setClusterCoordinator(ClusterCoordinator clusterCoordinator) {
        this.clusterCoordinator = clusterCoordinator;
        if (topologyService != null) {
            topologyService.setClusterCoordinator(clusterCoordinator);
            // In HA mode, try to load active topology from cluster
            topologyService.loadActiveFromCluster();
        }
    }

    public TopologyService getTopologyService() {
        return topologyService;
    }

    // ========== 确认请求委托（兼容 GatewayModeLauncher） ==========

    public GatewayConfirmHandler.PendingConfirmRequest pollPendingConfirm() {
        return confirmHandler != null ? confirmHandler.pollPendingConfirm() : null;
    }

    public void resolveConfirm(String requestId, String decision) {
        if (confirmHandler != null) {
            confirmHandler.resolveConfirm(requestId, decision);
        }
    }

    public void cancelAllPendingConfirmations() {
        if (confirmHandler != null) {
            confirmHandler.cancelAllPendingConfirmations();
        }
    }

    public boolean isGlobalAutoApprove() {
        return confirmHandler != null && confirmHandler.isGlobalAutoApprove();
    }

    public void setGlobalAutoApprove(boolean globalAutoApprove) {
        if (confirmHandler != null) {
            confirmHandler.setGlobalAutoApprove(globalAutoApprove);
        }
    }

    public void setUpstreamGatewayUrl(String upstreamGatewayUrl) {
        if (confirmHandler != null) {
            confirmHandler.setUpstreamGatewayUrl(upstreamGatewayUrl);
        }
    }

    public String getUpstreamGatewayUrl() {
        return confirmHandler != null ? confirmHandler.getUpstreamGatewayUrl() : null;
    }

    /**
     * PendingConfirmRequest — alias for backward compatibility.
     * The actual implementation is in GatewayConfirmHandler.
     */
    public static class PendingConfirmRequest extends GatewayConfirmHandler.PendingConfirmRequest {
        public PendingConfirmRequest(String requestId, String workerId, String toolName,
                              String action, String arguments, String toolCallId,
                              String callbackUrl, List<ChatMessage> messages) {
            super(requestId, workerId, toolName, action, arguments, toolCallId, callbackUrl, messages);
        }
    }
}
