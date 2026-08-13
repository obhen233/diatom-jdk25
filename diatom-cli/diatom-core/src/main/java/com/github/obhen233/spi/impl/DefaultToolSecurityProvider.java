package com.github.obhen233.spi.impl;

import com.github.obhen233.spi.ToolMetadataRegistry;
import com.github.obhen233.spi.ToolSecurityProvider;
import com.github.obhen233.util.I18n;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Default implementation of ToolSecurityProvider.
 * Migrates the hardcoded logic from ReActAgent into a configurable SPI implementation.
 */
public class DefaultToolSecurityProvider implements ToolSecurityProvider {

    private static final ToolMetadataRegistry registry = ToolMetadataRegistry.getInstance();

    private final Set<String> readOnlyTools = new HashSet<>(Arrays.asList(
            // Built-in tools
            "read_file", "list_files", "list_directory", "search_files",
            "read_skill", "list_skills", "list_libs", "get_sources_path",
            "get_core_path", "get_version_info", "list_core_versions",
            "init_sources", "get_source_tree", "get_active_file", "get_editor_context",
            // IDE MCP tools (editor namespace)
            "get_active_file", "get_cursor_context", "get_open_tabs", "get_selected_text",
            // IDE MCP tools (ecj namespace) - read-only diagnostics
            "get_diagnostics", "get_classpath",
            // IDE MCP tools (vcs namespace) - read-only version control
            "git_status", "git_diff", "git_log", "git_branch",
            "svn_status", "svn_diff", "svn_log", "svn_info"
    ));

    private final Set<String> pathRelatedTools = new HashSet<>(Arrays.asList(
            "exists", "read_file", "write_file", "list_files",
            "create_directory", "delete_file", "search_files",
            "read_skill", "replace_in_file",
            // IDE MCP tools that work with paths
            "compile_project", "compile_file"
    ));

    private final Set<String> lowRiskCommands = new HashSet<>(Arrays.asList(
            "ls", "dir", "cat", "echo", "pwd", "cd", "git", "mvn", "npm", "node",
            "python", "curl", "clear", "grep", "find", "head", "tail"
    ));

    private final Set<String> mediumRiskCommands = new HashSet<>(Arrays.asList(
            "rm", "del", "copy", "move", "mkdir", "touch", "chmod", "chown"
    ));

    private final Set<String> highRiskCommands = new HashSet<>(Arrays.asList(
            "rm -rf", "format", "fdisk", "mkfs", "dd if=", "shutdown", "reboot"
    ));

    @Override
    public boolean isReadOnly(String toolName, String argsJson) {
        return readOnlyTools.contains(toolName);
    }

    @Override
    public boolean checkWorkspaceBoundary(String toolName, String argsJson) {
        return pathRelatedTools.contains(toolName);
    }

    @Override
    public boolean requiresConfirmation(String toolName, String argsJson) {
        if (isReadOnly(toolName, argsJson)) {
            return false;
        }
        switch (toolName) {
            case "write_file":
            case "replace_in_file":
            case "delete_file":
            case "create_directory":
            case "run_command":
            case "compile_sources":
            case "restart_application":
                return true;
            default:
                return false;
        }
    }

    @Override
    public String getConfirmationMessage(String toolName, String argsJson) {
        switch (toolName) {
            case "write_file":
                return I18n.get("tool_confirm_write_file", extractPath(argsJson));
            case "replace_in_file":
                return I18n.get("tool_confirm_replace_file", extractPath(argsJson));
            case "delete_file":
                return I18n.get("tool_confirm_delete_file", extractPath(argsJson));
            case "run_command":
                String cmd = extractCmd(argsJson);
                if (isReadOnlyCommand(cmd)) {
                    return null;
                }
                return I18n.get("tool_confirm_run_command", cmd);
            case "create_directory":
                return I18n.get("tool_confirm_create_directory", extractPath(argsJson));
            case "compile_sources":
                return I18n.get("tool_confirm_compile");
            case "restart_application":
                return I18n.get("tool_confirm_restart");
            default:
                // Try to get confirmation message from registry (supports MCP tools)
                String lang = I18n.getLanguage();
                String registryMsg = registry.getConfirmationMessage(toolName, argsJson, lang);
                if (registryMsg != null) {
                    return registryMsg;
                }
                return I18n.get("tool_confirm_unknown", toolName);
        }
    }

