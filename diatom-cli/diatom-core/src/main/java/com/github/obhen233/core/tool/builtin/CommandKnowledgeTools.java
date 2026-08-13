package com.github.obhen233.core.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.obhen233.core.database.CommandKnowledgeDao;
import com.github.obhen233.core.database.CommandKnowledgeDao.CommandKnowledge;
import com.github.obhen233.core.knowledge.CommandKnowledgeManager;
import com.github.obhen233.core.knowledge.CommandKnowledgeManager.KnowledgeStats;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.obhen233.util.JsonUtils;

/**
 * Command Knowledge Tools
 *
 * Provides tools for managing the command knowledge base:
 * - Query command permissions
 * - Add new commands
 * - Update command permissions
 * - Delete commands
 * - List commands with filters
 * - Get statistics
 */
public class CommandKnowledgeTools extends Tool {
    private static final Logger logger = LoggerFactory.getLogger(CommandKnowledgeTools.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final CommandKnowledgeManager knowledgeManager;

    public CommandKnowledgeTools(CommandKnowledgeManager knowledgeManager) {
        this.knowledgeManager = knowledgeManager;
    }

    @ToolMethod(name = "manage_command_knowledge",
        description = "Manage command knowledge base - query/add/update/delete command permissions. " +
                      "Use 'get' to query a command, 'add' to add new command, 'update' to modify, " +
                      "'delete' to remove, 'list' to view commands, 'stats' for statistics.",
        parametersSchema = "{\"type\": \"object\", \"properties\": {\"action\": {\"type\": \"string\", \"enum\": [\"get\", \"add\", \"update\", \"delete\", \"list\", \"stats\"]}, \"command\": {\"type\": \"string\"}, \"permission\": {\"type\": \"string\"}, \"risk_level\": {\"type\": \"integer\"}, \"tool_type\": {\"type\": \"string\"}, \"filter\": {\"type\": \"string\"}, \"confidence\": {\"type\": \"integer\"}, \"source\": {\"type\": \"string\"}}}",
        requiresConfirmation = true,
        riskLevel = "medium")
    public String manageCommandKnowledge(String argsJson) {
        try {
            if (argsJson == null || argsJson.trim().isEmpty()) {
                return "Error: arguments required";
            }

            JsonNode args = mapper.readTree(argsJson);
            String action = args.has("action") ? args.get("action").asText() : null;

            if (action == null) {
                return "Error: 'action' parameter is required. Use: get, add, update, delete, list, stats";
            }

            switch (action) {
                case "get":
                    return handleGet(args);
                case "add":
                    return handleAdd(args);
                case "update":
                    return handleUpdate(args);
                case "delete":
                    return handleDelete(args);
                case "list":
                    return handleList(args);
                case "stats":
                    return handleStats();
                default:
                    return "Error: unknown action '" + action + "'. Use: get, add, update, delete, list, stats";
            }
        } catch (Exception e) {
            logger.error("Error managing command knowledge", e);
            return "Error: " + e.getMessage();
        }
    }

    private String handleGet(JsonNode args) {
        String command = args.has("command") ? args.get("command").asText() : null;
        if (command == null || command.trim().isEmpty()) {
            return I18n.get("command.knowledge.query.require_command", "command is required");
        }

        CommandKnowledgeManager.CommandPermission perm = knowledgeManager.getCommandPermission(command);

        if (perm == null || (perm.permission == null && perm.riskLevel == 0)) {
            return I18n.get("command.knowledge.query.notfound", "Command not found: %s", command);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Command Info ===\n");
        sb.append("Command: ").append(command).append("\n");
        sb.append("Permission: ").append(perm.permission).append("\n");
        sb.append("Risk Level: ").append(perm.riskLevel).append("\n");
        sb.append("Confidence: ").append(perm.confidence).append("%\n");

        // Check if it's a built-in dangerous command
        if (knowledgeManager.isBuiltinDangerous(command)) {
            sb.append("Note: This is a built-in dangerous command and cannot be modified.\n");
        }

        return sb.toString();
    }

    private String handleAdd(JsonNode args) {
        String command = args.has("command") ? args.get("command").asText() : null;
        if (command == null || command.trim().isEmpty()) {
            return I18n.get("command.knowledge.add.require_command", "command is required");
        }

        String permission = args.has("permission") ? args.get("permission").asText() : "ALLOW";
        int riskLevel = args.has("risk_level") ? args.get("risk_level").asInt() : 0;
        String toolType = args.has("tool_type") ? args.get("tool_type").asText() : null;
        int confidence = args.has("confidence") ? args.get("confidence").asInt() : 50;
        String source = args.has("source") ? args.get("source").asText() : "learned";

        // Validate permission
        if (!isValidPermission(permission)) {
            return "Error: invalid permission. Use ALLOW, DENY, or UNSURE";
        }

        // Validate risk level
        if (riskLevel < 0 || riskLevel > 3) {
            return "Error: risk_level must be 0-3 (0=safe, 1=caution, 2=dangerous, 3=highly dangerous)";
        }

        knowledgeManager.addOrUpdateCommand(command, toolType, permission, riskLevel, confidence, source);

        return I18n.get("command.knowledge.add.success", "Command added: %s", command);
    }

    private String handleUpdate(JsonNode args) {
        String command = args.has("command") ? args.get("command").asText() : null;
        if (command == null || command.trim().isEmpty()) {
            return I18n.get("command.knowledge.update.require_command", "command is required");
        }

        // Check if it's a built-in dangerous command
        if (knowledgeManager.isBuiltinDangerous(command)) {
            return "Error: Cannot modify built-in dangerous command: " + command;
        }

        String permission = args.has("permission") ? args.get("permission").asText() : null;
        int riskLevel = args.has("risk_level") ? args.get("risk_level").asInt() : -1;
        String toolType = args.has("tool_type") ? args.get("tool_type").asText() : null;
        Integer confidence = args.has("confidence") ? args.get("confidence").asInt() : null;

        // Get existing knowledge
        CommandKnowledgeManager.CommandPermission existing = knowledgeManager.getCommandPermission(command);
        if (existing == null || existing.permission == null) {
            return I18n.get("command.knowledge.query.notfound", "Command not found: %s", command);
        }

        // Apply updates
        String newPermission = permission != null ? permission : existing.permission;
        int newRiskLevel = riskLevel >= 0 ? riskLevel : existing.riskLevel;
        String newToolType = toolType != null ? toolType : null;
        int newConfidence = confidence != null ? confidence : existing.confidence;

        // Validate
        if (!isValidPermission(newPermission)) {
            return "Error: invalid permission. Use ALLOW, DENY, or UNSURE";
        }

        knowledgeManager.addOrUpdateCommand(command, newToolType, newPermission, newRiskLevel, newConfidence, "learned");

        return I18n.get("command.knowledge.update.success", "Command updated: %s", command);
    }

    private String handleDelete(JsonNode args) {
        String command = args.has("command") ? args.get("command").asText() : null;
        if (command == null || command.trim().isEmpty()) {
            return I18n.get("command.knowledge.delete.require_command", "command is required");
        }

        // Check if it's a built-in dangerous command
        if (knowledgeManager.isBuiltinDangerous(command)) {
            return "Error: Cannot delete built-in dangerous command: " + command;
        }

        knowledgeManager.deleteCommand(command);

        return I18n.get("command.knowledge.delete.success", "Command deleted: %s", command);
    }

    private String handleList(JsonNode args) {
        String filter = args.has("filter") ? args.get("filter").asText() : null;

        List<CommandKnowledge> commands;
        if (filter != null && !filter.isEmpty()) {
            // Parse filter (e.g., "source=builtin", "permission=ALLOW", "tool_type=git")
            if (filter.startsWith("source=")) {
                commands = knowledgeManager.getAllCommands(); // Filter after
            } else if (filter.startsWith("permission=")) {
                String perm = filter.substring("permission=".length());
                commands = knowledgeManager.getAllCommands(); // Filter after
            } else if (filter.startsWith("tool_type=")) {
                String toolType = filter.substring("tool_type=".length());
                commands = knowledgeManager.getAllCommands(); // Filter after
            } else {
                commands = knowledgeManager.getAllCommands();
            }
        } else {
            commands = knowledgeManager.getAllCommands();
        }

        if (commands.isEmpty()) {
            return I18n.get("command.knowledge.list.empty", "No commands in knowledge base");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(I18n.get("command.knowledge.list.header", "Command Knowledge (Total: %d)", commands.size()));
        sb.append("\n");
        sb.append(String.format("%-30s %-10s %-5s %-5s %-10s\n",
            "Command", "Permission", "Risk", "Conf", "Source"));
        sb.append("-----------------------------------------------------------------\n");

        for (CommandKnowledge cmd : commands) {
            // Apply filters if specified
            if (filter != null && !filter.isEmpty()) {
                boolean matches = true;
                if (filter.startsWith("source=")) {
                    String val = filter.substring("source=".length());
                    if (!val.equals(cmd.source)) matches = false;
                } else if (filter.startsWith("permission=")) {
                    String val = filter.substring("permission=".length());
                    if (!val.equals(cmd.permission)) matches = false;
                } else if (filter.startsWith("tool_type=")) {
                    String val = filter.substring("tool_type=".length());
                    if (!val.equals(cmd.toolType)) matches = false;
                }
                if (!matches) continue;
            }

            sb.append(String.format("%-30s %-10s %-5d %-5d %-10s\n",
                truncate(cmd.command, 30),
                cmd.permission,
                cmd.riskLevel,
                cmd.confidence,
                cmd.source != null ? cmd.source : "builtin"));
        }

        return sb.toString();
    }

    private String handleStats() {
        KnowledgeStats stats = knowledgeManager.getStats();

        StringBuilder sb = new StringBuilder();
        sb.append(I18n.get("command.knowledge.stats.title", "Command Knowledge Statistics"));
        sb.append("\n");
        sb.append("----------------------------------------\n");
        sb.append(I18n.get("command.knowledge.stats.total", "Total commands: %d", stats.total)).append("\n");
        sb.append(I18n.get("command.knowledge.stats.builtin", "Built-in: %d", stats.builtin)).append("\n");
        sb.append(I18n.get("command.knowledge.stats.learned", "Learned: %d", stats.learned)).append("\n");
        sb.append(I18n.get("command.knowledge.stats.llm", "LLM judged: %d", stats.llm)).append("\n");
        sb.append("\n");
        sb.append("By Permission:\n");
        sb.append("  ALLOW: ").append(stats.allow).append("\n");
        sb.append("  DENY: ").append(stats.deny).append("\n");
        sb.append("  UNSURE: ").append(stats.unsure).append("\n");

        return sb.toString();
    }

    private boolean isValidPermission(String permission) {
        return "ALLOW".equals(permission) || "DENY".equals(permission) || "UNSURE".equals(permission);
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 3) + "...";
    }
}
