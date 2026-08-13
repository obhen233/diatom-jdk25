package com.github.obhen233.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.obhen233.util.I18n;
import com.github.obhen233.spi.PasswordProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Service for parsing deploy.yaml and orchestrating pipeline execution.
 * Non-Spring version — dependencies are passed via constructor.
 */
public class PipelineService {

    private static final Logger logger = LoggerFactory.getLogger(PipelineService.class);

    private static final String DEPLOY_YAML = "deploy.yaml";

    private final RunnerRegistry runnerRegistry;
    private final String workspacePath;
    private final ObjectMapper yamlMapper;

    private PasswordProvider passwordProvider;
    private final Map<String, String> cachedPasswords = new HashMap<>();

    public PipelineService(RunnerRegistry runnerRegistry, String workspacePath) {
        this.runnerRegistry = runnerRegistry;
        this.workspacePath = workspacePath;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.findAndRegisterModules();
    }

    /**
     * Set a password provider for interactive SSH password prompting.
     * When neither a password nor an SSH key is configured for a host,
     * the provider is invoked to collect the password from the user.
     *
     * @param passwordProvider the password provider, or null to disable interactive prompting
     */
    public void setPasswordProvider(PasswordProvider passwordProvider) {
        this.passwordProvider = passwordProvider;
    }

    /**
     * Check if a deploy.yaml exists in the given project directory.
     */
    public boolean hasDeployConfig(String projectName) {
        File deployFile = getDeployFile(projectName);
        return deployFile.exists() && deployFile.isFile();
    }

