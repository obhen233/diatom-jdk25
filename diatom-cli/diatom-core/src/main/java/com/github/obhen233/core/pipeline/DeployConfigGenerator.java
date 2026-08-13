package com.github.obhen233.core.pipeline;

import com.github.obhen233.spi.SshPasswordCipher;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.util.I18n;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.context.ProjectContext;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-driven deploy.yaml generator.
 *
 * Uses ProjectIndexer to analyze project structure and calls the AI model
 * to suggest a deploy.yaml configuration, then interactively collects
 * server/cluster information from the user.
 */
public class DeployConfigGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DeployConfigGenerator.class);

    private final String workspacePath;
    private final AppConfig appConfig;
    private final ProjectIndexer projectIndexer;

    public DeployConfigGenerator(String workspacePath, AppConfig appConfig) {
        this.workspacePath = workspacePath;
        this.appConfig = appConfig;
        this.projectIndexer = new ProjectIndexer(workspacePath);
    }

    /**
     * Generate a deploy.yaml configuration interactively.
     *
     * @param projectName the project name
     * @param output      callback for output messages
     * @return the generated deploy.yaml content, or null if cancelled
     */
    public String generate(String projectName, DeployGeneratorOutput output) {
        // 1. Analyze project context
        output.onOutput(I18n.get("deploy.generate.analyzing") + "\n");
        projectIndexer.setProjectDir(Paths.get(workspacePath, projectName).toString());
        ProjectContext context = projectIndexer.getContext(true);

        String projectType = context.getProjectType();
        String directoryTree = context.getDirectoryTree();
        String buildFileContent = context.getBuildFileContent();

        output.onOutput(I18n.get("deploy.generate.project", context.getProjectName(), projectType) + "\n");

        // 2. Ask deployment type
        boolean isCluster = askYesNo("Deployment type: single host or cluster? (s/c)", false);
        String strategy = "all";
        String healthCheckType = "none";
        int healthPort = 8080;
        String healthPath = "/health";
        int healthRetries = 3;

        if (isCluster) {
            strategy = askOption("Cluster strategy", Arrays.asList("all", "rolling", "canary"), "all");
            healthCheckType = askOption("Health check type", Arrays.asList("http", "tcp", "none"), "http");
            if ("http".equals(healthCheckType)) {
                healthPort = askInt("Health check port", 8080);
                healthPath = askString("Health check path", "/health");
                healthRetries = askInt("Health check retries", 3);
            } else if ("tcp".equals(healthCheckType)) {
                healthPort = askInt("Health check port", 8080);
                healthRetries = askInt("Health check retries", 3);
            }
        }

        // 3. Collect server information
        List<ServerInfo> servers = new ArrayList<>();
        if (isCluster) {
            int hostCount = askInt("Number of hosts in cluster", 2);
            for (int i = 0; i < hostCount; i++) {
                output.onOutput(I18n.get("deploy.generate.host_header", i + 1) + "\n");
                ServerInfo server = collectServerInfo();
                servers.add(server);
            }
        } else {
            output.onOutput(I18n.get("deploy.generate.server_info") + "\n");
            ServerInfo server = collectServerInfo();
            servers.add(server);
        }

        // 4. Collect environment variables
        output.onOutput(I18n.get("deploy.generate.env_vars") + "\n");
        Map<String, String> envVars = new LinkedHashMap<>();
        output.onOutput(I18n.get("deploy.generate.env_prompt") + "\n");
        String line;
        // Add some common defaults
        envVars.put("APP_PORT", String.valueOf(healthPort));

        while (true) {
            line = askString("  Variable (key=value or empty to finish)", "");
            if (line == null || line.trim().isEmpty()) break;
            String[] parts = line.split("=", 2);
            if (parts.length == 2) {
                envVars.put(parts[0].trim(), parts[1].trim());
            } else {
                output.onOutput(I18n.get("deploy.generate.invalid_var") + "\n");
            }
        }

        // 5. Collect multi-environment profiles (optional)
        Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
        boolean useProfiles = askYesNo("Multi-environment profiles? (y/n)", false);
        if (useProfiles) {
            output.onOutput(I18n.get("deploy.generate.profiles_intro") + "\n");
            int profileCount = askInt("Number of environments", 2);
            for (int i = 0; i < profileCount; i++) {
                output.onOutput(I18n.get("deploy.generate.profile_header", i + 1) + "\n");
                String profileName = askString("  Profile name (e.g., dev, uat, pro)", "");
                if (profileName == null || profileName.trim().isEmpty()) continue;
                profileName = profileName.trim();
                output.onOutput(I18n.get("deploy.generate.profile_vars", profileName) + "\n");
                Map<String, String> profileVars = new LinkedHashMap<>();
                while (true) {
                    String kv = askString("  Variable (key=value or empty to finish)", "");
                    if (kv == null || kv.trim().isEmpty()) break;
                    String[] parts = kv.split("=", 2);
                    if (parts.length == 2) {
                        profileVars.put(parts[0].trim(), parts[1].trim());
                    } else {
                        output.onOutput(I18n.get("deploy.generate.invalid_var") + "\n");
                    }
                }
                profiles.put(profileName, profileVars);
            }
        }

        // 6. Call AI to generate deploy.yaml content
        output.onOutput(I18n.get("deploy.generate.calling_ai") + "\n");
        String aiConfig = callAiForConfig(context, isCluster, strategy, servers, envVars, profiles, output);
        if (aiConfig == null) {
            output.onOutput(I18n.get("deploy.generate.ai_fallback") + "\n");
            aiConfig = generateTemplateConfig(projectName, projectType, isCluster, strategy,
                    healthCheckType, healthPort, healthPath, healthRetries, servers, envVars, profiles);
        }

        // 7. Encrypt passwords and write file
        String writtenPath = writeDeployYaml(projectName, aiConfig);
        return writtenPath != null ? aiConfig : null;
    }

    /**
     * Collect server information from the user.
     */
    private ServerInfo collectServerInfo() {
        ServerInfo server = new ServerInfo();
        server.setHost(askString("  Host (IP or domain)", ""));
        server.setUser(askString("  SSH user", "root"));
        server.setPort(askInt("  SSH port", 22));
        server.setKey(askString("  SSH key path (or empty for password auth)", ""));
        if (server.getKey().isEmpty()) {
            server.setPassword(askString("  SSH password (or empty to skip — will prompt during deploy)", ""));
            if (!server.getPassword().isEmpty()) {
                System.out.println(I18n.get("deploy.generate.ssh_pwd_tip"));
            } else {
                System.out.println("  (Password will be requested interactively during deployment)");
            }
        } else {
            System.out.println(I18n.get("deploy.generate.ssh_key_tip"));
        }
        return server;
    }

    /**
     * Call the AI model to generate a deploy.yaml configuration.
     */
    private String callAiForConfig(ProjectContext context, boolean isCluster, String strategy,
                                    List<ServerInfo> servers, Map<String, String> envVars,
                                    Map<String, Map<String, String>> profiles,
                                    DeployGeneratorOutput output) {
        String apiKey = appConfig.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            output.onOutput(I18n.get("deploy.generate.ai_no_key") + "\n");
            return null;
        }

        String baseUrl = appConfig.getBaseUrl();
        String model = appConfig.getModel();
        String apiUrl = appConfig.getApiUrl();

        // Build system prompt
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("You are a DevOps expert. Generate deploy.yaml for a pipeline system.\n\n");
        systemPrompt.append("RULES:\n");
        systemPrompt.append("- Pipeline handles SSH natively. Use ssh_command for remote exec and scp for file transfer. NEVER raw 'ssh user@host' or shell scp.\n");
        systemPrompt.append("- NEVER put passwords in commands. Put SSH credentials under servers[].password or servers[].key; they are auto-encrypted on save. NO sshpass/expect.\n");
        systemPrompt.append("- SSH password can be omitted (no key, no password). The system will prompt interactively during deployment.\n");
        systemPrompt.append("- File transfers: use action: scp with files: [{local: ..., remote: ...}]. NEVER use action: upload, source/target.\n");
        systemPrompt.append("- Local build: use action: run_command or maven/gradle/docker.\n\n");
        systemPrompt.append("Actions: ssh_command(remote exec)|scp(file xfer)|run_command|maven|gradle|docker|git|svn|jenkins|k8s.\n\n");
        systemPrompt.append("Vars: {{VAR_NAME}}. Built-in: PROJECT_NAME, PROJECT_DIR, WORKSPACE_DIR, timestamp.\n\n");

        if (isCluster) {
            systemPrompt.append("CLUSTER mode format:\n");
            systemPrompt.append("  clusters:\n");
            systemPrompt.append("    production:\n");
            systemPrompt.append("      strategy: all|rolling|canary\n");
            systemPrompt.append("      health_check: {enabled: true, type: http|tcp|command|none, port, path, timeout, retries}\n");
            systemPrompt.append("        - Health check runs AFTER all pipeline steps complete.\n");
            systemPrompt.append("        - enabled defaults to false. MUST set enabled: true to activate.\n");
            systemPrompt.append("      hosts: [{host, user, port, key?, password?}]\n");
            systemPrompt.append("        - SSH password can be omitted; system prompts interactively at deploy time.\n");
            systemPrompt.append("  steps referencing this cluster MUST include: cluster: \"production\"\n\n");
        } else {
            systemPrompt.append("SINGLE-HOST mode format:\n");
            systemPrompt.append("  servers: [{host, user, port, key?, password?}]\n");
            systemPrompt.append("  Remote steps (ssh_command or scp) MAY omit host/cluster; they automatically target the single server.\n");
            systemPrompt.append("  You can also explicitly set cluster: \"_default\" for clarity.\n\n");
        }

        systemPrompt.append("PROFILES (optional multi-environment):\n");
        systemPrompt.append("  Add a profiles section for environment-specific variable overrides.\n");
        systemPrompt.append("  profiles:\n");
        systemPrompt.append("    default: {VAR: \"value\"}  # used when 'deploy' is run with no arg\n");
        systemPrompt.append("    dev:     {VAR: \"value\"}\n");
        systemPrompt.append("    uat:     {VAR: \"value\"}\n");
        systemPrompt.append("  Profile variables OVERRIDE top-level variables of the same name.\n");
        systemPrompt.append("  Profiles do NOT change steps/servers/clusters structure.\n");
        systemPrompt.append("  CLI: 'deploy dev' uses profile \"dev\". 'deploy' (no arg) uses \"default\".\n\n");

        systemPrompt.append("Return ONLY the raw YAML content, no markdown formatting, no backticks.\n");
        systemPrompt.append("Use comments (starting with #) in the YAML to explain each section.\n");
        systemPrompt.append("IMPORTANT: Respond in ").append("zh".equals(I18n.getLanguage()) ? "Chinese" : "English").append(".\n");

        // Build user prompt
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Generate a deploy.yaml for the following project:\n\n");
        userPrompt.append("Project type: ").append(context.getProjectType()).append("\n");
        userPrompt.append("Directory structure:\n").append(context.getDirectoryTree()).append("\n");
        if (context.getBuildFileContent() != null && !context.getBuildFileContent().isEmpty()) {
            userPrompt.append("Build file content:\n").append(context.getBuildFileContent()).append("\n");
        }

        if (isCluster) {
            userPrompt.append("\nDeployment type: CLUSTER\n");
            userPrompt.append("Strategy: ").append(strategy).append("\n");
            userPrompt.append("Hosts:\n");
            for (int i = 0; i < servers.size(); i++) {
                ServerInfo s = servers.get(i);
                userPrompt.append("  - ").append(s.getUser()).append("@").append(s.getHost()).append(":").append(s.getPort()).append("\n");
            }
        } else {
            userPrompt.append("\nDeployment type: SINGLE HOST\n");
            if (!servers.isEmpty()) {
                ServerInfo s = servers.get(0);
                userPrompt.append("Host: ").append(s.getUser()).append("@").append(s.getHost()).append(":").append(s.getPort()).append("\n");
            }
        }

        if (!envVars.isEmpty()) {
            userPrompt.append("\nEnvironment variables:\n");
            for (Map.Entry<String, String> e : envVars.entrySet()) {
                userPrompt.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        }

        if (!profiles.isEmpty()) {
            userPrompt.append("\nMulti-environment profiles:\n");
            for (Map.Entry<String, Map<String, String>> entry : profiles.entrySet()) {
                userPrompt.append("  ").append(entry.getKey()).append(":\n");
                for (Map.Entry<String, String> v : entry.getValue().entrySet()) {
                    userPrompt.append("    ").append(v.getKey()).append(": \"").append(v.getValue()).append("\"\n");
                }
            }
        }

        userPrompt.append("\nGenerate the complete deploy.yaml configuration.");

        try {
            AiHttpClient aiClient = new AiHttpClient(apiKey, baseUrl);
            String requestBody = buildOpenAiRequest(systemPrompt.toString(), userPrompt.toString(), model);
            String response = aiClient.post(apiUrl, requestBody);
            return extractYamlFromResponse(response);
        } catch (Exception e) {
            logger.error("AI generation failed", e);
            output.onOutput(I18n.get("deploy.generate.ai_error", e.getMessage()) + "\n");
            return null;
        }
    }

    /**
     * Build an OpenAI-compatible chat completion request body.
     */
    private String buildOpenAiRequest(String systemPrompt, String userPrompt, String model) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        request.put("messages", messages);
        request.put("temperature", 0.3);
        request.put("max_tokens", 4096);

        return JsonUtils.toJson(request);
    }

    /**
     * Extract YAML content from AI response (handles markdown code blocks).
     */
    private String extractYamlFromResponse(String response) {
        try {
            // Parse OpenAI response format
            ObjectMapper mapper = JsonUtils.getMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("");
            if (content.isEmpty()) {
                return null;
            }

            // Strip markdown code block if present
            content = content.trim();
            if (content.startsWith("```yaml")) {
                content = content.substring(7).trim();
            } else if (content.startsWith("```")) {
                content = content.substring(3).trim();
            }
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3).trim();
            }

            return content;
        } catch (Exception e) {
            logger.warn("Failed to parse AI response JSON, trying raw content", e);
            // Try to extract yaml from raw response
            String text = response.trim();
            if (text.startsWith("```yaml")) {
                text = text.substring(7).trim();
            } else if (text.startsWith("```")) {
                text = text.substring(3).trim();
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
            return text.isEmpty() ? null : text;
        }
    }

    /**
     * Generate a template deploy.yaml when AI is not available.
     */
    private String generateTemplateConfig(String projectName, String projectType, boolean isCluster,
                                           String strategy, String healthCheckType, int healthPort,
                                           String healthPath, int healthRetries,
                                           List<ServerInfo> servers, Map<String, String> envVars,
                                           Map<String, Map<String, String>> profiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Deploy configuration for ").append(projectName).append("\n");
        sb.append("# Auto-generated by Diatom CLI\n\n");
        sb.append("version: \"1.0\"\n");
        sb.append("name: \"").append(projectName).append("-deploy\"\n\n");

        // Variables section - only user-defined env vars; SSH auth lives on servers/hosts
        if (envVars != null && !envVars.isEmpty()) {
            sb.append("variables:\n");
            for (Map.Entry<String, String> e : envVars.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": \"").append(e.getValue()).append("\"\n");
            }
            sb.append("\n");
        }

        // Profiles section (multi-environment)
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

        // Servers/clusters section
        if (isCluster && !servers.isEmpty()) {
            sb.append("clusters:\n");
            sb.append("  production:\n");
            sb.append("    strategy: \"").append(strategy).append("\"\n");

            if (!"none".equals(healthCheckType)) {
                sb.append("    health_check:\n");
                sb.append("      enabled: true\n");
                sb.append("      type: \"").append(healthCheckType).append("\"\n");
                sb.append("      port: ").append(healthPort).append("\n");
                sb.append("      path: \"").append(healthPath).append("\"\n");
                sb.append("      timeout: 30\n");
                sb.append("      retries: ").append(healthRetries).append("\n");
            }

            sb.append("    hosts:\n");
            for (ServerInfo s : servers) {
                appendHost(sb, s, "      ");
            }
            sb.append("\n");
        } else if (!servers.isEmpty()) {
            sb.append("servers:\n");
            for (ServerInfo s : servers) {
                appendHost(sb, s, "  ");
            }
            sb.append("\n");
        }

        // Steps
        sb.append("steps:\n");
        sb.append("  - name: \"Build\"\n");

        if ("maven".equals(projectType)) {
            sb.append("    action: \"maven\"\n");
            sb.append("    command: \"clean package -DskipTests\"\n\n");
        } else if ("gradle".equals(projectType)) {
            sb.append("    action: \"run_command\"\n");
            sb.append("    command: \"./gradlew build\"\n\n");
        } else if ("npm".equals(projectType)) {
            sb.append("    action: \"run_command\"\n");
            sb.append("    command: \"npm run build\"\n\n");
        } else {
            sb.append("    action: \"run_command\"\n");
            sb.append("    command: \"echo 'Build step - customize for your project'\"\n\n");
        }

        // Deploy step
        if (isCluster) {
            sb.append("  - name: \"Deploy to Cluster\"\n");
            sb.append("    action: \"ssh_command\"\n");
            sb.append("    cluster: \"production\"\n");
            sb.append("    commands:\n");
            sb.append("      - \"echo 'Deploying to {{PROJECT_NAME}}'\"\n");
            sb.append("      - \"mkdir -p /opt/{{PROJECT_NAME}}\"\n");
        } else if (!servers.isEmpty()) {
            sb.append("  - name: \"Deploy to Server\"\n");
            sb.append("    action: \"ssh_command\"\n");
            // For single-server mode, omit host/cluster so execution falls back to the default server.
            sb.append("    commands:\n");
            sb.append("      - \"echo 'Deploying to {{PROJECT_NAME}}'\"\n");
            sb.append("      - \"mkdir -p /opt/{{PROJECT_NAME}}\"\n");
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

    /**
     * Write deploy.yaml to the project's .diatom directory.
     * Password fields are automatically encrypted before writing.
     *
     * @param projectName the project name
     * @param yamlContent the YAML content to write
     * @return the path to the written file, or null on failure
     */
    public String writeDeployYaml(String projectName, String yamlContent) {
        try {
            // Encrypt all SSH password fields before persisting to disk
            String processedContent = encryptPasswordFields(yamlContent);

            Path projectDir = Paths.get(workspacePath, projectName);
            Path diatomDir = projectDir.resolve(".diatom");
            Files.createDirectories(diatomDir);

            Path deployFile = diatomDir.resolve("deploy.yaml");
            Files.write(deployFile, processedContent.getBytes(StandardCharsets.UTF_8));
            return deployFile.toString();
        } catch (IOException e) {
            logger.error("Failed to write deploy.yaml", e);
            return null;
        }
    }

    /**
     * Encrypt all {@code password:} fields in a deploy.yaml content string.
     * Uses the {@link SshPasswordCipher} SPI (custom or default) for encryption.
     * Already-encrypted values (starting with {@code $ENC$}) are left unchanged.
     */
    public static String encryptPasswordFields(String yamlContent) {
        if (yamlContent == null || yamlContent.isEmpty()) {
            return yamlContent;
        }
        SshPasswordCipher cipher = SpiLoader.getFirst(SshPasswordCipher.class,
                new DefaultSshPasswordCipher());
        // Match lines like:  password: "value"  or  password: value
        Pattern pattern = Pattern.compile(
                "^(\\s*password\\s*:\\s*\"?)([^\"\\n]+)(\"?)\\s*$",
                Pattern.MULTILINE
        );
        StringBuffer sb = new StringBuffer();
        Matcher matcher = pattern.matcher(yamlContent);
        while (matcher.find()) {
            String value = matcher.group(2).trim();
            // Skip already-encrypted values
            if (value.startsWith("$ENC$")) {
                continue;
            }
            String prefix = matcher.group(1);
            String suffix = matcher.group(3);
            String encrypted = cipher.encrypt(value);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + encrypted + suffix));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Delete existing deploy.yaml.
     */
    public boolean deleteDeployYaml(String projectName) {
        Path deployFile = Paths.get(workspacePath, projectName, ".diatom", "deploy.yaml");
        try {
            return Files.deleteIfExists(deployFile);
        } catch (IOException e) {
            logger.error("Failed to delete deploy.yaml", e);
            return false;
        }
    }

    // ========== Interactive Input Helpers ==========

    private String askString(String prompt, String defaultValue) {
        String fullPrompt = defaultValue != null && !defaultValue.isEmpty()
                ? prompt + " [" + defaultValue + "]: "
                : prompt + ": ";
        System.out.print(fullPrompt);
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) return defaultValue;
            line = line.trim();
            return line.isEmpty() ? defaultValue : line;
        } catch (IOException e) {
            return defaultValue;
        }
    }

    private boolean askYesNo(String prompt, boolean defaultValue) {
        while (true) {
            String result = askString(prompt + " (y/n)", defaultValue ? "y" : "n");
            if (result == null) return defaultValue;
            String lower = result.trim().toLowerCase();
            if ("y".equals(lower) || "yes".equals(lower)) return true;
            if ("n".equals(lower) || "no".equals(lower)) return false;
            System.out.println("  Please enter y or n.");
        }
    }

    private String askOption(String prompt, List<String> options, String defaultOption) {
        System.out.print(prompt + " (" + String.join("/", options) + ") [" + defaultOption + "]: ");
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) return defaultOption;
            line = line.trim().toLowerCase();
            for (String opt : options) {
                if (opt.equals(line)) return opt;
            }
            return defaultOption;
        } catch (IOException e) {
            return defaultOption;
        }
    }

    private int askInt(String prompt, int defaultValue) {
        while (true) {
            String result = askString(prompt, String.valueOf(defaultValue));
            if (result == null) return defaultValue;
            try {
                return Integer.parseInt(result.trim());
            } catch (NumberFormatException e) {
                System.out.println("  Please enter a valid number.");
            }
        }
    }

    // ========== Inner Classes ==========

    /**
     * Output callback for generation progress messages.
     */
    public interface DeployGeneratorOutput {
        void onOutput(String text);
    }
}
