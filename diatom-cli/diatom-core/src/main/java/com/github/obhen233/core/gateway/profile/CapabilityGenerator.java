package com.github.obhen233.core.gateway.profile;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Worker 能力自述文档生成器
 * 首次启动时生成 capability.md
 */
public class CapabilityGenerator {
    private static final Logger logger = LoggerFactory.getLogger(CapabilityGenerator.class);

    private final AppConfig config;

    public CapabilityGenerator(AppConfig config) {
        this.config = config;
    }

    /**
     * Save capability.md to default path (~/.diatom/workers/{workerId}/capability.md)
     */
    public void saveProfile(String workerId, CapabilityProfile profile) {
        try {
            String installHome = System.getProperty("diatom.install.home", System.getProperty("user.home") + "/.diatom");
            Path profilePath = Paths.get(installHome, "workers", workerId, "capability.md");
            saveProfile(profilePath, profile);
        } catch (Exception e) {
            logger.warn("Failed to save capability profile: {}", e.getMessage());
        }
    }

    /**
     * 保存 capability.md 文件到指定路径
     */
    public void saveProfile(Path savePath, CapabilityProfile profile) {
        try {
            Files.createDirectories(savePath.getParent());
            Files.write(savePath, profile.getRawMarkdown().getBytes(StandardCharsets.UTF_8));
            logger.info("Capability profile saved: {}", savePath);
        } catch (Exception e) {
            logger.warn("Failed to save capability profile: {}", e.getMessage());
        }
    }

