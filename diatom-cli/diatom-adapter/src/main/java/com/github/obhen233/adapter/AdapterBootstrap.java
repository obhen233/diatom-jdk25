package com.github.obhen233.adapter;

import com.github.obhen233.adapter.database.HibernateConfig;
import com.github.obhen233.adapter.internal.DirectoryLayout;
import com.github.obhen233.adapter.internal.JsonUtil;
import com.github.obhen233.adapter.internal.PluginClassLoader;
import com.github.obhen233.adapter.security.DefaultSecurityMapper;
import com.github.obhen233.adapter.security.SecurityConfigParser;
import com.github.obhen233.adapter.spi.*;
import com.github.obhen233.adapter.util.WorkspaceDirResolver;
import com.github.obhen233.adapter.worker.CapabilityLoader;
import com.github.obhen233.adapter.worker.WorkerHttpServer;
import com.github.obhen233.adapter.worker.WorkerRegistrationClient;
import com.github.obhen233.adapter.worker.model.RegistrationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main entry point for diatom-adapter.
 *
 * <p>Bootstraps the adapter: parses CLI args, discovers AgentAdapter via SPI,
 * initializes the adapter, loads capability, starts HTTP server, and registers
 * with the Gateway.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar diatom-adapter-core.jar --port 8083 --gateway-url http://gateway:8080 --instance-id worker-1
 * </pre>
 */
