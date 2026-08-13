package com.github.obhen233.cli.provider;

import com.github.obhen233.cli.TerminalUI;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.pipeline.*;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;
import com.github.obhen233.util.I18n;
import com.github.obhen233.util.PathUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deploy command — unified deploy pipeline execution and config generation.
 *
 * Usage: deploy
 *
 * Flow:
 *   1. Check if in a project directory
 *   2. deploy.yaml exists → execute deploy pipeline directly
 *   3. deploy.yaml missing → start AI agent to generate config (CLI)
 *      (In IDE, the frontend handles the AI generation flow)
 */
public class DeployCommandProvider implements CoreCommandProvider, TerminalUI.AgentAware {

    /**
     * Deploy generation prompt — injected when no deploy.yaml exists.
     * Style: concise, no markdown, token-minimal. Follows BASE_PROMPT pattern.
     * Flow: analyze -> collect -> generate. Regen: delete old file first.
     */
    private static final String DEPLOY_GUIDE_PROMPT =
        "Generate deploy.yaml for project {0}.\n" +
        "CRITICAL RULES:\n" +
        "- Pipeline handles SSH natively. NEVER use raw shell 'ssh user@host' or shell scp.\n" +
        "- Remote exec uses action: ssh_command. File transfer uses action: scp with files: [{local, remote}]. NEVER action: upload or source/target.\n" +
        "- NEVER put passwords in commands. SSH auth goes under servers[].key (recommended) or servers[].password (auto-encrypted on save). NO sshpass/expect.\n" +
        "- Local build uses action: run_command, maven, gradle, or docker.\n" +
        "Actions: ssh_command|scp|run_command|maven|gradle|docker|git|svn|jenkins|k8s.\n" +
        "Vars: '{{'PROJECT_NAME'}}','{{'PROJECT_DIR'}}','{{'WORKSPACE_DIR'}}','{{'timestamp'}}'.\n\n" +
        "PROFILES (multi-environment):\n" +
        "  Add a profiles section to deploy.yaml for environment-specific variables.\n" +
        "  profiles:\n" +
        "    default: {DEPLOY_HOST: \"dev.example.com\"}\n" +
        "    dev:     {DEPLOY_HOST: \"dev.example.com\", SSH_USER: \"dev\"}\n" +
        "    uat:     {DEPLOY_HOST: \"uat.example.com\"}\n" +
        "    pro:     {DEPLOY_HOST: \"prod.example.com\", SSH_PORT: \"2222\"}\n" +
        "  Profile variables OVERRIDE top-level variables with the same name.\n" +
        "  Profiles do NOT change steps/servers/clusters structure.\n" +
        "  CLI: 'deploy dev' uses profile \"dev\". 'deploy' (no arg) uses \"default\" if it exists.\n\n" +
        "SINGLE-HOST format:\n" +
        "  servers: [{host, user, port, key?, password?}]\n" +
        "  Remote steps may omit host/cluster; they automatically target the single server.\n\n" +
        "CLUSTER format:\n" +
        "  clusters:\n" +
        "    <name>:\n" +
        "      strategy: all|rolling|canary\n" +
        "      health_check: {type: http|tcp|command|none, port, path, timeout, retries}\n" +
        "      hosts: [{host, user, port, key?, password?}]\n" +
        "  Steps MUST include: cluster: \"<name>\"\n\n" +
        "Flow:\n" +
        "1. Analyze project build type.\n" +
        "2. Ask: single server or cluster?\n" +
        "3. Collect: host, SSH user(22), key (recommended) or password.\n" +
        "4. Cluster: host count, strategy, health(type+port+path+retries).\n" +
        "5. Ask: multi-environment profiles? If yes, collect profile names and per-env vars.\n" +
        "6. Write .diatom/deploy.yaml.\n" +
        "7. Done. Tell user: run 'deploy' or 'deploy <profile>' to execute.\n" +
        "Regen rule: delete .diatom/deploy.yaml FIRST, then generate new.";

    private final String defaultWorkspacePath;
    private ReActAgent agent;

    public DeployCommandProvider() {
        this.defaultWorkspacePath = PathUtils.getWorkingDir();
    }

    @Override
    public void init(ReActAgent agent) {
        this.agent = agent;
    }

    /**
     * Resolve the project directory path.
     * In IDE mode, checks diatom.project.dir system property.
     * In CLI mode, falls back to user.dir.
     */
    private String resolveProjectDir() {
        String projectDir = System.getProperty("diatom.project.dir");
        if (projectDir != null && !projectDir.isEmpty()) {
            return projectDir;
        }
        return defaultWorkspacePath;
    }

    @Override
    public String getCommandName() {
        return "deploy";
    }

