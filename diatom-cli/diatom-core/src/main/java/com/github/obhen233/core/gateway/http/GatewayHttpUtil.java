package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.gateway.GatewayJsonUtil;
import com.github.obhen233.core.gateway.checkpoint.CheckpointReport;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;
import com.github.obhen233.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Static utility methods extracted from GatewayHttpServer.
 * Contains JSON, I/O, and string helper methods used by Gateway HTTP handlers.
 */
public final class GatewayHttpUtil {
    private static final Logger logger = LoggerFactory.getLogger(GatewayHttpUtil.class);

    private GatewayHttpUtil() {}

    /**
     * Compute monitor prefix from config, defaulting to "monitor".
     */
    public static String computeMonitorPrefix(ConfigManager configManager) {
        if (configManager == null) return "monitor";
        String prefix = configManager.get("monitor.prefix");
        return (prefix != null && !prefix.trim().isEmpty()) ? prefix.trim() : "monitor";
    }

    // ==================== JSON/Array parsing ====================

    /**
     * Parse a JSON array of strings into a List<String>.
     * Handles basic JSON array format: ["a", "b", "c"]
     */
    public static List<String> parseJsonStringArray(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return result;

        String content = json.trim();
        if (content.startsWith("[") && content.endsWith("]")) {
            content = content.substring(1, content.length() - 1).trim();
            if (content.isEmpty()) return result;

            // Split by comma, extract quoted strings
            boolean inQuote = false;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                    inQuote = !inQuote;
                } else if (c == ',' && !inQuote) {
                    addJsonString(result, current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
            addJsonString(result, current.toString());
        }
        return result;
    }

    private static void addJsonString(List<String> result, String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        if (!s.isEmpty()) {
            result.add(s);
        }
    }

    /**
     * Gzip compress byte array.
     */
    public static byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2);
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Resolve the workspace directory from system property, defaulting to user.dir.
     */
    public static String resolveWorkspaceDir() {
        String ws = System.getProperty("diatom.workspace.dir");
        if (ws != null && !ws.trim().isEmpty()) return ws.trim();
        return System.getProperty("user.dir", ".");
    }

    /**
     * Check if a file path should be ignored based on common patterns.
     */
    public static boolean isIgnoredPath(Path file, Path projectRoot) {
        String relative = projectRoot.relativize(file).toString().replace('\\', '/');
        if (relative.startsWith(".git/") || relative.equals(".git")) return true;
        if (relative.startsWith("node_modules/") || relative.equals("node_modules")) return true;
        if (relative.startsWith("target/") || relative.equals("target")) return true;
        if (relative.startsWith("build/") || relative.equals("build")) return true;
        if (relative.startsWith("dist/") || relative.equals("dist")) return true;
        if (relative.startsWith("__pycache__/") || relative.equals("__pycache__")) return true;
        return false;
    }

