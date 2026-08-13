package com.github.obhen233.core.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.knowledge.CommandKnowledgeManager;
import com.github.obhen233.core.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.github.obhen233.util.JsonUtils;

/**
 * LLM-based Command Classifier
 *
 * Uses LLM to analyze unknown commands and classify their risk level.
 * Combines --help output parsing with LLM judgment for intelligent learning.
 */
public class LlmCommandClassifier {
    private static final Logger logger = LoggerFactory.getLogger(LlmCommandClassifier.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final AiHttpClient httpClient;
    private final ModelAdapter adapter;
    private final String endpoint;
    private final CommandKnowledgeManager knowledgeManager;

    // System prompt for command classification
   /* private static final String CLASSIFIER_SYSTEM_PROMPT =
        "You are a command risk classifier. Your task is to analyze shell commands and classify their risk level.\n\n" +
        "Risk Levels:\n" +
        "  0 = SAFE - Read-only operations that don't modify files or system state\n" +
        "  1 = CAUTION - Operations that may have side effects or modify project files\n" +
        "  2 = DANGEROUS - Operations that can modify/delete significant data or system state\n" +
        "  3 = HIGHLY_DANGEROUS - Operations that can cause data loss or system damage\n\n" +
        "Commands to classify:\n" +
        "  - git, mvn, npm, docker, python, etc. (development tools)\n" +
        "  - file operations: cat, ls, find, grep (read-only)\n" +
        "  - modifications: chmod, chown, mv, rm (caution)\n" +
        "  - deletions: rm -rf, del /s /q (dangerous)\n" +
        "  - system: format, fdisk, dd, mkfs (highly dangerous)\n\n" +
        "Output format (JSON only, no other text):\n" +
        "{\n" +
        "  \"command\": \"the exact command string\",\n" +
        "  \"risk_level\": 0-3,\n" +
        "  \"permission\": \"ALLOW\" or \"DENY\",\n" +
        "  \"tool_type\": \"git|maven|npm|docker|shell|etc\",\n" +
        "  \"reasoning\": \"brief explanation of classification\"\n" +
        "}\n\n" +
        "Rules:\n" +
        "  - If risk_level >= 2, permission should be DENY (dangerous commands should not be auto-approved)\n" +
        "  - If command contains rm -rf /, format, fdisk, dd, mkfs → risk_level = 3, permission = DENY\n" +
        "  - If command contains rm -rf *, rm -rf .*, rm -rf with wildcard after cd / → risk_level = 3, permission = DENY\n" +
        "  - Be conservative: when in doubt, assign higher risk level";*/

    // System prompt for command classification
    private static final String CLASSIFIER_SYSTEM_PROMPT =
            "You are a command risk classifier. Analyze shell commands and classify their risk level.\n\n" +
                    "Risk Levels:\n" +
                    "  0 = SAFE: Read-only, no modifications (e.g., cat, ls, git log)\n" +
                    "  1 = CAUTION: May modify project files (e.g., chmod, mv, npm install)\n" +
                    "  2 = DANGEROUS: Can delete/modify significant data (e.g., rm -rf *)\n" +
                    "  3 = HIGHLY_DANGEROUS: System damage or data loss (e.g., format, dd, rm -rf /)\n\n" +
                    "Output JSON only:\n" +
                    "{\"command\":\"...\",\"risk_level\":0-3,\"permission\":\"ALLOW|DENY\",\"tool_type\":\"git|maven|npm|docker|shell|etc\",\"reasoning\":\"...\"}\n\n" +
                    "Rules:\n" +
                    "  - risk_level >= 2 → permission = DENY\n" +
                    "  - rm -rf /, format, fdisk, dd, mkfs → risk_level = 3, DENY\n" +
                    "  - rm -rf with wildcard after cd / → risk_level = 3, DENY\n" +
                    "  - Conservative: when uncertain, assign higher risk level";

    public LlmCommandClassifier(AiHttpClient httpClient, ModelAdapter adapter,
                                 String endpoint, CommandKnowledgeManager knowledgeManager) {
        this.httpClient = httpClient;
        this.adapter = adapter;
        this.endpoint = endpoint;
        this.knowledgeManager = knowledgeManager;
    }

    /**
     * Classify an unknown command using LLM
     *
     * @param command The command to classify
     * @param helpOutput The output from --help (can be null)
     * @return Classification result
     */
    public ClassificationResult classify(String command, String helpOutput) {
        if (command == null || command.trim().isEmpty()) {
            return new ClassificationResult(command, 1, "UNSURE", "unknown", "Empty command");
        }

        try {
            // Build user message with command and help output
            StringBuilder userContent = new StringBuilder();
            userContent.append("Please classify this command:\n\n");
            userContent.append("Command: ").append(command).append("\n\n");

            if (helpOutput != null && !helpOutput.isEmpty()) {
                userContent.append("--help output:\n");
                userContent.append(truncate(helpOutput, 2000));
                userContent.append("\n\n");
            }

            userContent.append("Respond with only the JSON classification.");

            // Build messages
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage("system", CLASSIFIER_SYSTEM_PROMPT));
            messages.add(new ChatMessage("user", userContent.toString()));

            // Build and send request (same format as main agent loop)
            String requestBody = adapter.buildRequest(messages, null, false);
            String response = httpClient.post(endpoint, requestBody);

            // Parse response using adapter first (extracts model output from API wrapper)
            // Then extract classification JSON from the model's text content
            ChatMessage assistantMsg = adapter.parseResponse(response).getMessage();
            if (assistantMsg == null) {
                logger.warn("Empty response from model for command: {}", command);
                return new ClassificationResult(command, 1, "UNSURE", "unknown", "Empty response");
            }
            return parseClassificationResponse(command, assistantMsg.getContent());

        } catch (Exception e) {
            logger.error("LLM classification failed for command: {}", command, e);
            return new ClassificationResult(command, 1, "UNSURE", "unknown",
                "Classification failed: " + e.getMessage());
        }
    }

