package com.github.obhen233.cli;

import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.util.I18n;
import com.github.obhen233.util.TokenCounter;

import java.util.List;
import java.util.Map;

/**
 * Context visualization utility for TerminalUI.
 * Provides formatted output for context information.
 */
public class ContextViewer {
    private final TokenCounter tokenCounter;

    public ContextViewer() {
        this.tokenCounter = new TokenCounter();
    }

    public ContextViewer(String modelName) {
        this.tokenCounter = new TokenCounter(modelName);
    }

    /**
     * View context summary
     */
    public String viewSummary(List<ChatMessage> messages, Map<String, Tool> tools, String mode) {
        StringBuilder sb = new StringBuilder();

        sb.append(I18n.get("context_overview_title")).append("\n");

        // Count messages by role
        int systemCount = 0;
        int userCount = 0;
        int assistantCount = 0;
        int toolCount = 0;

        for (ChatMessage msg : messages) {
            String role = msg.getRole();
            if ("system".equals(role)) systemCount++;
            else if ("user".equals(role)) userCount++;
            else if ("assistant".equals(role)) assistantCount++;
            else if ("tool".equals(role)) toolCount++;
        }

        sb.append(I18n.get("context_message_count", String.valueOf(messages.size())));
        sb.append(I18n.get("context_message_breakdown", 
                String.valueOf(systemCount), 
                String.valueOf(userCount), 
                String.valueOf(assistantCount), 
                String.valueOf(toolCount))).append("\n");

        // Token count
        int totalTokens = tokenCounter.countMessages(messages);
        sb.append(I18n.get("context_token_count", String.valueOf(totalTokens))).append("\n");

        // Mode
        if (mode != null) {
            sb.append(I18n.get("context_mode", mode)).append("\n");
        }

        // Tools count
        if (tools != null && !tools.isEmpty()) {
            sb.append(I18n.get("context_tools_registered", String.valueOf(tools.size()))).append("\n");
        }

        return sb.toString();
    }

    /**
     * View messages in detail
     */
    public String viewMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();

        sb.append(I18n.get("context_recent_messages_title")).append("\n");

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            String role = msg.getRole();
            String content = msg.getContent();

            // Role label
            String roleLabel;
            if ("system".equals(role)) {
                roleLabel = I18n.get("context_role_system");
            } else if ("user".equals(role)) {
                roleLabel = I18n.get("context_role_user");
            } else if ("assistant".equals(role)) {
                roleLabel = I18n.get("context_role_assistant");
            } else if ("tool".equals(role)) {
                roleLabel = I18n.get("context_role_tool");
            } else {
                roleLabel = "[" + role + "]";
            }

            // Truncate content for display
            String displayContent = content;
            if (content != null && content.length() > 100) {
                displayContent = content.substring(0, 100) + "...";
            }

            sb.append(roleLabel).append(" ").append(displayContent).append("\n");

            // Show tool calls if present
            if (msg.hasToolCalls()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getFunction() != null && tc.getFunction().getName() != null) {
                        sb.append("  ").append(I18n.get("context_tool_call", tc.getFunction().getName())).append("\n");
                    }
                }
            }

            // Show tool call ID if this is a tool result
            if ("tool".equals(role) && msg.getToolCallId() != null) {
                sb.append("  ").append(I18n.get("context_tool_result", msg.getToolCallId())).append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * View token statistics
     */
    public String viewTokenStats(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();

        sb.append(I18n.get("context_token_stats_title")).append("\n");

        if (messages == null || messages.isEmpty()) {
            sb.append(I18n.get("context_no_messages")).append("\n");
            return sb.toString();
        }

        int totalTokens = 0;
        int messageOverhead = 3 * messages.size();

        sb.append(I18n.get("context_message_overhead", String.valueOf(messageOverhead))).append("\n");
        sb.append("\n");

        sb.append(String.format("%-10s %-10s %s%n", 
                I18n.get("context_role_header"), 
                I18n.get("context_token_header"), 
                I18n.get("context_percent_header")));
        sb.append(String.format("%-10s %-10s %s%n", "------", "-------", "----"));

        // Per-role breakdown
        Map<String, Integer> roleTokens = new java.util.HashMap<>();
        for (ChatMessage msg : messages) {
            String role = msg.getRole();
            int tokens = tokenCounter.countMessage(msg);
            roleTokens.merge(role, tokens, Integer::sum);
            totalTokens += tokens;
        }

        for (Map.Entry<String, Integer> entry : roleTokens.entrySet()) {
            double percentage = totalTokens > 0 ? (entry.getValue() * 100.0 / totalTokens) : 0;
            sb.append(String.format("%-10s %-10d %.1f%%%n", entry.getKey(), entry.getValue(), percentage));
        }

        sb.append("\n");
        sb.append(I18n.get("context_token_total", String.valueOf(totalTokens))).append("\n");

        return sb.toString();
    }

    /**
     * View tools list
     */
    public String viewTools(Map<String, Tool> tools) {
        StringBuilder sb = new StringBuilder();

        sb.append(I18n.get("context_available_tools_title")).append("\n");

        if (tools == null || tools.isEmpty()) {
            sb.append(I18n.get("context_no_tools")).append("\n");
            return sb.toString();
        }

        for (Map.Entry<String, Tool> entry : tools.entrySet()) {
            sb.append("- ").append(entry.getKey());
            Tool tool = entry.getValue();
            if (tool != null) {
                String desc = tool.getDescription();
                if (desc != null && !desc.isEmpty()) {
                    sb.append(": ").append(truncateForDisplay(desc, 60));
                }
            }
            sb.append("\n");
        }

        sb.append("\n");
        sb.append(I18n.get("context_tool_count", String.valueOf(tools.size()))).append("\n");

        return sb.toString();
    }

    private String truncateForDisplay(String text, int maxLength) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength) + "...";
    }
}