    /**
     * Simple glob matching: checks prefix, suffix, contains, or exact match.
     */
    public static boolean matchesGlob(String path, String pattern) {
        if (pattern == null || path == null) return false;
        if (pattern.startsWith("*") && pattern.endsWith("*")) {
            String middle = pattern.substring(1, pattern.length() - 1);
            return path.contains(middle);
        } else if (pattern.startsWith("*")) {
            return path.endsWith(pattern.substring(1));
        } else if (pattern.endsWith("*")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return path.equals(pattern);
    }

    /**
     * Truncate a string to maxLen, appending "..." if truncated.
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== Jackson-based JSON extraction ====================

    /**
     * Extract the last user message text from a ChatRequest body.
     * Handles "messages" (plural, array), "message" (singular, string or array).
     */
    public static String extractMessageText(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            Map<String, Object> parsed = JsonUtils.getMapper().readValue(body, Map.class);

            // Try "messages" (plural, array of message objects) — OpenAI-compatible format
            Object messagesObj = parsed.get("messages");
            if (messagesObj instanceof List) {
                List<Map<String, Object>> messages = (List<Map<String, Object>>) messagesObj;
                // Walk backwards to find last user message
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Map<String, Object> msg = messages.get(i);
                    if ("user".equals(msg.get("role"))) {
                        Object content = msg.get("content");
                        if (content instanceof String && !((String) content).isEmpty()) {
                            return (String) content;
                        }
                    }
                }
                // Fallback: join all user message contents
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> msg : messages) {
                    if ("user".equals(msg.get("role"))) {
                        Object content = msg.get("content");
                        if (content instanceof String && !((String) content).isEmpty()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append((String) content);
                        }
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }

            // Try "message" (singular) — could be a string or an array
            Object messageObj = parsed.get("message");
            if (messageObj instanceof String) {
                return (String) messageObj;
            }
            if (messageObj instanceof List) {
                List<Map<String, Object>> messageList = (List<Map<String, Object>>) messageObj;
                for (int i = messageList.size() - 1; i >= 0; i--) {
                    Map<String, Object> msg = messageList.get(i);
                    if ("user".equals(msg.get("role"))) {
                        Object content = msg.get("content");
                        if (content instanceof String && !((String) content).isEmpty()) {
                            return (String) content;
                        }
                    }
                }
            }

            String text = extractJsonValue(body, "text");
            if (text != null && !text.isEmpty()) return text;
        } catch (Exception e) {
            logger.debug("Failed to extract message text from body: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract a JSON value for a given key. Delegates to {@link GatewayJsonUtil#extractJsonValue}.
     */
    public static String extractJsonValue(String json, String key) {
        return GatewayJsonUtil.extractJsonValue(json, key);
    }

    /**
     * Extract a JSON string value for a given key, supports long values up to 50000 chars.
     */
    public static String extractJsonValueLong(String json, String key) {
        return GatewayJsonUtil.extractJsonValue(json, key);
    }

    /**
     * Extract the full JSON value (array/object/primitive) for a given key.
     * Delegates to {@link GatewayJsonUtil#extractFullJsonValue}.
     */
    public static String extractFullJsonValue(String json, String key) {
        return GatewayJsonUtil.extractFullJsonValue(json, key);
    }

    // ==================== String utilities ====================

    /**
     * Escape a string for JSON output.
     */
    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Safe parse of long, returning defaultValue on failure.
     */
    public static long parseLong(String s, long defaultValue) {
        if (s == null || s.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== String-based JSON extraction (no Jackson dependency) ====================

    /**
     * Extract a simple string value from a JSON string using indexOf (no Jackson).
     * Delegates to {@link GatewayJsonUtil#extractJsonValue}.
     */
    public static String extractRawJsonValue(String json, String key) {
        return GatewayJsonUtil.extractJsonValue(json, key);
    }

    /**
     * Extract a raw JSON array string for the given key.
     * Returns the content between [ and ] inclusive.
     */
    public static String extractRawJsonArray(String json, String key) {
        return GatewayJsonUtil.extractRawJsonArray(json, key);
    }

    /**
     * Extract a JSON object value (everything between the first { and matching }).
     */
    public static String extractJsonObject(String json, String key) {
        return GatewayJsonUtil.extractRawJsonObject(json, key);
    }

    /**
     * Parse a capabilities JSON object like {"cap1":1.0,"cap2":0.5} into a Map.
     */
    public static Map<String, Double> parseCapabilitiesJson(String json) {
        Map<String, Double> result = new java.util.HashMap<>();
        if (json == null || json.isEmpty()) return result;
        String inner = json.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
        inner = inner.trim();
        if (inner.isEmpty()) return result;
        int i = 0;
        while (i < inner.length()) {
            // Skip whitespace
            while (i < inner.length() && inner.charAt(i) <= ' ') i++;
            if (i >= inner.length()) break;
            // Find key start (after ")
            if (inner.charAt(i) != '"') break;
            int keyStart = i + 1;
            int keyEnd = inner.indexOf('"', keyStart);
            if (keyEnd < 0) break;
            String capKey = inner.substring(keyStart, keyEnd);
            i = keyEnd + 1;
            // Skip to colon
            while (i < inner.length() && inner.charAt(i) != ':') i++;
            if (i >= inner.length()) break;
            i++; // skip colon
            // Read value
            StringBuilder valBuf = new StringBuilder();
            while (i < inner.length() && inner.charAt(i) != ',' && inner.charAt(i) != '}') {
                if (inner.charAt(i) > ' ') valBuf.append(inner.charAt(i));
                i++;
            }
            try {
                if (valBuf.length() > 0) {
                    result.put(capKey, Double.parseDouble(valBuf.toString()));
                }
            } catch (NumberFormatException ignored) {}
            if (i < inner.length() && inner.charAt(i) == ',') i++;
        }
        return result;
    }

    /**
     * Read full response/error stream body from an HttpURLConnection.
     */
    public static String readConnectionBody(HttpURLConnection conn, int code) throws IOException {
        return GatewayJsonUtil.readConnectionBody(conn, code);
    }

    // ==================== HTTP Response Helpers ====================

    /**
     * Send a JSON response with the given HTTP status code.
     */
    public static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Send a JSON response, serializing the object automatically.
     */
    public static void sendJson(HttpExchange exchange, int code, Object obj) throws IOException {
        sendJson(exchange, code, JsonUtils.toJson(obj));
    }

    /**
     * Send a JSON error response.
     */
    public static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        Map<String, String> error = new java.util.HashMap<>();
        error.put("error", message);
        sendJson(exchange, code, JsonUtils.toJson(error));
    }

    /**
     * Send a JSON response using SPI ServerResponse.
     */
    public static void sendJson(ServerResponse resp, int code, String json) throws IOException {
        resp.setHeader("Content-Type", "application/json; charset=UTF-8");
        resp.setStatus(code);
        resp.send(json);
    }

    /**
     * Send a JSON response using SPI ServerResponse, serializing the object automatically.
     */
    public static void sendJson(ServerResponse resp, int code, Object obj) throws IOException {
        sendJson(resp, code, JsonUtils.toJson(obj));
    }

    /**
     * Send a JSON error response using SPI ServerResponse.
     */
    public static void sendError(ServerResponse resp, int code, String message) throws IOException {
        java.util.Map<String, String> error = new java.util.HashMap<>();
        error.put("error", message);
        sendJson(resp, code, JsonUtils.toJson(error));
    }

    /**
     * Read the full request body from a ServerRequest.
     * Limits to 1MB to prevent memory exhaustion.
     * Handles optional encryption via SecurityHeadersInjector.
     */
    public static String readBody(ServerRequest req) throws IOException {
        String rawBody = req.getBody();
        if (rawBody == null) return null;
        byte[] rawBytes = rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Decrypt if encryption header is present
        String sourceId = req.getHeader(com.github.obhen233.core.gateway.security.SecurityHeadersInjector.HEADER_INSTANCE_ID);
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        if (sourceId != null) {
            headers.put(com.github.obhen233.core.gateway.security.SecurityHeadersInjector.HEADER_INSTANCE_ID, sourceId);
        }
        byte[] decrypted = com.github.obhen233.core.gateway.security.SecurityHeadersInjector.decryptBody(rawBytes, sourceId, headers);
        return new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Read the full request body from an HttpExchange.
     * Limits to 1MB to prevent memory exhaustion.
     * Handles optional encryption via SecurityHeadersInjector.
     */
    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] buf = new byte[8192];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int total = 0;
            int n;
            while ((n = is.read(buf, 0, buf.length)) != -1) {
                baos.write(buf, 0, n);
                total += n;
                if (total > 1024 * 1024) break; // 1MB limit
            }
            byte[] rawBytes = baos.toByteArray();

            // Decrypt if encryption header is present
            Map<String, String> headers = new java.util.HashMap<>();
            for (Map.Entry<String, java.util.List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    headers.put(entry.getKey(), entry.getValue().get(0));
                }
            }
            String sourceId = headers.get(com.github.obhen233.core.gateway.security.SecurityHeadersInjector.HEADER_INSTANCE_ID);
            byte[] decrypted = com.github.obhen233.core.gateway.security.SecurityHeadersInjector.decryptBody(rawBytes, sourceId, headers);
            return new String(decrypted, StandardCharsets.UTF_8);
        }
    }

    // ==================== Query/Path Extraction ====================

    /**
     * Extract a query parameter from a query string.
     */
    public static String extractQueryParam(String query, String key) {
        if (query == null) return null;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try {
                    return URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    /**
     * Extract a task ID from a path like /gateway/v1/tasks/{taskId}/...
     */
    public static String extractTaskIdFromPath(String path, String suffix) {
        String prefix = "/gateway/v1/tasks/";
        if (!path.startsWith(prefix)) return null;
        String remaining = path.substring(prefix.length());
        if (remaining.isEmpty()) return null;
        int slash = remaining.indexOf('/');
        if (slash > 0) {
            String idPart = remaining.substring(0, slash);
            String suffixPart = remaining.substring(slash);
            if (suffix.isEmpty() || suffixPart.equals(suffix)) {
                return idPart;
            }
            return null;
        }
        return suffix.isEmpty() ? remaining : null;
    }

    // ==================== Model Parsing ====================

    /**
     * Parse a CheckpointReport from JSON.
     */
    public static CheckpointReport parseCheckpointReport(String json) {
        CheckpointReport report = new CheckpointReport();
        report.setTaskId(extractJsonValue(json, "taskId"));
        String stepStr = extractJsonValue(json, "stepCount");
        if (stepStr != null) report.setStepCount(Integer.parseInt(stepStr));
        String tokenStr = extractJsonValue(json, "tokenUsage");
        if (tokenStr != null) report.setTokenUsage(Integer.parseInt(tokenStr));
        String msgStr = extractJsonValue(json, "messageCount");
        if (msgStr != null) report.setMessageCount(Integer.parseInt(msgStr));
        report.setAgentState(extractJsonValue(json, "agentState"));
        report.setLlmSummary(extractJsonValue(json, "llmSummary"));
        report.setFileChangeSummary(extractJsonValue(json, "fileChangeSummary"));
        String progStr = extractJsonValue(json, "progress");
        if (progStr != null) report.setProgress(Integer.parseInt(progStr));
        return report;
    }

    /**
     * Convert a TaskState to JSON string.
     */
    public static String taskStateToJson(TaskState s) {
        return JsonUtils.toJson(s);
    }

    /**
     * Safe-escape a string for JSON (backslash, quote, newline).
     */
    public static String safe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * Parse WorkerInfo from JSON registration body.
     */
    public static WorkerInfo parseWorkerRegistration(String json) {
        WorkerInfo worker = new WorkerInfo();
        worker.setWorkerId(extractJsonValue(json, "workerId"));
        worker.setHost(extractJsonValue(json, "host"));
        String portStr = extractJsonValue(json, "port");
        if (portStr != null) worker.setPort(Integer.parseInt(portStr));
        worker.setModel(extractJsonValue(json, "model"));
        worker.setWorkspace(extractJsonValue(json, "workspace"));
        worker.setTier(extractJsonValue(json, "tier"));
        worker.setGroup(extractJsonValue(json, "group"));
        worker.setAuthToken(extractJsonValue(json, "authToken"));
        String costStr = extractJsonValue(json, "costPer1kTokens");
        if (costStr != null) worker.setCostPer1kTokens(Double.parseDouble(costStr));
        String maxConStr = extractJsonValue(json, "maxConcurrency");
        if (maxConStr != null) worker.setMaxConcurrency(Integer.parseInt(maxConStr));
        String pidStr = extractJsonValue(json, "pid");
        if (pidStr != null) worker.setPid(Long.parseLong(pidStr));
        String useSslStr = extractJsonValue(json, "useSsl");
        if (useSslStr != null) worker.setUseSsl(Boolean.parseBoolean(useSslStr));
        String capsJson = extractJsonObject(json, "capabilities");
        if (capsJson != null && !capsJson.isEmpty() && !"null".equals(capsJson)) {
            Map<String, Double> caps = parseCapabilitiesJson(capsJson);
            if (!caps.isEmpty()) worker.setCapabilities(caps);
        }
        String gp = extractJsonValueLong(json, "gatewayProfile");
        if (gp != null && !gp.isEmpty()) worker.setGatewayProfile(gp);
        if (!"gateway".equals(worker.getTier())) worker.setGatewayProfile(null);
        return worker;
    }

    /**
     * Parse WorkerMetrics from JSON heartbeat body.
     * Returns null if body is empty or null.
     */
    public static WorkerMetrics parseWorkerMetrics(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        WorkerMetrics metrics = new WorkerMetrics();
        String loadStr = extractJsonValue(json, "currentLoad");
        if (loadStr != null) metrics.setCurrentLoad(Double.parseDouble(loadStr));
        String latStr = extractJsonValue(json, "avgLatencyMs");
        if (latStr != null) metrics.setAvgLatencyMs(Double.parseDouble(latStr));
        String srStr = extractJsonValue(json, "successRate");
        if (srStr != null) metrics.setSuccessRate(Double.parseDouble(srStr));
        String atStr = extractJsonValue(json, "activeTasks");
        if (atStr != null) metrics.setActiveTasks(Integer.parseInt(atStr));
        return metrics;
    }

    /**
     * Build a unified Gateway HTTP API response from the worker's response body.
     * Worker response format: {"response":"text","workerMeta":{...},"fileDiffs":[...]}
     */
    public static String buildUnifiedResponse(String taskId, String workerId, WorkerInfo worker, String workerBody) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = JsonUtils.getMapper().readTree(workerBody);
            String textResponse = root.has("response") && !root.get("response").isNull()
                    ? root.get("response").asText() : workerBody;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);

            Map<String, Object> workerObj = new LinkedHashMap<>();
            workerObj.put("id", workerId);
            workerObj.put("url", worker.getBaseUrl());
            workerObj.put("model", worker.getModel() != null ? worker.getModel() : "");
            result.put("worker", workerObj);

            result.put("status", "completed");
            result.put("response", textResponse);

            com.fasterxml.jackson.databind.JsonNode workerMeta = root.get("workerMeta");
            if (workerMeta != null && !workerMeta.isNull()) {
                result.put("workerMeta", workerMeta);
            }

            com.fasterxml.jackson.databind.JsonNode fileDiffs = root.get("fileDiffs");
            if (fileDiffs != null && !fileDiffs.isNull() && fileDiffs.isArray() && fileDiffs.size() > 0) {
                result.put("fileDiffs", fileDiffs);
            }

            return JsonUtils.toJson(result);
        } catch (Exception e) {
            logger.warn("Failed to parse worker response JSON, using raw body: {}", e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("taskId", taskId);

            Map<String, Object> workerObj = new LinkedHashMap<>();
            workerObj.put("id", workerId);
            workerObj.put("url", worker.getBaseUrl());
            workerObj.put("model", worker.getModel() != null ? worker.getModel() : "");
            fallback.put("worker", workerObj);

            fallback.put("status", "completed");
            fallback.put("response", workerBody);
            return JsonUtils.toJson(fallback);
        }
    }
}
