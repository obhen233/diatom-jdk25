package com.github.obhen233.core.gateway;

import com.github.obhen233.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JSON extraction and formatting utilities used by Gateway mode components.
 * <p>
 * Provides both string-based and Jackson-based JSON parsing for extracting
 * values, arrays, and objects from raw JSON strings.
 * </p>
 */
public final class GatewayJsonUtil {

    private GatewayJsonUtil() {}

    /**
     * Split a JSON array of objects into individual object strings.
     */
    public static String[] splitJsonArrayObjects(String jsonArray) {
        if (jsonArray == null || jsonArray.length() < 2) return new String[0];
        String content = jsonArray.trim();
        if (content.startsWith("[")) content = content.substring(1);
        if (content.endsWith("]")) content = content.substring(0, content.length() - 1);
        content = content.trim();
        if (content.isEmpty()) return new String[0];

        List<String> result = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    result.add(content.substring(start, i + 1));
                }
            }
        }
        return result.toArray(new String[0]);
    }

    /**
     * Extract a raw JSON object (content between { and }) for the given key.
     */
    public static String extractRawJsonObject(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":{";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": {";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start = json.indexOf('{', start);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * Extract a raw JSON array for the given key.
     */
    public static String extractRawJsonArray(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":[";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": [";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start = json.indexOf('[', start);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * Extract comma-separated string values from a JSON array field.
     */
    public static String extractJsonArrayValues(String json, String key) {
        String array = extractRawJsonArray(json, key);
        if (array == null || array.length() < 3) return null;
        String content = array.trim();
        if (content.startsWith("[")) content = content.substring(1);
        if (content.endsWith("]")) content = content.substring(0, content.length() - 1);
        content = content.trim();
        if (content.isEmpty()) return null;
        String[] parts = content.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.startsWith("\"") && part.endsWith("\"")) {
                part = part.substring(1, part.length() - 1);
            }
            if (i > 0) sb.append(", ");
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * Extract a boolean value for the given key.
     */
    public static boolean extractJsonBoolean(String json, String key) {
        if (json == null) return false;
        String search = "\"" + key + "\":true";
        int start = json.indexOf(search);
        if (start >= 0) return true;
        search = "\"" + key + "\": true";
        return json.indexOf(search) >= 0;
    }

    /**
     * Read full response/error stream body from an HttpURLConnection.
     */
    public static String readConnectionBody(HttpURLConnection conn, int code) throws java.io.IOException {
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf, 0, buf.length)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Extract a string or primitive JSON value by key from a JSON string.
     * Tries Jackson {@link JsonNode} first for robustness, falls back to
     * string-based extraction for non-standard JSON fragments.
     */
    public static String extractJsonValue(String json, String key) {
        if (json == null || key == null) return null;
        // Try Jackson first
        try {
            JsonNode node = JsonUtils.getMapper().readTree(json);
            JsonNode value = node.get(key);
            if (value != null) {
                return value.isTextual() ? value.asText() : value.toString();
            }
        } catch (Exception ignored) {
            // Fall back to string-based parsing for non-standard JSON
        }
        // String-based fallback (preserves backward compatibility)
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start += search.length();

        int end = start;
        boolean escaped = false;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (escaped) {
                escaped = false;
                end++;
            } else if (c == '\\') {
                escaped = true;
                end++;
            } else if (c == '"') {
                break;
            } else {
                end++;
            }
        }

        if (end >= json.length() || json.charAt(end) != '"') {
            search = "\"" + key + "\":";
            start = json.indexOf(search);
            if (start < 0) return null;
            start += search.length();
            while (start < json.length() && json.charAt(start) == ' ') start++;
            end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            if (end < 0) return json.substring(start).trim();
            return json.substring(start, end).trim();
        }

        String raw = json.substring(start, end);
        return unescapeJsonString(raw);
    }

    /**
     * Unescape common JSON escape sequences in a string value.
     */
    public static String unescapeJsonString(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    default: sb.append('\\').append(c); break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                sb.append(c);
            }
        }
        if (escaped) sb.append('\\');
        return sb.toString();
    }

    /**
     * Extract a JSON array or object value (not just a string) from JSON by key.
     */
    public static String extractFullJsonValue(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":[";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": [";
            start = json.indexOf(search);
        }
        if (start >= 0) {
            start = json.indexOf('[', start);
            if (start >= 0) {
                int depth = 0;
                for (int i = start; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (c == '[') depth++;
                    else if (c == ']') { depth--; if (depth == 0) return json.substring(start, i + 1); }
                }
            }
        }
        // Try object
        search = "\"" + key + "\":{";
        start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": {";
            start = json.indexOf(search);
        }
        if (start >= 0) {
            start = json.indexOf('{', start);
            if (start >= 0) {
                int depth = 0;
                for (int i = start; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') { depth--; if (depth == 0) return json.substring(start, i + 1); }
                }
            }
        }
        return null;
    }

    /**
     * Format collaborative task JSON result into human-readable text.
     */
    public static String formatCollabResult(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return "    (no results)";
        }
        try {
            StringBuilder sb = new StringBuilder();
            int summaryStart = json.indexOf("\"collaboration_summary\":{");
            if (summaryStart >= 0) {
                int summaryEnd = json.indexOf("},", summaryStart);
                if (summaryEnd < 0) summaryEnd = json.indexOf("},\"", summaryStart);
                if (summaryEnd > summaryStart) {
                    String summary = json.substring(summaryStart, summaryEnd + 1);
                    sb.append("    Summary: ").append(summary.replaceAll("\"", "").replaceAll("[{}\"]", " ").trim()).append("\n");
                }
            }
            int resultsStart = json.indexOf("\"results\":[");
            if (resultsStart >= 0) {
                int resultsEnd = json.lastIndexOf("]}");
                if (resultsEnd > resultsStart) {
                    String resultsPart = json.substring(resultsStart + 10, resultsEnd);
                    String[] items = resultsPart.split("\\},\\{");
                    for (int i = 0; i < items.length; i++) {
                        String item = items[i].replaceAll("\"", "").replaceAll("[{}]", "");
                        sb.append("    [" + (i + 1) + "] ").append(item).append("\n");
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : "    " + json;
        } catch (Exception e) {
            return "    " + json;
        }
    }

    /**
     * Truncate a string to a maximum length, appending "..." if truncated.
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