public class AdapterBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(AdapterBootstrap.class);

    private static final int DEFAULT_PORT = 8083;
    private static final int MAX_PORT_RETRIES = 10;
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_MS = 30_000;

    // ---- CLI option keys ----
    private static final String OPT_PORT = "--port";
    private static final String OPT_PORT_SHORT = "-P";
    private static final String OPT_INSTANCE_ID = "--instance-id";
    private static final String OPT_INSTANCE_ID_SHORT = "-id";
    private static final String OPT_GATEWAY_URL = "--gateway-url";
    private static final String OPT_GATEWAY_URL_SHORT = "-u";
    private static final String OPT_CAPABILITY = "--capability";
    private static final String OPT_CAPABILITY_SHORT = "-c";
    private static final String OPT_DESCRIPTION = "--description";
    private static final String OPT_DESCRIPTION_SHORT = "-desc";
    private static final String OPT_DESCRIPTION_SHORT2 = "-d";
    private static final String OPT_GROUP = "--group";
    private static final String OPT_MAX_CONCURRENCY = "--max-concurrency";
    private static final String OPT_WORKSPACE_DIR = "--workspace-dir";
    private static final String OPT_WORKSPACE_DIR_SHORT = "-w";
    private static final String OPT_LEVEL = "--level";
    private static final String OPT_LEVEL_SHORT = "-l";
    private static final String OPT_APPROVAL = "--approval-policy";
    private static final String OPT_APPROVAL_SHORT = "-a";
    private static final String OPT_MODE = "--mode";
    private static final String OPT_MODE_SHORT = "-m";
    private static final String OPT_HELP = "--help";
    private static final String OPT_HELP_SHORT = "-h";

    private AdapterBootstrap() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            logger.error("Fatal error during startup", e);
            System.exit(1);
        }
    }

    static int run(String[] args) {
        // ---- 1. Parse CLI arguments ----
        Map<String, String> cliOpts = parseCliArgs(args);

        if (cliOpts.containsKey(OPT_HELP) || cliOpts.containsKey(OPT_HELP_SHORT)) {
            printUsage();
            return 0;
        }

        // Parse --workspace-dir / -w CLI arg into system property for WorkspaceDirResolver
        WorkspaceDirResolver.parseCliArg(args);

        int requestedPort = parseInt(cliOpts.getOrDefault(OPT_PORT,
                cliOpts.getOrDefault(OPT_PORT_SHORT, String.valueOf(DEFAULT_PORT))), DEFAULT_PORT);
        String instanceId = cliOpts.getOrDefault(OPT_INSTANCE_ID,
                cliOpts.getOrDefault(OPT_INSTANCE_ID_SHORT, "adapter-" + UUID.randomUUID().toString().substring(0, 8)));
        String capabilityFile = cliOpts.getOrDefault(OPT_CAPABILITY,
                cliOpts.getOrDefault(OPT_CAPABILITY_SHORT, null));
        String description = cliOpts.getOrDefault(OPT_DESCRIPTION,
                cliOpts.getOrDefault(OPT_DESCRIPTION_SHORT,
                cliOpts.get(OPT_DESCRIPTION_SHORT2)));
        String group = cliOpts.getOrDefault(OPT_GROUP, "adapters");
        int maxConcurrency = parseInt(cliOpts.getOrDefault(OPT_MAX_CONCURRENCY, "1"), 1);
        String configWorkspaceDir = readPropertyFromFile("workspace.dir");
        String workspaceDir = WorkspaceDirResolver.resolve(configWorkspaceDir);
        String wsSource = WorkspaceDirResolver.getSourceLabel(configWorkspaceDir);

        // If workspace not explicitly configured, try fetching from Gateway (matches core worker behavior)
        if ("default".equals(wsSource)) {
            String gwWorkspace = fetchWorkspaceFromGateway(args);
            if (gwWorkspace != null) {
                workspaceDir = gwWorkspace;
                wsSource = "gateway";
            }
        }

        // Override user.dir with resolved workspace (matches core behavior)
        System.setProperty("diatom.original.user.dir", workspaceDir);
        System.setProperty("user.dir", workspaceDir);

        // Security options
        String cliLevel = cliOpts.getOrDefault(OPT_LEVEL, cliOpts.get(OPT_LEVEL_SHORT));
        String cliPolicy = cliOpts.getOrDefault(OPT_APPROVAL, cliOpts.get(OPT_APPROVAL_SHORT));
        String cliMode = cliOpts.getOrDefault(OPT_MODE, cliOpts.get(OPT_MODE_SHORT));

        // ---- 2. Initialize PluginClassLoader and discover AgentAdapter ----
        PluginClassLoader pluginLoader = PluginClassLoader.init(
                DirectoryLayout.getPluginsDir(DirectoryLayout.getJarDir()),
                DirectoryLayout.getGlobalPluginsDir()
        );
        if (pluginLoader.hasPlugins()) {
            pluginLoader.registerJdbcDrivers();
            // TCCL already set to PluginClassLoader by init()
        }

        // Read agent.type from config: system property → application.properties
        String agentType = System.getProperty("diatom.agent.type");
        if (agentType == null || agentType.isEmpty()) {
            agentType = readPropertyFromFile("agent.type");
        }

        // Discover AgentAdapter via PluginClassLoader (per-JAR isolation) or classpath ServiceLoader
        List<AgentAdapter> allAdapters;
        List<AgentAdapter> adapters;
        if (pluginLoader.hasPlugins()) {
            // Per-JAR isolation: each plugin driver loaded from its own classloader
            allAdapters = pluginLoader.loadAll(AgentAdapter.class);
            adapters = filterByType(allAdapters, agentType);
        } else {
            // No plugins: fall back to classpath ServiceLoader
            allAdapters = discoverAllSpi(AgentAdapter.class);
            adapters = filterByType(allAdapters, agentType);
        }

        if (adapters.isEmpty()) {
            if (agentType != null) {
                String available = allAdapters.stream().map(AgentAdapter::getAgentType)
                        .collect(java.util.stream.Collectors.joining(", "));
                if (available.isEmpty()) {
                    logger.error("No AgentAdapter implementation found. "
                            + "Ensure a driver JAR is in plugins/ directory.");
                    System.err.println("ERROR: No AgentAdapter implementation found. "
                            + "Add a driver JAR to ~/.diatom/plugins/.");
                } else {
                    logger.error("No AgentAdapter matched agent.type='{}'. Available types: {}",
                            agentType, available);
                    System.err.println("ERROR: No AgentAdapter matched agent.type='" + agentType
                            + "'. Available: " + available);
                }
            } else {
                logger.error("No AgentAdapter implementation found. "
                        + "Ensure a driver JAR is in plugins/ directory.");
                System.err.println("ERROR: No AgentAdapter implementation found. "
                        + "Add a driver JAR to ~/.diatom/plugins/.");
            }
            return 1;
        }

        AgentAdapter adapter = adapters.get(0);
        String resolvedAgentType = adapter.getAgentType();
        logger.info("Loaded AgentAdapter: type='{}', class={}", resolvedAgentType, adapter.getClass().getName());

        // ---- 3. Resolve security config ----
        SecurityConfigParser securityParser = new SecurityConfigParser(cliLevel, cliPolicy, cliMode);
        SandboxLevel sandboxLevel = securityParser.getLevel();
        ApprovalPolicy approvalPolicy = securityParser.getApprovalPolicy();

        // Find SecurityMapper from plugins — prefer agent-specific, fallback to default
        SecurityMapper securityMapper = findSecurityMapper(resolvedAgentType, pluginLoader);

        // Map security config to agent-native config
        Map<String, String> securityConfig = securityMapper.mapSecurity(sandboxLevel, approvalPolicy);
        logger.info("Security config: level={}, policy={}, mapped={}",
                sandboxLevel, approvalPolicy, securityConfig);

        // ---- 4. Build adapter config and init ----
        Map<String, String> adapterConfig = new HashMap<>();
        adapterConfig.putAll(securityConfig);
        // Load from application.properties if available
        loadProperties(adapterConfig);

        // CLI overrides for api.key
        if (cliOpts.containsKey("api.key") || cliOpts.containsKey("--api-key") || cliOpts.containsKey("-k")) {
            String key = cliOpts.getOrDefault("api.key",
                    cliOpts.getOrDefault("--api-key", cliOpts.get("-k")));
            adapterConfig.put("api.key", key);
        }

        boolean hasApiKey = adapterConfig.containsKey("api.key")
                && !adapterConfig.get("api.key").isEmpty();

        adapter.init(adapterConfig);

        // Resolve gateway URL: CLI > GATEWAY_URL env > gateway.url config > default
        resolveGatewayUrl(args);
        String gatewayUrl = System.getProperty("gateway.url", "http://127.0.0.1:8080");
        String gwUrlSource = getGatewayUrlSource();

        // ---- 5. Load capability.md (optional) ----
        Path jarDir = DirectoryLayout.getJarDir();
        // Adapter never uses P1 (LLM generation) — it bridges to an external agent, has no direct LLM access
        CapabilityLoader capLoader = new CapabilityLoader(jarDir, description, capabilityFile, false);
        String capabilityContent = capLoader.load();

        // ---- 6. Create workspace directory and set on adapter ----
        Path workspacePath = Paths.get(workspaceDir);
        try {
            Files.createDirectories(workspacePath);
        } catch (IOException e) {
            logger.warn("Failed to create workspace directory {}: {}", workspaceDir, e.getMessage());
        }
        adapter.setWorkspace(workspaceDir);
        logger.info("Workspace: {} (from {})", workspaceDir, wsSource);

        // ---- 7. Initialize database (Hibernate + HikariCP) ----
        try {
            HibernateConfig hibernateConfig = new HibernateConfig();
            hibernateConfig.buildSessionFactory();
            logger.info("Database initialized at: {}", hibernateConfig.getJdbcUrl());
        } catch (Exception e) {
            logger.error("Failed to initialize database", e);
            System.err.println("ERROR: Failed to initialize database: " + e.getMessage());
            return 1;
        }

        // ---- 8. Determine port (auto-retry on conflict) ----
        int port = findAvailablePort(requestedPort);

        // ---- 9. Start HTTP server ----
        WorkerHttpServer httpServer;
        try {
            httpServer = new WorkerHttpServer(port, adapter);
            httpServer.setCapabilityContent(capabilityContent);
            httpServer.start();
        } catch (IOException e) {
            logger.error("Failed to start HTTP server on port {}: {}", port, e.getMessage());
            System.err.println("ERROR: Failed to start HTTP server on port " + port + ": " + e.getMessage());
            return 1;
        }

        // ---- 9. Build registration payload ----
        AgentInfo agentInfo = adapter.getAgentInfo();

        RegistrationPayload regPayload = new RegistrationPayload(
                instanceId,
                getLocalHost(),
                port,
                agentInfo.model(),
                agentInfo.traits(),
                agentInfo.capabilities(),
                group,
                Math.min(maxConcurrency, agentInfo.maxConcurrency()),
                workspaceDir,
                0.0,
                0,
                System.currentTimeMillis(),
                getVersion(),
                "adapter",
                false,
                false);

        // ---- 10. Register with Gateway ----
        WorkerRegistrationClient regClient = new WorkerRegistrationClient(gatewayUrl, regPayload);

        boolean registered = regClient.register();
        if (!registered) {
            logger.warn("Initial registration failed. Will retry via heartbeat.");
            // Don't exit — heartbeat will retry
        }

        regClient.startHeartbeat();

        // ---- 11. Register shutdown hook ----
        final AtomicBoolean shuttingDown = new AtomicBoolean(false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (shuttingDown.compareAndSet(false, true)) {
                logger.info("Shutting down adapter gracefully...");

                // 1. Stop accepting new requests
                httpServer.stop(1);

                // 2. Wait briefly for active tasks
                try {
                    Thread.sleep(Math.min(GRACEFUL_SHUTDOWN_TIMEOUT_MS, 5000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 3. Deregister from Gateway
                regClient.deregister();

                // 4. Shutdown adapter
                adapter.shutdown();

                // 5. Shutdown database
                HibernateConfig.shutdown();

                // 6. Deregister JDBC drivers
                pluginLoader.deregisterJdbcDrivers();

                logger.info("Adapter shutdown complete.");
            }
        }));

        logger.info("Adapter started: instanceId={}, agentType={}, port={}, gatewayUrl={}",
                instanceId, agentType, port, gatewayUrl);

        // Print banner
        printBanner();
        String capLine = capabilityContent == null
                ? "  [Config] Capability: none (awaiting gateway task push)\n"
                : "";
        System.out.print(("""
                  [Diatom Adapter v%s] instance=%s agent=%s port=%d gateway=%s
                %s  [Config] Sandbox level: %s, Approval policy: %s
                """).formatted(getVersion(), instanceId, resolvedAgentType, port, gatewayUrl,
                capLine, sandboxLevel.name().toLowerCase(), approvalPolicy.name().toLowerCase()));

        // Block main thread
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return 0;
    }

    // ---- SPI: find SecurityMapper ----

    private static SecurityMapper findSecurityMapper(String agentType, PluginClassLoader pluginLoader) {
        // 1. Plugin-based discovery with per-JAR isolation
        if (pluginLoader != null && pluginLoader.hasPlugins()) {
            List<SecurityMapper> matched = pluginLoader.loadByType(SecurityMapper.class, agentType, "getAgentType");
            if (!matched.isEmpty()) {
                logger.info("Found agent-specific SecurityMapper from plugin: {}", matched.get(0).getClass().getName());
                return matched.get(0);
            }
        }

        // 2. Classpath ServiceLoader fallback
        List<SecurityMapper> allMappers = discoverAllSpi(SecurityMapper.class);

        // Find agent-specific mapper first
        for (SecurityMapper mapper : allMappers) {
            String type = mapper.getAgentType();
            if (type != null && type.equals(agentType)) {
                logger.info("Found agent-specific SecurityMapper: {}", mapper.getClass().getName());
                return mapper;
            }
        }

        // Fallback: any mapper without agentType constraint
        for (SecurityMapper mapper : allMappers) {
            if (mapper.getAgentType() == null) {
                return mapper;
            }
        }

        return new DefaultSecurityMapper();
    }

    /**
     * Discover all SPI implementations via ServiceLoader (default classloader).
     */
    private static <T> List<T> discoverAllSpi(Class<T> spiType) {
        List<T> result = new ArrayList<>();
        ServiceLoader<T> loader = ServiceLoader.load(spiType);
        for (T instance : loader) {
            result.add(instance);
        }
        return result;
    }

    /**
     * Filter a list of AgentAdapter by getAgentType() return value.
     * Returns matching adapters, or all adapters if agentType is null/empty
     * (auto-detect: only when exactly one adapter is available).
     */
    private static List<AgentAdapter> filterByType(List<AgentAdapter> adapters, String agentType) {
        if (agentType != null && !agentType.isEmpty()) {
            List<AgentAdapter> matched = new ArrayList<>();
            for (AgentAdapter a : adapters) {
                if (agentType.equals(a.getAgentType())) {
                    matched.add(a);
                }
            }
            return matched;
        }
        // No agent.type configured: auto-select only if exactly one adapter
        if (adapters.size() == 1) {
            return adapters;
        }
        if (adapters.size() > 1) {
            logger.warn("Multiple AgentAdapter implementations found but no agent.type configured. "
                    + "Available types: {}",
                    adapters.stream().map(AgentAdapter::getAgentType)
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        return adapters;
    }

    // ---- CLI parsing ----

    private static Map<String, String> parseCliArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--") || arg.startsWith("-")) {
                // Check if next arg exists and is not another flag
                if (i + 1 < args.length && !args[i + 1].startsWith("--") && !args[i + 1].startsWith("-")) {
                    opts.put(arg, args[++i]);
                } else {
                    opts.put(arg, "");
                }
            }
        }
        return opts;
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -jar diatom-adapter-core.jar [options]

                Worker options:
                  --port, -P <port>              HTTP server port (default: %d, auto-conflict avoidance)
                  --instance-id, -id <id>        Worker unique identifier
                  --gateway-url, -u <url>        Gateway address for registration and heartbeat
                  --capability, -c <file>        capability.md file path
                  --description, -desc <text>    Text description (conditionally LLM-generates capability)
                  --group <group>                Worker group (default: adapters)
                  --max-concurrency <n>          Maximum concurrent tasks (default: 1)
                  --workspace-dir, -w <dir>      Workspace directory

                Security options:
                  --level, -l <level>            Sandbox level: read-only / workspace / full (default: workspace)
                  --approval-policy, -a <policy> Approval policy: ask / auto / silent (default: silent)
                  --mode, -m <preset>            Preset: normal / auto / silent / readonly / unrestricted

                  --help, -h                     Show this help
                """.formatted(DEFAULT_PORT));
    }

    // ---- Port resolution ----

    private static int findAvailablePort(int preferredPort) {
        for (int i = 0; i < MAX_PORT_RETRIES; i++) {
            int port = preferredPort + i;
            if (isPortAvailable(port)) {
                return port;
            }
            logger.debug("Port {} is in use, trying {}", port, port + 1);
        }
        throw new RuntimeException("No available port in range "
                + preferredPort + "-" + (preferredPort + MAX_PORT_RETRIES - 1));
    }

    private static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- Networking ----

    private static String getLocalHost() {
        try {
            // Iterate all NICs to find a real routable IPv4 address,
            // skipping loopback, virtual/VPN interfaces.
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    java.net.NetworkInterface ni = interfaces.nextElement();
                    try {
                        if (ni.isLoopback() || !ni.isUp()) continue;
                    } catch (java.net.SocketException e) {
                        continue;
                    }
                    String name = ni.getName().toLowerCase();
                    String displayName = ni.getDisplayName().toLowerCase();
                    if (name.contains("vmnet") || name.contains("vboxnet")
                            || name.contains("docker") || name.contains("veth")
                            || displayName.contains("virtual") || displayName.contains("vmware")
                            || displayName.contains("vpn") || displayName.contains("tap")
                            || displayName.contains("tun") || displayName.contains("bridge")
                            || displayName.contains("hyper-v") || displayName.contains("pseudo")) {
                        continue;
                    }
                    java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress addr = addrs.nextElement();
                        if (addr instanceof java.net.Inet4Address
                                && !addr.isLoopbackAddress()
                                && !addr.isLinkLocalAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (java.net.SocketException e) {
            // fall through
        }
        // Fallback
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    // ---- Properties loading (supports .properties + .yml/.yaml) ----

    private static final String[] CONFIG_FILE_NAMES = {
        "application.yml", "application.yaml", "application.properties"
    };

    /**
     * Load configuration from multiple locations and formats.
     *
     * <p>Search locations (highest → lowest priority):</p>
     * <ol>
     *   <li>{jarDir}/application.{yml,yaml,properties}</li>
     *   <li>{jarDir}/.diatom/application.{yml,yaml,properties}</li>
     *   <li>classpath application.{yml,yaml,properties}</li>
     * </ol>
     *
     * <p>At each level, YAML keys override properties keys.</p>
     */
    private static void loadProperties(Map<String, String> config) {
        Path jarDir = DirectoryLayout.getJarDir();

        // Highest priority first: {jarDir}/
        loadConfigFromDir(config, jarDir);
        // Then: {jarDir}/.diatom/
        loadConfigFromDir(config, jarDir.resolve(DirectoryLayout.DIATOM_DIR));
        // Lowest priority: classpath
        loadConfigFromClasspath(config);
    }

    /**
     * Load config from classpath resources (lowest priority).
     */
    private static void loadConfigFromClasspath(Map<String, String> config) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = AdapterBootstrap.class.getClassLoader();

        // YAML first (higher priority than properties at same level)
        loadYamlFromClasspath(config, cl, "application.yml");
        loadYamlFromClasspath(config, cl, "application.yaml");
        // Then properties (only for keys not already set by YAML)
        loadPropsFromClasspath(config, cl, "application.properties");
    }

    private static void loadYamlFromClasspath(Map<String, String> config, ClassLoader cl, String name) {
        try (InputStream is = cl.getResourceAsStream(name)) {
            if (is == null) return;
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> yamlProps = yamlMapper.readValue(is, LinkedHashMap.class);
            Map<String, String> temp = new LinkedHashMap<>();
            flattenYaml("", yamlProps, temp);
            for (Map.Entry<String, String> e : temp.entrySet()) {
                if (!config.containsKey(e.getKey())) {
                    config.put(e.getKey(), e.getValue());
                }
            }
            logger.debug("Loaded config from classpath: {}", name);
        } catch (Exception e) {
            logger.debug("Failed to load config from classpath {}: {}", name, e.getMessage());
        }
    }

    private static void loadPropsFromClasspath(Map<String, String> config, ClassLoader cl, String name) {
        try (InputStream is = cl.getResourceAsStream(name)) {
            if (is == null) return;
            java.util.Properties props = new java.util.Properties();
            props.load(is);
            for (String key : props.stringPropertyNames()) {
                if (!config.containsKey(key)) {
                    config.put(key, props.getProperty(key));
                }
            }
            logger.debug("Loaded config from classpath: {}", name);
        } catch (Exception e) {
            logger.debug("Failed to load config from classpath {}: {}", name, e.getMessage());
        }
    }

    /**
     * Try all config file names in a directory.
     * YAML is loaded first; properties only fill keys not already set by YAML.
     */
    private static void loadConfigFromDir(Map<String, String> config, Path dir) {
        // YAML first (higher priority than properties at same level)
        loadYamlFromDir(config, dir, "application.yml");
        loadYamlFromDir(config, dir, "application.yaml");
        // Then properties (only for keys not already set by YAML)
        loadPropsFromDir(config, dir, "application.properties");
    }

    private static void loadYamlFromDir(Map<String, String> config, Path dir, String name) {
        Path filePath = dir.resolve(name);
        if (!Files.exists(filePath)) return;
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> yamlProps = yamlMapper.readValue(filePath.toFile(), LinkedHashMap.class);
            Map<String, String> temp = new LinkedHashMap<>();
            flattenYaml("", yamlProps, temp);
            for (Map.Entry<String, String> e : temp.entrySet()) {
                if (!config.containsKey(e.getKey())) {
                    config.put(e.getKey(), e.getValue());
                }
            }
            logger.debug("Loaded config from YAML: {}", filePath);
        } catch (Exception e) {
            logger.warn("Failed to load {}: {}", filePath, e.getMessage());
        }
    }

    private static void loadPropsFromDir(Map<String, String> config, Path dir, String name) {
        Path filePath = dir.resolve(name);
        if (!Files.exists(filePath)) return;
        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (!config.containsKey(key)) {
                        config.put(key, value);
                    }
                }
            }
            logger.debug("Loaded config from properties: {}", filePath);
        } catch (Exception e) {
            logger.warn("Failed to load {}: {}", filePath, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void flattenYaml(String prefix, Map<String, Object> source, Map<String, String> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flattenYaml(key, (Map<String, Object>) value, target);
            } else if (value instanceof String) {
                target.put(key, (String) value);
            } else if (value != null) {
                target.put(key, value.toString());
            }
        }
    }

    /**
     * Read a single property value from config files (all locations, both .properties and .yml).
     * Priority order: {jarDir}/ > {jarDir}/.diatom/ > classpath
     * Returns null if the key is not found.
     */
    private static String readPropertyFromFile(String key) {
        // Search in same order as loadProperties()
        Path jarDir = DirectoryLayout.getJarDir();
        Path[] searchDirs = {
                jarDir,
                jarDir.resolve(DirectoryLayout.DIATOM_DIR),
        };
        for (Path dir : searchDirs) {
            String val = readPropertyFromDir(key, dir);
            if (val != null) return val;
        }
        // Fallback: classpath
        String cpVal = readPropertyFromClasspath(key);
        if (cpVal != null) return cpVal;
        return null;
    }

    private static String readPropertyFromDir(String key, Path dir) {
        // YAML first (higher priority)
        String yamlVal = readYamlProperty(key, dir.resolve("application.yml"));
        if (yamlVal != null) return yamlVal;
        yamlVal = readYamlProperty(key, dir.resolve("application.yaml"));
        if (yamlVal != null) return yamlVal;
        // Then properties
        return readPropsProperty(key, dir.resolve("application.properties"));
    }

    private static String readYamlProperty(String key, Path filePath) {
        if (!Files.exists(filePath)) return null;
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> yamlProps = yamlMapper.readValue(filePath.toFile(), LinkedHashMap.class);
            Map<String, String> flat = new LinkedHashMap<>();
            flattenYaml("", yamlProps, flat);
            return flat.get(key);
        } catch (Exception e) {
            logger.debug("Failed to read {} from {}: {}", key, filePath, e.getMessage());
            return null;
        }
    }

    private static String readPropsProperty(String key, Path filePath) {
        if (!Files.exists(filePath)) return null;
        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String k = line.substring(0, eq).trim();
                    if (key.equals(k)) {
                        return line.substring(eq + 1).trim();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to read {} from {}: {}", key, filePath, e.getMessage());
        }
        return null;
    }

    private static String readPropertyFromClasspath(String key) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = AdapterBootstrap.class.getClassLoader();
        // YAML first
        String yamlVal = readYamlPropertyFromClasspath(cl, key, "application.yml");
        if (yamlVal != null) return yamlVal;
        yamlVal = readYamlPropertyFromClasspath(cl, key, "application.yaml");
        if (yamlVal != null) return yamlVal;
        // Then properties
        try (InputStream is = cl.getResourceAsStream("application.properties")) {
            if (is == null) return null;
            java.util.Properties props = new java.util.Properties();
            props.load(is);
            return props.getProperty(key);
        } catch (Exception e) {
            logger.debug("Failed to read {} from classpath: {}", key, e.getMessage());
            return null;
        }
    }

    private static String readYamlPropertyFromClasspath(ClassLoader cl, String key, String name) {
        try (InputStream is = cl.getResourceAsStream(name)) {
            if (is == null) return null;
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> yamlProps = yamlMapper.readValue(is, LinkedHashMap.class);
            Map<String, String> flat = new LinkedHashMap<>();
            flattenYaml("", yamlProps, flat);
            return flat.get(key);
        } catch (Exception e) {
            logger.debug("Failed to read {} from classpath {}: {}", key, name, e.getMessage());
            return null;
        }
    }

    /**
     * Try all config file names in a directory. Used for legacy multi-file iteration.
     * Kept for backward compatibility but not called by the new priority chain directly.
     */

    // ---- Banner ----

    private static void printBanner() {
        try {
            InputStream is = AdapterBootstrap.class.getResourceAsStream("/banner.txt");
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
                reader.close();
            }
        } catch (Exception ignored) {
        }
    }

    // ---- Gateway URL resolution ----

    /**
     * Resolve gateway URL and set it as the {@code gateway.url} system property.
     * Priority: CLI {@code -u/--gateway-url} &gt; {@code GATEWAY_URL} env &gt;
     * {@code gateway.url} config &gt; default {@code http://127.0.0.1:8080}.
     */
    private static void resolveGatewayUrl(String[] args) {
        String gatewayUrlFromArgs = parseGatewayUrlFromArgs(args);
        if (gatewayUrlFromArgs != null) {
            gatewayUrlFromArgs = normalizeGatewayUrl(gatewayUrlFromArgs);
            System.setProperty("gateway.url", gatewayUrlFromArgs);
        }
        // Fallback: GATEWAY_URL environment variable
        if (System.getProperty("gateway.url") == null || System.getProperty("gateway.url").isEmpty()) {
            String envGwUrl = System.getenv("GATEWAY_URL");
            if (envGwUrl != null && !envGwUrl.isEmpty()) {
                envGwUrl = normalizeGatewayUrl(envGwUrl);
                System.setProperty("gateway.url", envGwUrl);
                logger.info("Gateway URL set from GATEWAY_URL env: {}", envGwUrl);
            }
        }
        // Fallback: gateway.url from config file
        if (System.getProperty("gateway.url") == null || System.getProperty("gateway.url").isEmpty()) {
            String cfgGwUrl = readPropertyFromFile("gateway.url");
            if (cfgGwUrl != null && !cfgGwUrl.isEmpty()) {
                System.setProperty("gateway.url", cfgGwUrl);
                logger.info("Gateway URL set from config: {}", cfgGwUrl);
            }
        }
        // Default
        if (System.getProperty("gateway.url") == null || System.getProperty("gateway.url").isEmpty()) {
            System.setProperty("gateway.url", "http://127.0.0.1:8080");
        }
    }

    /**
     * Return a human-readable label describing which source resolved the gateway URL.
     */
    private static String getGatewayUrlSource() {
        String val = System.getProperty("gateway.url", "");
        if (val.isEmpty()) return "default";
        // Try to determine source by checking what's set
        if (parseGatewayUrlFromArgs(new String[]{}) != null) return "CLI arg";
        // Simple heuristic: check if the user likely set it
        return "resolved";
    }

    /**
     * Parse -u/--gateway-url from CLI args, supporting both
     * {@code --gateway-url value} and {@code --gateway-url=value} syntax.
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

    /**
     * Try to fetch workspace directory from Gateway via {@code GET /gateway/v1/workspace}.
     * Returns the workspace path if successful, or {@code null} if Gateway is unreachable
     * or returns no workspace (matches core worker behavior).
     */
    private static String fetchWorkspaceFromGateway(String[] args) {
        String gatewayUrl = null;

        // Try CLI args first
        String fromArgs = parseGatewayUrlFromArgs(args);
        if (fromArgs != null) {
            gatewayUrl = fromArgs;
        }
        // Then env var
        if (gatewayUrl == null) {
            gatewayUrl = System.getenv("GATEWAY_URL");
        }
        // Then config file
        if (gatewayUrl == null) {
            gatewayUrl = readPropertyFromFile("gateway.url");
        }
        if (gatewayUrl == null || gatewayUrl.isEmpty()) {
            return null;
        }
        gatewayUrl = normalizeGatewayUrl(gatewayUrl.trim());

        try {
            URL url = new URL(gatewayUrl + "/gateway/v1/workspace");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readBody(conn);
                conn.disconnect();
                // Parse JSON: {"workspaceDir":"..."}
                try {
                    java.util.Map<String, String> parsed = JsonUtil.toStringMap(body);
                    String ws = parsed != null ? parsed.get("workspaceDir") : null;
                    if (ws != null && !ws.isEmpty()) {
                        logger.info("Fetched workspace from Gateway: {}", ws);
                        return ws;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to parse workspace response: {}", e.getMessage());
                }
            } else {
                conn.disconnect();
            }
        } catch (java.net.ConnectException e) {
            logger.debug("Gateway not reachable for workspace fetch: {}", e.getMessage());
        } catch (Exception e) {
            logger.debug("Failed to fetch workspace from Gateway: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Normalize gateway URL: auto-prepend http:// if protocol is missing.
     */
    private static String normalizeGatewayUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            logger.warn("Gateway URL missing protocol, prepending http:// ({})", trimmed);
            return "http://" + trimmed;
        }
        return trimmed;
    }

    // ---- Version ----

    private static String getVersion() {
        Package pkg = AdapterBootstrap.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null ? version : "1.0.0";
    }

    private static int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Read the full response body from an HttpURLConnection as a UTF-8 string.
     */
    private static String readBody(HttpURLConnection conn) {
        try {
            InputStream is = conn.getResponseCode() >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "";
            java.util.Scanner scanner = new java.util.Scanner(is, StandardCharsets.UTF_8).useDelimiter("\\A");
            String body = scanner.hasNext() ? scanner.next() : "";
            scanner.close();
            return body;
        } catch (Exception e) {
            return "";
        }
    }
}