    @Override
    public String getDescription() {
        return "{{cli.deploy.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.deploy.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        String trimmed = args.trim();
        String lower = trimmed.toLowerCase();

        // Show help
        if ("help".equals(lower) || "--help".equals(lower) || "-h".equals(lower)) {
            return I18n.resolveTemplate(getHelp());
        }

        String projectDir = resolveProjectDir();
        Path projectPath = Paths.get(projectDir);

        if (!hasProjectFiles(projectPath)) {
            String msg = "{{cli.deploy.no_project}}";
            output.printWarning(msg + "\n");
            return msg;
        }

        Path workspaceRoot = projectPath.getParent();
        String projectName = projectPath.getFileName().toString();
        String wsRoot = (workspaceRoot != null) ? workspaceRoot.toString() : projectDir;

        RunnerRegistry registry = new RunnerRegistry();
        registry.discoverFromSpi();
        PipelineService pipelineService = new PipelineService(registry, wsRoot);
        pipelineService.setPasswordProvider(new ConsolePasswordProvider());

        boolean hasConfig = pipelineService.hasDeployConfig(projectName);

        // "deploy list" — list available profiles
        if ("list".equals(lower)) {
            if (!hasConfig) {
                output.printInfo("{{cli.deploy.no_config:" + projectName + "}}\n");
                return "";
            }
            java.util.List<String> profiles = pipelineService.getAvailableProfiles(projectName);
            if (profiles.isEmpty()) {
                output.printInfo(I18n.get("cli.deploy.list_no_profiles") + "\n");
            } else {
                output.printInfo(I18n.get("cli.deploy.list_header") + "\n");
                for (String p : profiles) {
                    output.print("  - " + p + (p.equals("default") ? " (default)" : "") + "\n");
                }
            }
            return "";
        }

        // Parse optional profile argument: "deploy dev" → profile="dev"
        String profile = null;
        if (!trimmed.isEmpty() && !"help".equals(lower)) {
            profile = trimmed;
        }

        if (hasConfig) {
            // Validate profile exists
            java.util.List<String> availableProfiles = pipelineService.getAvailableProfiles(projectName);
            if (!availableProfiles.isEmpty() && profile != null && !availableProfiles.contains(profile)) {
                String msg = I18n.get("cli.deploy.profile_not_found", profile,
                        String.join(", ", availableProfiles));
                output.printWarning(msg + "\n");
                return msg;
            }
            return executeDeploy(projectName, output, pipelineService, profile);
        } else {
            // No config — start AI agent to generate one
            output.printInfo("{{cli.deploy.ai_start}}\n");
            if (agent != null) {
                String lang = "zh".equals(I18n.getLanguage()) ? "Chinese" : "English";
                String prompt = MessageFormat.format(DEPLOY_GUIDE_PROMPT, projectName)
                        + "\nIMPORTANT: Respond in " + lang + ".";
                agent.run(prompt);
                return "{{cli.deploy.generated}}";
            } else {
                String msg = "{{cli.deploy.no_config:" + projectName + "}}";
                output.printWarning(msg + "\n");
                return msg;
            }
        }
    }

    private String executeDeploy(String projectName, CommandOutput output,
                                  PipelineService pipelineService, String profile) {
        output.print("{{cli.deploy.deploying}}\n");
        StringBuilder result = new StringBuilder();
        final AtomicBoolean scpNoticePrinted = new AtomicBoolean(false);
        pipelineService.execute(projectName, new PipelineCallback() {
            @Override
            public void onOutput(String text) {
                output.print(text);
                result.append(text);
            }

            @Override
            public void onProgress(String stepName, long current, long total, long speedBps) {
                if (total <= 0) return;
                boolean canInline = System.console() != null
                        && !System.getProperty("os.name", "").toLowerCase().contains("windows");
                if (canInline) {
                    int percent = (int) (current * 100 / total);
                    String line = String.format("\r    [%3d%%] %s / %s  %s  %s",
                            percent,
                            formatFileSize(current),
                            formatFileSize(total),
                            formatSpeed(speedBps),
                            stepName);
                    System.out.print(line);
                    if (current >= total) {
                        System.out.println();
                    }
                } else {
                    if (scpNoticePrinted.compareAndSet(false, true)) {
                        output.printInfo(I18n.get("deploy.scp.please_wait") + "\n");
                    }
                }
            }

            @Override
            public void onStepComplete(String stepName, boolean success) {
                // reported inline via onOutput
            }

            @Override
            public void onPipelineComplete(boolean success) {
                if (success) {
                    output.printSuccess("\n{{cli.deploy.success}}\n");
                }
            }

            @Override
            public void onError(String message) {
                output.printError("Error: " + message + "\n");
                result.append("Error: ").append(message).append("\n");
            }
        }, profile);
        return result.toString();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String formatSpeed(long bps) {
        if (bps < 1024) return bps + " B";
        if (bps < 1024 * 1024) return String.format("%.1f KB", bps / 1024.0);
        if (bps < 1024 * 1024 * 1024) return String.format("%.1f MB", bps / (1024.0 * 1024.0));
        return String.format("%.1f GB", bps / (1024.0 * 1024.0 * 1024.0));
    }

    private boolean hasProjectFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        String[] projectFiles = {"pom.xml", "build.gradle", "build.gradle.kts",
                "package.json", "go.mod", "Cargo.toml", "deploy.yaml", ".diatom"};
        for (String file : projectFiles) {
            if (Files.exists(dir.resolve(file))) return true;
        }
        Path diatomDir = dir.resolve(".diatom");
        if (Files.isDirectory(diatomDir)) {
            try {
                return Files.list(diatomDir).findAny().isPresent();
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }
}
