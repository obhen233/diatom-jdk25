package com.github.obhen233.adapter.spi;

import java.util.List;
import java.util.Map;

/**
 * Request DTO passed to AgentAdapter.execute().
 *
 * <p>The {@code metadata} map contains agent-native security configuration
 * produced by {@link SecurityMapper#mapSecurity(SandboxLevel, ApprovalPolicy)},
 * so the driver can apply it directly without understanding diatom enums.</p>
 *
 * @param taskId              the task id
 * @param sessionId           the session id
 * @param message             the user message
 * @param workspacePath       the workspace directory
 * @param conversationHistory prior chat messages
 * @param metadata            agent-native security config
 */
public record AgentRequest(
        String taskId,
        String sessionId,
        String message,
        String workspacePath,
        List<ChatMessage> conversationHistory,
        Map<String, String> metadata) {

    /**
     * A single chat message in the conversation history.
     */
    public record ChatMessage(String role, String content) {}
}
