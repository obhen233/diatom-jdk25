package com.github.obhen233.core.gateway;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.security.SandboxLevel;
import com.github.obhen233.core.security.ApprovalPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI argument parsing and configuration resolution utilities for Gateway mode.
 * <p>
 * Provides 4-level priority parsing (CLI arg → mode preset → env var → config file → default)
 * for all Gateway configuration parameters including port, instance ID, sandbox settings,
 * approval policy, upstream gateway URL, and cluster configuration.
 * </p>
 */
public final class GatewayArgParser {
    private static final Logger logger = LoggerFactory.getLogger(GatewayArgParser.class);

    private GatewayArgParser() {}

    // ==================== Port ====================

    /**
     * Return the raw --port/-P argument string value, or null if not specified.
     */
    public static String parsePortArgValue(String[] args) {
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
     * Resolve port with auto-increment on conflict.
     * Tests up to 10 ports starting from preferredPort.
     */
    public static int resolvePort(int preferredPort) {
        for (int attempt = 0; attempt < 10; attempt++) {
            int port = preferredPort + attempt;
            try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
                logger.info("Resolved port: {} (preferred: {})", port, preferredPort);
                return port;
            } catch (java.io.IOException e) {
                logger.warn("Port {} is in use, trying next", port);
            }
        }
        throw new RuntimeException("No available port in range "
                + preferredPort + "-" + (preferredPort + 9));
    }

    // ==================== Instance ID ====================

    public static String parseInstanceIdFromArgs(String[] args) {
        if (args == null) return "gateway-" + System.currentTimeMillis();
        for (int i = 0; i < args.length; i++) {
            if ("--instance-id".equals(args[i]) || "-i".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) return args[i + 1];
            }
            if (args[i].startsWith("--instance-id=")) {
                String val = args[i].substring("--instance-id=".length()).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return "gateway-" + System.currentTimeMillis();
    }

    // ==================== Daemonize ====================

    public static boolean parseDaemonizeArg(String[] args) {
        if (args == null) return false;
        for (int i = 0; i < args.length; i++) {
            if ("--daemonize".equals(args[i]) || "-d".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return "true".equalsIgnoreCase(args[i + 1]);
                }
                return true;
            }
            if (args[i].startsWith("--daemonize=")) {
                return "true".equalsIgnoreCase(args[i].substring("--daemonize=".length()));
            }
        }
        return false;
    }

    // ==================== Queue ====================

