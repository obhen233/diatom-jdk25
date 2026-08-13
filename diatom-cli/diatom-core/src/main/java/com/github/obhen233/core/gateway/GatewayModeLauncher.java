package com.github.obhen233.core.gateway;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.CoreInitializer;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.cancel.CancelPropagator;
import com.github.obhen233.core.gateway.collaboration.CompositeTaskManager;
import com.github.obhen233.core.gateway.collaboration.CompositeStrategyProvider;
import com.github.obhen233.core.gateway.collaboration.DefaultWorkerCoordinator;
import com.github.obhen233.core.gateway.collaboration.ParallelTaskExecutor;
import com.github.obhen233.core.gateway.collaboration.Pipeline;
import com.github.obhen233.core.gateway.collaboration.PipelineOrchestrator;
import com.github.obhen233.core.gateway.collaboration.PipelineStage;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.collaboration.SandboxWorkspaceManager;
import com.github.obhen233.core.gateway.sync.ProjectSyncService;
import com.github.obhen233.core.gateway.collaboration.WorkspaceManager;
import com.github.obhen233.core.gateway.collaboration.WorkerCoordinator;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.gateway.cluster.ClusterCoordinatorLoader;
import com.github.obhen233.core.gateway.checkpoint.CheckpointReport;
import com.github.obhen233.core.gateway.checkpoint.CheckpointService;
import com.github.obhen233.core.gateway.http.GatewayConfirmHandler;
import com.github.obhen233.core.gateway.http.GatewayHttpServer;
import com.github.obhen233.core.gateway.registry.ClusteredWorkerRegistry;
import com.github.obhen233.core.gateway.topology.TopologyConfigProvider;
import com.github.obhen233.core.gateway.topology.TopologyService;
import com.github.obhen233.core.gateway.registry.FileSystemWorkerRegistry;
import com.github.obhen233.core.gateway.registry.RegistryEvent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.core.gateway.security.SecurityHeadersInjector;
import com.github.obhen233.core.gateway.security.SecurityProviderLoader;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;
import com.github.obhen233.core.gateway.transport.DefaultHttpTransport;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.TaskQueueProvider;
import com.github.obhen233.spi.ScheduledTaskProvider;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.obhen233.core.gateway.http.dto.ChatRequestPayload;
import com.github.obhen233.core.gateway.http.dto.ResumeRequestPayload;
import com.github.obhen233.util.InstallPaths;
import com.github.obhen233.util.JsonUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gateway 模式启动器
 */
public class GatewayModeLauncher {
    private static final Logger logger = LoggerFactory.getLogger(GatewayModeLauncher.class);

    private GatewayModeLauncher() {}