    /**
     * 从指定路径加载 capability.md 文件，解析为 CapabilityProfile
     * @return 如果文件存在且解析成功返回 CapabilityProfile，否则返回 null
     */
    public CapabilityProfile loadProfile(Path profilePath, String workerId) {
        if (!Files.exists(profilePath)) {
            logger.warn("Capability profile not found: {}", profilePath);
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(profilePath), StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                logger.warn("Capability profile is empty: {}", profilePath);
                return null;
            }
            CapabilityProfile profile = new CapabilityAnalyzer().parseMarkdown(content, workerId);
            if (profile == null) {
                profile = new CapabilityProfile();
            }
            // Ensure model is set (markdown may not have model line)
            if (profile.getModel() == null || profile.getModel().isEmpty()) {
                profile.setModel(config.getModel());
            }
            profile.setRawMarkdown(content);
            profile.setSummary("Worker capability: " + profilePath.getFileName());
            logger.debug("Loaded capability profile for {}: strengths={}",
                    workerId, profile.getStrengths().size());
            return profile;
        } catch (Exception e) {
            logger.warn("Failed to load capability profile from {}: {}", profilePath, e.getMessage());
            return null;
        }
    }

    /**
     * Generate capability profile from description via AI.
     */
    public CapabilityProfile generateProfileFromDescription(String description,
                                                             AiHttpClient httpClient,
                                                             ModelAdapter adapter,
                                                             String endpoint,
                                                             String workerId) {
        CapabilityProfile profile = new CapabilityProfile();
        profile.setWorkerId(workerId);
        profile.setModel(config.getModel());
        profile.setTier("standard");
        profile.setSupportsToolCalls(true);
        profile.setSupportsStreaming(true);
        profile.setMaxSteps(config.getMaxSteps());
        profile.setMaxTokens(128000);
        profile.setMaxOutputTokens(8192);

        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage("system",
                "You are a Worker capability document generator.\n" +
                "Generate a capability.md from the user's description. Output raw Markdown only, no code fences, no extra commentary.\n" +
                "Format:\n" +
                "# Worker Capability\n\n" +
                "## Basic Info\n" +
                "- **Worker ID**: " + workerId + "\n" +
                "- **Model**: " + profile.getModel() + "\n" +
                "- **Tier**: standard\n\n" +
                "## Strengths\n" +
                "(list 3-5 strengths, one per line)\n\n" +
                "## Boundaries\n" +
                "(list limitations)\n\n" +
                "## Suitable Task Types\n" +
                "(list suitable task types, one per line)\n\n" +
                "## Complex Task Handling\n" +
                "- Supports complex tasks: yes\n" +
                "- Recommended max steps: " + profile.getMaxSteps() + "\n" +
                "- Per-task token budget: 128000\n"));
            messages.add(new ChatMessage("user", description));

            String requestBody = adapter.buildRequest(messages, null, false);
            String responseJson = httpClient.post(endpoint, requestBody);
            ChatResponse response = adapter.parseResponse(responseJson);

            String markdown = "";
            if (response != null && response.getMessage() != null) {
                markdown = response.getMessage().getContent();
            }
            if (markdown == null || markdown.trim().isEmpty()) {
                logger.warn("AI returned empty response, using minimal fallback");
            } else {
                // Clean up LLM output — remove possible markdown code block fences
                markdown = markdown.trim();
                if (markdown.startsWith("```markdown") || markdown.startsWith("```md")) {
                    markdown = markdown.substring(markdown.indexOf('\n') + 1);
                }
                if (markdown.startsWith("```")) {
                    markdown = markdown.substring(markdown.indexOf('\n') + 1);
                }
                if (markdown.endsWith("```")) {
                    markdown = markdown.substring(0, markdown.lastIndexOf("```")).trim();
                }

                profile.setRawMarkdown(markdown);
                profile.setSummary("Worker: " + description);
            }
        } catch (Exception e) {
            logger.warn("AI profile generation failed, using minimal fallback: {}", e.getMessage());
        }

        if (profile.getRawMarkdown() == null) {
            // Populate fallback content from description
            profile.getStrengths().add("Powered by " + profile.getModel());
            if (description != null && !description.trim().isEmpty()) {
                // Use brief summary of description as strengths
                String brief = description.length() > 100 ? description.substring(0, 100) + "..." : description;
                profile.getStrengths().add("Specialized for: " + brief);
            } else {
                profile.getStrengths().add("General-purpose AI assistant");
            }
            profile.getStrengths().add("Multi-language support (code generation, review, debugging)");
            profile.getStrengths().add("File system operations within workspace");
            profile.getStrengths().add("Tool-calling and command execution capability");

            profile.getBoundaries().add("Network access: no");
            profile.getBoundaries().add("File operations: yes (within workspace)");
            profile.getBoundaries().add("Command execution: sandboxed (whitelist only)");
            profile.getBoundaries().add("No persistent memory across sessions");
            profile.getBoundaries().add("Workspace directory: " + config.getWorkspaceDir());

            profile.getSuitableTaskTypes().add("Code generation and refactoring");
            profile.getSuitableTaskTypes().add("Bug fixing and code review");
            profile.getSuitableTaskTypes().add("Project analysis and documentation");
            profile.getSuitableTaskTypes().add("Database query and data processing");
            profile.getSuitableTaskTypes().add("DevOps and build automation");

            profile.setRawMarkdown(generateMarkdown(profile));
            profile.setSummary("Worker: " + (description != null ? description : profile.getModel()));
        }
        return profile;
    }

    private String generateMarkdown(CapabilityProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Worker Capability\n\n");
        sb.append("## Basic Info\n");
        sb.append("- **Worker ID**: ").append(profile.getWorkerId()).append("\n");
        sb.append("- **Model**: ").append(profile.getModel()).append("\n");
        sb.append("- **Tier**: ").append(profile.getTier()).append("\n\n");

        sb.append("## Strengths\n");
        for (String strength : profile.getStrengths()) {
            sb.append("- ").append(strength).append("\n");
        }
        sb.append("\n");

        sb.append("## Boundaries\n");
        for (String boundary : profile.getBoundaries()) {
            sb.append("- ").append(boundary).append("\n");
        }
        sb.append("\n");

        sb.append("## Suitable Task Types\n");
        for (String type : profile.getSuitableTaskTypes()) {
            sb.append("- [x] ").append(type).append("\n");
        }
        for (String type : profile.getUnsuitableTaskTypes()) {
            sb.append("- [ ] ").append(type).append(" (unsupported)\n");
        }
        sb.append("\n");

        sb.append("## Complex Task Handling\n");
        sb.append("- Supports complex tasks: yes\n");
        sb.append("- Recommended max steps: ").append(profile.getMaxSteps()).append("\n");
        sb.append("- Per-task token budget: ").append(profile.getMaxTokens()).append("\n");

        return sb.toString();
    }
}
