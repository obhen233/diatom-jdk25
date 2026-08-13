package com.github.obhen233.core.pipeline;

import com.github.obhen233.core.context.ProjectContext;
import com.github.obhen233.core.context.ProjectIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Default implementation of {@link DeployConfigService}.
 * <p>
 * Uses {@link ProjectIndexer} for project analysis and builds deploy.yaml
 * from structured parameters via a simple template approach.
 * <p>
 * Intended for both CLI (via DeployCommandProvider/DeployConfigGenerator)
 * and IDE (via AI Agent tools through springboot-starter) usage.
 */
public class DefaultDeployConfigService implements DeployConfigService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDeployConfigService.class);

    @Override
    public ProjectContext analyzeProject(String projectDir) {
        ProjectIndexer indexer = new ProjectIndexer(projectDir);
        return indexer.getContext(true);
    }

    @Override
    public String generateYaml(DeployConfigParams params) {
        StringBuilder sb = new StringBuilder();
        String projectName = params.getProjectName();

        sb.append("# Deploy configuration for ").append(projectName).append("\n");
        sb.append("version: \"1.0\"\n");
        sb.append("name: \"").append(projectName).append("-deploy\"\n\n");

        // ---- Variables ----
        if (params.getEnvVars() != null && !params.getEnvVars().isEmpty()) {
            sb.append("variables:\n");
            for (java.util.Map.Entry<String, String> e : params.getEnvVars().entrySet()) {
                sb.append("  ").append(e.getKey()).append(": \"").append(e.getValue()).append("\"\n");
            }
            sb.append("\n");
        }

        // ---- Profiles (multi-environment) ----
        Map<String, Map<String, String>> profiles = params.getProfiles();
        if (profiles != null && !profiles.isEmpty()) {
            sb.append("profiles:\n");
            for (Map.Entry<String, Map<String, String>> entry : profiles.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(":\n");
                Map<String, String> profileVars = entry.getValue();
                if (profileVars != null && !profileVars.isEmpty()) {
                    for (Map.Entry<String, String> v : profileVars.entrySet()) {
                        sb.append("    ").append(v.getKey()).append(": \"").append(v.getValue()).append("\"\n");
                    }
                }
            }
            sb.append("\n");
        }

        // ---- Clusters (cluster mode) or top-level servers (single-host mode) ----
        if (params.isClusterMode() && params.getServers() != null && !params.getServers().isEmpty()) {
            sb.append("clusters:\n");
            sb.append("  ").append(params.getClusterName()).append(":\n");
            sb.append("    strategy: \"").append(params.getStrategy() != null ? params.getStrategy() : "all").append("\"\n");

            // Health check
            HealthCheckConfig hc = params.getHealthCheck();
            if (hc != null && !"none".equals(hc.getType())) {
                sb.append("    health_check:\n");
                sb.append("      type: \"").append(hc.getType()).append("\"\n");
                if (hc.getPort() > 0) sb.append("      port: ").append(hc.getPort()).append("\n");
                if (hc.getPath() != null && !hc.getPath().isEmpty())
                    sb.append("      path: \"").append(hc.getPath()).append("\"\n");
                sb.append("      timeout: ").append(hc.getTimeout() > 0 ? hc.getTimeout() : 30).append("\n");
                sb.append("      retries: ").append(hc.getRetries() > 0 ? hc.getRetries() : 3).append("\n");
            }

            sb.append("    hosts:\n");
            for (ServerInfo s : params.getServers()) {
                appendHost(sb, s, "      ");
            }
            sb.append("\n");
        } else if (params.getServers() != null && !params.getServers().isEmpty()) {
            sb.append("servers:\n");
            for (ServerInfo s : params.getServers()) {
                appendHost(sb, s, "  ");
            }
            sb.append("\n");
        }

        // ---- Steps ----
        sb.append("steps:\n");
        sb.append("  - name: \"Build\"\n");

        // Detect build command from project name heuristic (maven/gradle/npm)
        String buildCmd = detectBuildCommand(params);
        String buildAction = buildCmd.startsWith("mvn") ? "maven" : "run_command";
        sb.append("    action: \"").append(buildAction).append("\"\n");
        sb.append("    command: \"").append(buildCmd).append("\"\n\n");

        // Deploy step
        if (params.isClusterMode() && params.getServers() != null && !params.getServers().isEmpty()) {
            sb.append("  - name: \"Deploy to Cluster\"\n");
            sb.append("    action: \"ssh_command\"\n");
            sb.append("    cluster: \"").append(params.getClusterName()).append("\"\n");
            sb.append("    commands:\n");
            sb.append("      - \"echo 'Deploying {{PROJECT_NAME}}'\"\n");
            sb.append("      - \"mkdir -p /opt/{{PROJECT_NAME}}\"\n");
        } else if (params.getServers() != null && !params.getServers().isEmpty()) {
            sb.append("  - name: \"Deploy to Server\"\n");
            sb.append("    action: \"ssh_command\"\n");
            // Single-server mode: rely on implicit default host fallback.
            sb.append("    commands:\n");
            sb.append("      - \"echo 'Deploying {{PROJECT_NAME}}'\"\n");
            sb.append("      - \"mkdir -p /opt/{{PROJECT_NAME}}\"\n");
        } else {
            sb.append("  - name: \"Deploy Artifact\"\n");
            sb.append("    action: \"run_command\"\n");
            sb.append("    command: \"echo 'Deploy step - customize for your project'\"\n");
        }

        return sb.toString();
    }

    /**
     * Append a host entry with consistent indentation, including optional SSH key or password.
     */
    private void appendHost(StringBuilder sb, ServerInfo s, String indent) {
        sb.append(indent).append("- host: \"").append(s.getHost()).append("\"\n");
        sb.append(indent).append("  user: \"").append(s.getUser()).append("\"\n");
        sb.append(indent).append("  port: ").append(s.getPort()).append("\n");
        if (s.getKey() != null && !s.getKey().isEmpty()) {
            sb.append(indent).append("  key: \"").append(s.getKey()).append("\"\n");
        }
        if (s.getPassword() != null && !s.getPassword().isEmpty()) {
            sb.append(indent).append("  password: \"").append(s.getPassword()).append("\"\n");
        }
    }

    @Override
    public boolean writeYaml(String workspacePath, String projectName, String yamlContent) {
        try {
            Path projectDir = Paths.get(workspacePath, projectName);
            Path diatomDir = projectDir.resolve(".diatom");
            Files.createDirectories(diatomDir);

            Path deployFile = diatomDir.resolve("deploy.yaml");
            Files.write(deployFile, yamlContent.getBytes(StandardCharsets.UTF_8));
            logger.info("Written deploy.yaml for project {} at {}", projectName, deployFile);
            return true;
        } catch (IOException e) {
            logger.error("Failed to write deploy.yaml for project {}", projectName, e);
            return false;
        }
    }

    @Override
    public boolean deleteYaml(String workspacePath, String projectName) {
        Path deployFile = Paths.get(workspacePath, projectName, ".diatom", "deploy.yaml");
        try {
            return Files.deleteIfExists(deployFile);
        } catch (IOException e) {
            logger.error("Failed to delete deploy.yaml for project {}", projectName, e);
            return false;
        }
    }

    /**
     * Detect the build command based on project files or env vars hint.
     */
    private String detectBuildCommand(DeployConfigParams params) {
        String projectName = params.getProjectName();
        if (projectName == null || projectName.isEmpty()) {
            return "echo 'Build step - customize for your project'";
        }
        String lowerName = projectName.toLowerCase();
        if (lowerName.contains("maven") || lowerName.contains("mvn") || lowerName.contains("spring")) {
            return "mvn clean package -DskipTests";
        }
        if (lowerName.contains("gradle") || lowerName.contains("boot")) {
            return "./gradlew build";
        }
        if (lowerName.contains("node") || lowerName.contains("npm") || lowerName.contains("vue") || lowerName.contains("react")) {
            return "npm run build";
        }
        return "echo 'Build step - customize for your project'";
    }
}
