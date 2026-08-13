package com.github.obhen233.core.agent.loop;

import com.github.obhen233.core.skill.Skill;
import java.util.List;

/**
 * Interface for permission checking during tool execution.
 * Extracted to allow AgentLoop implementations to delegate permission checks.
 */
public interface PermissionChecker {

    /**
     * Check if tool execution needs user confirmation
     * @param toolName the tool name
     * @param argsJson the tool arguments as JSON
     * @param aiClassification optional AI classification from reasoning_content
     * @return null if no confirmation needed, otherwise a description of the action for confirmation
     */
    String needsConfirmation(String toolName, String argsJson, String aiClassification);

    /**
     * Parse AI classification from reasoning content
     */
    String parseAiClassification(String reasoningContent);

    /**
     * Extract file path from tool arguments
     */
    String extractPathFromArgs(String argsJson);

    /**
     * Check if result indicates a permission error
     */
    boolean isPermissionError(String result);

    /**
     * Record an exploration tool call for budget tracking and check if allowed.
     * Called before executing each tool to track exploration usage.
     * @param toolName the tool name
     * @param argsJson the tool arguments as JSON
     * @return null if allowed, otherwise an error message to return to the model
     */
    default String checkExplorationBudget(String toolName, String argsJson) {
        // Default: allow all exploration
        return null;
    }

    /**
     * Check if the current tool is allowed by active skills with allowedTools restrictions.
     * Returns null if allowed or no restrictions, or an error message if blocked.
     *
     * Logic:
     * - If ANY active skill allows the tool or has no allowedTools constraint → allowed
     * - If ALL active skills with allowedTools block the tool → blocked
     * - System skills (kind=system) are not subject to allowedTools constraints
     */
    default String checkSkillToolAllowed(String toolName, List<Skill> activeSkills) {
        if (activeSkills == null || activeSkills.isEmpty()) return null;

        boolean hasRestrictions = false;
        for (Skill skill : activeSkills) {
            // System skills are not constrained by allowedTools
            if ("system".equals(skill.getKind())) continue;

            String allowedTools = skill.getAllowedTools();
            if (allowedTools != null && !allowedTools.trim().isEmpty()) {
                hasRestrictions = true;
                String[] tools = allowedTools.split(",");
                for (String t : tools) {
                    if (t.trim().equals(toolName)) {
                        return null; // Allowed by this skill
                    }
                }
            }
        }

        if (hasRestrictions) {
            return "Tool '" + toolName + "' is not allowed by any active skill with tool restrictions.";
        }
        return null; // No skill has restrictions
    }

    /**
     * @deprecated Use {@link #checkExplorationBudget} instead
     */
    @Deprecated
    default void recordExplorationToolCall(String toolName, String argsJson) {
        // Default no-op implementation for backwards compatibility
    }
}