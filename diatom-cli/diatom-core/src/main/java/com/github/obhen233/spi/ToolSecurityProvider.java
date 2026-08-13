package com.github.obhen233.spi;

/**
 * SPI interface for tool security policies.
 * Allows custom modules to provide security metadata and confirmation logic for tools.
 *
 * Implement this interface to:
 * - Override read-only detection for specific tools
 * - Provide custom workspace boundary checks
 * - Define confirmation messages for write operations
 * - Define risk levels for tools
 *
 * The default implementation handles built-in tools with hardcoded logic.
 * Custom implementations can use database rules, external services, etc.
 */
public interface ToolSecurityProvider {

    /**
     * Check if a tool is read-only (no confirmation needed).
     * @param toolName the tool name
     * @param argsJson the tool arguments as JSON
     * @return true if the tool is read-only and doesn't need confirmation
     */
    default boolean isReadOnly(String toolName, String argsJson) {
        return false;
    }

    /**
     * Check if a tool should have its path checked against workspace boundary.
     * @param toolName the tool name
     * @param argsJson the tool arguments as JSON
     * @return true if the tool path should be checked against workspace
     */
    default boolean checkWorkspaceBoundary(String toolName, String argsJson) {
        return false;
    }

    /**
     * Check if a tool requires confirmation before execution.
     * @param toolName the tool name
     * @param argsJson the tool arguments as JSON
     * @return true if the tool requires confirmation
     */
    default boolean requiresConfirmation(String toolName, String argsJson) {
        return false;
    }

    /**
     * Get the confirmation message for a tool.
     * @param toolName the tool name
     * @param argsJson the tool arguments as JSON
     * @return the confirmation message, or null if no confirmation needed
     */
    default String getConfirmationMessage(String toolName, String argsJson) {
        return null;
    }

    /**
     * Get the risk description for a tool.
     * @param toolName the tool name
     * @param argsJson the tool arguments as JSON
     * @return the risk description
     */
    default String getRiskDescription(String toolName, String argsJson) {
        return null;
    }

    /**
     * Get the risk level for a tool.
     * @param toolName the tool name
     * @param argsJson the tool arguments as JSON
     * @return risk level: none, low, medium, high, critical
     */
    default String getRiskLevel(String toolName, String argsJson) {
        return "none";
    }

    /**
     * Check if a command (for run_command tool) is approved.
     * @param command the command string
     * @return true if the command is approved
     */
    default boolean isCommandApproved(String command) {
        return false;
    }

    /**
     * Check if a command is read-only (safe to auto-approve).
     * @param command the command string
     * @return true if the command is read-only
     */
    default boolean isReadOnlyCommand(String command) {
        return false;
    }

    /**
     * Get the priority of this provider.
     * Higher priority providers are called first.
     * @return priority (higher = more priority)
     */
    default int getPriority() {
        return 0;
    }
}