    /**
     * Classify a command and automatically learn the result.
     * Returns detailed classification result.
     */
    public ClassificationResult classifyAndLearn(String command) {
        // First get --help output
        String helpOutput = getHelpOutput(command);

        // Classify using LLM
        ClassificationResult result = classify(command, helpOutput);

        // Log detailed classification info
        logger.info("[LLM Classification] Command: {}", command);
        logger.info("[LLM Classification] Tool Type: {}", result.toolType);
        logger.info("[LLM Classification] Risk Level: {} (0=safe, 1=caution, 2=dangerous, 3=highly dangerous)", result.riskLevel);
        logger.info("[LLM Classification] Permission: {}", result.permission);
        logger.info("[LLM Classification] Reasoning: {}", result.reasoning);

        // Learn the result
        if (result != null && result.riskLevel >= 0) {
            knowledgeManager.learnCommand(
                command,
                result.toolType,
                result.permission,
                result.riskLevel,
                CommandKnowledgeManager.SOURCE_LLM
            );
            logger.info("[LLM Classification] Learned and stored: {}", command);
        }

        return result;
    }

    /**
     * Get help output for a command
     */
    private String getHelpOutput(String command) {
        StringBuilder output = new StringBuilder();
        try {
            String[] parts = command.trim().split("\\s+");
            if (parts.length == 0) return "";

            String cmd = parts[0];
            List<String> cmdList = new ArrayList<>();

            // Try --help first
            cmdList.add(cmd);
            cmdList.add("--help");

            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < 50) {
                    output.append(line).append("\n");
                    lineCount++;
                }
            }

            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("Failed to get --help for command {}: {}", command, e.getMessage());
        }
        return output.toString();
    }

    private ClassificationResult parseClassificationResponse(String originalCommand, String response) {
        try {
            // Try to extract JSON from response
            String jsonStr = extractJson(response);

            if (jsonStr == null) {
                logger.warn("No JSON found in LLM response for command: {}", originalCommand);
                return new ClassificationResult(originalCommand, 1, "UNSURE", "unknown", "Failed to parse LLM response");
            }

            JsonNode node = mapper.readTree(jsonStr);

            String command = node.has("command") ? node.get("command").asText() : originalCommand;
            int riskLevel = node.has("risk_level") ? node.get("risk_level").asInt() : 1;
            String permission = node.has("permission") ? node.get("permission").asText() : "UNSURE";
            String toolType = node.has("tool_type") ? node.get("tool_type").asText() : "unknown";
            String reasoning = node.has("reasoning") ? node.get("reasoning").asText() : "";

            return new ClassificationResult(command, riskLevel, permission, toolType, reasoning);

        } catch (Exception e) {
            logger.error("Failed to parse classification response for {}: {}", originalCommand, e.getMessage());
            return new ClassificationResult(originalCommand, 1, "UNSURE", "unknown",
                "Parse error: " + e.getMessage());
        }
    }

    /**
     * Extract JSON from LLM response (may be wrapped in markdown code blocks)
     */
    private String extractJson(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        // Try direct parse
        try {
            mapper.readTree(response);
            return response;
        } catch (Exception ignored) {}

        // Try to find JSON in markdown code block
        int jsonStart = response.indexOf("```json");
        if (jsonStart >= 0) {
            jsonStart += 7;
        } else {
            jsonStart = response.indexOf("```");
        }

        if (jsonStart >= 0) {
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                String json = response.substring(jsonStart, jsonEnd).trim();
                try {
                    mapper.readTree(json);
                    return json;
                } catch (Exception ignored) {}
            }
        }

        // Try to find { } block
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            String json = response.substring(braceStart, braceEnd + 1);
            try {
                mapper.readTree(json);
                return json;
            } catch (Exception ignored) {}
        }

        return null;
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "... (truncated)";
    }

    /**
     * Classification result
     */
    public static class ClassificationResult {
        public final String command;
        public final int riskLevel;
        public final String permission;
        public final String toolType;
        public final String reasoning;

        public ClassificationResult(String command, int riskLevel, String permission,
                                    String toolType, String reasoning) {
            this.command = command;
            this.riskLevel = Math.max(0, Math.min(3, riskLevel));
            this.permission = permission;
            this.toolType = toolType;
            this.reasoning = reasoning;
        }
    }
}