    public static void start(String[] args, AppConfig config,
                              AiHttpClient httpClient, ModelAdapter adapter,
                              ConfigManager configManager,
                              DatabaseManager dbManager) {
        int port = 8080;
        String portSource = "default";
        // Priority 1: CLI argument -P/--port
        String portArg = GatewayArgParser.parsePortArgValue(args);
        if (portArg != null) {
            try { port = Integer.parseInt(portArg); portSource = "-P/--port CLI argument"; } catch (NumberFormatException ignored) {}
        }
        // Priority 2: DIATOM_PORT environment variable
        if ("default".equals(portSource)) {
            String envPort = System.getenv("DIATOM_PORT");
            if (envPort != null && !envPort.isEmpty()) {
                try { port = Integer.parseInt(envPort); portSource = "DIATOM_PORT env"; } catch (NumberFormatException ignored) {}
            }
        }
        // Priority 3: diatom.port from config file
        if ("default".equals(portSource)) {
            String cfgPort = config.getProperty("diatom.port", "");
            if (!cfgPort.isEmpty()) {
                try { port = Integer.parseInt(cfgPort); portSource = "diatom.port config"; } catch (NumberFormatException ignored) {}
            }
        }
        port = GatewayArgParser.resolvePort(port);
        System.out.println("  [Config] Port: " + port + " (from " + portSource + ")");
        String instanceId = GatewayArgParser.parseInstanceIdFromArgs(args);
        boolean daemonize = GatewayArgParser.parseDaemonizeArg(args);
        GatewayArgParser.ModePreset preset = GatewayArgParser.parseModePreset(args);
        com.github.obhen233.core.security.SandboxLevel sandboxLevel = GatewayArgParser.parseSandboxLevel(args, config, preset);
        com.github.obhen233.core.security.ApprovalPolicy approvalPolicy = GatewayArgParser.parseApprovalPolicy(args, config, preset);

        System.setProperty("diatom.instance.id", instanceId);
        String resolvedWorkspace = config.getWorkspaceDir();
        String wsSource = com.github.obhen233.util.WorkspaceDirResolver.getSourceLabel(config);
        System.out.println("  [Config] Workspace: " + resolvedWorkspace + " (from " + wsSource + ")");
        System.setProperty("diatom.workspace.dir", resolvedWorkspace);
        logger.info("Starting gateway mode (instance={}, port={}, daemonize={})",
                instanceId, port, daemonize);

        try {
            // gateway_tasks table is auto-created by Hibernate hbm2ddl.auto=update
            // via GatewayTaskEntity

            // Seed default command rules for Gateway if table is empty
            try {
                CommandRulesDao rulesDao = new CommandRulesDao(dbManager);
                if (rulesDao.findAll().isEmpty()) {
                    for (CommandRulesDao.CommandRule rule : com.github.obhen233.cli.provider.RulesCommandProvider.getBuiltinRules()) {
                        rulesDao.insertIfNotExists(rule);
                    }
                    logger.info("Gateway: seeded {} built-in command rules", com.github.obhen233.cli.provider.RulesCommandProvider.getBuiltinRules().size());
                }
            } catch (Exception e) {
                logger.warn("Gateway: failed to seed default rules: {}", e.getMessage());
            }

            WorkerRegistry registry = new FileSystemWorkerRegistry();

            // Determine if cluster coordination is needed:
            //   1. Has upstream Gateway (chain mode: CEO → department)
            //   2. Has TCP-IP peer list (Nginx load-balanced multi-Gateway mode)
            //   Otherwise standalone → no cluster needed
            boolean hasUpstream = GatewayArgParser.detectUpstream(args, config);
            boolean hasPeers = GatewayArgParser.hasClusterTcpIpPeers(config);
            // gateway.ha.enabled=true overrides auto-detection, forces cluster on
            boolean haEnabled = "true".equalsIgnoreCase(
                    GatewayArgParser.clusterProperty("gateway.ha.enabled", config, "false"));
            boolean clusterEnabled = hasUpstream || hasPeers || haEnabled;
            if (!clusterEnabled) {
                logger.info("Standalone Gateway mode, cluster coordination disabled");
            }
            if (haEnabled) {
                logger.info("Gateway HA mode enabled via gateway.ha.enabled=true");
            }

            // Initialize ClusterCoordinator for cross-Gateway context sharing
            // Auto-detects: plugin SPI → classpath SPI → Hazelcast default (optional)
            // Set cluster.enabled=true to force-enable when needed
            // Config sources (高优先级覆盖低): system property → yml/properties → default
            java.util.Map<String, String> clusterConfig = new java.util.HashMap<>();
            clusterConfig.put("cluster.enabled",
                    GatewayArgParser.clusterProperty("cluster.enabled", config, String.valueOf(clusterEnabled)));
            clusterConfig.put("cluster.hazelcast.port",
                    GatewayArgParser.clusterProperty("cluster.hazelcast.port", config, "5701"));
            clusterConfig.put("cluster.hazelcast.tcpip.members",
                    GatewayArgParser.clusterProperty("cluster.hazelcast.tcpip.members", config, ""));
            clusterConfig.put("cluster.hazelcast.tcpip.enabled",
                    GatewayArgParser.clusterProperty("cluster.hazelcast.tcpip.enabled", config, "false"));
            clusterConfig.put("cluster.hazelcast.multicast.enabled",
                    GatewayArgParser.clusterProperty("cluster.hazelcast.multicast.enabled", config, "true"));
            com.github.obhen233.spi.ClusterCoordinator clusterCoordinator =
                    ClusterCoordinatorLoader.load(clusterConfig);
            logger.info("Gateway cluster coordinator: {}",
                    clusterCoordinator != null ? clusterCoordinator.getName() : "none");

            // Wrap registry with ClusteredWorkerRegistry if HA enabled
            if (haEnabled && clusterCoordinator != null) {
                registry = new ClusteredWorkerRegistry(registry, clusterCoordinator, instanceId, true);
                logger.info("Wrapped registry with ClusteredWorkerRegistry (gatewayId={})", instanceId);
            }

            // Create GatewayAgent and CapabilityRouter for intelligent routing
            // Resolve API URL with same logic as CLI mode (handle Anthropic format properly)
            boolean isAnthropic = CoreInitializer.detectAnthropicFormat(config);
            String gatewayApiUrl = isAnthropic
                    ? CoreInitializer.resolveAnthropicEndpoint(config)
                    : config.getApiUrl();
            GatewayAgent gatewayAgent = new GatewayAgent(httpClient, adapter, config.getModel(), gatewayApiUrl, registry);
            CapabilityRouter capabilityRouter = new CapabilityRouter(registry);

            // Create collaboration components (CompositeTaskManager extends TaskManager)
            WorkerCoordinator coordinator = new DefaultWorkerCoordinator();
            ParallelTaskExecutor parallelExecutor = new ParallelTaskExecutor();
            WorkspaceManager workspaceManager = new WorkspaceManager();

            // Create resource contention SPI components (sandbox + lock)
            String sandboxDirPath = GatewayArgParser.resolveSandboxDir(args, config);
            java.nio.file.Path sandboxBaseDir = java.nio.file.Paths.get(sandboxDirPath);
            SandboxWorkspaceManager sandboxManager = new SandboxWorkspaceManager(sandboxBaseDir);
            ResourceLockManager lockManager = new ResourceLockManager();

            // Load SPI: use custom ResourceContentionProvider if registered, otherwise CompositeStrategyProvider
            com.github.obhen233.spi.ResourceContentionProvider contentionProvider =
                    com.github.obhen233.spi.SpiLoader.getFirst(
                            com.github.obhen233.spi.ResourceContentionProvider.class,
                            new CompositeStrategyProvider(sandboxManager, lockManager));

            CompositeTaskManager compositeTaskManager = new CompositeTaskManager(
                    coordinator, parallelExecutor, capabilityRouter, registry,
                    workspaceManager, null, contentionProvider, new ProjectSyncService());
            CancelPropagator cancelPropagator = new CancelPropagator(compositeTaskManager, registry);

            // Create PipelineOrchestrator for sequential multi-worker pipelines
            PipelineOrchestrator pipelineOrchestrator = new PipelineOrchestrator(registry);

            // Predefined pipeline: high-cost model analyzes, low-cost model executes
            Pipeline analysisExecPipeline = Pipeline.builder("analysis-execution")
                    .stage("analysis",
                            w -> w.getCostPer1kTokens() >= 0.01,
                            PipelineStage.PipelineContext::getUserInput,
                            PipelineStage::extractResponse)
                    .stage("execution",
                            w -> w.getCostPer1kTokens() < 0.01,
                            ctx -> "Previous analysis:\n" + ctx.getPreviousResult()
                                    + "\n---\nExecute the above plan and implement the solution.",
                            PipelineStage::extractResponse)
                    .build();

            // Listen for worker offline events to fail their tasks
            registry.subscribe(event -> {
                if (event.getType() == RegistryEvent.EventType.HEARTBEAT_TIMEOUT) {
                    compositeTaskManager.handleWorkerOffline(event.getWorkerId());
                }
            });

            // Parse --queue / -q flag for async queue mode
            boolean queueEnabled = GatewayArgParser.parseQueueArg(args);
            if (queueEnabled) {
                System.setProperty("diatom.gateway.queue.enabled", "true");
            }

            // Load TaskQueueProvider SPI (queue mode only, or always for lifecycle management)
            TaskQueueProvider taskQueueProvider = null;
            if (queueEnabled) {
                Properties queueConfig = new Properties();
                // Load config properties with gateway.queue. prefix
                String queueCapStr = config.getProperty("gateway.queue.capacity", "");
                if (queueCapStr != null) queueConfig.setProperty("capacity", queueCapStr);

                // Load SPI: highest priority implementation wins
                java.util.List<TaskQueueProvider> providers = SpiLoader.getAll(TaskQueueProvider.class);
                if (providers != null && !providers.isEmpty()) {
                    taskQueueProvider = providers.get(0);
                    taskQueueProvider.init(queueConfig);
                    System.out.println("  [Config] Queue mode enabled: provider=" + taskQueueProvider.getName()
                        + ", concurrency=" + Math.max(1, Runtime.getRuntime().availableProcessors()));
                } else {
                    System.err.println("  [Config] WARNING: --queue true but no TaskQueueProvider SPI found");
                }
            }

            // Load ScheduledTaskProvider SPI — external scheduler integration (XXL-Job, Quartz, etc.)
            java.util.List<ScheduledTaskProvider> scheduledProviders =
                    SpiLoader.getAll(ScheduledTaskProvider.class);

            // Always start HTTP server (needed for gateway-worker communication)
            DefaultHttpTransport transport = new DefaultHttpTransport();
            GatewayHttpServer httpServer = new GatewayHttpServer(port, compositeTaskManager, registry,
                    gatewayAgent, capabilityRouter, daemonize, transport, configManager,
                    pipelineOrchestrator, analysisExecPipeline, dbManager, taskQueueProvider);
            httpServer.setLockManager(lockManager);

            // Wire ScheduledTaskProvider with callback after transport is ready
            if (scheduledProviders != null && !scheduledProviders.isEmpty()) {
                ScheduledTaskProvider.SchedulerCallback callback = createSchedulerCallback(
                        compositeTaskManager, gatewayAgent, capabilityRouter, transport, registry);
                for (ScheduledTaskProvider sp : scheduledProviders) {
                    try {
                        sp.init(callback);
                        sp.register();
                        logger.info("Scheduled task provider registered: {} (priority={})",
                                sp.getName(), sp.getPriority());
                    } catch (Exception e) {
                        logger.warn("Failed to register scheduled task provider: {}", sp.getName(), e);
                    }
                }
            }
            httpServer.setLockManager(lockManager);

            // Initialize topology config provider for routing integration
            TopologyConfigProvider topologyConfigProvider = new TopologyConfigProvider();
            TopologyService topologyService = httpServer.getTopologyService();
            if (topologyService != null) {
                topologyConfigProvider.setTopologyService(topologyService);
                if (clusterCoordinator != null) {
                    topologyService.setClusterCoordinator(clusterCoordinator);
                }
            }

            if (clusterCoordinator != null) {
                httpServer.setClusterCoordinator(clusterCoordinator);
            }
            // Global auto-approve: SILENT or AUTO policy enables auto-approval for worker requests
            if (approvalPolicy == com.github.obhen233.core.security.ApprovalPolicy.SILENT
                    || approvalPolicy == com.github.obhen233.core.security.ApprovalPolicy.AUTO) {
                httpServer.setGlobalAutoApprove(true);
                System.out.println("  [Config] Sandbox level: " + sandboxLevel.name().toLowerCase()
                    + ", Approval policy: " + approvalPolicy.name().toLowerCase()
                    + " (global auto-approve enabled, extends to all Workers/child Gateways)");
            } else {
                System.out.println("  [Config] Sandbox level: " + sandboxLevel.name().toLowerCase()
                    + ", Approval policy: " + approvalPolicy.name().toLowerCase());
            }
            httpServer.start();

            // Generate gateway self-description skill file (non-fatal)
            ensureGatewaySelfSkillFile(instanceId, port, config);

            // Wire CheckpointService after httpServer creates it internally
            parallelExecutor.setCheckpointService(httpServer.getCheckpointService());

            // Wire TaskManager with gateway database for task state persistence
            if (dbManager != null) {
                compositeTaskManager.setDatabase(dbManager);
                compositeTaskManager.loadFromDatabase();
                int suspendedCount = compositeTaskManager.getTasksByStatus(com.github.obhen233.core.gateway.task.TaskStatus.SUSPENDED).size();
                if (suspendedCount > 0) {
                    logger.info("Loaded {} suspended tasks from previous session", suspendedCount);
                }
            }

            // Register to upstream Gateway if configured (nested Gateway mode)
            String upstreamGateway = GatewayArgParser.parseUpstreamGatewayFromArgs(args);
            if (upstreamGateway != null) {
                upstreamGateway = GatewayArgParser.normalizeGwUrl(upstreamGateway);
            }
            // Fallback: GATEWAY_URL environment variable (same as Worker)
            if (upstreamGateway == null) {
                String envGwUrl = System.getenv("GATEWAY_URL");
                if (envGwUrl != null && !envGwUrl.isEmpty()) {
                    upstreamGateway = GatewayArgParser.normalizeGwUrl(envGwUrl);
                }
            }
            // Fallback: gateway.url from config file (same as Worker)
            if (upstreamGateway == null) {
                String cfgGwUrl = config.getProperty("gateway.url", "");
                if (!cfgGwUrl.isEmpty()) {
                    upstreamGateway = GatewayArgParser.normalizeGwUrl(cfgGwUrl);
                }
            }
            if (upstreamGateway != null) {
                System.setProperty("gateway.url", upstreamGateway);

                // 通知 GatewayHttpServer 上游 Gateway URL，用于确认请求转发
                httpServer.setUpstreamGatewayUrl(upstreamGateway);

                // Self-check: 防止 Gateway 注册到自身
                if (GatewayArgParser.isSelfUrl(upstreamGateway, port)) {
                    System.out.println("  [Config] GATEWAY_URL points to itself (" + upstreamGateway + "), skipping self-registration");
                    logger.warn("GATEWAY_URL/gateway.url points to itself ({}), skipping upstream registration", upstreamGateway);
                } else {
                    registerToUpstreamGateway(instanceId, port, config, upstreamGateway);
                    httpRegisterToUpstream(upstreamGateway, instanceId, port, config);
                    startUpstreamHeartbeat(instanceId, upstreamGateway);
                    logger.info("Registered to upstream Gateway: {}", upstreamGateway);
                }
            }

            long shutdownTimeoutMs = GatewayArgParser.parseShutdownTimeout(args);

            if (daemonize) {
                // Daemon mode: HTTP accessible, no CLI — block with waitLock
                startDaemonMode(args, shutdownTimeoutMs, instanceId, registry, httpServer, port, scheduledProviders);
            } else {
                // CLI mode: HTTP internal only, user interacts via CLI
                startCliMode(shutdownTimeoutMs, instanceId, registry, httpServer,
                        gatewayAgent, capabilityRouter, compositeTaskManager,
                        pipelineOrchestrator, analysisExecPipeline, scheduledProviders);
            }
        } catch (Exception e) {
            logger.error("Failed to start gateway mode", e);
            System.exit(1);
        }
    }