    public static boolean parseQueueArg(String[] args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if ("--queue".equals(args[i]) || "-q".equals(args[i])) {
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        return "true".equalsIgnoreCase(args[i + 1]);
                    }
                    return true;
                }
                if (args[i].startsWith("--queue=")) {
                    return "true".equalsIgnoreCase(args[i].substring("--queue=".length()));
                }
            }
        }
        String envVal = System.getenv("DIATOM_GATEWAY_QUEUE_ENABLED");
        if (envVal != null) {
            return "true".equalsIgnoreCase(envVal.trim());
        }
        return false;
    }

    // ==================== Mode Preset ====================

    public static ModePreset parseModePreset(String[] args) {
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

    public static ModePreset resolveModePreset(String value) {
        if (value == null) return null;
        String lower = value.trim().toLowerCase();
        switch (lower) {
            case "normal":
                return new ModePreset(SandboxLevel.WORKSPACE, ApprovalPolicy.ASK);
            case "auto":
                return new ModePreset(SandboxLevel.WORKSPACE, ApprovalPolicy.AUTO);
            case "silent":
                return new ModePreset(SandboxLevel.WORKSPACE, ApprovalPolicy.SILENT);
            case "readonly":
            case "read-only":
                return new ModePreset(SandboxLevel.READ_ONLY, ApprovalPolicy.SILENT);
            case "unrestricted":
                return new ModePreset(SandboxLevel.FULL, ApprovalPolicy.AUTO);
            default:
                logger.warn("Unknown mode preset: {}, ignoring", value);
                return null;
        }
    }

    /**
     * Groups a SandboxLevel and ApprovalPolicy into a single preset tuple.
     */
    static class ModePreset {
        final SandboxLevel level;
        final ApprovalPolicy policy;
        ModePreset(SandboxLevel level, ApprovalPolicy policy) {
            this.level = level;
            this.policy = policy;
        }
    }

    // ==================== Approval Policy ====================

    public static ApprovalPolicy parseApprovalPolicy(String[] args, AppConfig config, ModePreset preset) {
        ApprovalPolicy cliValue = parseApprovalPolicyFromCli(args);
        if (cliValue != null) return cliValue;
        if (preset != null) return preset.policy;
        ApprovalPolicy envValue = parseApprovalPolicyFromEnv();
        if (envValue != null) return envValue;
        ApprovalPolicy cfgValue = parseApprovalPolicyFromConfig(config);
        if (cfgValue != null) return cfgValue;
        return ApprovalPolicy.ASK;
    }

    public static ApprovalPolicy parseApprovalPolicyFromCli(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--approval-policy".equals(args[i]) || "-a".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return parseApprovalPolicyValue(args[i + 1]);
                }
                return ApprovalPolicy.SILENT;
            }
            if (args[i].startsWith("--approval-policy=")) {
                return parseApprovalPolicyValue(args[i].substring("--approval-policy=".length()));
            }
            if ("--auto-approve".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return "true".equalsIgnoreCase(args[i + 1])
                        ? ApprovalPolicy.SILENT : ApprovalPolicy.ASK;
                }
                return ApprovalPolicy.SILENT;
            }
        }
        return null;
    }

    public static ApprovalPolicy parseApprovalPolicyFromEnv() {
        String envVal = System.getenv("DIATOM_APPROVAL_POLICY");
        if (envVal != null && !envVal.isEmpty()) return parseApprovalPolicyValue(envVal);
        String oldEnvVal = System.getenv("DIATOM_AUTO_APPROVE");
        if (oldEnvVal != null && !oldEnvVal.isEmpty()) {
            return "true".equalsIgnoreCase(oldEnvVal)
                ? ApprovalPolicy.SILENT : ApprovalPolicy.ASK;
        }
        return null;
    }

    public static ApprovalPolicy parseApprovalPolicyFromConfig(AppConfig config) {
        String cfgVal = config.getProperty("diatom.approval-policy", "");
        if (!cfgVal.isEmpty()) return parseApprovalPolicyValue(cfgVal);
        String oldCfgVal = config.getProperty("diatom.auto-approve", "");
        if (!oldCfgVal.isEmpty()) {
            return "true".equalsIgnoreCase(oldCfgVal)
                ? ApprovalPolicy.SILENT : ApprovalPolicy.ASK;
        }
        return null;
    }

    public static ApprovalPolicy parseApprovalPolicyValue(String value) {
        if (value == null) return ApprovalPolicy.ASK;
        String lower = value.trim().toLowerCase();
        switch (lower) {
            case "ask": return ApprovalPolicy.ASK;
            case "auto": return ApprovalPolicy.AUTO;
            case "silent": return ApprovalPolicy.SILENT;
            case "custom": return ApprovalPolicy.CUSTOM;
            default:
                logger.warn("Unknown approval policy: {}, defaulting to ASK", value);
                return ApprovalPolicy.ASK;
        }
    }

    // ==================== Sandbox Level ====================

    public static SandboxLevel parseSandboxLevel(String[] args, AppConfig config, ModePreset preset) {
        SandboxLevel cliValue = parseSandboxLevelFromCli(args);
        if (cliValue != null) return cliValue;
        if (preset != null) return preset.level;
        SandboxLevel envValue = parseSandboxLevelFromEnv();
        if (envValue != null) return envValue;
        SandboxLevel cfgValue = parseSandboxLevelFromConfig(config);
        if (cfgValue != null) return cfgValue;
        return SandboxLevel.WORKSPACE;
    }

    public static SandboxLevel parseSandboxLevelFromCli(String[] args) {
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

    public static SandboxLevel parseSandboxLevelFromEnv() {
        String envVal = System.getenv("DIATOM_LEVEL");
        if (envVal != null && !envVal.isEmpty()) return parseSandboxLevelValue(envVal);
        return null;
    }

    public static SandboxLevel parseSandboxLevelFromConfig(AppConfig config) {
        String cfgVal = config.getProperty("diatom.level", "");
        if (!cfgVal.isEmpty()) return parseSandboxLevelValue(cfgVal);
        return null;
    }

    public static SandboxLevel parseSandboxLevelValue(String value) {
        if (value == null) return SandboxLevel.WORKSPACE;
        String lower = value.trim().toLowerCase();
        switch (lower) {
            case "read-only":
            case "readonly":
                return SandboxLevel.READ_ONLY;
            case "workspace":
                return SandboxLevel.WORKSPACE;
            case "full":
                return SandboxLevel.FULL;
            default:
                logger.warn("Unknown sandbox level: {}, defaulting to WORKSPACE", value);
                return SandboxLevel.WORKSPACE;
        }
    }

    /**
     * Parse --sandbox (-s) argument value from CLI args.
     */
    public static String parseSandboxDirArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--sandbox".equals(args[i]) || "-s".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) return args[i + 1];
            }
            if (args[i].startsWith("--sandbox=")) {
                String val = args[i].substring("--sandbox=".length()).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    /**
     * Resolve sandbox directory with 4-level priority and validation.
     */
    public static String resolveSandboxDir(String[] args, AppConfig config) {
        String source = "default";
        String sandboxDir = null;

        // Priority 1: CLI argument
        String cliArg = parseSandboxDirArg(args);
        if (cliArg != null) {
            sandboxDir = cliArg;
            source = "--sandbox CLI argument";
        }

        // Priority 2: environment variable
        if (sandboxDir == null) {
            String envDir = System.getenv("DIATOM_SANDBOX_DIR");
            if (envDir != null && !envDir.trim().isEmpty()) {
                sandboxDir = envDir.trim();
                source = "DIATOM_SANDBOX_DIR env";
            }
        }

        // Priority 3: config property
        if (sandboxDir == null) {
            sandboxDir = config.getProperty("gateway.collaboration.sandbox.dir", null);
            if (sandboxDir != null && !sandboxDir.trim().isEmpty()) {
                sandboxDir = sandboxDir.trim();
                source = "gateway.collaboration.sandbox.dir config";
            } else {
                sandboxDir = null;
            }
        }

        // Priority 4: default
        if (sandboxDir == null) {
            sandboxDir = System.getProperty("java.io.tmpdir") + "/.diatom-sandbox";
        }

        // Validate path
        Path path = Paths.get(sandboxDir);
        if (Files.exists(path)) {
            if (!Files.isDirectory(path)) {
                logger.warn("Sandbox path exists but is not a directory: {}. Falling back to default.", sandboxDir);
                sandboxDir = System.getProperty("java.io.tmpdir") + "/.diatom-sandbox";
                source = "fallback (default after validation failure)";
                path = Paths.get(sandboxDir);
            } else if (!Files.isWritable(path)) {
                logger.warn("Sandbox directory is not writable: {}. Falling back to default.", sandboxDir);
                sandboxDir = System.getProperty("java.io.tmpdir") + "/.diatom-sandbox";
                source = "fallback (default after validation failure)";
                path = Paths.get(sandboxDir);
            }
        } else {
            try {
                Files.createDirectories(path);
                logger.info("Created sandbox directory: {}", path);
            } catch (java.io.IOException e) {
                logger.warn("Failed to create sandbox directory: {}. Falling back to default.", sandboxDir, e);
                sandboxDir = System.getProperty("java.io.tmpdir") + "/.diatom-sandbox";
                source = "fallback (default after creation failure)";
                try {
                    Files.createDirectories(Paths.get(sandboxDir));
                } catch (java.io.IOException ex) {
                    logger.error("Failed to create default sandbox directory either: {}", sandboxDir, ex);
                }
            }
        }

        logger.info("Sandbox directory resolved: {} ({})", sandboxDir, source);
        return sandboxDir;
    }

    // ==================== Upstream Gateway ====================

    public static String parseUpstreamGatewayFromArgs(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--gateway-url".equals(args[i]) || "-u".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) return args[i + 1];
            }
            if (args[i].startsWith("--gateway-url=")) {
                String val = args[i].substring("--gateway-url=".length()).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    /**
     * Detect if an upstream Gateway URL is configured (chain mode).
     * Checks CLI args → GATEWAY_URL env → gateway.url config property.
     */
    public static boolean detectUpstream(String[] args, AppConfig config) {
        if (parseUpstreamGatewayFromArgs(args) != null) return true;
        if (System.getenv("GATEWAY_URL") != null && !System.getenv("GATEWAY_URL").isEmpty()) return true;
        if (config != null) {
            String cfgUrl = config.getProperty("gateway.url", "");
            if (!cfgUrl.isEmpty()) return true;
        }
        return false;
    }

    public static boolean hasClusterTcpIpPeers(AppConfig config) {
        String members = clusterProperty("cluster.hazelcast.tcpip.members", config, "");
        return !members.isEmpty();
    }

    /**
     * Read a cluster.* property with priority:
     *   1. System property (-Dcluster.xxx=value)
     *   2. Environment variable (CLUSTER_HAZELCAST_XXX=value, dots → underscores, uppercase)
     *   3. Config file (application.yml / application.properties)
     *   4. Default value
     */
    public static String clusterProperty(String key, AppConfig config, String defaultValue) {
        String sysVal = System.getProperty(key);
        if (sysVal != null && !sysVal.isEmpty()) {
            return sysVal;
        }
        String envKey = key.replace('.', '_').toUpperCase();
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isEmpty()) {
            return envVal;
        }
        if (config != null) {
            String cfgVal = config.getProperty(key, "");
            if (!cfgVal.isEmpty()) {
                return cfgVal;
            }
        }
        return defaultValue;
    }

    public static String normalizeGwUrl(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            logger.warn("Gateway URL missing protocol, prepending http:// ({})", trimmed);
            return "http://" + trimmed;
        }
        return trimmed;
    }

    /**
     * Check if a URL points to this gateway instance (localhost + same port).
     */
    public static boolean isSelfUrl(String url, int port) {
        if (url == null) return false;
        try {
            String host;
            int urlPort;
            String tmp = url;
            if (tmp.startsWith("http://")) tmp = tmp.substring(7);
            else if (tmp.startsWith("https://")) tmp = tmp.substring(8);
            int colon = tmp.indexOf(':');
            if (colon > 0) {
                host = tmp.substring(0, colon);
                int slash = tmp.indexOf('/');
                String portStr = slash > 0 ? tmp.substring(colon + 1, slash) : tmp.substring(colon + 1);
                urlPort = Integer.parseInt(portStr);
            } else {
                host = tmp;
                urlPort = 80;
            }
            if (urlPort != port) return false;
            return "127.0.0.1".equals(host) || "localhost".equals(host)
                    || "0.0.0.0".equals(host)
                    || InetAddress.getLocalHost().getHostAddress().equals(host)
                    || InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Shutdown ====================

    public static long parseShutdownTimeout(String[] args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if ("--shutdown-timeout".equals(args[i]) && i + 1 < args.length) {
                    try { return Long.parseLong(args[i + 1]); } catch (NumberFormatException ignored) {}
                }
            }
        }
        String sysProp = System.getProperty("shutdown.timeout", "30000");
        try { return Long.parseLong(sysProp); } catch (NumberFormatException e) { return 30000; }
    }

    public static boolean parseShutdownForce(String[] args) {
        String sysProp = System.getProperty("shutdown.force", "true");
        return "true".equalsIgnoreCase(sysProp);
    }
}