    /**
     * Get the list of available profile names from the project's deploy.yaml.
     * Returns an empty list if no profiles section exists.
     *
     * @param projectName the project name
     * @return list of profile names, never null
     */
    public java.util.List<String> getAvailableProfiles(String projectName) {
        try {
            File deployFile = getDeployFile(projectName);
            if (!deployFile.exists()) return java.util.Collections.emptyList();
            PipelineConfig config = parseDeployYaml(deployFile);
            if (config.getProfiles() == null || config.getProfiles().isEmpty()) {
                return java.util.Collections.emptyList();
            }
            return new java.util.ArrayList<>(config.getProfiles().keySet());
        } catch (Exception e) {
            logger.warn("Failed to read profiles from deploy.yaml", e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Validate the deploy.yaml for a project by attempting to parse it.
     *
     * @param projectName the project name
     * @return null if valid, or an error message describing the problem
     */
    public String validateDeployConfig(String projectName) {
        File deployFile = getDeployFile(projectName);
        if (!deployFile.exists()) {
            return "deploy.yaml not found for project: " + projectName;
        }
        try {
            parseDeployYaml(deployFile);
            return null; // valid
        } catch (Exception e) {
            return "Invalid deploy.yaml: " + e.getMessage();
        }
    }

    /**
     * Execute the deploy pipeline for a project (no profile — backward compatible).
     *
     * @param projectName the project name
     * @param callback    callback for streaming output and completion
     */
    public void execute(String projectName, PipelineCallback callback) {
        execute(projectName, callback, null);
    }

    /**
     * Execute the deploy pipeline for a project with an optional profile.
     *
     * @param projectName the project name
     * @param callback    callback for streaming output and completion
     * @param profile     the profile name (e.g., "dev", "uat"), or null for default behavior
     */
    public void execute(String projectName, PipelineCallback callback, String profile) {
        File deployFile = getDeployFile(projectName);
        if (!deployFile.exists()) {
            callback.onError("deploy.yaml not found in project: " + projectName);
            callback.onPipelineComplete(false);
            return;
        }

        PipelineConfig config;
        try {
            config = parseDeployYaml(deployFile);
        } catch (Exception e) {
            callback.onError("Failed to parse deploy.yaml: " + e.getMessage());
            callback.onPipelineComplete(false);
            return;
        }

        // Normalize: if servers defined at top level but no clusters, create a default cluster
        normalizeServers(config);

        // Resolve variables. PROJECT_DIR must be the project root, not the .diatom directory.
        File projectDir = deployFile.getParentFile() != null ? deployFile.getParentFile().getParentFile() : null;
        Map<String, String> variables = resolveVariables(config, projectName,
                projectDir != null ? projectDir.getAbsolutePath() : workspacePath, profile);

        // Show profile info if active
        if (profile != null) {
            callback.onOutput(I18n.get("deploy.pipeline.profile", profile) + "\n");
        } else if (config.getProfiles() != null && config.getProfiles().containsKey("default")) {
            callback.onOutput(I18n.get("deploy.pipeline.profile", "default") + "\n");
        }

        callback.onOutput(I18n.get("deploy.pipeline.start", config.getName()) + "\n");
        callback.onOutput(I18n.get("deploy.pipeline.version", config.getVersion()) + "\n");
        callback.onOutput(I18n.get("deploy.pipeline.steps", config.getSteps().size()) + "\n\n");

        boolean allSuccess = true;
        for (int i = 0; i < config.getSteps().size(); i++) {
            PipelineStep step = config.getSteps().get(i);

            // Replace {{VARIABLES}} in step fields
            resolveStepVariables(step, variables);

            callback.onOutput("\n" + I18n.get("deploy.pipeline.step_header", i + 1, config.getSteps().size(), step.getName()) + "\n");

            try {
                boolean stepSuccess;

                // If a remote step omits both host and cluster, try to fallback to the default
                // single-server cluster created from top-level "servers". This makes simple
                // single-server deploy.yaml less error-prone.
                if (needsDefaultHost(step)) {
                    ClusterConfig defaultCluster = getDefaultCluster(config);
                    if (defaultCluster != null && defaultCluster.getHosts() != null && !defaultCluster.getHosts().isEmpty()) {
                        step.setCluster("_default");
                    }
                }

                // Check if this step targets a cluster
                if (step.getCluster() != null && !step.getCluster().isEmpty()) {
                    stepSuccess = executeClusterStep(step, config, variables, callback);
                } else {
                    // Standard single-host execution
                    PipelineRunner runner = runnerRegistry.getRunner(step.getAction());
                    if (runner == null) {
                        callback.onOutput("\n" + I18n.get("deploy.pipeline.no_runner", step.getAction()) + "\n");
                        callback.onStepComplete(step.getName(), false);
                        allSuccess = false;
                        break;
                    }
                    stepSuccess = runner.execute(step, variables, callback);
                }

                callback.onStepComplete(step.getName(), stepSuccess);
                if (!stepSuccess) {
                    allSuccess = false;
                    break;
                }
            } catch (Exception e) {
                logger.error("Pipeline step '{}' failed with exception", step.getName(), e);
                callback.onOutput("\n" + I18n.get("deploy.pipeline.step_error", step.getName(), e.getMessage()) + "\n");
                callback.onStepComplete(step.getName(), false);
                allSuccess = false;
                break;
            }
        }

        if (allSuccess) {
            // Run health checks after all steps complete successfully
            allSuccess = runClusterHealthChecks(config, callback);
        }

        if (allSuccess) {
            callback.onOutput("\n" + I18n.get("deploy.pipeline.success") + "\n");
        } else {
            callback.onOutput("\n" + I18n.get("deploy.pipeline.failed") + "\n");
        }
        callback.onPipelineComplete(allSuccess);
    }

    /**
     * Determine if a step needs implicit default host resolution.
     * Only remote actions that have neither host nor cluster configured qualify.
     */
    private boolean needsDefaultHost(PipelineStep step) {
        String action = step.getAction();
        if (action == null) return false;
        boolean isRemoteAction = "ssh_command".equals(action) || "scp".equals(action);
        if (!isRemoteAction) return false;
        boolean hasHost = step.getHost() != null && !step.getHost().trim().isEmpty();
        boolean hasCluster = step.getCluster() != null && !step.getCluster().trim().isEmpty();
        return !hasHost && !hasCluster;
    }

    /**
     * Return the default single-server cluster if one was normalized from top-level servers.
     */
    ClusterConfig getDefaultCluster(PipelineConfig config) {
        if (config.getClusters() == null) return null;
        return config.getClusters().get("_default");
    }

    /**
     * Execute a step across a cluster of hosts according to the configured strategy.
     */
    private boolean executeClusterStep(PipelineStep step, PipelineConfig config,
                                        Map<String, String> variables, PipelineCallback callback) throws Exception {
        String clusterName = step.getCluster();
        if (config.getClusters() == null || !config.getClusters().containsKey(clusterName)) {
            callback.onError("Cluster '" + clusterName + "' not found in configuration");
            return false;
        }

        ClusterConfig cluster = config.getClusters().get(clusterName);
        if (cluster.getHosts() == null || cluster.getHosts().isEmpty()) {
            callback.onError("Cluster '" + clusterName + "' has no hosts defined");
            return false;
        }

        // Determine strategy: step-level overrides cluster-level, defaults to "all"
        String strategy = step.getStrategy() != null ? step.getStrategy() : cluster.getStrategy();
        if (strategy == null) strategy = "all";

        callback.onOutput(I18n.get("deploy.cluster.header", clusterName, cluster.getHosts().size(), strategy) + "\n");

        // Build commands string — only required for command-based actions
        String command = null;
        boolean isCommandAction = "ssh_command".equals(step.getAction()) || "run_command".equals(step.getAction());
        if (isCommandAction) {
            command = step.getCommand();
            if ((command == null || command.trim().isEmpty()) && step.getCommands() != null && !step.getCommands().isEmpty()) {
                command = joinCommands(step.getCommands());
            }
            if (command == null || command.trim().isEmpty()) {
                callback.onError("Cluster step '" + step.getName() + "' has no command");
                return false;
            }
        }

        // Health checks are performed after all steps complete, not per-step.
        // Pass null for healthCheck here — the post-pipeline phase uses the
        // cluster-level health check configuration.
        switch (strategy) {
            case "rolling":
                return executeRolling(cluster, step, command, variables, callback);
            case "canary":
                return executeCanary(cluster, step, command, variables, callback);
            default: // "all"
                return executeAll(cluster, step, command, variables, callback);
        }
    }

    /**
     * Join step commands. See {@link CommandJoiner#join(java.util.List)} for details.
     */
    private String joinCommands(List<String> commands) {
        return CommandJoiner.join(commands);
    }

    /**
     * "all" strategy: execute on all hosts in parallel.
     */
    private boolean executeAll(ClusterConfig cluster, PipelineStep step, String command,
                                Map<String, String> variables,
                                PipelineCallback callback) throws Exception {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (ClusterHost host : cluster.getHosts()) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return executeOnHost(host, step, command, variables, callback);
                } catch (Exception e) {
                    callback.onOutput(I18n.get("deploy.cluster.host_error", host.getHost(), e.getMessage()) + "\n");
                    return false;
                }
            }));
        }

        boolean allSuccess = true;
        for (int i = 0; i < futures.size(); i++) {
            try {
                long timeoutSeconds;
                if ("scp".equals(step.getAction())) {
                    // SCP uploads may take a long time depending on file size; do not enforce a hard future timeout.
                    // The ScpRunner still has its own connection-level timeouts and progress events.
                    timeoutSeconds = Long.MAX_VALUE / 1000;
                } else if (cluster.getHealthCheck() != null && cluster.getHealthCheck().getTimeout() > 0) {
                    timeoutSeconds = cluster.getHealthCheck().getTimeout() + 60L;
                } else {
                    timeoutSeconds = 90L;
                }
                boolean success = futures.get(i).get(timeoutSeconds, TimeUnit.SECONDS);
                if (!success) allSuccess = false;
            } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
                callback.onOutput(I18n.get("deploy.cluster.host_timeout", cluster.getHosts().get(i).getHost()) + "\n");
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    /**
     * "rolling" strategy: execute on hosts one by one.
     */
    private boolean executeRolling(ClusterConfig cluster, PipelineStep step, String command,
                                    Map<String, String> variables,
                                    PipelineCallback callback) throws Exception {
        for (int i = 0; i < cluster.getHosts().size(); i++) {
            ClusterHost host = cluster.getHosts().get(i);
            callback.onOutput(I18n.get("deploy.cluster.rolling", i + 1, cluster.getHosts().size(), host.getHost()) + "\n");
            boolean success = executeOnHost(host, step, command, variables, callback);
            if (!success) {
                callback.onOutput(I18n.get("deploy.cluster.rolling_stopped", host.getHost()) + "\n");
                return false;
            }
            if (i < cluster.getHosts().size() - 1) {
                callback.onOutput(I18n.get("deploy.cluster.rolling_done", host.getHost()) + "\n");
            }
        }
        return true;
    }

    /**
     * "canary" strategy: deploy to first host, then deploy to all remaining hosts in parallel.
     */
    private boolean executeCanary(ClusterConfig cluster, PipelineStep step, String command,
                                   Map<String, String> variables,
                                   PipelineCallback callback) throws Exception {
        List<ClusterHost> hosts = cluster.getHosts();
        if (hosts.isEmpty()) return true;

        // Canary: deploy to first host
        ClusterHost canaryHost = hosts.get(0);
        callback.onOutput(I18n.get("deploy.cluster.canary", canaryHost.getHost()) + "\n");
        boolean canarySuccess = executeOnHost(canaryHost, step, command, variables, callback);
        if (!canarySuccess) {
            callback.onOutput(I18n.get("deploy.cluster.canary_failed", canaryHost.getHost()) + "\n");
            return false;
        }
        callback.onOutput(I18n.get("deploy.cluster.canary_passed", canaryHost.getHost()) + "\n");

        // Deploy to remaining hosts in parallel
        if (hosts.size() > 1) {
            List<ClusterHost> remainingHosts = hosts.subList(1, hosts.size());
            callback.onOutput(I18n.get("deploy.cluster.remaining", remainingHosts.size()) + "\n");

            List<CompletableFuture<Boolean>> futures = new ArrayList<>();
            for (ClusterHost host : remainingHosts) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return executeOnHost(host, step, command, variables, callback);
                    } catch (Exception e) {
                        callback.onOutput(I18n.get("deploy.cluster.host_error", host.getHost(), e.getMessage()) + "\n");
                        return false;
                    }
                }));
            }

            boolean allSuccess = true;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    boolean success = futures.get(i).get(cluster.getHealthCheck() != null ?
                            cluster.getHealthCheck().getTimeout() + 60 : 90, TimeUnit.SECONDS);
                    if (!success) allSuccess = false;
                } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
                    callback.onOutput(I18n.get("deploy.cluster.host_timeout", remainingHosts.get(i).getHost()) + "\n");
                    allSuccess = false;
                }
            }
            return allSuccess;
        }

        return true;
    }

    /**
     * Execute commands on a single cluster host via SSH.
     */
    private boolean executeOnHost(ClusterHost host, PipelineStep step, String command,
                                   Map<String, String> variables,
                                   PipelineCallback callback) throws Exception {
        // Build host variables by merging cluster host vars with global vars
        Map<String, String> hostVars = new HashMap<>(variables);
        if (host.getVariables() != null) {
            hostVars.putAll(host.getVariables());
        }

        // Build the host string in the format expected by SSH runners: user@host:port
        String sshUser = host.getUser() != null ? host.getUser() : hostVars.getOrDefault("SSH_USER", "root");
        String hostStr = sshUser + "@" + host.getHost() + ":" + host.getPort();

        // Set SSH auth variables for this host
        Map<String, String> runnerVars = new HashMap<>(hostVars);
        if (host.getKey() != null) runnerVars.put("SSH_KEY_PATH", host.getKey());
        if (host.getPassword() != null) runnerVars.put("SSH_PASSWORD", host.getPassword());

        // If neither key nor password is configured, try interactive prompt
        if (host.getPassword() == null && host.getKey() == null && passwordProvider != null) {
            String cacheKey = sshUser + "@" + host.getHost();
            String password = cachedPasswords.get(cacheKey);
            if (password == null) {
                password = passwordProvider.promptPassword(host.getHost(), sshUser);
                if (password != null && !password.isEmpty()) {
                    cachedPasswords.put(cacheKey, password);
                }
            }
            if (password != null && !password.isEmpty()) {
                runnerVars.put("SSH_PASSWORD", password);
            }
        }

        callback.onOutput(I18n.get("deploy.cluster.host_exec", host.getHost(), sshUser) + "\n");

        // Create a host-specific step — use the original action type so the
        // correct runner (SshRunner, ScpRunner, etc.) is looked up.
        PipelineStep hostStep = new PipelineStep();
        hostStep.setName(step.getName() + " @" + host.getHost());
        hostStep.setAction(step.getAction());
        hostStep.setHost(hostStr);
        hostStep.setCommand(command != null ? replaceVariables(command, hostVars) : null);
        hostStep.setFiles(step.getFiles());

        PipelineRunner runner = runnerRegistry.getRunner(step.getAction());
        if (runner == null) {
            callback.onError("No runner available for action: " + step.getAction());
            return false;
        }

        return runner.execute(hostStep, runnerVars, callback);
    }

    /**
     * Perform a health check on a host after deployment.
     */
    private boolean performHealthCheck(ClusterHost host, HealthCheckConfig healthCheck, PipelineCallback callback) {
        int retries = Math.max(healthCheck.getRetries(), 1);
        int timeoutMs = Math.max(healthCheck.getTimeout() * 1000, 5000);

        switch (healthCheck.getType()) {
            case "http":
                return healthCheckHttp(host, healthCheck, retries, timeoutMs, callback);
            case "tcp":
                return healthCheckTcp(host, healthCheck, retries, timeoutMs, callback);
            case "command":
                return healthCheckCommand(healthCheck, callback);
            default:
                callback.onOutput(I18n.get("deploy.health.unknown", healthCheck.getType()) + "\n");
                return true;
        }
    }

    /**
     * HTTP health check — sends GET request to http://host:port/path.
     */
    private boolean healthCheckHttp(ClusterHost host, HealthCheckConfig healthCheck,
                                     int retries, int timeoutMs, PipelineCallback callback) {
        String urlStr = "http://" + host.getHost() + ":" + healthCheck.getPort() + healthCheck.getPath();
        for (int i = 0; i < retries; i++) {
            try {
                if (i > 0) {
                    Thread.sleep(Math.min(timeoutMs / retries, 5000));
                }
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(Math.min(timeoutMs / retries, 5000));
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                int responseCode = conn.getResponseCode();
                conn.disconnect();
                if (responseCode >= 200 && responseCode < 500) {
                    return true;
                }
                callback.onOutput(I18n.get("deploy.health.http", i + 1, retries, responseCode) + "\n");
            } catch (Exception e) {
                callback.onOutput(I18n.get("deploy.health.http", i + 1, retries, e.getMessage()) + "\n");
            }
        }
        return false;
    }

    /**
     * TCP health check — attempts to open a socket connection to host:port.
     */
    private boolean healthCheckTcp(ClusterHost host, HealthCheckConfig healthCheck,
                                    int retries, int timeoutMs, PipelineCallback callback) {
        for (int i = 0; i < retries; i++) {
            try {
                if (i > 0) {
                    Thread.sleep(Math.min(timeoutMs / retries, 5000));
                }
                try (Socket socket = new Socket(host.getHost(), healthCheck.getPort())) {
                    return true;
                }
            } catch (Exception e) {
                callback.onOutput(I18n.get("deploy.health.tcp", i + 1, retries, e.getMessage()) + "\n");
            }
        }
        return false;
    }

    /**
     * Command health check — runs a local command and checks exit code.
     */
    private boolean healthCheckCommand(HealthCheckConfig healthCheck, PipelineCallback callback) {
        String command = healthCheck.getCommand();
        if (command == null || command.trim().isEmpty()) {
            callback.onOutput(I18n.get("deploy.health.no_cmd") + "\n");
            return true;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder();
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("/bin/sh", "-c", command);
            }
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            }
            callback.onOutput(I18n.get("deploy.health.cmd_fail", exitCode) + "\n");
            return false;
        } catch (Exception e) {
            callback.onOutput(I18n.get("deploy.health.cmd_err", e.getMessage()) + "\n");
            return false;
        }
    }

    /**
     * Run health checks on all clusters that have health check configured and enabled.
     * Called once after all pipeline steps have completed successfully.
     */
    private boolean runClusterHealthChecks(PipelineConfig config, PipelineCallback callback) {
        if (config.getClusters() == null) return true;

        boolean allHealthy = true;
        for (Map.Entry<String, ClusterConfig> entry : config.getClusters().entrySet()) {
            ClusterConfig cluster = entry.getValue();
            HealthCheckConfig hc = cluster.getHealthCheck();
            if (hc == null || !hc.isEnabled() || "none".equals(hc.getType())) {
                continue;
            }

            String clusterName = entry.getKey();
            callback.onOutput("\n" + I18n.get("deploy.health.cluster_start", clusterName) + "\n");

            for (ClusterHost host : cluster.getHosts()) {
                callback.onOutput(I18n.get("deploy.health.start", host.getHost(), hc.getType()) + "\n");
                boolean ok = performHealthCheck(host, hc, callback);
                if (ok) {
                    callback.onOutput(I18n.get("deploy.health.passed", host.getHost()) + "\n");
                } else {
                    callback.onOutput(I18n.get("deploy.health.failed", host.getHost()) + "\n");
                    allHealthy = false;
                }
            }
        }
        return allHealthy;
    }

    /**
     * Get the deploy.yaml file for a project.
     */
    File getDeployFile(String projectName) {
        if (projectName != null && !projectName.isEmpty()) {
            File projectDir = new File(workspacePath, projectName);
            return new File(new File(projectDir, ".diatom"), DEPLOY_YAML);
        }
        return new File(new File(workspacePath, ".diatom"), DEPLOY_YAML);
    }

    /**
     * Parse deploy.yaml into PipelineConfig using Jackson YAML.
     */
    PipelineConfig parseDeployYaml(File deployFile) throws IOException {
        return yamlMapper.readValue(deployFile, PipelineConfig.class);
    }

    /**
     * Resolve built-in and user-defined variables, optionally merged with profile variables.
     */
    private Map<String, String> resolveVariables(PipelineConfig config, String projectName, String projectDir, String profile) {
        Map<String, String> variables = new HashMap<>();
        if (config.getVariables() != null) {
            variables.putAll(config.getVariables());
        }
        // Merge profile variables if a profile is specified or "default" is available
        String effectiveProfile = profile;
        if (effectiveProfile == null && config.getProfiles() != null && config.getProfiles().containsKey("default")) {
            effectiveProfile = "default";
        }
        if (effectiveProfile != null && config.getProfiles() != null) {
            Map<String, String> profileVars = config.getProfiles().get(effectiveProfile);
            if (profileVars != null) {
                variables.putAll(profileVars);
            }
        }
        // Built-in variables
        variables.put("PROJECT_NAME", projectName != null ? projectName : "");
        variables.put("PROJECT_DIR", projectDir);
        variables.put("WORKSPACE_DIR", workspacePath);
        // {{timestamp}} — current time formatted as yyyyMMdd-HHmmss
        variables.put("timestamp", new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
        return variables;
    }

    /**
     * Keep old overload for backward compatibility (no profile).
     */
    private Map<String, String> resolveVariables(PipelineConfig config, String projectName, String projectDir) {
        return resolveVariables(config, projectName, projectDir, null);
    }

    /**
     * Replace {{VARIABLE}} placeholders in step fields.
     * For SCP steps, relative local file paths are resolved against PROJECT_DIR.
     */
    void resolveStepVariables(PipelineStep step, Map<String, String> variables) {
        if (step.getCommand() != null) {
            step.setCommand(replaceVariables(step.getCommand(), variables));
        }
        if (step.getHost() != null) {
            step.setHost(replaceVariables(step.getHost(), variables));
        }
        if (step.getCluster() != null) {
            step.setCluster(replaceVariables(step.getCluster(), variables));
        }
        if (step.getCommands() != null) {
            for (int i = 0; i < step.getCommands().size(); i++) {
                step.getCommands().set(i, replaceVariables(step.getCommands().get(i), variables));
            }
        }
        if (step.getFiles() != null) {
            String projectDir = variables.get("PROJECT_DIR");
            for (ScpFileEntry file : step.getFiles()) {
                if (file.getLocal() != null) {
                    String localPath = replaceVariables(file.getLocal(), variables);
                    // Resolve relative local paths against PROJECT_DIR so that
                    // "target/app.jar" works relative to the project directory
                    // regardless of the CLI's working directory.
                    if (projectDir != null && !projectDir.isEmpty()) {
                        File f = new File(localPath);
                        if (!f.isAbsolute()) {
                            localPath = new File(projectDir, localPath).getPath();
                        }
                    }
                    file.setLocal(localPath);
                }
                if (file.getRemote() != null) {
                    file.setRemote(replaceVariables(file.getRemote(), variables));
                }
            }
        }
    }

    /**
     * Normalize top-level servers and healthCheck into a default cluster.
     * This supports the simpler single-server deploy.yaml format where hosts
     * are listed under "servers" instead of nested inside "clusters.<name>.hosts".
     */
    private void normalizeServers(PipelineConfig config) {
        List<ClusterHost> servers = config.getServers();
        if (servers != null && !servers.isEmpty()) {
            if (config.getClusters() == null || config.getClusters().isEmpty()) {
                ClusterConfig defaultCluster = new ClusterConfig();
                defaultCluster.setStrategy("all");
                defaultCluster.setHosts(servers);
                // Apply top-level health check if present
                if (config.getHealthCheck() != null) {
                    defaultCluster.setHealthCheck(config.getHealthCheck());
                }
                config.setClusters(new java.util.LinkedHashMap<>());
                config.getClusters().put("_default", defaultCluster);
            }
            // Clear servers to avoid confusion downstream
            config.setServers(null);
        }
    }

    /**
     * Replace {{VAR}} patterns with actual values from the variables map.
     */
    public static String replaceVariables(String text, Map<String, String> variables) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
