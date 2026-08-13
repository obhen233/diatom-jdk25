package com.github.obhen233.core.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.TaskCheckpointManager;
import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import com.github.obhen233.util.JsonUtils;

/**
 * Checkpoint MCP Server - Provides task checkpoint and resume functionality
 * Allows listing saved checkpoints and resuming interrupted tasks
 */
public class CheckpointMcpServer implements McpServer {
    private static final Logger logger = LoggerFactory.getLogger(CheckpointMcpServer.class);
    private static final String SERVER_NAME = "checkpoint";
    private static final String SERVER_DESCRIPTION = "[TASK] Task checkpoint and resume server - allows saving task progress and resuming later";
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final TaskCheckpointManager checkpointManager;

    public CheckpointMcpServer(DatabaseManager db) {
        this.checkpointManager = new TaskCheckpointManager(db);
    }

    @Override
    public String getName() {
        return SERVER_NAME;
    }

    @Override
    public String getDescription() {
        return SERVER_DESCRIPTION;
    }

    @Override
    public Map<String, Tool> listTools() {
        Map<String, Tool> tools = new HashMap<>();

        tools.put("list_checkpoints", new Tool(
            "list_checkpoints",
            "[TASK] List all saved task checkpoints. Returns task summaries with IDs for resuming. Use this when user wants to continue a previous task or see interrupted tasks.",
            "{\"type\": \"object\", \"properties\": {}}"
        ));

        tools.put("search_checkpoints", new Tool(
            "search_checkpoints",
            "[TASK] Search checkpoints by user input text. Use when user mentions specific keywords or partial content from their original request.",
            "{\"type\": \"object\", \"properties\": {\"query\": {\"type\": \"string\", \"description\": \"Search query to match against user input\"}}}"
        ));

        tools.put("get_checkpoint_detail", new Tool(
            "get_checkpoint_detail",
            "[TASK] Get detailed information about a specific checkpoint including conversation history and tool results.",
            "{\"type\": \"object\", \"properties\": {\"task_id\": {\"type\": \"string\", \"description\": \"The task ID to get details for\"}}}"
        ));

        return tools;
    }

    @Override
    public String callTool(String toolName, String args) {
        try {
            switch (toolName) {
                case "list_checkpoints": return listCheckpoints();
                case "search_checkpoints": return searchCheckpoints(args);
                case "get_checkpoint_detail": return getCheckpointDetail(args);
                default: return "{\"error\": \"Unknown tool: " + toolName + "\"}";
            }
        } catch (Exception e) {
            logger.error("Error executing checkpoint tool: {}", toolName, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String listCheckpoints() {
        List<TaskCheckpointManager.TaskCheckpoint> checkpoints = checkpointManager.listCheckpoints();

        if (checkpoints.isEmpty()) {
            return "{\"success\": true, \"checkpoints\": [], \"message\": \"No saved checkpoints found\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"success\": true, \"checkpoints\": [");

        for (int i = 0; i < checkpoints.size(); i++) {
            if (i > 0) sb.append(",");
            TaskCheckpointManager.TaskCheckpoint cp = checkpoints.get(i);
            sb.append("{");
            sb.append("\"task_id\": \"").append(escapeJson(cp.getTaskId())).append("\",");
            sb.append("\"user_input\": \"").append(escapeJson(truncate(cp.getUserInput(), 100))).append("\",");
            sb.append("\"step_count\": ").append(cp.getStepCount()).append(",");
            sb.append("\"updated_at\": \"").append(formatTimestamp(cp.getUpdatedAt())).append("\"");
            sb.append("}");
        }

        sb.append("], \"count\": ").append(checkpoints.size()).append("}");
        return sb.toString();
    }

    private String searchCheckpoints(String args) {
        String query = null;
        try {
            if (args != null && !args.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(args);
                if (node.has("query")) {
                    query = node.get("query").asText();
                }
            }
        } catch (Exception e) {
            return "{\"error\": \"Invalid args format\"}";
        }

        if (query == null || query.trim().isEmpty()) {
            return "{\"error\": \"Query is required\"}";
        }

        List<TaskCheckpointManager.TaskCheckpoint> checkpoints = checkpointManager.findCheckpointsByInput(query);

        if (checkpoints.isEmpty()) {
            return "{\"success\": true, \"checkpoints\": [], \"message\": \"No checkpoints found matching: " + escapeJson(query) + "\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"success\": true, \"query\": \"").append(escapeJson(query)).append("\", \"checkpoints\": [");

        for (int i = 0; i < checkpoints.size(); i++) {
            if (i > 0) sb.append(",");
            TaskCheckpointManager.TaskCheckpoint cp = checkpoints.get(i);
            sb.append("{");
            sb.append("\"task_id\": \"").append(escapeJson(cp.getTaskId())).append("\",");
            sb.append("\"user_input\": \"").append(escapeJson(truncate(cp.getUserInput(), 100))).append("\",");
            sb.append("\"step_count\": ").append(cp.getStepCount()).append(",");
            sb.append("\"updated_at\": \"").append(formatTimestamp(cp.getUpdatedAt())).append("\"");
            sb.append("}");
        }

        sb.append("], \"count\": ").append(checkpoints.size()).append("}");
        return sb.toString();
    }

    private String getCheckpointDetail(String args) {
        String taskId = null;
        try {
            if (args != null && !args.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(args);
                if (node.has("task_id")) {
                    taskId = node.get("task_id").asText();
                }
            }
        } catch (Exception e) {
            return "{\"error\": \"Invalid args format\"}";
        }

        if (taskId == null || taskId.trim().isEmpty()) {
            return "{\"error\": \"task_id is required\"}";
        }

        TaskCheckpointManager.TaskCheckpoint cp = checkpointManager.loadCheckpoint(taskId);

        if (cp == null) {
            return "{\"error\": \"Checkpoint not found: " + escapeJson(taskId) + "\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"success\": true, \"checkpoint\": {");
        sb.append("\"task_id\": \"").append(escapeJson(cp.getTaskId())).append("\",");
        sb.append("\"user_input\": \"").append(escapeJson(cp.getUserInput())).append("\",");
        sb.append("\"step_count\": ").append(cp.getStepCount()).append(",");
        sb.append("\"created_at\": \"").append(formatTimestamp(cp.getCreatedAt())).append("\",");
        sb.append("\"updated_at\": \"").append(formatTimestamp(cp.getUpdatedAt())).append("\",");
        sb.append("\"conversation_history\": ").append(cp.getConversationHistory()).append(",");
        sb.append("\"tool_results\": ").append(cp.getToolResults()).append("");
        sb.append("}}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private String formatTimestamp(long epochMilli) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(epochMilli);
        return instant.toString();
    }
}
