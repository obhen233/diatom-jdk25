package com.github.obhen233.compiler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.entity.IdeSetting;
import com.github.obhen233.compiler.repository.IdeSettingRepository;
import com.github.obhen233.core.adapter.AnthropicAdapter;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.adapter.OpenAIAdapter;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Service using diatom-core components (AiHttpClient, ModelAdapter).
 *
 * Provides AI programming assistant capabilities with file operation confirmation.
 * This integrates with diatom-core for API communication while maintaining
 * the IDE's confirmation workflow for file operations.
 */
@Service
public class AiChatService {

    private static final Logger logger = LoggerFactory.getLogger(AiChatService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private AiHttpClient aiHttpClient;

    @Autowired(required = false)
    private IdeSettingRepository settingRepo;

    // Pending operations per project - 使用 ConcurrentHashMap 保证线程安全
    private final Map<String, List<FileOp>> pendingOps = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        logger.info("AiChatService initialized, AiHttpClient available: {}", aiHttpClient != null);
    }

    /**
     * Execute AI chat with project context.
     * Returns AI response and pending file operations (not auto-executed).
     */
    public AiChatResult chat(String prompt, String context, String fileName,
                            String filePath, String projectName) {
        AiChatResult result = new AiChatResult();

        // Get AI config
        AiConfig config = getAiConfig();
        if (!config.isEnabled()) {
            result.setSuccess(false);
            result.setMessage("AI not configured. Please check AI settings.");
            return result;
        }

        try {
            // Build messages with context
            List<ChatMessage> messages = buildMessages(prompt, context, fileName, filePath, projectName);

            // Create adapter based on model
            ModelAdapter adapter = createAdapter(config.getModel());

            // Build endpoint
            String endpoint = buildEndpoint(config.getApiUrl());

            // Build request
            String requestBody = adapter.buildRequest(messages, null, false);

            logger.debug("AI request: {}", requestBody.substring(0, Math.min(500, requestBody.length())));

            // Configure HTTP client
            aiHttpClient.setBaseUrl(config.getApiUrl());
            aiHttpClient.setApiKey(config.getApiToken());
            aiHttpClient.setAuthStyle(detectAuthStyle(config.getModel()));

            // Execute call
            String response = aiHttpClient.post(endpoint, requestBody);

            logger.debug("AI response: {}", response.substring(0, Math.min(500, response.length())));

            // Parse response
            ChatResponse chatResponse = adapter.parseResponse(response);
            if (chatResponse == null || chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
                result.setSuccess(false);
                result.setMessage("Failed to parse AI response");
                return result;
            }

            String content = chatResponse.getChoices().get(0).getMessage().getContent();

            // Parse file operations from content
            List<FileOp> ops = parseFileOps(content, projectName);
            pendingOps.put(projectName, ops);

            // Clean content for display
            String cleanContent = cleanContent(content);

            result.setSuccess(true);
            result.setContent(content);
            result.setCleanContent(cleanContent);
            result.setHasFileOps(!ops.isEmpty());
            result.setFileOps(ops);

            logger.info("AI chat completed for project {}, file ops: {}", projectName, ops.size());

        } catch (Exception e) {
            logger.error("AI chat error", e);
            result.setSuccess(false);
            result.setMessage("AI error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Confirm and execute a pending file operation.
     */
    public ConfirmResult confirmOp(String projectName, String path, String content) {
        ConfirmResult result = new ConfirmResult();

        try {
            String projectPath = new File(Constants.workspacePath, projectName).getAbsolutePath();
            File targetFile = new File(projectPath, path);

            // Security check - ensure path is within project
            File projectDir = new File(projectPath);
            if (!targetFile.getCanonicalPath().startsWith(projectDir.getCanonicalPath())) {
                result.setSuccess(false);
                result.setMessage("Path outside project directory");
                return result;
            }

            // Create parent directories
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            // Write file
            Files.write(targetFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

            // Remove from pending
            List<FileOp> ops = pendingOps.get(projectName);
            if (ops != null) {
                ops.removeIf(op -> op.getPath().equals(path));
            }

            result.setSuccess(true);
            result.setMessage("File written: " + path);
            logger.info("File confirmed: {} in project {}", path, projectName);

        } catch (Exception e) {
            logger.error("Confirm op error", e);
            result.setSuccess(false);
            result.setMessage("Write failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Get pending operations for a project.
     */
    public List<FileOp> getPendingOps(String projectName) {
        return pendingOps.getOrDefault(projectName, new ArrayList<>());
    }

    /**
     * Clear pending operations.
     */
    public void clearPendingOps(String projectName) {
        pendingOps.remove(projectName);
    }

    // ==================== Private Methods ====================

    private AiConfig getAiConfig() {
        AiConfig config = new AiConfig();
        if (settingRepo != null) {
            config.setApiUrl(settingRepo.findById("aiApiUrl").map(IdeSetting::getValue).orElse(""));
            config.setApiToken(settingRepo.findById("aiApiToken").map(IdeSetting::getValue).orElse(""));
            config.setModel(settingRepo.findById("aiModel").map(IdeSetting::getValue).orElse("gpt-4"));
            config.setEnabled("true".equalsIgnoreCase(
                settingRepo.findById("aiEnabled").map(IdeSetting::getValue).orElse("false")));
        }
        return config;
    }

    private List<ChatMessage> buildMessages(String prompt, String context,
            String fileName, String filePath, String projectName) {
        List<ChatMessage> messages = new ArrayList<>();

        // System message
        ChatMessage system = new ChatMessage();
        system.setRole("system");
        system.setContent(getSystemPrompt());
        messages.add(system);

        // Project context
        String projectContext = buildProjectContext(projectName);
        if (!projectContext.isEmpty()) {
            ChatMessage ctxMsg = new ChatMessage();
            ctxMsg.setRole("user");
            ctxMsg.setContent("## Project Context\n" + projectContext);
            messages.add(ctxMsg);
        }

        // Current file context
        if (context != null && !context.isEmpty()) {
            ChatMessage ctxMsg = new ChatMessage();
            ctxMsg.setRole("user");
            String ctx = "## Current File: " + (filePath != null ? filePath : fileName) + "\n";
            ctx += "```java\n" + context + "\n```\n";
            ctxMsg.setContent(ctx);
            messages.add(ctxMsg);
        }

        // User prompt
        ChatMessage userMsg = new ChatMessage();
        userMsg.setRole("user");
        userMsg.setContent(prompt);
        messages.add(userMsg);

        return messages;
    }

    private String getSystemPrompt() {
        return "You are an expert Java IDE programming assistant like Kiro/Claude Code.\n\n" +
               "## Editor Context (IMPORTANT)\n" +
               "- When user asks about 'this file', '当前文件', '这个文件', or wants to understand the current file:\n" +
               "  → FIRST call get_active_file MCP tool to get the currently open file path, THEN use that information\n" +
               "- Use get_open_tabs to see all open files if needed\n" +
               "- Use get_cursor_context to get code around cursor position\n\n" +
               "## Capabilities\n" +
               "- Generate/modify Java code files\n" +
               "- Modify pom.xml for Maven dependencies\n" +
               "- Modify build.gradle for Gradle dependencies\n" +
               "- Create new classes, interfaces, configs\n\n" +
               "## Output Format for File Operations\n" +
               "When creating/modifying files, use:\n" +
               "```file:relative/path/to/File.java\n" +
               "// full file content\n" +
               "```\n\n" +
               "## Rules\n" +
               "- Only operate on files within the project workspace\n" +
               "- Add dependencies by modifying pom.xml or build.gradle\n" +
               "- Java files must have correct package declarations and imports\n" +
               "- When not doing file operations, respond in plain text\n" +
               "- Respond in Chinese (中文回答)";
    }

    private String buildProjectContext(String projectName) {
        if (projectName == null || projectName.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        File projectDir = new File(Constants.workspacePath, projectName);
        if (!projectDir.exists()) return "";

        // Project structure
        sb.append("Project: ").append(projectName).append("\n");
        sb.append("Structure:\n");
        collectFileTree(projectDir, projectDir, sb, 0, 3);

        // Build files
        File pom = new File(projectDir, "pom.xml");
        if (pom.exists()) {
            sb.append("\npom.xml:\n```xml\n");
            sb.append(readFileSafe(pom, 4000)).append("\n```\n");
        }

        File gradle = new File(projectDir, "build.gradle");
        if (gradle.exists()) {
            sb.append("\nbuild.gradle:\n```groovy\n");
            sb.append(readFileSafe(gradle, 4000)).append("\n```\n");
        }

        return sb.toString();
    }

    private void collectFileTree(File root, File dir, StringBuilder sb, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        String indent = "";
        for (int i = 0; i < depth; i++) indent += "  ";

        int count = 0;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (f.getName().equals("target") || f.getName().equals("build") ||
                f.getName().equals("node_modules")) continue;

            if (count++ > 50) {
                sb.append(indent).append("  ...\n");
                break;
            }

            if (f.isDirectory()) {
                sb.append(indent).append(f.getName()).append("/\n");
                collectFileTree(root, f, sb, depth + 1, maxDepth);
            } else {
                sb.append(indent).append("  ").append(f.getName()).append("\n");
            }
        }
    }

    private String readFileSafe(File f, int maxLen) {
        try {
            String c = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return c.length() > maxLen ? c.substring(0, maxLen) + "\n...(truncated)" : c;
        } catch (Exception e) {
            return "(read error)";
        }
    }

    private ModelAdapter createAdapter(String model) {
        if (model != null && model.toLowerCase().contains("claude")) {
            return new AnthropicAdapter(model, 8192);
        }
        return new OpenAIAdapter(model, 8192);
    }

    private String buildEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://api.openai.com";
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.contains("anthropic")) {
            return baseUrl + "/v1/messages";
        }
        return baseUrl + "/v1/chat/completions";
    }

    private AiHttpClient.AuthStyle detectAuthStyle(String model) {
        if (model != null && model.toLowerCase().contains("claude")) {
            return AiHttpClient.AuthStyle.ANTHROPIC;
        }
        return AiHttpClient.AuthStyle.BEARER;
    }

    private List<FileOp> parseFileOps(String content, String projectName) {
        List<FileOp> ops = new ArrayList<>();
        if (content == null) return ops;

        // Parse ```file:path\n...``` blocks
        String[] lines = content.split("\n");
        String currentPath = null;
        StringBuilder currentContent = new StringBuilder();
        boolean inFileBlock = false;
        boolean isFirstLine = true;

        for (String line : lines) {
            if (line.startsWith("```file:") || line.startsWith("```file")) {
                // Save previous operation
                if (currentPath != null && currentContent.length() > 0) {
                    FileOp op = new FileOp();
                    op.setPath(currentPath.trim());
                    op.setContent(currentContent.toString());
                    ops.add(op);
                }
                // Extract path
                String marker = line.startsWith("```file:") ? "```file:" : "```file";
                currentPath = line.substring(line.indexOf(marker) + marker.length()).trim();
                if (currentPath.contains("```")) {
                    currentPath = currentPath.substring(0, currentPath.indexOf("```")).trim();
                }
                currentContent = new StringBuilder();
                inFileBlock = true;
                isFirstLine = true;
            } else if (line.startsWith("```") && inFileBlock) {
                // End of file block
                inFileBlock = false;
                if (currentPath != null && currentContent.length() > 0) {
                    FileOp op = new FileOp();
                    op.setPath(currentPath.trim());
                    op.setContent(currentContent.toString());
                    ops.add(op);
                }
                currentPath = null;
                currentContent = new StringBuilder();
            } else if (inFileBlock) {
                if (!isFirstLine) {
                    currentContent.append("\n");
                }
                currentContent.append(line);
                isFirstLine = false;
            }
        }

        // Handle last operation
        if (currentPath != null && currentContent.length() > 0) {
            FileOp op = new FileOp();
            op.setPath(currentPath.trim());
            op.setContent(currentContent.toString());
            ops.add(op);
        }

        return ops;
    }

    private String cleanContent(String content) {
        if (content == null) return "";
        // Remove file blocks from display content
        String cleaned = content.replaceAll("```file:[^\\n]*\\n[\\s\\S]*?```", "[文件操作待确认]");
        cleaned = cleaned.replaceAll("```file\\s*\\n[\\s\\S]*?```", "[文件操作待确认]");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        return cleaned.trim();
    }

    // ==================== Data Classes ====================

    public static class AiConfig {
        private String apiUrl;
        private String apiToken;
        private String model;
        private boolean enabled;

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiToken() { return apiToken; }
        public void setApiToken(String apiToken) { this.apiToken = apiToken; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class FileOp {
        private String path;
        private String content;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class AiChatResult {
        private boolean success;
        private String message;
        private String content;
        private String cleanContent;
        private boolean hasFileOps;
        private List<FileOp> fileOps;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getCleanContent() { return cleanContent; }
        public void setCleanContent(String cleanContent) { this.cleanContent = cleanContent; }
        public boolean isHasFileOps() { return hasFileOps; }
        public void setHasFileOps(boolean hasFileOps) { this.hasFileOps = hasFileOps; }
        public List<FileOp> getFileOps() { return fileOps; }
        public void setFileOps(List<FileOp> fileOps) { this.fileOps = fileOps; }
    }

    public static class ConfirmResult {
        private boolean success;
        private String message;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