    /**
     * Get the human-readable name for a tool.
     * @param toolName the tool name
     * @return the readable name, or the original toolName if not found
     */
    public String getReadableName(String toolName) {
        return registry.getReadableName(toolName, I18n.getLanguage());
    }

    @Override
    public String getRiskDescription(String toolName, String argsJson) {
        switch (toolName) {
            case "run_command":
                return I18n.get("tool_dangerous_command", extractCmd(argsJson));
            case "delete_file":
                return I18n.get("tool_dangerous_delete", extractPath(argsJson));
            case "write_file":
                return I18n.get("tool_dangerous_write", extractPath(argsJson));
            default:
                return I18n.get("tool_dangerous_unknown", toolName);
        }
    }

    @Override
    public String getRiskLevel(String toolName, String argsJson) {
        switch (toolName) {
            case "write_file":
            case "replace_in_file":
                return "medium";
            case "delete_file":
            case "create_directory":
                return "medium";
            case "run_command":
                String cmd = extractCmd(argsJson);
                if (cmd != null) {
                    String lowerCmd = cmd.toLowerCase();
                    for (String highRisk : highRiskCommands) {
                        if (lowerCmd.contains(highRisk)) {
                            return "critical";
                        }
                    }
                    for (String mediumRisk : mediumRiskCommands) {
                        if (lowerCmd.contains(mediumRisk)) {
                            return "high";
                        }
                    }
                }
                return "medium";
            case "compile_sources":
            case "restart_application":
                return "high";
            default:
                if (isReadOnly(toolName, argsJson)) {
                    return "none";
                }
                return "low";
        }
    }

    @Override
    public boolean isCommandApproved(String command) {
        if (command == null) return false;
        String lowerCmd = command.toLowerCase().trim();
        for (String safe : lowRiskCommands) {
            if (lowerCmd.startsWith(safe)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isReadOnlyCommand(String command) {
        if (command == null) return false;
        String lowerCmd = command.toLowerCase().trim();
        for (String safe : lowRiskCommands) {
            if (lowerCmd.startsWith(safe)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getPriority() {
        return -100; // Lowest priority, fallback for built-in tools
    }

    private String extractPath(String argsJson) {
        if (argsJson == null) return "";
        // Simple extraction - look for "path" or "filePath" field
        int pathIdx = argsJson.indexOf("\"path\"");
        if (pathIdx < 0) pathIdx = argsJson.indexOf("\"filePath\"");
        if (pathIdx < 0) pathIdx = argsJson.indexOf("\"target\"");
        if (pathIdx < 0) return "";
        int colonIdx = argsJson.indexOf(":", pathIdx);
        if (colonIdx < 0) return "";
        int startQuote = argsJson.indexOf("\"", colonIdx);
        if (startQuote < 0) return "";
        int endQuote = argsJson.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return "";
        return argsJson.substring(startQuote + 1, endQuote);
    }

    private String extractCmd(String argsJson) {
        if (argsJson == null) return "";
        int cmdIdx = argsJson.indexOf("\"cmd\"");
        if (cmdIdx < 0) return "";
        int colonIdx = argsJson.indexOf(":", cmdIdx);
        if (colonIdx < 0) return "";
        int startQuote = argsJson.indexOf("\"", colonIdx);
        if (startQuote < 0) return "";
        int endQuote = argsJson.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return "";
        return argsJson.substring(startQuote + 1, endQuote);
    }
}
