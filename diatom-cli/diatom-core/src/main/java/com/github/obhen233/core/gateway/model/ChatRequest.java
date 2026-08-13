package com.github.obhen233.core.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

import com.github.obhen233.util.JsonUtils;

/**
 * Gateway → Worker 的对话请求数据结构
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatRequest {
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private String taskId;
    private String sessionId;
    private String message;
    private List<Map<String, Object>> conversationHistory;
    private String checkpointRef;
    private boolean isMigration;
    private Map<String, Object> migrationContext;
    private String syncStrategy;
    private String gatewayUrl;
    private String workspacePath;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<Map<String, Object>> getConversationHistory() { return conversationHistory; }
    public void setConversationHistory(List<Map<String, Object>> conversationHistory) { this.conversationHistory = conversationHistory; }
    public String getCheckpointRef() { return checkpointRef; }
    public void setCheckpointRef(String checkpointRef) { this.checkpointRef = checkpointRef; }
    public boolean isMigration() { return isMigration; }
    public void setMigration(boolean migration) { isMigration = migration; }
    public Map<String, Object> getMigrationContext() { return migrationContext; }
    public void setMigrationContext(Map<String, Object> migrationContext) { this.migrationContext = migrationContext; }
    public String getSyncStrategy() { return syncStrategy; }
    public void setSyncStrategy(String syncStrategy) { this.syncStrategy = syncStrategy; }
    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }

    /**
     * 从 JSON 字符串反序列化为 ChatRequest 对象。
     * 使用 Jackson 解析，支持嵌套对象（migrationContext, conversationHistory）。
     */
    public static ChatRequest fromJson(String json) {
        ChatRequest req = new ChatRequest();
        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            req.taskId = getJsonText(root, "taskId");
            req.sessionId = getJsonText(root, "sessionId");
            req.message = getJsonText(root, "message");
            req.checkpointRef = getJsonText(root, "checkpointRef");
            req.isMigration = root.has("isMigration") && root.get("isMigration").asBoolean();
            req.syncStrategy = getJsonText(root, "syncStrategy");
            req.gatewayUrl = getJsonText(root, "gatewayUrl");
            req.workspacePath = getJsonText(root, "workspacePath");

            if (root.has("migrationContext") && !root.get("migrationContext").isNull()) {
                String mcRaw = root.get("migrationContext").toString();
                req.migrationContext = mapper.readValue(mcRaw,
                    new TypeReference<Map<String, Object>>() {});
            }
            if (root.has("conversationHistory") && !root.get("conversationHistory").isNull()) {
                String chRaw = root.get("conversationHistory").toString();
                req.conversationHistory = mapper.readValue(chRaw,
                    new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            // Fallback: use simple extraction
            req.workspacePath = extractSimple(json, "workspacePath");
            req.taskId = extractSimple(json, "taskId");
            req.sessionId = extractSimple(json, "sessionId");
            req.message = extractSimple(json, "message");
            req.checkpointRef = extractSimple(json, "checkpointRef");
            req.syncStrategy = extractSimple(json, "syncStrategy");
            req.gatewayUrl = extractSimple(json, "gatewayUrl");
        }
        return req;
    }

    private static String getJsonText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }

    private static String extractSimple(String json, String field) {
        String key = "\"" + field + "\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        StringBuilder val = new StringBuilder();
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                val.append(json.charAt(i + 1));
                i++;
            } else if (c == '"') {
                break;
            } else {
                val.append(c);
            }
        }
        return val.toString();
    }
}
