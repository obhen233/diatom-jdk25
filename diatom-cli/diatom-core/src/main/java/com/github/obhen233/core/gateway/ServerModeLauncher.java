package com.github.obhen233.core.gateway;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.TaskCheckpointManager;
import com.github.obhen233.core.gateway.http.WorkerHttpServer;
import com.github.obhen233.core.gateway.profile.CapabilityGenerator;
import com.github.obhen233.core.gateway.profile.CapabilityProfile;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.adapter.OpenAIAdapter;
import com.github.obhen233.core.adapter.ProviderRegistry;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.CoreCommandRegistry;
import com.github.obhen233.cli.provider.ConfigCommandProvider;
import com.github.obhen233.cli.provider.RulesCommandProvider;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.util.InstallPaths;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Worker 模式启动器
 */
public class ServerModeLauncher {
    private static final Logger logger = LoggerFactory.getLogger(ServerModeLauncher.class);
    private static final long HEARTBEAT_INTERVAL_SECONDS = 10;
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_MS = 30_000;

    /** 标记 Worker 是否已从 Gateway 获取证书并升级到 HTTPS */
    private static volatile boolean upgradedToHttps = false;

    /** Worker 心跳调度器，用于生命周期管理 */
    private static volatile ScheduledExecutorService workerHeartbeatScheduler;

    static void stopWorkerHeartbeat() {
        if (workerHeartbeatScheduler != null) {
            workerHeartbeatScheduler.shutdown();
            workerHeartbeatScheduler = null;
        }
    }

    private ServerModeLauncher() {}

