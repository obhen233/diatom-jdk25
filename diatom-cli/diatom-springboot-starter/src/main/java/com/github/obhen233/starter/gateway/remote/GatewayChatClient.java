package com.github.obhen233.starter.gateway.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Pure JDK HTTP client for communicating with a remote Diatom Gateway.
 * <p>
 * Uses only {@link java.net.HttpURLConnection} — no extra dependencies.
 * Supports non-streaming chat, SSE streaming chat, and Gateway management endpoints.
 */
public class GatewayChatClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayChatClient.class);

    /** Response wrapper for non-streaming chat. */
    public static class ChatResponse {
        private String taskId;
        private String worker;
        private String status;
        private String response;

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getWorker() { return worker; }
        public void setWorker(String worker) { this.worker = worker; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getResponse() { return response; }
        public void setResponse(String response) { this.response = response; }
    }

    /**
     * Callback interface for Server-Sent Events during streaming chat.
     */
    public interface SseEventHandler {
        /** Called when the request has been routed to a worker. */
        void onRouted(String taskId, String worker);

        /** Called for each content token in the stream. */
        void onToken(String content);

        /** Called when streaming is complete. */
        void onComplete(String taskId, String worker, Object fileDiffs);

        /** Called when an error occurs. */
        void onError(String error);
    }

    private final String remoteUrl;
    private final int connectTimeout;

    /**
     * @param remoteUrl base URL of the remote Gateway (e.g. "http://gateway:8080")
     */
    public GatewayChatClient(String remoteUrl) {
        this(remoteUrl, 10_000);
    }

    /**
     * @param remoteUrl       base URL of the remote Gateway
     * @param connectTimeout  connection timeout in milliseconds
     */
    public GatewayChatClient(String remoteUrl, int connectTimeout) {
        this.remoteUrl = remoteUrl.endsWith("/") ? remoteUrl.substring(0, remoteUrl.length() - 1) : remoteUrl;
        this.connectTimeout = connectTimeout;
    }

    // ========== Chat ==========

    /**
     * Send a non-streaming chat message to the remote Gateway.
     */
    public ChatResponse chat(String message, String sessionId, String taskId) {
        try {
            String body = buildChatJson(message, sessionId, taskId);
            String response = httpPost(remoteUrl + "/gateway/v1/chat", body,
                    "application/json", connectTimeout, 60_000);
            return parseChatResponse(response);
        } catch (Exception e) {
            throw new RuntimeException("Gateway chat request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Send a streaming chat request via SSE and deliver events to the handler.
     */
    public void chatStream(String message, String sessionId, String taskId,
                           SseEventHandler handler) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            String body = buildChatJson(message, sessionId, taskId);
            URL url = new URL(remoteUrl + "/gateway/v1/chat/stream");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(0); // no read timeout for streaming
            conn.connect();

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                if (responseCode == 404 || responseCode == 405) {
                    // 远端 Gateway 未实现 /gateway/v1/chat/stream（例如 starter Gateway 旧版本）：
                    // 降级到非流式 chat()，一次性返回全部内容，避免链路断路。
                    log.info("Remote Gateway has no /gateway/v1/chat/stream (HTTP {}), "
                            + "falling back to non-streaming chat", responseCode);
                    try {
                        ChatResponse resp = chat(message, sessionId, taskId);
                        String content = resp.getResponse();
                        if (content != null && !content.isEmpty()) {
                            handler.onToken(content);
                        }
                        handler.onComplete(resp.getTaskId(), resp.getWorker(), null);
                    } catch (Exception e) {
                        handler.onError("Gateway chat fallback failed: " + e.getMessage());
                    }
                    return;
                }
                String errorBody = readStream(conn.getErrorStream());
                handler.onError("Gateway returned HTTP " + responseCode + ": " + errorBody);
                return;
            }

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder currentData = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // Empty line = event separator, process accumulated data
                    if (currentData.length() > 0) {
                        processSseEvent(currentData.toString().trim(), handler);
                        currentData.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    currentData.append(line.substring(5).trim());
                } else if (line.startsWith("event:")) {
                    // We don't need event type for parsing, data lines carry the payload
                }
                // Other SSE fields (id, retry) are ignored
            }
            // Process any remaining data
            if (currentData.length() > 0) {
                processSseEvent(currentData.toString().trim(), handler);
            }

        } catch (Exception e) {
            if (handler != null) {
                handler.onError("SSE stream error: " + e.getMessage());
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ========== Management ==========

    /**
     * Get Gateway health information.
     */
    public Map<String, Object> health() {
        String json = httpGet(remoteUrl + "/gateway/v1/health", connectTimeout, 10_000);
        return parseJsonMap(json);
    }

    /**
     * List tasks, optionally filtered by status.
     */
    public List<Map<String, Object>> listTasks(String status) {
        String url = remoteUrl + "/gateway/v1/tasks";
        if (status != null && !status.isEmpty()) {
            url += "?status=" + encode(status);
        }
        String json = httpGet(url, connectTimeout, 10_000);
        return parseJsonList(json);
    }

    /**
     * Cancel a running task.
     */
    public boolean cancelTask(String taskId) {
        try {
            String json = httpPost(remoteUrl + "/gateway/v1/tasks/" + encode(taskId) + "/cancel",
                    "", "application/json", connectTimeout, 10_000);
            Map<String, Object> map = parseJsonMap(json);
            return Boolean.TRUE.equals(map.get("success"));
        } catch (Exception e) {
            log.error("Failed to cancel task {}: {}", taskId, e.getMessage());
            return false;
        }
    }

    /**
     * List registered workers.
     */
    public List<Map<String, Object>> listWorkers() {
        String json = httpGet(remoteUrl + "/gateway/v1/workers", connectTimeout, 10_000);
        return parseJsonList(json);
    }

    /**
     * Get pending confirmations.
     */
    public List<Map<String, Object>> getPendingConfirmations() {
        String json = httpGet(remoteUrl + "/gateway/v1/confirmations", connectTimeout, 10_000);
        return parseJsonList(json);
    }

    /**
     * Resolve a pending confirmation (approve/reject).
     */
    public boolean resolveConfirmation(String requestId, String decision) {
        try {
            String body = "{\"requestId\":\"" + escapeJson(requestId) + "\",\"decision\":\"" + escapeJson(decision) + "\"}";
            String json = httpPost(remoteUrl + "/gateway/v1/confirmations/resolve", body,
                    "application/json", connectTimeout, 10_000);
            Map<String, Object> map = parseJsonMap(json);
            return Boolean.TRUE.equals(map.get("success"));
        } catch (Exception e) {
            log.error("Failed to resolve confirmation {}: {}", requestId, e.getMessage());
            return false;
        }
    }

    // ========== Internal helpers ==========

    private String buildChatJson(String message, String sessionId, String taskId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"message\":").append(jsonString(message));
        if (sessionId != null && !sessionId.isEmpty()) {
            sb.append(",\"sessionId\":").append(jsonString(sessionId));
        }
        if (taskId != null && !taskId.isEmpty()) {
            sb.append(",\"taskId\":").append(jsonString(taskId));
        }
        sb.append("}");
        return sb.toString();
    }

    private String httpGet(String urlStr, int connectTimeout, int readTimeout) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.connect();
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            }
            throw new IOException("HTTP " + code + ": " + readStream(conn.getErrorStream()));
        } catch (Exception e) {
            throw new RuntimeException("GET " + urlStr + " failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String httpPost(String urlStr, String body, String contentType,
                            int connectTimeout, int readTimeout) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", contentType);
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.connect();

            if (body != null && !body.isEmpty()) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return readStream(conn.getInputStream());
            }
            throw new IOException("HTTP " + code + ": " + readStream(conn.getErrorStream()));
        } catch (Exception e) {
            throw new RuntimeException("POST " + urlStr + " failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Parse a simple JSON object into a Map. Supports flat string/number/boolean values.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return result;
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return result;
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return result;
        parseJsonEntries(inner, result);
        return result;
    }

    /**
     * Parse a simple JSON array into a List of Maps.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return result;
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return result;
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return result;

        // Split by top-level commas respecting braces
        List<String> items = splitTopLevel(inner);
        for (String item : items) {
            item = item.trim();
            if (item.startsWith("{")) {
                result.add(parseJsonMap(item));
            }
        }
        return result;
    }

    private List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) {
            parts.add(s.substring(start));
        }
        return parts;
    }

    private void parseJsonEntries(String s, Map<String, Object> map) {
        List<String> entries = splitTopLevelEntries(s);
        for (String entry : entries) {
            int colonIdx = findColon(entry);
            if (colonIdx < 0) continue;
            String key = unescapeJsonString(entry.substring(0, colonIdx).trim());
            String valueStr = entry.substring(colonIdx + 1).trim();
            map.put(key, parseJsonValue(valueStr));
        }
    }

    private List<String> splitTopLevelEntries(String s) {
        List<String> entries = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    entries.add(s.substring(start, i));
                    start = i + 1;
                }
            }
        }
        if (start < s.length()) {
            entries.add(s.substring(start));
        }
        return entries;
    }

    private int findColon(String s) {
        boolean inString = false;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ':' && depth == 0) return i;
            }
        }
        return -1;
    }

    private Object parseJsonValue(String value) {
        if (value == null || value.isEmpty()) return "";
        String v = value.trim();
        if (v.startsWith("\"") && v.endsWith("\"")) {
            return unescapeJsonString(v);
        }
        if (v.startsWith("{")) {
            // 嵌套对象（如 starter 网关返回的 worker={id,url,model} / workerMeta）
            Map<String, Object> nested = new LinkedHashMap<>();
            String inner = v.substring(1, v.length() - 1).trim();
            if (!inner.isEmpty()) {
                parseJsonEntries(inner, nested);
            }
            return nested;
        }
        if (v.startsWith("[")) {
            // 嵌套数组（如 fileDiffs）
            List<Object> list = new ArrayList<>();
            String inner = v.substring(1, v.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (String item : splitTopLevel(inner)) {
                    list.add(parseJsonValue(item.trim()));
                }
            }
            return list;
        }
        if ("true".equalsIgnoreCase(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
        if ("null".equalsIgnoreCase(v)) return null;
        try {
            if (v.contains(".")) return Double.parseDouble(v);
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static String toStringOrNull(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private String unescapeJsonString(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\t", "\t");
    }

    private String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    /**
     * Minimal ChatResponse parser from Gateway JSON response.
     */
    private ChatResponse parseChatResponse(String json) {
        ChatResponse resp = new ChatResponse();
        if (json == null || json.trim().isEmpty()) return resp;
        Map<String, Object> map = parseJsonMap(json);
        if (map.get("taskId") != null) resp.setTaskId(String.valueOf(map.get("taskId")));
        if (map.get("worker") != null) {
            Object workerObj = map.get("worker");
            // starter Gateway 把 worker 写成 {id,url,model} 对象，独立 Gateway 写成字符串。
            // 统一提取 id（或 url）避免输出 Java Map 的 toString。
            if (workerObj instanceof Map) {
                Map<?, ?> workerMap = (Map<?, ?>) workerObj;
                Object id = workerMap.get("id");
                Object url = workerMap.get("url");
                resp.setWorker(String.valueOf(id != null ? id : (url != null ? url : workerObj)));
            } else {
                resp.setWorker(String.valueOf(workerObj));
            }
        }
        if (map.get("status") != null) resp.setStatus(String.valueOf(map.get("status")));
        if (map.get("response") != null) resp.setResponse(String.valueOf(map.get("response")));
        return resp;
    }

    /**
     * Process a single SSE data payload and dispatch to the appropriate handler method.
     */
    private void processSseEvent(String data, SseEventHandler handler) {
        if (data == null || data.isEmpty()) return;
        try {
            Map<String, Object> event = parseJsonMap(data);
            String type = (String) event.get("type");
            if (type == null) type = (String) event.get("event");

            if ("routed".equals(type)) {
                String taskId = (String) event.get("taskId");
                String worker = (String) event.get("worker");
                handler.onRouted(taskId, worker);
            } else if ("token".equals(type)) {
                String content = (String) event.get("content");
                if (content != null) {
                    handler.onToken(content);
                }
            } else if ("complete".equals(type)) {
                String taskId = (String) event.get("taskId");
                String worker = (String) event.get("worker");
                Object fileDiffs = event.get("fileDiffs");
                handler.onComplete(taskId, worker, fileDiffs);
            } else if ("error".equals(type)) {
                // 兼容三种字段：error / message / content（core SseEvent.error 写 content）。
                // 注意避免 String.valueOf(null) 产生 "null" 字符串。
                String error = toStringOrNull(event.get("error"));
                if (error == null) error = toStringOrNull(event.get("message"));
                if (error == null) error = toStringOrNull(event.get("content"));
                handler.onError(error != null ? error : "Unknown SSE error");
            }
        } catch (Exception e) {
            log.warn("Failed to parse SSE event: {}", data, e);
        }
    }
}