    /**
     * 后台模式：HTTP 服务器常驻，无 CLI
     */
    private static void startDaemonMode(String[] args, long shutdownTimeoutMs,
                                         String instanceId, WorkerRegistry registry,
                                         GatewayHttpServer httpServer, int port,
                                         java.util.List<ScheduledTaskProvider> scheduledProviders) {
        System.out.println("Gateway daemon mode (port=" + port + ").");
        Object waitLock = new Object();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down gateway daemon (timeout={}ms)...", shutdownTimeoutMs);
            System.setProperty("diatom.gateway.status", "SHUTTING_DOWN");
            notifyWorkersShutdown(registry, instanceId);
            try {
                Thread.sleep(Math.min(shutdownTimeoutMs, 10000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            unregisterScheduledProviders(scheduledProviders);
            registry.shutdown();
            httpServer.stop(1);
            synchronized (waitLock) {
                waitLock.notifyAll();
            }
            logger.info("Gateway daemon shutdown complete");
        }));

        logger.info("Gateway daemon mode started — waiting for HTTP requests on port");
        synchronized (waitLock) {
            try {
                waitLock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * CLI 模式：HTTP 服务器内部使用（checkpoint 等），用户通过 CLI 交互
     */
    private static void startCliMode(long shutdownTimeoutMs, String instanceId,
                                      WorkerRegistry registry, GatewayHttpServer httpServer,
                                      GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                                      TaskManager taskManager,
                                      PipelineOrchestrator pipelineOrchestrator,
                                      Pipeline analysisExecPipeline,
                                      java.util.List<ScheduledTaskProvider> scheduledProviders) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down gateway CLI (timeout={}ms)...", shutdownTimeoutMs);
            System.setProperty("diatom.gateway.status", "SHUTTING_DOWN");
            notifyWorkersShutdown(registry, instanceId);
            try {
                Thread.sleep(Math.min(shutdownTimeoutMs, 10000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            unregisterScheduledProviders(scheduledProviders);
            registry.shutdown();
            httpServer.stop(1);
            running = false;
            logger.info("Gateway CLI shutdown complete");
        }));

        // Check for suspended tasks from previous session
        List<TaskState> suspendedTasks = taskManager.getTasksByStatus(TaskStatus.SUSPENDED);
        if (!suspendedTasks.isEmpty()) {
            System.out.println("  [Checkpoint] Found " + suspendedTasks.size()
                    + " suspended tasks from previous session.");
            System.out.println("  Type 'resume' to view and restore them.");
        }

        System.out.println("Gateway CLI mode (instance=" + instanceId + "). "
                + "Commands: 'exit'/'quit' to stop, 'workers' to list workers, "
                + "'status' for runtime state, 'dashboard' for TUI.");
        startCliLoop(gatewayAgent, capabilityRouter, registry, taskManager, instanceId,
                pipelineOrchestrator, analysisExecPipeline, httpServer);
    }

    private static volatile boolean running = true;
    private static final Set<String> autoApproveWorkers = new HashSet<>();

    /**
     * CLI 交互循环
     * 用户输入 → GatewayAgent 分析 → CapabilityRouter 路由 → 转发到 Worker → 打印响应
     */
    private static void startCliLoop(GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                                     WorkerRegistry registry, TaskManager taskManager,
                                     String instanceId,
                                     PipelineOrchestrator pipelineOrchestrator,
                                     Pipeline analysisExecPipeline,
                                     GatewayHttpServer httpServer) {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .name("diatom-gateway")
                    .build();
            // Configure history file for JLine (same as TerminalUI)
            Path historyFile = InstallPaths.getInstallHome().resolve("history");
            try {
                Files.createDirectories(historyFile.getParent());
            } catch (IOException e) {
                logger.warn("Failed to create history directory", e);
            }

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, historyFile.toFile())
                    .option(LineReader.Option.BRACKETED_PASTE, true)
                    .build();

            // Paste collapse: display large pastes as [N chars] placeholder
            final java.util.concurrent.atomic.AtomicReference<String> collapsedPasteBlock = new java.util.concurrent.atomic.AtomicReference<>(null);
            final int PASTE_COLLAPSE_THRESHOLD = 200;
            java.util.Map<String, org.jline.reader.Widget> widgetMap = reader.getWidgets();

            // Override self-insert to detect large pastes
            org.jline.reader.Widget defaultSelfInsert = widgetMap.get(org.jline.reader.LineReader.SELF_INSERT);
            if (defaultSelfInsert != null) {
                widgetMap.put(org.jline.reader.LineReader.SELF_INSERT, () -> {
                    String block = collapsedPasteBlock.get();
                    if (block != null) {
                        // Already collapsed: let char insert, then capture into stored content
                        defaultSelfInsert.apply();
                        String buf = reader.getBuffer().toString();
                        String prefix = "[" + block.length() + " chars]";
                        if (buf.startsWith(prefix) && buf.length() > prefix.length()) {
                            block += buf.substring(prefix.length());
                            collapsedPasteBlock.set(block);
                        }
                        String newPlaceholder = "[" + block.length() + " chars]";
                        reader.getBuffer().clear();
                        reader.getBuffer().write(newPlaceholder);
                        return true;
                    }
                    // Normal: insert char, then check threshold
                    boolean result = defaultSelfInsert.apply();
                    String buf = reader.getBuffer().toString();
                    if (buf.length() > PASTE_COLLAPSE_THRESHOLD) {
                        collapsedPasteBlock.set(buf);
                        reader.getBuffer().clear();
                        reader.getBuffer().write("[" + buf.length() + " chars]");
                        return true;
                    }
                    return result;
                });
            }

            // Override backward-delete-char to clear collapsed paste
            org.jline.reader.Widget defaultBackwardDelete = widgetMap.get(org.jline.reader.LineReader.BACKWARD_DELETE_CHAR);
            if (defaultBackwardDelete != null) {
                widgetMap.put(org.jline.reader.LineReader.BACKWARD_DELETE_CHAR, () -> {
                    String buf = reader.getBuffer().toString();
                    String block = collapsedPasteBlock.get();
                    if (block != null && buf != null && buf.matches("\\[\\d+ chars\\]")) {
                        collapsedPasteBlock.set(null);
                        reader.getBuffer().clear();
                        return true;
                    }
                    if (buf.length() > PASTE_COLLAPSE_THRESHOLD) {
                        reader.getBuffer().clear();
                        return true;
                    }
                    return defaultBackwardDelete.apply();
                });
            }

            // Override accept-line to restore full text before submission
            org.jline.reader.Widget defaultAcceptLine = widgetMap.get(org.jline.reader.LineReader.ACCEPT_LINE);
            if (defaultAcceptLine != null) {
                widgetMap.put(org.jline.reader.LineReader.ACCEPT_LINE, () -> {
                    String buf = reader.getBuffer().toString();
                    String block = collapsedPasteBlock.get();
                    if (block != null && buf != null && buf.matches("\\[\\d+ chars\\]")) {
                        collapsedPasteBlock.set(null);
                        reader.getBuffer().clear();
                        reader.getBuffer().write(block);
                    }
                    return defaultAcceptLine.apply();
                });
            }

            while (running) {
                String input;
                try {
                    input = reader.readLine("> ");
                } catch (UserInterruptException e) {
                    Thread.interrupted();
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }
                if (input == null) break;

                // Collapse long pasted content display
                if (input.length() > PASTE_COLLAPSE_THRESHOLD) {
                    try {
                        int termWidth = terminal.getWidth();
                        if (termWidth <= 0) termWidth = 80;
                        int lines = (2 + input.length() + termWidth - 1) / termWidth;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < lines; i++) {
                            sb.append("\033[A\033[2K\r");
                        }
                        sb.append("> [.... ").append(input.length()).append(" chars]\n");
                        terminal.writer().print(sb.toString());
                        terminal.writer().flush();
                    } catch (Exception ignored) {
                    }
                }

                input = input.trim();
                if (input.isEmpty()) continue;

                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                    System.out.println("Exiting gateway...");
                    break;
                }

                if ("workers".equalsIgnoreCase(input)) {
                    listWorkers(registry, capabilityRouter);
                    continue;
                }

                if ("status".equalsIgnoreCase(input)) {
                    printGatewayStatus(registry, capabilityRouter, httpServer, taskManager);
                    continue;
                }

                if ("workspaces".equalsIgnoreCase(input)) {
                    listWorkspacesOnDisk();
                    continue;
                }

                if ("dashboard".equalsIgnoreCase(input)) {
                    enterDashboardMode(terminal, registry, taskManager, instanceId);
                    continue;
                }

                if ("resume".equalsIgnoreCase(input) || input.startsWith("resume ")) {
                    handleResumeCommand(registry, taskManager, httpServer, input);
                    continue;
                }

                // Check if input starts with a known worker name (e.g. "worker01 config list")
                int firstSpace = input.indexOf(' ');
                if (firstSpace > 0) {
                    String potentialWorker = input.substring(0, firstSpace);
                    WorkerInfo worker = registry.getWorker(potentialWorker);
                    if (worker != null) {
                        String command = input.substring(firstSpace + 1).trim();
                        if (!command.isEmpty()) {
                            proxyCommandToWorker(worker, command);
                            continue;
                        }
                    }
                }

                // Check for collaborative task command
                if (input.startsWith("collab ") && input.length() > 7) {
                    String collabMessage = input.substring(7).trim();
                    if (!collabMessage.isEmpty()) {
                        handleCollabCommand(gatewayAgent, capabilityRouter, registry,
                                taskManager, collabMessage);
                        continue;
                    }
                }

                // Check for pipeline command (manual override)
                if (input.startsWith("pipeline ") && input.length() > 9) {
                    String pipelineMessage = input.substring(9).trim();
                    if (!pipelineMessage.isEmpty()) {
                        handlePipelineCommand(pipelineOrchestrator, analysisExecPipeline,
                                pipelineMessage);
                        continue;
                    }
                }

                // Check for reduce flag: "-r message" or "--reduce message"
                boolean reduceMode = false;
                String actualInput = input;
                if (input.startsWith("--reduce ") && input.length() > 9) {
                    reduceMode = true;
                    actualInput = input.substring(9).trim();
                } else if (input.startsWith("-r ") && input.length() > 3) {
                    reduceMode = true;
                    actualInput = input.substring(3).trim();
                }

                if (!actualInput.isEmpty() && reduceMode) {
                    handleCollabCommand(gatewayAgent, capabilityRouter, registry,
                            taskManager, actualInput);
                    continue;
                }

                // Auto-detect: pipeline or single worker (uses single analyzeRequest call internally)
                handleUserInput(gatewayAgent, capabilityRouter, input,
                        pipelineOrchestrator, analysisExecPipeline, registry, httpServer);
            }
        } catch (IOException e) {
            logger.error("Failed to initialize terminal", e);
        }

        logger.info("CLI loop ended");
    }

    /**
     * 处理单条用户输入。
     * 自动判断走 pipeline 还是单 Worker 路由（一次 analyzeRequest 决定）。
     */
    private static void handleUserInput(GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                                        String input,
                                        PipelineOrchestrator pipelineOrchestrator,
                                        Pipeline analysisExecPipeline,
                                        WorkerRegistry registry,
                                        GatewayHttpServer httpServer) {
        String workerId = null;
        String taskId = null;
        try {
            // 1. Create task for monitoring
            taskId = httpServer != null && httpServer.getTaskManager() != null
                    ? httpServer.getTaskManager().createTask("cli", input)
                    : null;

            // 2. Analyze (single LLM call — decides task features + pipeline recommendation)
            TaskRequirement req = gatewayAgent.analyzeRequest(input);
            logger.debug("Request analysis: type={}, complexity={}, pipeline={}, capabilities={}",
                    req.getTaskType(), req.getComplexity(), req.isPipelineRecommended(),
                    req.getRequiredCapabilities());

            // 3. Auto-detect: pipeline recommended and diverse workers available
            if (req.isPipelineRecommended() && hasDiverseWorkers(registry)) {
                if (taskId != null) httpServer.getTaskManager().startTask(taskId);
                System.out.println("  [Pipeline] Detected complex task, using multi-model pipeline.");
                handlePipelineCommand(pipelineOrchestrator, analysisExecPipeline, input);
                if (taskId != null) httpServer.getTaskManager().completeTask(taskId);
                return;
            }

            // 4. Route to single worker
            WorkerInfo worker = capabilityRouter.routeWithLLMSuggestion(req);
            if (worker == null) {
                System.out.println("  [Error] No available workers. Start a worker instance first.");
                if (taskId != null) httpServer.getTaskManager().failTask(taskId, "No available workers");
                return;
            }
            workerId = worker.getWorkerId();
            capabilityRouter.incrementActive(workerId);
            if (taskId != null) {
                httpServer.getTaskManager().assignTask(taskId, workerId);
            }

            System.out.println("  [Route] " + worker.getWorkerId()
                    + " (" + worker.getModel() + " @ " + worker.getBaseUrl() + ")");

            // 5. Forward to worker with confirmation support
            String fullResponse = postToWorkerWithConfirm(worker, input, taskId, httpServer);

            // 6. Display audit info from workerMeta
            String responseText = GatewayJsonUtil.extractJsonValue(fullResponse, "response");
            if (responseText == null) {
                responseText = fullResponse;
            }
            displayWorkerMeta(worker.getWorkerId(), fullResponse);

            // 7. Print response
            System.out.println(responseText != null ? responseText : fullResponse);

            // 8. Mark task complete
            if (taskId != null) httpServer.getTaskManager().completeTask(taskId);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Gateway routing model unavailable")) {
                System.out.println("  [Error] Gateway routing model unavailable. Please check your API configuration.");
            } else {
                System.out.println("  [Error] " + msg);
            }
            logger.error("Failed to process input", e);
            if (taskId != null && httpServer != null && httpServer.getTaskManager() != null) {
                try { httpServer.getTaskManager().failTask(taskId, e.getMessage()); } catch (Exception ignored) {}
            }
        } finally {
            if (workerId != null) {
                capabilityRouter.decrementActive(workerId);
            }
        }
    }

    /**
     * 处理协同任务命令
     * 分析请求 -> 列出 Workers -> 提交协同任务 -> 打印合并结果
     */
    private static void handleCollabCommand(GatewayAgent gatewayAgent, CapabilityRouter capabilityRouter,
                                             WorkerRegistry registry, TaskManager taskManager,
                                             String message) {
        if (!(taskManager instanceof CompositeTaskManager)) {
            System.out.println("  [Error] CompositeTaskManager not available for collaborative tasks.");
            return;
        }
        CompositeTaskManager compositeTaskManager = (CompositeTaskManager) taskManager;

        try {
            // 1. Analyze request
            System.out.println("  [Analyzing] " + message);
            TaskRequirement req = gatewayAgent.analyzeRequest(message);
            System.out.println("  [Type] " + req.getTaskType()
                    + " | capabilities: " + req.getRequiredCapabilities());

            // 2. List available workers
            List<WorkerInfo> available = registry.availableWorkers();
            if (available.isEmpty()) {
                System.out.println("  [Error] No available workers. Start a worker instance first.");
                return;
            }
            System.out.println("  [Workers] " + available.size() + " available:");
            for (WorkerInfo w : available) {
                System.out.println("    - " + w.getWorkerId() + " (" + w.getModel()
                        + ") load=" + w.getMetrics().getCurrentLoad()
                        + " capabilities=" + w.getCapabilities().keySet());
            }

            // 3. Submit collaborative task
            System.out.println("  [Submitting] collaborative task...");
            String result = compositeTaskManager.submitCollaborativeTask("cli-session", message, req);

            // 4. Print merged result (prettify JSON)
            System.out.println("  [Result]");
            System.out.println(GatewayJsonUtil.formatCollabResult(result));
        } catch (Exception e) {
            System.out.println("  [Error] Collaborative task failed: " + e.getMessage());
            logger.error("Collaborative task failed", e);
        }
    }

    /**
     * 处理流水线命令。
     * 按顺序通过多个 Worker 处理请求（分析→执行），
     * 前一阶段的输出作为下一阶段的输入。
     */
    private static void handlePipelineCommand(PipelineOrchestrator orchestrator,
                                               Pipeline pipeline, String message) {
        try {
            System.out.println("  [Pipeline] Executing '" + pipeline.getName()
                    + "' with " + pipeline.getStages().size() + " stages...");
            System.out.println("  [Input] " + message);

            PipelineOrchestrator.PipelineResult result = orchestrator.execute(pipeline, message);

            System.out.println(result.format());

            // If there are completed stages, show the final response in full
            String lastResponse = result.getLastResponse();
            if (lastResponse != null) {
                System.out.println("\n  [Final Response]");
                System.out.println(lastResponse);
            }

            if (!result.isSuccess()) {
                System.out.println("  [Warning] Some stages failed. Check output above.");
            }
        } catch (Exception e) {
            System.out.println("  [Error] Pipeline execution failed: " + e.getMessage());
            logger.error("Pipeline execution failed", e);
        }
    }

    /**
     * 处理 resume 命令
     * resume              → 列出所有 SUSPENDED 任务
     * resume <taskId>     → 执行恢复
     */
    private static void handleResumeCommand(WorkerRegistry registry, TaskManager taskManager,
                                            GatewayHttpServer httpServer, String input) {
        String taskId = input.length() > 7 ? input.substring(7).trim() : "";
        try {
            if (taskId.isEmpty()) {
                // List all suspended tasks
                List<TaskState> suspended = taskManager.getTasksByStatus(TaskStatus.SUSPENDED);
                if (suspended.isEmpty()) {
                    System.out.println("  [Checkpoint] No suspended tasks found.");
                    return;
                }
                System.out.println("  Suspended tasks:");
                int idx = 1;
                for (TaskState state : suspended) {
                    String req = state.getOriginalRequest();
                    if (req != null && req.length() > 50) req = req.substring(0, 50) + "...";
                    System.out.println("    #" + (idx++) + "  " + state.getTaskId()
                            + "  \"" + (req != null ? req : "?") + "\""
                            + "  step=" + state.getCheckpointStep()
                            + "  worker=" + (state.getWorkerId() != null ? state.getWorkerId() : "?"));
                }
                return;
            }

            // Resume specific task
            TaskState taskState = taskManager.getTask(taskId);
            if (taskState == null) {
                System.out.println("  [Error] Task not found: " + taskId);
                return;
            }
            if (taskState.getStatus() != TaskStatus.SUSPENDED) {
                System.out.println("  [Error] Task " + taskId + " is not suspended (status="
                        + taskState.getStatus() + ")");
                return;
            }

            // Route to an available worker
            List<WorkerInfo> available = registry.availableWorkers();
            if (available.isEmpty()) {
                System.out.println("  [Error] No available workers. Start a worker instance first.");
                return;
            }
            WorkerInfo worker = available.get(0);
            System.out.println("  [Resume] Routing task " + taskId + " to worker "
                    + worker.getWorkerId());

            // Build resume request
            ResumeRequestPayload resumePayload = new ResumeRequestPayload();
            resumePayload.taskId = taskId;
            resumePayload.checkpointStep = taskState.getCheckpointStep();
            resumePayload.originalRequest = taskState.getOriginalRequest();

            // Load checkpoint data from Gateway's CheckpointService
            if (httpServer != null) {
                String checkpointData = httpServer.getCheckpointService().loadFullCheckpoint(taskId);
                if (checkpointData != null) {
                    resumePayload.conversationHistory = GatewayJsonUtil.extractFullJsonValue(checkpointData, "conversationHistory");
                    resumePayload.agentState = GatewayJsonUtil.extractJsonValue(checkpointData, "agentState");
                }
            }
            String json = JsonUtils.toJson(resumePayload);

            // POST to worker
            String targetUrl = "http://" + worker.getHost() + ":" + worker.getPort() + "/worker/v1/resume";
            logger.info("Resuming task {} via worker {} at {}", taskId, worker.getWorkerId(), targetUrl);
            HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(600000);
            byte[] requestBytes = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBytes);
            }
            int code = conn.getResponseCode();
            String response = GatewayJsonUtil.readConnectionBody(conn, code);
            conn.disconnect();

            if (code == 200) {
                String responseText = GatewayJsonUtil.extractJsonValue(response, "response");
                if (responseText != null) {
                    System.out.println("  [Resume] Task resumed successfully:");
                    System.out.println(responseText);
                } else {
                    System.out.println("  [Resume] Task resumed: " + response);
                }
                taskManager.completeTask(taskId);
            } else {
                String errMsg = GatewayJsonUtil.extractJsonValue(response, "error");
                System.out.println("  [Error] Resume failed (HTTP " + code + "): "
                        + (errMsg != null ? errMsg : response));
            }
        } catch (Exception e) {
            System.out.println("  [Error] Resume failed: " + e.getMessage());
            logger.error("Failed to resume task", e);
        }
    }

    /**
     * Enter the real-time terminal dashboard.
     * Blocks until user presses 'q'.
     */
    private static void enterDashboardMode(Terminal terminal, WorkerRegistry registry,
                                            TaskManager taskManager, String instanceId) {
        TerminalDashboardRenderer renderer = new TerminalDashboardRenderer(
                terminal, registry, taskManager, instanceId);
        renderer.enterDashboard();
        // After exit, refresh the prompt display
        terminal.writer().println();
        terminal.writer().println("Exited dashboard. Type 'dashboard' to re-enter.");
    }

    /**
     * 检查是否存在不同类型的 Worker（高成本 + 低成本），
     * 满足流水线多阶段 Worker 选择条件。
     */
    private static boolean hasDiverseWorkers(WorkerRegistry registry) {
        List<WorkerInfo> workers = registry.availableWorkers();
        if (workers.size() < 2) return false;

        boolean hasHighCost = false;
        boolean hasLowCost = false;
        for (WorkerInfo w : workers) {
            if (w.getCostPer1kTokens() >= 0.01) {
                hasHighCost = true;
            } else {
                hasLowCost = true;
            }
        }
        return hasHighCost && hasLowCost;
    }

    /**
     * 解析 --gateway-url / -u 参数，返回上游 Gateway URL 或 null。
     */
    /**
     * Normalize gateway URL: auto-prepend http:// if protocol is missing.
     */
    /**
     * 检查 URL 是否指向自己（防止自注册循环）。
     * 匹配规则：localhost/127.0.0.1 + 当前端口，或本机 IP + 当前端口。
     */

    /**
     * 将当前 Gateway 注册为上游 Gateway 的一个 Worker。
     * 写入注册文件到本地的 registry 目录，上游 Gateway 的 FileSystemWorkerRegistry 会自动发现。
     */
    private static void registerToUpstreamGateway(String instanceId, int port,
                                                   AppConfig config, String upstreamUrl) {
        try {
            Path registryDir = InstallPaths.getGatewayRegistryDir();
            Files.createDirectories(registryDir);
            Path regFile = registryDir.resolve(instanceId + ".json");

            String model = config.getModel();
            if (model == null || model.isEmpty()) {
                model = System.getProperty("api.model", "");
            }
            String tier = System.getProperty("diatom.tier", "gateway-proxy");
            String authToken = System.getProperty("diatom.auth.token", "");
            double costPer1kTokens = 0.01;
            try {
                costPer1kTokens = Double.parseDouble(System.getProperty("diatom.cost.per1k", "0.01"));
            } catch (NumberFormatException ignored) {}

            String localHost = com.github.obhen233.util.NetworkUtils.getRealLocalIP();
            boolean useSsl = "true".equalsIgnoreCase(System.getProperty("diatom.ssl.enabled", "false"));

            String gatewayProfile = loadGatewaySelfSkillFile();

            Map<String, Object> regMap = new java.util.LinkedHashMap<>();
            regMap.put("workerId", instanceId);
            regMap.put("host", localHost);
            regMap.put("port", port);
            regMap.put("model", model);
            regMap.put("traits", java.util.Collections.emptyList());
            regMap.put("capabilities", java.util.Collections.emptyMap());
            regMap.put("tier", tier);
            regMap.put("costPer1kTokens", costPer1kTokens);
            regMap.put("maxConcurrency", 5);
            if (authToken != null && !authToken.isEmpty()) {
                regMap.put("authToken", authToken);
            }
            regMap.put("group", "");
            regMap.put("useSsl", useSsl);
            regMap.put("status", "ONLINE");
            regMap.put("lastHeartbeat", System.currentTimeMillis());
            regMap.put("currentLoad", 0.0);
            regMap.put("pid", getPid());
            regMap.put("registeredAt", System.currentTimeMillis());
            if (gatewayProfile != null && !gatewayProfile.isEmpty()) {
                regMap.put("gatewayProfile", gatewayProfile);
            }
            String content = JsonUtils.toJson(regMap);
            Files.write(regFile, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Gateway registered as worker to upstream: {} -> {}", upstreamUrl, regFile);
        } catch (Exception e) {
            logger.warn("Failed to register to upstream Gateway: {}", e.getMessage());
        }
    }

    /**
     * 启动上游 Gateway 的心跳更新。
     */
    private static volatile ScheduledExecutorService heartbeatScheduler;

    static void stopUpstreamHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
            heartbeatScheduler = null;
        }
    }

    private static void startUpstreamHeartbeat(String instanceId, String upstreamUrl) {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(
            () -> {
                updateUpstreamHeartbeat(instanceId);
                httpHeartbeatToUpstream(upstreamUrl, instanceId);
            },
            0, 10, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopUpstreamHeartbeat();
            removeUpstreamRegistration(instanceId);
            httpDeregisterFromUpstream(upstreamUrl, instanceId);
            logger.info("Gateway deregistered from upstream: {}", upstreamUrl);
        }));
    }

    private static void updateUpstreamHeartbeat(String instanceId) {
        try {
            Path regFile = InstallPaths.getGatewayRegistryDir().resolve(instanceId + ".json");
            if (Files.exists(regFile)) {
                String content = new String(Files.readAllBytes(regFile), StandardCharsets.UTF_8);
                content = content.replaceAll("\"lastHeartbeat\":\\s*\\d+",
                    "\"lastHeartbeat\":" + System.currentTimeMillis());
                Files.write(regFile, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Exception e) {
            logger.warn("Failed to update upstream heartbeat: {}", e.getMessage());
        }
    }

    private static void removeUpstreamRegistration(String instanceId) {
        try {
            Path regFile = InstallPaths.getGatewayRegistryDir().resolve(instanceId + ".json");
            Files.deleteIfExists(regFile);
            logger.info("Gateway registration removed: {}", regFile);
        } catch (Exception e) {
            logger.warn("Failed to remove upstream registration: {}", e.getMessage());
        }
    }

    /**
     * HTTP POST to register with the upstream Gateway.
     * Non-fatal: logs warning on failure but does not prevent startup.
     */
    private static boolean httpRegisterToUpstream(String upstreamUrl, String instanceId, int port,
                                                   AppConfig config) {
        String url = upstreamUrl + "/gateway/v1/workers";
        logger.info("HTTP registering gateway {} to upstream: {}", instanceId, url);
        try {
            String model = config.getModel();
            if (model == null || model.isEmpty()) {
                model = System.getProperty("api.model", "");
            }
            String tier = System.getProperty("diatom.tier", "gateway-proxy");
            String authToken = System.getProperty("diatom.auth.token", "");
            double costPer1kTokens = 0.01;
            try {
                costPer1kTokens = Double.parseDouble(System.getProperty("diatom.cost.per1k", "0.01"));
            } catch (NumberFormatException ignored) {}

            String localHost = com.github.obhen233.util.NetworkUtils.getRealLocalIP();
            boolean useSsl = "true".equalsIgnoreCase(System.getProperty("diatom.ssl.enabled", "false"));

            String gatewayProfile = loadGatewaySelfSkillFile();

            Map<String, Object> regMap = new java.util.LinkedHashMap<>();
            regMap.put("workerId", instanceId);
            regMap.put("host", localHost);
            regMap.put("port", port);
            regMap.put("model", model);
            regMap.put("traits", java.util.Collections.emptyList());
            regMap.put("capabilities", java.util.Collections.emptyMap());
            regMap.put("tier", tier);
            regMap.put("costPer1kTokens", costPer1kTokens);
            regMap.put("maxConcurrency", 5);
            if (authToken != null && !authToken.isEmpty()) {
                regMap.put("authToken", authToken);
            }
            regMap.put("group", "");
            regMap.put("useSsl", useSsl);
            regMap.put("status", "ONLINE");
            regMap.put("lastHeartbeat", System.currentTimeMillis());
            regMap.put("currentLoad", 0.0);
            regMap.put("pid", getPid());
            regMap.put("registeredAt", System.currentTimeMillis());
            if (gatewayProfile != null && !gatewayProfile.isEmpty()) {
                regMap.put("gatewayProfile", gatewayProfile);
            }
            String json = JsonUtils.toJson(regMap);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (!authToken.isEmpty()) {
                conn.setRequestProperty("X-Diatom-Auth", authToken);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                logger.info("HTTP registration successful for gateway {} at {}", instanceId, url);
            } else {
                logger.warn("HTTP registration returned HTTP {} for gateway {}", code, instanceId);
            }
            conn.disconnect();
            return code == 200;
        } catch (java.net.ConnectException e) {
            logger.warn("Upstream Gateway not reachable for HTTP registration ({}): {}", url, e.getMessage());
        } catch (Exception e) {
            logger.warn("Failed to HTTP register gateway ({}): {}", url, e.getMessage());
        }
        return false;
    }

    /**
     * HTTP PUT to send heartbeat to the upstream Gateway.
     */
    private static void httpHeartbeatToUpstream(String upstreamUrl, String instanceId) {
        String url = upstreamUrl + "/gateway/v1/workers/" + instanceId + "/heartbeat";
        String json = "{\"currentLoad\":0.0,\"activeTasks\":0}";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            String authToken = System.getProperty("diatom.auth.token", "");
            if (!authToken.isEmpty()) {
                conn.setRequestProperty("X-Diatom-Auth", authToken);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                logger.debug("HTTP heartbeat returned HTTP {} for gateway {}", code, instanceId);
            }
            conn.disconnect();
        } catch (java.net.ConnectException e) {
            logger.debug("Upstream Gateway not reachable for heartbeat ({}): {}", url, e.getMessage());
        } catch (Exception e) {
            logger.debug("Failed to send HTTP heartbeat ({}): {}", url, e.getMessage());
        }
    }

    /**
     * HTTP DELETE to deregister from the upstream Gateway.
     */
    private static void httpDeregisterFromUpstream(String upstreamUrl, String instanceId) {
        String url = upstreamUrl + "/gateway/v1/workers/" + instanceId;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String authToken = System.getProperty("diatom.auth.token", "");
            if (!authToken.isEmpty()) {
                conn.setRequestProperty("X-Diatom-Auth", authToken);
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200) {
                logger.info("HTTP deregistration successful for gateway {}", instanceId);
            } else {
                logger.warn("HTTP deregistration returned HTTP {} for gateway {}", code, instanceId);
            }
        } catch (Exception e) {
            logger.warn("Failed to HTTP deregister gateway: {}", e.getMessage());
        }
    }

    /**
     * 从 URL 中提取 host（去掉端口）。
     */
    private static String extractHostFromUrl(String url) {
        try {
            String tmp = url;
            if (tmp.startsWith("http://")) tmp = tmp.substring(7);
            else if (tmp.startsWith("https://")) tmp = tmp.substring(8);
            int colon = tmp.indexOf(':');
            if (colon > 0) tmp = tmp.substring(0, colon);
            return tmp;
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static long getPid() {
        try {
            return Long.parseLong(java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 列出所有可用 Worker
     */
    private static void listWorkers(WorkerRegistry registry, CapabilityRouter capabilityRouter) {
        List<WorkerInfo> workers = registry.availableWorkers();
        if (workers.isEmpty()) {
            System.out.println("  No workers available.");
            return;
        }
        System.out.println("  Available workers (" + workers.size() + "):");
        for (WorkerInfo w : workers) {
            String groupRaw = w.getGroup();
            String groupStr = groupRaw != null && !groupRaw.isEmpty()
                    ? groupRaw.replaceAll("[\\x00-\\x1f\\x7f]", "_") : "-";
            String wsPath = resolveWorkerWorkspacePath(w);
            int gwActive = capabilityRouter != null ? capabilityRouter.getActiveRequests(w.getWorkerId()) : 0;
            int maxConc = w.getMaxConcurrency() > 0 ? w.getMaxConcurrency() : 5;
            logger.debug("Worker {} group raw='{}' (null={}, empty={})",
                    w.getWorkerId(), groupRaw, groupRaw == null, groupRaw != null && groupRaw.isEmpty());
            System.out.println("    " + w.getWorkerId() + " | " + w.getModel()
                    + " | group=" + groupStr
                    + " | load=" + w.getMetrics().getCurrentLoad()
                    + " | gw-req=" + gwActive + "/" + maxConc
                    + " | workspace=" + wsPath
                    + " | " + w.getBaseUrl());
        }
    }

    /**
     * 打印 Gateway 运行时状态（含并发信息）
     */
    private static void printGatewayStatus(WorkerRegistry registry, CapabilityRouter capabilityRouter,
                                           GatewayHttpServer httpServer, TaskManager taskManager) {
        List<WorkerInfo> workers = registry != null ? registry.availableWorkers() : null;
        int activeTasks = taskManager != null ? taskManager.getActiveTaskCount() : 0;
        int totalTasks = taskManager != null ? taskManager.getTotalTaskCount() : 0;

        System.out.println("  Gateway Runtime Status:");
        System.out.println("    Workers: " + (workers != null ? workers.size() : 0) + " online");
        System.out.println("    Tasks: " + activeTasks + " active, " + totalTasks + " total");
        System.out.println("    Routing Queue: " + capabilityRouter.getQueueSize() + " pending");
        if (httpServer != null && httpServer.isQueueEnabled()) {
            System.out.println("    Task Queue: " + httpServer.getQueueDepth() + " depth (async mode)");
        }

        // Per-worker active requests
        if (workers != null && !workers.isEmpty()) {
            System.out.println("    Worker Load:");
            for (WorkerInfo w : workers) {
                int gwActive = capabilityRouter != null ? capabilityRouter.getActiveRequests(w.getWorkerId()) : 0;
                int maxConc = w.getMaxConcurrency() > 0 ? w.getMaxConcurrency() : 5;
                System.out.println("      " + w.getWorkerId()
                        + ": gw-req=" + gwActive + "/" + maxConc
                        + " | load=" + String.format("%.1f", w.getMetrics().getCurrentLoad() * 100) + "%"
                        + " | model=" + w.getModel());
            }
        }
    }

    /**
     * 解析 Worker 对应的工作空间路径。
     * 优先使用 worker 上报的真实 workspace（可能通过 -Dworkspace.dir 自定义），
     * 兜底使用 gateway 自身的 workspace。
     */
    private static String resolveWorkerWorkspacePath(WorkerInfo worker) {
        if (worker == null) {
            return System.getProperty("diatom.workspace.dir",
                    System.getProperty("diatom.original.user.dir",
                            System.getProperty("user.dir")));
        }
        // Worker 上报了自己的 workspace → 直接使用
        String reported = worker.getWorkspace();
        if (reported != null && !reported.isEmpty()) {
            return reported;
        }
        // 未上报 → 按 group 计算
        String workspaceDir = System.getProperty("diatom.workspace.dir",
                System.getProperty("diatom.original.user.dir",
                        System.getProperty("user.dir")));
        String group = worker.getGroup();
        if (group == null || group.isEmpty()) {
            return workspaceDir;
        }
        java.nio.file.Path baseDir = java.nio.file.Paths.get(workspaceDir, "gateway-collab");
        return baseDir.resolve("group-" + group.replaceAll("[^a-zA-Z0-9._-]", "_")).toString();
    }

    /**
     * 列出磁盘上实际存在的工作空间目录
     */
    private static void listWorkspacesOnDisk() {
        String workspaceDir = System.getProperty("workspace.dir",
                System.getProperty("user.home") + "/.diatom/workspaces");
        java.nio.file.Path collabDir = java.nio.file.Paths.get(workspaceDir, "gateway-collab");
        if (!java.nio.file.Files.exists(collabDir)) {
            System.out.println("  No workspace directories found at: " + collabDir);
            return;
        }
        try {
            java.util.List<java.nio.file.Path> dirs = new java.util.ArrayList<>();
            try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                     java.nio.file.Files.newDirectoryStream(collabDir)) {
                for (java.nio.file.Path entry : stream) {
                    if (java.nio.file.Files.isDirectory(entry)) {
                        dirs.add(entry);
                    }
                }
            }
            if (dirs.isEmpty()) {
                System.out.println("  No workspace directories found at: " + collabDir);
                return;
            }
            System.out.println("  Workspace directories (" + dirs.size() + "):");
            for (java.nio.file.Path dir : dirs) {
                System.out.println("    " + dir.toString());
            }
        } catch (Exception e) {
            System.out.println("  [Error] Failed to list workspaces: " + e.getMessage());
        }
    }

    /**
     * 发送请求到 Worker 并返回响应文本
     */
    private static String postToWorker(WorkerInfo worker, String message, String taskId) {
        try {
            URL url = new URL(worker.getBaseUrl() + "/worker/v1/chat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(600000);

            // Inject security headers
            SecurityHeadersInjector injector = new SecurityHeadersInjector(
                    SecurityProviderLoader.getAuthProvider(),
                    SecurityProviderLoader.getEncryptionProvider());
            injector.injectIntoConnection(conn, worker.getWorkerId());

            ChatRequestPayload chatPayload = new ChatRequestPayload();
            chatPayload.taskId = taskId != null ? taskId : "";
            chatPayload.message = message;
            String requestBody = JsonUtils.toJson(chatPayload);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String responseBody = GatewayJsonUtil.readConnectionBody(conn, code);
            conn.disconnect();

            if (code != 200) {
                return "[Worker error " + code + "] " + GatewayJsonUtil.truncate(responseBody, 500);
            }

            // Extract 'response' field from worker JSON
            String response = GatewayJsonUtil.extractJsonValue(responseBody, "response");
            return response != null ? response : responseBody;
        } catch (Exception e) {
            return "[Request failed] " + e.getMessage();
        }
    }

    /**
     * Send request to Worker and return the full raw JSON response body.
     */
    private static String postToWorkerRaw(WorkerInfo worker, String message, String taskId) {
        try {
            URL url = new URL(worker.getBaseUrl() + "/worker/v1/chat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(600000);

            SecurityHeadersInjector injector = new SecurityHeadersInjector(
                    SecurityProviderLoader.getAuthProvider(),
                    SecurityProviderLoader.getEncryptionProvider());
            injector.injectIntoConnection(conn, worker.getWorkerId());

            ChatRequestPayload chatPayload = new ChatRequestPayload();
            chatPayload.taskId = taskId != null ? taskId : "";
            chatPayload.message = message;
            String requestBody = JsonUtils.toJson(chatPayload);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String responseBody = GatewayJsonUtil.readConnectionBody(conn, code);
            conn.disconnect();

            if (code != 200) {
                Map<String, String> errMap = new HashMap<>();
                errMap.put("status", "error");
                errMap.put("error", "[Worker error " + code + "] " + GatewayJsonUtil.truncate(responseBody, 500));
                return JsonUtils.toJson(errMap);
            }

            return responseBody;
        } catch (Exception e) {
            Map<String, String> errMap = new HashMap<>();
            errMap.put("status", "error");
            errMap.put("error", "[Request failed] " + e.getMessage());
            return JsonUtils.toJson(errMap);
        }
    }

    /**
     * Send request to Worker with confirmation support.
     * Runs the HTTP request in a background thread and polls for pending
     * confirmation requests from the GatewayHttpServer while waiting.
     */
    private static String postToWorkerWithConfirm(WorkerInfo worker, String message,
                                                   String taskId, GatewayHttpServer httpServer) {
        if (httpServer == null) {
            // Fallback to synchronous call if no httpServer (should not happen in CLI mode)
            return postToWorkerRaw(worker, message, taskId);
        }

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                postToWorkerRaw(worker, message, taskId));

        while (!future.isDone()) {
            // Poll for pending confirmation requests from Worker
            GatewayConfirmHandler.PendingConfirmRequest pendingReq = httpServer.pollPendingConfirm();
            if (pendingReq != null) {
                handleWorkerConfirmRequest(pendingReq, httpServer);
            }

            // Short sleep before polling again
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        try {
            return future.get();
        } catch (Exception e) {
            Map<String, String> errMap = new HashMap<>();
            errMap.put("status", "error");
            errMap.put("error", "[Request failed] " + e.getMessage());
            return JsonUtils.toJson(errMap);
        }
    }

    /**
     * Handle a pending confirmation request from a Worker.
     * Shows the confirmation prompt to the user and resolves the request.
     */
    private static void handleWorkerConfirmRequest(
            GatewayConfirmHandler.PendingConfirmRequest pendingReq,
            GatewayHttpServer httpServer) {

        System.out.println();
        System.out.println("  [Confirmation Required from " + pendingReq.workerId + "]");
        System.out.println("    Tool: " + pendingReq.toolName);
        System.out.println("    Action: " + pendingReq.action);
        if (pendingReq.arguments != null && !pendingReq.arguments.isEmpty()) {
            System.out.println("    Arguments: " + pendingReq.arguments);
        }
        System.out.println();

        // Check if this worker is already in auto-approve list
        if (autoApproveWorkers.contains(pendingReq.workerId)) {
            System.out.println("  [Auto-approved] Worker " + pendingReq.workerId + " is in auto-approve list.");
            httpServer.resolveConfirm(pendingReq.requestId, "y");
            return;
        }

        // Check global auto-approve
        if (httpServer.isGlobalAutoApprove()) {
            System.out.println("  [Auto-approved] Global auto-approve is enabled.");
            httpServer.resolveConfirm(pendingReq.requestId, "y");
            return;
        }

        System.out.println("  Options: y (yes) / n (no) / a (always) / aw (always this worker)");
        System.out.println("           s (allow shell) / t (temp allow shell) / c (cancel)");
        System.out.print("  Confirm? [y/n/a/aw/s/t/c]: ");

        String input = readUserInput();
        if (input == null || input.isEmpty()) {
            input = "c";
        }

        String trimmed = input.trim().toLowerCase();
        String decision = "c";

        switch (trimmed) {
            case "y":
                decision = "y";
                break;
            case "n":
                decision = "n";
                break;
            case "a":
                httpServer.setGlobalAutoApprove(true);
                System.out.println("  [Global auto-approve enabled for this session]");
                decision = "y";
                break;
            case "aw":
                autoApproveWorkers.add(pendingReq.workerId);
                System.out.println("  [Auto-approve enabled for worker " + pendingReq.workerId + "]");
                decision = "y";
                break;
            case "s":
                decision = "s";
                break;
            case "t":
                decision = "t";
                break;
            case "c":
            default:
                decision = "c";
                System.out.println("  [Cancelled]");
                break;
        }

        httpServer.resolveConfirm(pendingReq.requestId, decision);
    }

    /**
     * Display worker metadata (audit entries and file changes) from a worker response.
     */
    private static void displayWorkerMeta(String workerId, String responseJson) {
        if (responseJson == null || responseJson.isEmpty()) return;

        // Extract workerMeta object
        String workerMeta = GatewayJsonUtil.extractRawJsonObject(responseJson, "workerMeta");
        if (workerMeta == null) return;

        // Check audit enabled
        boolean auditEnabled = GatewayJsonUtil.extractJsonBoolean(workerMeta, "auditEnabled");
        if (!auditEnabled) return;

        // Display audit entries
        String auditEntries = GatewayJsonUtil.extractRawJsonArray(workerMeta, "auditEntries");
        if (auditEntries != null && auditEntries.length() > 2) {
            // Parse each entry in the array
            String[] entries = GatewayJsonUtil.splitJsonArrayObjects(auditEntries);
            for (String entry : entries) {
                String ts = GatewayJsonUtil.extractJsonValue(entry, "timestamp");
                String op = GatewayJsonUtil.extractJsonValue(entry, "operation");
                String path = GatewayJsonUtil.extractJsonValue(entry, "path");
                if (ts != null && op != null && path != null) {
                    System.out.println("  [" + workerId + "] [" + ts + "] " + op + " " + path);
                }
            }
        }

        // Display file changes
        String fileChanges = GatewayJsonUtil.extractRawJsonObject(workerMeta, "fileChanges");
        if (fileChanges != null) {
            String created = GatewayJsonUtil.extractJsonArrayValues(fileChanges, "created");
            if (created != null && !created.isEmpty()) {
                System.out.println("  [" + workerId + "] Created files: " + created);
            }
            String modified = GatewayJsonUtil.extractJsonArrayValues(fileChanges, "modified");
            if (modified != null && !modified.isEmpty()) {
                System.out.println("  [" + workerId + "] Modified files: " + modified);
            }
            String deleted = GatewayJsonUtil.extractJsonArrayValues(fileChanges, "deleted");
            if (deleted != null && !deleted.isEmpty()) {
                System.out.println("  [" + workerId + "] Deleted files: " + deleted);
            }
        }
    }

    /**
     * Read a single line of user input from the console (blocking).
     */
    private static String readUserInput() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in));
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Proxy a CLI command to a worker via HTTP POST /worker/v1/command.
     * Prints the response to stdout.
     */
    private static void proxyCommandToWorker(WorkerInfo worker, String command) {
        try {
            URL url = new URL(worker.getBaseUrl() + "/worker/v1/command");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            SecurityHeadersInjector injector = new SecurityHeadersInjector(
                    SecurityProviderLoader.getAuthProvider(),
                    SecurityProviderLoader.getEncryptionProvider());
            injector.injectIntoConnection(conn, worker.getWorkerId());

            Map<String, String> cmdMap = new HashMap<>();
            cmdMap.put("command", command);
            String requestBody = JsonUtils.toJson(cmdMap);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String responseBody = GatewayJsonUtil.readConnectionBody(conn, code);
            conn.disconnect();

            if (code == 200) {
                // Extract 'output' or 'error' from response
                String output = GatewayJsonUtil.extractJsonValue(responseBody, "output");
                if (output != null) {
                    System.out.println(output);
                } else {
                    String error = GatewayJsonUtil.extractJsonValue(responseBody, "error");
                    System.out.println("  [Worker Error] " + (error != null ? error : "Unknown error"));
                }
            } else {
                System.out.println("  [Worker HTTP " + code + "] " + GatewayJsonUtil.truncate(responseBody, 500));
            }
        } catch (Exception e) {
            System.out.println("  [Failed to proxy command to " + worker.getWorkerId() + "] " + e.getMessage());
        }
    }


    /**
     * Generate the gateway-self.skill.md file in the JAR-level skills directory.
     * This file describes the gateway's identity to its own LLM and to upstream gateways.
     * Non-fatal: logs warning on failure but does not prevent startup.
     */
    private static void ensureGatewaySelfSkillFile(String instanceId, int port, AppConfig config) {
        try {
            Path skillsDir = InstallPaths.getInstallHome().resolve("skills");
            Files.createDirectories(skillsDir);
            Path skillFile = skillsDir.resolve("gateway-self.skill.md");

            String model = config.getModel();
            if (model == null || model.isEmpty()) {
                model = System.getProperty("api.model", "");
            }
            String tier = System.getProperty("diatom.tier", "gateway-proxy");

            String content =
                "---\n"
                + "name: gateway-self\n"
                + "description: Gateway self-description\n"
                + "version: 1.0.0\n"
                + "---\n"
                + "# Gateway Self-Description\n\n"
                + "- **Instance ID**: " + instanceId + "\n"
                + "- **Port**: " + port + "\n"
                + "- **Model**: " + model + "\n"
                + "- **Tier**: " + tier + "\n"
                + "- **PID**: " + getPid() + "\n";

            Files.write(skillFile, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Gateway self-description skill file generated: {}", skillFile);
        } catch (Exception e) {
            logger.warn("Failed to generate gateway self-description skill file: {}", e.getMessage());
        }
    }

    /**
     * Load the content of the gateway-self.skill.md file.
     * Returns empty string if the file does not exist or cannot be read.
     */
    private static String loadGatewaySelfSkillFile() {
        try {
            Path skillFile = InstallPaths.getInstallHome().resolve("skills").resolve("gateway-self.skill.md");
            if (Files.exists(skillFile)) {
                return new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            logger.debug("Could not load gateway self-description skill file: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 创建 SchedulerCallback 实现，将外部调度系统的任务触发委托给 diatom 的任务执行引擎。
     * SPI 实现方只需调用 callback.submitTask(params)，无需了解内部路由细节。
     */
    private static ScheduledTaskProvider.SchedulerCallback createSchedulerCallback(
            CompositeTaskManager taskManager, GatewayAgent gatewayAgent,
            CapabilityRouter router, DefaultHttpTransport transport, WorkerRegistry registry) {
        return new ScheduledTaskProvider.SchedulerCallback() {
            @Override
            public String submitTask(String params) {
                return submitTask("scheduled", params);
            }

            @Override
            public String submitTask(String sessionId, String params) {
                String taskId = taskManager.createTask(sessionId, params);
                try {
                    TaskRequirement requirement = gatewayAgent.analyzeRequest(params);
                    WorkerInfo worker = router.route(requirement);
                    if (worker == null) {
                        taskManager.failTask(taskId, "No available worker for scheduled task");
                        return taskId;
                    }
                    com.github.obhen233.core.gateway.model.ChatRequest chatReq =
                            new com.github.obhen233.core.gateway.model.ChatRequest();
                    chatReq.setMessage(params);
                    chatReq.setSessionId(sessionId);
                    chatReq.setTaskId(taskId);
                    transport.sendTaskAsync(worker, chatReq, 600000,
                            new com.github.obhen233.core.gateway.transport.TransportCallback() {
                        @Override
                        public void onSuccess(String wid,
                                com.github.obhen233.core.gateway.transport.TransportResponse response) {
                            taskManager.completeTask(taskId);
                        }
                        @Override
                        public void onFailure(String wid, String error) {
                            taskManager.failTask(taskId, error);
                        }
                        @Override
                        public void onTimeout(String wid) {
                            taskManager.failTask(taskId, "Scheduled task timed out");
                        }
                    });
                } catch (Exception e) {
                    taskManager.failTask(taskId, e.getMessage());
                }
                return taskId;
            }

            @Override
            public String getTaskStatus(String taskId) {
                com.github.obhen233.core.gateway.task.TaskState state = taskManager.getTask(taskId);
                return state != null ? state.getStatus().name() : "UNKNOWN";
            }
        };
    }

    /**
     * 注销所有已注册的 ScheduledTaskProvider（关闭时调用）。
     */
    private static void unregisterScheduledProviders(java.util.List<ScheduledTaskProvider> providers) {
        if (providers == null || providers.isEmpty()) return;
        for (ScheduledTaskProvider sp : providers) {
            try {
                sp.unregister();
                logger.info("Scheduled task provider unregistered: {}", sp.getName());
            } catch (Exception e) {
                logger.warn("Failed to unregister scheduled task provider: {}", sp.getName(), e);
            }
        }
    }

    /**
     * 通知所有在线 Worker 切换备用 Gateway
     */
    private static void notifyWorkersShutdown(WorkerRegistry registry, String instanceId) {
        for (WorkerInfo worker : registry.availableWorkers()) {
            try {
                String url = "http://" + worker.getHost() + ":" + worker.getPort()
                        + "/worker/v1/shutdown-notice";
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                String body = "{\"gatewayId\":\"" + instanceId
                        + "\",\"status\":\"SHUTTING_DOWN\"}";
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                logger.debug("Shutdown notice sent to worker {} (response={})",
                        worker.getWorkerId(), code);
            } catch (Exception e) {
                logger.debug("Failed to notify worker {}: {}", worker.getWorkerId(), e.getMessage());
            }
        }
    }


}