    public static void start(String[] args, AppConfig config, ReActAgent agent,
                              AiHttpClient httpClient, ModelAdapter adapter,
                              String apiEndpoint, String capabilityFile, String description,
                              DatabaseManager dbManager) {
        int port = 8083;
        String portSource = "default";
        // Priority 1: CLI argument -P/--port
        String portArg = parsePortArgValue(args);
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
        port = resolvePort(port);
        System.out.println("  [Config] Port: " + port + " (from " + portSource + ")");
        String instanceId = parseWorkerIdFromArgs(args);

        System.setProperty("diatom.instance.id", instanceId);

        resolveGatewayUrl(args, config);
        ModePreset preset = parseModePreset(args);
        com.github.obhen233.core.security.SandboxLevel sandboxLevel = parseSandboxLevel(args, config, preset);
        com.github.obhen233.core.security.ApprovalPolicy approvalPolicy = parseApprovalPolicy(args, config, preset);

        // Capability default path — use the worker's own "home" directory
        // (JAR directory), NOT the workspace. Multiple workers may share the same
        // workspace but each worker has its own private capability profile.
        String jarDir = System.getProperty("diatom.jar.dir");
        String userDir = (jarDir != null && !jarDir.isEmpty()) ? jarDir : System.getProperty("user.dir");
        Path defaultCapPath = Paths.get(userDir, ".diatom", "capability.md");

        // Fetch workspace from Gateway (unless explicitly set locally)
        fetchWorkspaceFromGateway(config);

        // Capability profile loading with priority:
        //   1. --description (-desc) → AI-generate and save to default path
        //   2. --capability (-c) → load from specified path
        //   3. Default path {userDir}/.diatom/capability.md
        //   4. None → start with empty default (topology editor can configure later)
        CapabilityProfile capabilityProfile = null;
        CapabilityGenerator capabilityGen = new CapabilityGenerator(config);
        String capSource = "none";

        // Priority 1: --description provided → AI-generate
        if (description != null && !description.trim().isEmpty()) {
            System.out.println("Generating capability profile from description...");
            capabilityProfile = capabilityGen.generateProfileFromDescription(
                description, httpClient, adapter, apiEndpoint, instanceId);
            if (capabilityProfile != null) {
                capabilityGen.saveProfile(defaultCapPath, capabilityProfile);
                capSource = "--description/-desc (AI-generated)";
                System.out.println("Capability profile generated and saved to: " + defaultCapPath);
            }
        }

        // Priority 2: --capability path provided → load from file
        if (capabilityProfile == null && capabilityFile != null && !capabilityFile.isEmpty()) {
            Path capPath = Paths.get(capabilityFile);
            capabilityProfile = capabilityGen.loadProfile(capPath, instanceId);
            capSource = "--capability/-c: " + capabilityFile;
            if (capabilityProfile == null) {
                System.err.println("ERROR: Capability file not found or invalid: " + capPath);
                System.exit(1);
            }
        }

        // Priority 3: Default path exists → load from there
        if (capabilityProfile == null && Files.exists(defaultCapPath)) {
            capabilityProfile = capabilityGen.loadProfile(defaultCapPath, instanceId);
            capSource = "default .diatom/capability.md";
        }

        // Priority 4: None → use empty default (configure via topology editor)
        if (capabilityProfile == null) {
            System.out.println("  [Config] Capability: none (configure via topology editor)");
            capabilityProfile = new CapabilityProfile();
            capabilityProfile.setWorkerId(instanceId);
            capSource = "none (topology editor)";
        }

        System.out.println("  [Config] Capability: " + capSource);

        System.out.println("Worker starting (instance=" + instanceId + ", port=" + port + ")");

        // worker_tasks table is auto-created by Hibernate hbm2ddl.auto=update
        // via WorkerTaskEntity

        // Initialize ConfigManager with the shared database
        ConfigManager configManager = null;
        CoreCommandRegistry commandRegistry = new CoreCommandRegistry();
        try {
            if (dbManager != null) {
                configManager = new ConfigManager(dbManager);
                configManager.loadFromDatabase();

                // Register ConfigCommandProvider
                ConfigCommandProvider configCommand = new ConfigCommandProvider();
                configCommand.setConfigTools(
                    new com.github.obhen233.core.command.tools.ConfigTools(configManager, dbManager));
                commandRegistry.register(configCommand);

                // Register RulesCommandProvider
                CommandRulesDao rulesDao = new CommandRulesDao(dbManager);
                RulesCommandProvider rulesCommand = new RulesCommandProvider();
                rulesCommand.setCommandRulesDao(rulesDao);
                commandRegistry.register(rulesCommand);
                // Initialize built-in rules on first run
                if (rulesDao.findAll().isEmpty()) {
                    for (CommandRulesDao.CommandRule rule : RulesCommandProvider.getBuiltinRules()) {
                        rulesDao.insertIfNotExists(rule);
                    }
                }

                // Inject checkpoint manager for resumable tasks
                TaskCheckpointManager tcm = new TaskCheckpointManager(dbManager);
                agent.setCheckpointManager(tcm);
                logger.info("TaskCheckpointManager injected into agent (shared database)");
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize worker config/command system: {}", e.getMessage());
        }

        // Always register a config command — fallback provider when db is unavailable
        final ConfigManager cm = configManager;
        if (!commandRegistry.isRegistered("config")) {
            commandRegistry.register(new CoreCommandProvider() {
                @Override public String getCommandName() { return "config"; }
                @Override public String getDescription() { return "Worker config management"; }
                @Override public String getHelp() { return "config list|set <k> <v>|reset <k>"; }
                @Override
                public String execute(String args, com.github.obhen233.spi.command.CommandOutput output) {
                    if (args == null || args.trim().isEmpty()) args = "list";
                    String lower = args.trim().toLowerCase();
                    if (lower.startsWith("list")) {
                        if (cm != null) {
                            java.util.List<com.github.obhen233.core.database.SystemConfigDao.SystemConfig> cfgs = cm.getAll();
                            StringBuilder sb = new StringBuilder();
                            String currentCategory = null;
                            for (com.github.obhen233.core.database.SystemConfigDao.SystemConfig cfg : cfgs) {
                                if (!cfg.category.equals(currentCategory)) {
                                    currentCategory = cfg.category;
                                    sb.append("[").append(cfg.category).append("]\n");
                                }
                                String val = cfg.configValue != null ? cfg.configValue : "(default: " + (cfg.defaultValue != null ? cfg.defaultValue : "") + ")";
                                sb.append("  ").append(cfg.configKey).append(" = ").append(val).append("\n");
                            }
                            return sb.toString();
                        }
                        return "Config database not available on this worker";
                    }
                    if (lower.startsWith("set ")) {
                        String remaining = args.trim().substring(4).trim();
                        int spaceIdx = remaining.indexOf(' ');
                        if (spaceIdx <= 0) return "Usage: config set <key> <value>";
                        String key = remaining.substring(0, spaceIdx).trim();
                        String value = remaining.substring(spaceIdx + 1).trim();
                        if (cm != null) {
                            String result = cm.set(key, value);
                            return result != null ? result : "Config updated: " + key + " = " + value;
                        }
                        return "Config database not available on this worker";
                    }
                    if (lower.startsWith("reset ")) {
                        String key = args.trim().substring(6).trim();
                        if (cm != null) {
                            String result = cm.reset(key);
                            return result != null ? result : "Config reset: " + key;
                        }
                        return "Config database not available on this worker";
                    }
                    return "Usage: config list|set <key> <value>|reset <key>";
                }
            });
        }

        try {
            com.github.obhen233.core.security.ApprovalStrategyResolver resolver =
                new com.github.obhen233.core.security.ApprovalStrategyResolver(sandboxLevel, approvalPolicy);
            agent.setApprovalStrategyResolver(resolver);
            System.out.println("  [Config] Sandbox level: " + sandboxLevel.name().toLowerCase()
                + ", Approval policy: " + approvalPolicy.name().toLowerCase());

            WorkerHttpServer httpServer = new WorkerHttpServer(port, agent, commandRegistry, configManager);

            // Set up confirmation callback to Gateway
            String gatewayUrl = System.getProperty("gateway.url", "http://127.0.0.1:8080");
            String firstGwUrl = gatewayUrl.contains(",") ? gatewayUrl.split(",")[0].trim() : gatewayUrl.trim();
            httpServer.setGatewayUrl(firstGwUrl);
            httpServer.setInstanceId(instanceId);
            httpServer.setConfirmationCallback(
                    new WorkerHttpServer.GatewayConfirmationCallback(
                            firstGwUrl, instanceId, httpServer.getCallbackBaseUrl(),
                            httpServer.getPendingConfirmCallbacks()));
            if (capabilityFile != null && !capabilityFile.isEmpty()) {
                httpServer.setRulesPath(capabilityFile);
            }
            logger.info("Confirmation callback configured for worker {} -> {}", instanceId, firstGwUrl);

            httpServer.start();

            // Sync config from gateway after server starts
            syncConfigFromGateway(configManager);

            // Connection message
            // (gatewayUrl already resolved above)
            logger.info("Gateway URL resolved: {}", gatewayUrl);
            System.out.println("Connected to gateway at " + gatewayUrl);

            // Parse group and capabilities from args (merged with capability profile)
            String group = parseGroupFromArgs(args);
            java.util.Map<String, Double> capabilities = buildCapabilitiesFromProfile(capabilityProfile, args);

            logger.info("Registering worker to gateway at: {}", gatewayUrl);
            System.out.println("Registering worker to gateway: " + gatewayUrl);

            // Write registration file (filesystem-based, for same-host scenarios)
            writeRegistrationFile(instanceId, port, config, group, capabilities);

            // HTTP registration (remote registration, for cross-machine scenarios)
            httpRegister(gatewayUrl, instanceId, port, config, group, capabilities);

            // Handle SSL certificate request/upgrade
            requestAndInstallCert(gatewayUrl, instanceId, httpServer, config);

            // Start heartbeat loop — capture registration params for auto-re-registration
            final int hbPort = port;
            final AppConfig hbConfig = config;
            final String hbGroup = group;
            final java.util.Map<String, Double> hbCapabilities = capabilities;
            workerHeartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "worker-heartbeat");
                t.setDaemon(true);
                return t;
            });
            workerHeartbeatScheduler.scheduleAtFixedRate(
                () -> {
                    updateHeartbeat(instanceId);
                    httpHeartbeat(gatewayUrl, instanceId, hbPort, hbConfig, hbGroup, hbCapabilities);
                },
                0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

            System.out.println("Worker started on port " + port);

            // Graceful shutdown
            Object waitLock = new Object();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down worker gracefully...");

                // 1. Mark SHUTTING_DOWN in registry
                markShuttingDown(instanceId);

                // 2. Stop accepting new requests
                httpServer.stop(0);

                // 3. Wait for active tasks (simplified)
                try {
                    Thread.sleep(Math.min(GRACEFUL_SHUTDOWN_TIMEOUT_MS, 5000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 4. Deregister
                stopWorkerHeartbeat();
                removeRegistrationFile(instanceId);
                httpDeregister(gatewayUrl, instanceId);
                synchronized (waitLock) {
                    waitLock.notifyAll();
                }
            }));

            synchronized (waitLock) {
                try {
                    waitLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            logger.error("Failed to start worker mode", e);
            System.exit(1);
        }
    }

    /**
     * 解析 --capability / -c 参数
     * 返回 capability.md 文件路径，或 null
     */
    private static String parseCapabilityFileArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--capability".equals(args[i]) || "-c".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) return args[i + 1];
            }
            if (args[i].startsWith("--capability=")) {
                String val = args[i].substring("--capability=".length()).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    /**
     * 解析 --description / -desc 参数
     * 返回描述文本，或 null
     */
    private static String parseDescriptionArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--description".equals(args[i]) || "-desc".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) return args[i + 1];
            }
            if (args[i].startsWith("--description=")) {
                String val = args[i].substring("--description=".length()).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    private static void writeRegistrationFile(String instanceId, int port, AppConfig config,
                                               String group, java.util.Map<String, Double> capabilities) {
        try {
            Path registryDir = InstallPaths.getGatewayRegistryDir();
            Files.createDirectories(registryDir);
            Path regFile = registryDir.resolve(instanceId + ".json");
            String content = buildRegistrationJson(instanceId, port, config, group, capabilities);
            Files.write(regFile, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Worker registration written: {}", regFile);
        } catch (Exception e) {
            logger.warn("Failed to write registration file: {}", e.getMessage());
        }
    }


    private static void updateHeartbeat(String instanceId) {
        try {
            Path regFile = InstallPaths.getGatewayRegistryDir().resolve(instanceId + ".json");
            if (Files.exists(regFile)) {
                String content = new String(Files.readAllBytes(regFile), StandardCharsets.UTF_8);
                content = content.replaceAll("\"lastHeartbeat\": \\d+",
                    "\"lastHeartbeat\": " + System.currentTimeMillis());
                Files.write(regFile, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Exception e) {
            logger.warn("Failed to update heartbeat: {}", e.getMessage());
        }
    }

    private static void markShuttingDown(String instanceId) {
        try {
            Path regFile = InstallPaths.getGatewayRegistryDir().resolve(instanceId + ".json");
            if (Files.exists(regFile)) {
                String content = new String(Files.readAllBytes(regFile), StandardCharsets.UTF_8);
                content = content.replace("\"ONLINE\"", "\"SHUTTING_DOWN\"")
                        .replace("\"status\": \"ONLINE\"", "\"status\": \"SHUTTING_DOWN\"");
                Files.write(regFile, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Exception e) {
            logger.warn("Failed to mark shutting down: {}", e.getMessage());
        }
    }

    private static void removeRegistrationFile(String instanceId) {
        try {
            Path regFile = InstallPaths.getGatewayRegistryDir().resolve(instanceId + ".json");
            Files.deleteIfExists(regFile);
            logger.info("Worker registration removed: {}", regFile);
        } catch (Exception e) {
            logger.warn("Failed to remove registration: {}", e.getMessage());
        }
    }

    private static String lastNormalizedUrl = null;

    /**
     * Get the first URL from a potentially comma-separated gateway URL list.
     */
    private static String firstGatewayUrl(String gatewayUrl) {
        if (gatewayUrl == null || gatewayUrl.isEmpty()) return "http://127.0.0.1:8080";
        if (gatewayUrl.contains(",")) return gatewayUrl.split(",")[0].trim();
        return gatewayUrl.trim();
    }

    /**
     * Normalize gateway URL: auto-prepend http:// if protocol is missing.
     * Prints a visible warning when the URL is invalid.
     */
    private static String normalizeGatewayUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            System.out.println("  [Warning] Gateway URL missing protocol, prepending http://");
            System.out.println("  Original: " + trimmed);
            System.out.println("  Normalized: http://" + trimmed);
            return "http://" + trimmed;
        }
        return trimmed;
    }

    /**
     * HTTP POST to register with the Gateway.
     * Non-fatal: logs warning on failure but does not prevent worker startup.
     */
    private static void httpRegister(String gatewayUrl, String instanceId, int port,
                                      AppConfig config, String group,
                                      java.util.Map<String, Double> capabilities) {
        String url = firstGatewayUrl(gatewayUrl) + "/gateway/v1/workers";
        logger.info("HTTP registering worker {} to Gateway URL: {}", instanceId, url);
        String json = buildRegistrationJson(instanceId, port, config, group, capabilities);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            // Forward auth token if available
            String authToken = System.getProperty("diatom.auth.token", "");
            if (!authToken.isEmpty()) {
                conn.setRequestProperty("X-Diatom-Auth", authToken);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                logger.info("HTTP registration successful for worker {} at {}", instanceId, url);
                lastNormalizedUrl = url;
            } else {
                String response = readConnectionBody(conn, code);
                System.out.println("  [Error] Gateway registration failed (HTTP " + code + "): " + response);
                logger.warn("HTTP registration returned HTTP {}: {}", code, response);
            }
            conn.disconnect();
        } catch (java.net.ConnectException e) {
            System.out.println("  [Error] Failed to connect to Gateway at " + url);
            System.out.println("  Make sure the Gateway is running and accessible.");
            logger.warn("Gateway not reachable for HTTP registration ({}): {}", url, e.getMessage());
        } catch (Exception e) {
            System.out.println("  [Error] Failed to register with Gateway at " + url + ": " + e.getMessage());
            logger.warn("Failed to HTTP register worker ({}): {}", url, e.getMessage());
        }
    }

    /**
     * HTTP PUT to send heartbeat to the Gateway.
     * On 404 (Gateway restarted), automatically re-register.
     */
    private static void httpHeartbeat(String gatewayUrl, String instanceId, int port,
                                       AppConfig config, String group,
                                       java.util.Map<String, Double> capabilities) {
        String url = firstGatewayUrl(gatewayUrl) + "/gateway/v1/workers/" + instanceId + "/heartbeat";
        Map<String, Object> hbMap = new HashMap<>();
        hbMap.put("currentLoad", 0.0);
        hbMap.put("activeTasks", 0);
        if (upgradedToHttps) {
            hbMap.put("useSsl", true);
        }
        String json = JsonUtils.toJson(hbMap);
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
            if (code == 404) {
                // Gateway restarted — worker no longer in registry, re-register
                logger.info("Worker not found in Gateway registry (heartbeat 404), re-registering...");
                httpRegister(gatewayUrl, instanceId, port, config, group, capabilities);
            } else if (code != 200) {
                logger.debug("HTTP heartbeat returned HTTP {}", code);
            }
            conn.disconnect();
        } catch (java.net.ConnectException e) {
            logger.debug("Gateway not reachable for heartbeat ({}): {}", url, e.getMessage());
        } catch (Exception e) {
            logger.debug("Failed to send HTTP heartbeat ({}): {}", url, e.getMessage());
        }
    }

    /**
     * Request a signed certificate from the Gateway and upgrade the Worker HTTP server to HTTPS.
     *
     * <p>Called after registration when {@code diatom.ssl.enabled=true} and no
     * {@code diatom.ssl.cert-path} is configured (Scenario B).</p>
     */
    private static void requestAndInstallCert(String gatewayUrl, String instanceId,
                                               WorkerHttpServer httpServer, AppConfig config) {
        String sslEnabled = System.getProperty("diatom.ssl.enabled", "false");
        String sslCertPath = System.getProperty("diatom.ssl.cert-path", "");

        if (!"true".equalsIgnoreCase(sslEnabled)) {
            return; // SSL not enabled, continue with HTTP
        }

        if (!sslCertPath.isEmpty()) {
            logger.info("Worker has own certificate at {}, skipping Gateway cert request", sslCertPath);
            return; // Scenario A: Worker has its own certificate
        }

        // Scenario B: Need to request certificate from Gateway
        logger.info("Requesting signed certificate from Gateway for HTTPS upgrade...");
        HttpURLConnection conn = null;
        try {
            String certUrl = firstGatewayUrl(gatewayUrl) + "/gateway/v1/cert/issue";
            String host = getLocalHost();
            String jsonBody = "{\"workerId\":\"" + instanceId
                    + "\",\"host\":\"" + host + "\"}";

            conn = (HttpURLConnection) new URL(certUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            // Forward auth token if available
            String authToken = System.getProperty("diatom.auth.token", "");
            if (!authToken.isEmpty()) {
                conn.setRequestProperty("X-Diatom-Auth", authToken);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                String respBody = readConnectionBody(conn, code);
                String certPem = extractJsonValue(respBody, "certPem");
                String keyPem = extractJsonValue(respBody, "keyPem");
                String caCertPem = extractJsonValue(respBody, "caCertPem");

                if (certPem != null && keyPem != null && caCertPem != null) {
                    httpServer.upgradeToHttps(certPem, keyPem, caCertPem, "");
                    upgradedToHttps = true;
                    logger.info("Worker upgraded to HTTPS successfully via Gateway certificate");
                } else {
                    logger.warn("Incomplete certificate response from Gateway, continuing with HTTP");
                }
            } else {
                String respBody = readConnectionBody(conn, code);
                logger.warn("Certificate request failed (HTTP {}): {}, continuing with HTTP", code, respBody);
            }
        } catch (Exception e) {
            logger.warn("Failed to request certificate from Gateway ({}), continuing with HTTP", e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Extract a JSON string value from a JSON response body.
     * Simple parser for flat JSON objects (no nesting).
     */
    private static String extractJsonValue(String json, String key) {
        if (json == null || key == null) return null;
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            // Try without quotes (for simple values)
            search = "\"" + key + "\":";
            start = json.indexOf(search);
            if (start < 0) return null;
            start += search.length();
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            if (end < 0) return null;
            String val = json.substring(start, end).trim();
            // Remove surrounding quotes if present
            if (val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            return val.isEmpty() ? null : val;
        }
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /**
     * HTTP DELETE to deregister from the Gateway.
     */
    private static void httpDeregister(String gatewayUrl, String instanceId) {
        String url = firstGatewayUrl(gatewayUrl) + "/gateway/v1/workers/" + instanceId;
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
                logger.info("HTTP deregistration successful for worker {}", instanceId);
            } else {
                logger.warn("HTTP deregistration returned HTTP {}", code);
            }
        } catch (Exception e) {
            logger.warn("Failed to HTTP deregister worker: {}", e.getMessage());
        }
    }

    /**
     * Build the registration JSON body matching the format expected by
     * GatewayHttpServer.parseWorkerRegistration().
     */
    private static String buildRegistrationJson(String instanceId, int port,
                                                 AppConfig config, String group,
                                                 java.util.Map<String, Double> capabilities) {
        String model = config.getModel();
        if (model == null || model.isEmpty()) {
            model = System.getProperty("api.model", "");
        }
        String tier = System.getProperty("diatom.tier", "default");
        String authToken = System.getProperty("diatom.auth.token", "");
        double costPer1kTokens = 0.0;
        try {
            costPer1kTokens = Double.parseDouble(System.getProperty("diatom.cost.per1k", "0.0"));
        } catch (NumberFormatException ignored) {}
        int maxConcurrency = 5;
        try {
            maxConcurrency = Integer.parseInt(System.getProperty("diatom.max.concurrency", "5"));
        } catch (NumberFormatException ignored) {}

        boolean sslEnabled = "true".equalsIgnoreCase(System.getProperty("diatom.ssl.enabled", "false"));
        boolean useSsl = sslEnabled;
        String sslCertPath = System.getProperty("diatom.ssl.cert-path", "");
        boolean requestCert = sslEnabled && sslCertPath.isEmpty();

        String workspaceDir = config.getWorkspaceDir();

        Map<String, Object> regMap = new java.util.LinkedHashMap<>();
        regMap.put("workerId", instanceId);
        regMap.put("host", getLocalHost());
        regMap.put("port", port);
        regMap.put("model", model);
        regMap.put("workspace", workspaceDir);
        regMap.put("traits", java.util.Collections.emptyList());
        regMap.put("capabilities", capabilities != null ? capabilities : java.util.Collections.emptyMap());
        regMap.put("tier", tier);
        regMap.put("costPer1kTokens", costPer1kTokens);
        regMap.put("maxConcurrency", maxConcurrency);
        if (authToken != null && !authToken.isEmpty()) {
            regMap.put("authToken", authToken);
        }
        regMap.put("group", group);
        regMap.put("useSsl", useSsl);
        if (requestCert) {
            regMap.put("requestCert", true);
        }
        regMap.put("status", "ONLINE");
        regMap.put("lastHeartbeat", System.currentTimeMillis());
        regMap.put("currentLoad", 0.0);
        regMap.put("pid", getPid());
        regMap.put("registeredAt", System.currentTimeMillis());
        return JsonUtils.toJson(regMap);
    }

    /**
     * Read HTTP connection body from stream.
     */
    private static String readConnectionBody(HttpURLConnection conn, int code) throws java.io.IOException {
        java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = is.read(buf, 0, buf.length)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static long getPid() {
        try {
            return Long.parseLong(java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Resolve local host address. Returns the real IP address when available,
     * falling back to 127.0.0.1 if the host address cannot be determined.
     * <p>Uses {@link com.github.obhen233.util.NetworkUtils#getRealLocalIP()} to
     * handle multiple NICs and VPN scenarios correctly.</p>
     */
    private static String getLocalHost() {
        return com.github.obhen233.util.NetworkUtils.getRealLocalIP();
    }

    /**
     * Fetch workspace directory from Gateway and set it in config.
     * Only applies when -Dworkspace.dir is NOT explicitly set (highest priority).
     * Falls back to default workspace if Gateway is unreachable.
     */
    /**
     * Parse and set gateway.url system property from CLI args, environment variable,
     * or config file. Priority: CLI args &gt; GATEWAY_URL env &gt; gateway.url config &gt; default.
     * Extracted so it can be called early from {@link #prefetchWorkspace(String[], AppConfig)}
     * before agent/tool initialization.
     */
    private static void resolveGatewayUrl(String[] args, AppConfig config) {
        String gwUrlSource = "-u/--gateway-url CLI argument";
        String gatewayUrlFromArgs = parseGatewayUrlFromArgs(args);
        if (gatewayUrlFromArgs != null) {
            gatewayUrlFromArgs = normalizeGatewayUrl(gatewayUrlFromArgs);
            System.setProperty("gateway.url", gatewayUrlFromArgs);
        }
        // Fallback: read from GATEWAY_URL environment variable
        if (System.getProperty("gateway.url") == null || System.getProperty("gateway.url").isEmpty()) {
            String envGatewayUrl = System.getenv("GATEWAY_URL");
            if (envGatewayUrl != null && !envGatewayUrl.isEmpty()) {
                envGatewayUrl = normalizeGatewayUrl(envGatewayUrl);
                System.setProperty("gateway.url", envGatewayUrl);
                gwUrlSource = "GATEWAY_URL env";
                logger.info("Gateway URL set from GATEWAY_URL env: {}", envGatewayUrl);
            }
        }
        // Fallback: read from AppConfig (loaded from application.properties) if not set by args or env
        if (System.getProperty("gateway.url") == null || System.getProperty("gateway.url").isEmpty()) {
            String configGatewayUrl = config.getProperty("gateway.url", "");
            if (!configGatewayUrl.isEmpty()) {
                System.setProperty("gateway.url", configGatewayUrl);
                gwUrlSource = "gateway.url config";
                logger.info("Gateway URL set from config: {}", configGatewayUrl);
            }
        }

        String finalGatewayUrl = System.getProperty("gateway.url", "");
        if (finalGatewayUrl.isEmpty()) {
            finalGatewayUrl = "http://127.0.0.1:8080";
            gwUrlSource = "default";
        }
        System.out.println("  [Config] Gateway URL: " + finalGatewayUrl + " (from " + gwUrlSource + ")");
    }

    /**
     * Called before agent/tool creation to ensure workspace is resolved from Gateway.
     * Only used in worker mode. After this call, config.getWorkspaceDir() returns the
     * correct Gateway workspace path.
     *
     * @param args   CLI arguments (for parsing --gateway-url)
     * @param config AppConfig to update with resolved workspace
     * @return the resolved workspace directory
     */
    public static String prefetchWorkspace(String[] args, AppConfig config) {
        resolveGatewayUrl(args, config);
        fetchWorkspaceFromGateway(config);
        return config.getWorkspaceDir();
    }

    /**
     * Fetch workspace directory from Gateway and set it in config.
     * Only applies when -Dworkspace.dir is NOT explicitly set (highest priority).
     * Falls back to default workspace if Gateway is unreachable.
     */
    private static void fetchWorkspaceFromGateway(AppConfig config) {
        // Use the unified resolver to check all local priority sources
        String resolved = com.github.obhen233.util.WorkspaceDirResolver.resolve(config);
        String defaultDir = System.getProperty("diatom.original.user.dir",
                System.getProperty("user.dir"));
        String normalizedDefault = java.nio.file.Paths.get(defaultDir).toAbsolutePath().normalize().toString();

        if (!resolved.equals(normalizedDefault)) {
            // Explicitly configured via a local source — skip Gateway fetch
            System.setProperty("diatom.original.user.dir", resolved);
            System.setProperty("user.dir", resolved);
            logger.info("Workspace resolved locally: {} (from {})",
                    resolved, com.github.obhen233.util.WorkspaceDirResolver.getSourceLabel(config));
            return;
        }

        // All local sources are unset (value equals default) → fallback to Gateway HTTP
        String gatewayUrl = System.getProperty("gateway.url", "http://127.0.0.1:8080");
        // Use first URL if comma-separated list
        if (gatewayUrl.contains(",")) {
            gatewayUrl = gatewayUrl.split(",")[0].trim();
        }

        try {
            URL url = new URL(gatewayUrl + "/gateway/v1/workspace");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            // Forward auth token if available
            String authToken = System.getProperty("diatom.auth.token", "");
            if (!authToken.isEmpty()) {
                conn.setRequestProperty("X-Diatom-Auth", authToken);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readConnectionBody(conn, code);
                conn.disconnect();
                String workspaceDir = extractWorkspaceDirFromJson(body);
                if (workspaceDir != null && !workspaceDir.isEmpty()) {
                    config.setProperty("workspace.dir", workspaceDir);
                    // Also update user.dir system properties so FileTools/PathUtils
                    // resolve relative paths to the Gateway workspace, not the worker's CWD.
                    System.setProperty("diatom.original.user.dir", workspaceDir);
                    System.setProperty("user.dir", workspaceDir);
                    logger.info("Fetched workspace from Gateway: {}", workspaceDir);
                }
            } else {
                logger.warn("Gateway workspace endpoint returned HTTP {}", code);
                conn.disconnect();
            }
        } catch (java.net.ConnectException e) {
            logger.warn("Gateway not reachable for workspace fetch ({}), using default workspace: {}", gatewayUrl, e.getMessage());
        } catch (Exception e) {
            logger.warn("Failed to fetch workspace from Gateway ({}): {}", gatewayUrl, e.getMessage());
        }
    }

    /**
     * Extract workspaceDir from JSON response: {"workspaceDir": "..."}
     */
    private static String extractWorkspaceDirFromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            String key = "\"workspaceDir\":\"";
            int start = json.indexOf(key);
            if (start < 0) {
                // Try with space
                key = "\"workspaceDir\": \"";
                start = json.indexOf(key);
            }
            if (start < 0) return null;
            start += key.length();
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    sb.append(json.charAt(i + 1));
                    i++;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to parse workspaceDir from JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Sync configuration from gateway after worker startup.
     * HTTP GET {gatewayUrl}/gateway/v1/config/sync -> configManager.loadSyncedConfig()
     */
    private static void syncConfigFromGateway(ConfigManager configManager) {
        if (configManager == null) return;
        String gatewayUrl = System.getProperty("gateway.url", "http://127.0.0.1:8080");
        // Use first URL if comma-separated list
        if (gatewayUrl.contains(",")) {
            gatewayUrl = gatewayUrl.split(",")[0].trim();
        }
        try {
            URL url = new URL(gatewayUrl + "/gateway/v1/config/sync");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readConnectionBody(conn, code);
                conn.disconnect();
                Map<String, String> synced = parseConfigSyncResponse(body);
                configManager.loadSyncedConfig(synced);
                logger.info("Synced {} config entries from gateway {}", synced.size(), gatewayUrl);
            } else {
                logger.warn("Gateway config sync returned HTTP {}", code);
                conn.disconnect();
            }
        } catch (java.net.ConnectException e) {
            logger.info("Gateway not reachable for config sync (will retry on next startup): {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("Failed to sync config from gateway: {}", e.getMessage());
        }
    }

    /**
     * Parse the config sync JSON response: {"configs":[{"key":"...","value":"..."},...]}
     */
    private static Map<String, String> parseConfigSyncResponse(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            // Simple JSON array parsing for configs array
            int configsStart = json.indexOf("\"configs\":[");
            if (configsStart < 0) return result;
            configsStart += "\"configs\":[".length();
            if (json.charAt(configsStart) == ']') return result;

            // Find the matching closing bracket
            int depth = 0;
            int configsEnd = configsStart;
            for (int i = configsStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth < 0) { configsEnd = i; break; } }
            }
            if (configsEnd <= configsStart) return result;

            String arrayContent = json.substring(configsStart, configsEnd);
            // Split by "{"key": to find individual config objects
            String[] parts = arrayContent.split("\\{\"key\":\"");
            for (String part : parts) {
                if (part.isEmpty()) continue;
                String key = extractJsonStringValue(part, 0);
                if (key == null) continue;
                // Skip the key prefix and find "value":
                int valueIdx = part.indexOf("\"value\":\"");
                if (valueIdx < 0) {
                    valueIdx = part.indexOf("\"value\": \"");
                }
                if (valueIdx < 0) continue;
                valueIdx = part.indexOf('"', valueIdx + 8) + 1;
                String value = extractJsonStringValue(part, valueIdx - 1);
                if (value != null) {
                    result.put(key, value);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse config sync response: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Extract a JSON string value starting after the opening quote at the given index.
     */
    private static String extractJsonStringValue(String s, int startIdx) {
        if (startIdx < 0 || startIdx >= s.length()) return null;
        if (s.charAt(startIdx) != '"') startIdx = s.indexOf('"', startIdx);
        if (startIdx < 0) return null;
        startIdx++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(i + 1));
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 返回 --port/-P 参数的原始字符串值，未指定时返回 null（不返回默认值）
     */
    private static String parsePortArgValue(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) || "-P".equals(args[i])) {
                if (i + 1 < args.length) {
                    return args[i + 1];
                }
            }
            if (args[i].startsWith("--port=")) {
                return args[i].substring("--port=".length());
            }
        }
        return null;
    }

    /**
     * 解析 --approval-policy / -a 参数
     * <p>
     * 优先级: CLI 参数 > -m 预设 > DIATOM_APPROVAL_POLICY 环境变量 > diatom.approval-policy 配置文件 > 默认 ASK
     * <p>
     * 向后兼容: 旧版 -a (无值) 或 --auto-approve=true 映射为 silent
     */
    private static com.github.obhen233.core.security.ApprovalPolicy parseApprovalPolicy(String[] args, AppConfig config, ModePreset preset) {
        // Priority 1: CLI argument
        com.github.obhen233.core.security.ApprovalPolicy cliValue = parseApprovalPolicyFromCli(args);
        if (cliValue != null) return cliValue;
        // Priority 2: -m preset
        if (preset != null) return preset.policy;
        // Priority 3: env vars
        com.github.obhen233.core.security.ApprovalPolicy envValue = parseApprovalPolicyFromEnv();
        if (envValue != null) return envValue;
        // Priority 4: config file
        com.github.obhen233.core.security.ApprovalPolicy cfgValue = parseApprovalPolicyFromConfig(config);
        if (cfgValue != null) return cfgValue;
        return com.github.obhen233.core.security.ApprovalPolicy.ASK;
    }

    /**
     * 解析 --level / -l 参数
     * <p>
     * 优先级: CLI 参数 > -m 预设 > DIATOM_LEVEL 环境变量 > diatom.level 配置文件 > 默认 WORKSPACE
     */
    private static com.github.obhen233.core.security.SandboxLevel parseSandboxLevel(String[] args, AppConfig config, ModePreset preset) {
        // Priority 1: CLI argument
        com.github.obhen233.core.security.SandboxLevel cliValue = parseSandboxLevelFromCli(args);
        if (cliValue != null) return cliValue;
        // Priority 2: -m preset
        if (preset != null) return preset.level;
        // Priority 3: env vars
        com.github.obhen233.core.security.SandboxLevel envValue = parseSandboxLevelFromEnv();
        if (envValue != null) return envValue;
        // Priority 4: config file
        com.github.obhen233.core.security.SandboxLevel cfgValue = parseSandboxLevelFromConfig(config);
        if (cfgValue != null) return cfgValue;
        return com.github.obhen233.core.security.SandboxLevel.WORKSPACE;
    }

    // ==================== -m / --mode preset ====================

    /**
     * 解析 --mode / -m 预设参数。仅从 CLI 解析（不读 env/config）。
     * 返回 null 表示未指定。
     */
    private static ModePreset parseModePreset(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--mode".equals(args[i]) || "-m".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return resolveModePreset(args[i + 1]);
                }
            }
            if (args[i].startsWith("--mode=")) {
                return resolveModePreset(args[i].substring("--mode=".length()));
            }
        }
        return null;
    }

    private static ModePreset resolveModePreset(String value) {
        if (value == null) return null;
        String lower = value.trim().toLowerCase();
        switch (lower) {
            case "normal":
                return new ModePreset(com.github.obhen233.core.security.SandboxLevel.WORKSPACE,
                    com.github.obhen233.core.security.ApprovalPolicy.ASK);
            case "auto":
                return new ModePreset(com.github.obhen233.core.security.SandboxLevel.WORKSPACE,
                    com.github.obhen233.core.security.ApprovalPolicy.AUTO);
            case "silent":
                return new ModePreset(com.github.obhen233.core.security.SandboxLevel.WORKSPACE,
                    com.github.obhen233.core.security.ApprovalPolicy.SILENT);
            case "readonly":
            case "read-only":
                return new ModePreset(com.github.obhen233.core.security.SandboxLevel.READ_ONLY,
                    com.github.obhen233.core.security.ApprovalPolicy.SILENT);
            case "unrestricted":
                return new ModePreset(com.github.obhen233.core.security.SandboxLevel.FULL,
                    com.github.obhen233.core.security.ApprovalPolicy.AUTO);
            default:
                logger.warn("Unknown mode preset: {}, ignoring", value);
                return null;
        }
    }

    /**
     * Predefined mode preset — combines a sandbox level and approval policy.
     */
    static class ModePreset {
        final com.github.obhen233.core.security.SandboxLevel level;
        final com.github.obhen233.core.security.ApprovalPolicy policy;

        ModePreset(com.github.obhen233.core.security.SandboxLevel level, com.github.obhen233.core.security.ApprovalPolicy policy) {
            this.level = level;
            this.policy = policy;
        }
    }

    // ==================== Low-level parsing helpers for -a / -l ====================

    private static com.github.obhen233.core.security.ApprovalPolicy parseApprovalPolicyFromCli(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--approval-policy".equals(args[i]) || "-a".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return parseApprovalPolicyValue(args[i + 1]);
                }
                return com.github.obhen233.core.security.ApprovalPolicy.SILENT; // bare -a
            }
            if (args[i].startsWith("--approval-policy=")) {
                return parseApprovalPolicyValue(args[i].substring("--approval-policy=".length()));
            }
            if ("--auto-approve".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return "true".equalsIgnoreCase(args[i + 1])
                        ? com.github.obhen233.core.security.ApprovalPolicy.SILENT
                        : com.github.obhen233.core.security.ApprovalPolicy.ASK;
                }
                return com.github.obhen233.core.security.ApprovalPolicy.SILENT;
            }
        }
        return null;
    }

    private static com.github.obhen233.core.security.ApprovalPolicy parseApprovalPolicyFromEnv() {
        String envVal = System.getenv("DIATOM_APPROVAL_POLICY");
        if (envVal != null && !envVal.isEmpty()) {
            return parseApprovalPolicyValue(envVal);
        }
        String oldEnvVal = System.getenv("DIATOM_AUTO_APPROVE");
        if (oldEnvVal != null && !oldEnvVal.isEmpty()) {
            return "true".equalsIgnoreCase(oldEnvVal)
                ? com.github.obhen233.core.security.ApprovalPolicy.SILENT
                : com.github.obhen233.core.security.ApprovalPolicy.ASK;
        }
        return null;
    }

    private static com.github.obhen233.core.security.ApprovalPolicy parseApprovalPolicyFromConfig(AppConfig config) {
        String cfgVal = config.getProperty("diatom.approval-policy", "");
        if (!cfgVal.isEmpty()) {
            return parseApprovalPolicyValue(cfgVal);
        }
        String oldCfgVal = config.getProperty("diatom.auto-approve", "");
        if (!oldCfgVal.isEmpty()) {
            return "true".equalsIgnoreCase(oldCfgVal)
                ? com.github.obhen233.core.security.ApprovalPolicy.SILENT
                : com.github.obhen233.core.security.ApprovalPolicy.ASK;
        }
        return null;
    }

    private static com.github.obhen233.core.security.SandboxLevel parseSandboxLevelFromCli(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--level".equals(args[i]) || "-l".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return parseSandboxLevelValue(args[i + 1]);
                }
            }
            if (args[i].startsWith("--level=")) {
                return parseSandboxLevelValue(args[i].substring("--level=".length()));
            }
        }
        return null;
    }

    private static com.github.obhen233.core.security.SandboxLevel parseSandboxLevelFromEnv() {
        String envVal = System.getenv("DIATOM_LEVEL");
        if (envVal != null && !envVal.isEmpty()) {
            return parseSandboxLevelValue(envVal);
        }
        return null;
    }

    private static com.github.obhen233.core.security.SandboxLevel parseSandboxLevelFromConfig(AppConfig config) {
        String cfgVal = config.getProperty("diatom.level", "");
        if (!cfgVal.isEmpty()) {
            return parseSandboxLevelValue(cfgVal);
        }
        return null;
    }

    private static com.github.obhen233.core.security.ApprovalPolicy parseApprovalPolicyValue(String value) {
        if (value == null) return com.github.obhen233.core.security.ApprovalPolicy.ASK;
        String lower = value.trim().toLowerCase();
        switch (lower) {
            case "ask": return com.github.obhen233.core.security.ApprovalPolicy.ASK;
            case "auto": return com.github.obhen233.core.security.ApprovalPolicy.AUTO;
            case "silent": return com.github.obhen233.core.security.ApprovalPolicy.SILENT;
            case "custom": return com.github.obhen233.core.security.ApprovalPolicy.CUSTOM;
            default:
                logger.warn("Unknown approval policy: {}, defaulting to ASK", value);
                return com.github.obhen233.core.security.ApprovalPolicy.ASK;
        }
    }

    private static com.github.obhen233.core.security.SandboxLevel parseSandboxLevelValue(String value) {
        if (value == null) return com.github.obhen233.core.security.SandboxLevel.WORKSPACE;
        String lower = value.trim().toLowerCase();
        switch (lower) {
            case "read-only":
            case "readonly":
                return com.github.obhen233.core.security.SandboxLevel.READ_ONLY;
            case "workspace":
                return com.github.obhen233.core.security.SandboxLevel.WORKSPACE;
            case "full":
                return com.github.obhen233.core.security.SandboxLevel.FULL;
            default:
                logger.warn("Unknown sandbox level: {}, defaulting to WORKSPACE", value);
                return com.github.obhen233.core.security.SandboxLevel.WORKSPACE;
        }
    }

    /**
     * 端口冲突自动递增
     * 尝试使用 preferredPort，如果被占用则尝试 port+1 直到 port+9
     */
    private static int resolvePort(int preferredPort) {
        for (int attempt = 0; attempt < 10; attempt++) {
            int port = preferredPort + attempt;
            try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
                return port;
            } catch (java.io.IOException e) {
                // Port in use, try next
            }
        }
        throw new RuntimeException("No available port in range "
                + preferredPort + "-" + (preferredPort + 9));
    }

    /**
     * Parse --gateway-url / -u from CLI args and return the URL, or null if not provided.
     */
    private static String parseGatewayUrlFromArgs(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--gateway-url".equals(args[i]) || "-u".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
            }
            if (args[i].startsWith("--gateway-url=")) {
                String val = args[i].substring("--gateway-url=".length()).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    private static String parseWorkerIdFromArgs(String[] args) {
        if (args == null) return "worker-" + System.currentTimeMillis();
        for (int i = 0; i < args.length; i++) {
            if ("--worker-id".equals(args[i]) || "-id".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) return args[i + 1];
            }
            if (args[i].startsWith("--worker-id=")) {
                String val = args[i].substring("--worker-id=".length()).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return "worker-" + System.currentTimeMillis();
    }

    private static String parseGroupFromArgs(String[] args) {
        if (args == null) return "";
        for (int i = 0; i < args.length; i++) {
            if ("--group".equals(args[i]) || "-g".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) return args[i + 1];
            }
            if (args[i].startsWith("--group=")) {
                String val = args[i].substring("--group=".length()).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return "";
    }

    /**
     * Build capabilities map from the loaded capability profile, merged with CLI args.
     * Profile strengths and suitable task types are mapped to capability keys with high scores.
     * CLI --capabilities arg entries (score=1.0) override profile entries.
     */
    private static java.util.Map<String, Double> buildCapabilitiesFromProfile(
            CapabilityProfile profile, String[] args) {
        java.util.Map<String, Double> caps = new java.util.HashMap<>();
        if (profile != null) {
            for (String strength : profile.getStrengths()) {
                caps.put(strength, 0.85);
            }
            for (String taskType : profile.getSuitableTaskTypes()) {
                caps.put(taskType, 0.90);
            }
            // Also add inferred capabilities from profile analysis
            if (profile.getInferredCapabilities() != null) {
                caps.putAll(profile.getInferredCapabilities());
            }
        }
        // Merge with CLI --capabilities (override with score 1.0)
        java.util.Map<String, Double> cliCaps = parseCapabilitiesFromArgs(args);
        caps.putAll(cliCaps);
        return caps;
    }

    private static java.util.Map<String, Double> parseCapabilitiesFromArgs(String[] args) {
        java.util.Map<String, Double> caps = new java.util.HashMap<>();
        if (args == null) return caps;
        for (int i = 0; i < args.length; i++) {
            if ("--capabilities".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    String val = args[i + 1];
                    for (String cap : val.split(",")) {
                        String trimmed = cap.trim();
                        if (!trimmed.isEmpty()) {
                            caps.put(trimmed, 1.0);
                        }
                    }
                }
            }
            if (args[i].startsWith("--capabilities=")) {
                String val = args[i].substring("--capabilities=".length()).trim();
                if (!val.isEmpty()) {
                    for (String cap : val.split(",")) {
                        String trimmed = cap.trim();
                        if (!trimmed.isEmpty()) {
                            caps.put(trimmed, 1.0);
                        }
                    }
                }
            }
        }
        return caps;
    }

}
