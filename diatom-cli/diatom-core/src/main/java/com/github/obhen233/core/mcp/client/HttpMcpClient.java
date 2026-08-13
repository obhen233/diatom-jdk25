package com.github.obhen233.core.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.tool.Tool;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.github.obhen233.util.JsonUtils;

/**
 * HTTP/SSE-based MCP client that communicates with a remote MCP server
 * via JSON-RPC over HTTP POST with SSE for server-initiated messages.
 */
public class HttpMcpClient implements McpServer {
    private static final Logger logger = LoggerFactory.getLogger(HttpMcpClient.class);

    private final String name;
    private final String baseUrl;
    private final String mcpEndpoint;
    private final String sseEndpoint;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = JsonUtils.getMapper();
    private final Map<Integer, CountDownLatch> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Integer, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Tool> toolsCache = new ConcurrentHashMap<>();
    private EventSource sseEventSource;
    private boolean initialized = false;
    private String description = "";

    public HttpMcpClient(String name, String baseUrl, String mcpEndpoint, String sseEndpoint) {
        this.name = name;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mcpEndpoint = mcpEndpoint != null ? mcpEndpoint : "/mcp";
        this.sseEndpoint = sseEndpoint != null ? sseEndpoint : "/sse";
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // SSE needs no timeout
                .build();
        initialize();
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getDescription() { return description; }

    private void initialize() {
        try {
            // Start SSE connection for receiving server messages
            startSseConnection();

            // Send initialize request
            ObjectNode initRequest = mapper.createObjectNode();
            initRequest.put("jsonrpc", "2.0");
            initRequest.put("method", "initialize");
            initRequest.put("id", 1);
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", "2024-11-05");
            ObjectNode clientInfo = mapper.createObjectNode();
            clientInfo.put("name", "diatom-cli");
            clientInfo.put("version", "1.0.0");
            params.set("clientInfo", clientInfo);
            initRequest.set("params", params);

            String response = sendRequest(initRequest);
            if (response != null) {
                JsonNode json = mapper.readTree(response);
                if (json.has("result")) {
                    JsonNode result = json.get("result");
                    if (result.has("serverInfo")) {
                        description = result.get("serverInfo").toString();
                    }
                    initialized = true;
                    logger.info("HTTP/SSE MCP server {} initialized successfully", name);
                }
            }

            // Cache tools list
            refreshToolsList();
        } catch (Exception e) {
            logger.error("Failed to initialize HTTP/SSE MCP server: {}", name, e);
        }
    }

    private void startSseConnection() {
        Request request = new Request.Builder()
                .url(baseUrl + sseEndpoint)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .build();

        EventSourceListener listener = new EventSourceListener() {
            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                handleSseMessage(data);
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                logger.error("SSE connection failed for {}: {}", name, t.getMessage());
                // Attempt reconnection after delay
                new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                        startSseConnection();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }

            @Override
            public void onClosed(EventSource eventSource) {
                logger.info("SSE connection closed for {}", name);
            }
        };

        sseEventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, listener);
    }

    private void handleSseMessage(String data) {
        if (data == null || data.isEmpty()) return;

        try {
            // SSE format: data: {"jsonrpc": "2.0", "id": 1, "result": {...}}
            String jsonStr = data.trim();
            if (jsonStr.startsWith("data:")) {
                jsonStr = jsonStr.substring(5).trim();
            }

            JsonNode json = mapper.readTree(jsonStr);

            // Handle response to a pending request
            if (json.has("id")) {
                int id = json.get("id").asInt();
                CountDownLatch latch = pendingRequests.remove(id);
                if (latch != null) {
                    responses.put(id, jsonStr);
                    latch.countDown();
                }
            }

            // Handle server-initiated notifications (no id)
            if (json.has("method") && !json.has("id")) {
                String method = json.get("method").asText();
                logger.debug("Received server notification: {}", method);
                // Handle notifications like "tools/list_changed", etc.
            }
        } catch (Exception e) {
            logger.error("Error parsing SSE message: {}", data, e);
        }
    }

    private synchronized String sendRequest(JsonNode request) throws Exception {
        int id = request.get("id").asInt();
        CountDownLatch latch = new CountDownLatch(1);
        pendingRequests.put(id, latch);

        String jsonStr = mapper.writeValueAsString(request);
        RequestBody body = RequestBody.create(jsonStr, MediaType.parse("application/json"));

        Request httpRequest = new Request.Builder()
                .url(baseUrl + mcpEndpoint)
                .post(body)
                .header("Content-Type", "application/json")
                .build();

        // Fire and forget - response comes via SSE
        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                logger.error("HTTP request failed: {}", e.getMessage());
                pendingRequests.remove(id);
                latch.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });

        // Wait for response via SSE with timeout
        boolean received = latch.await(30, TimeUnit.SECONDS);
        if (!received) {
            logger.warn("Request {} timed out waiting for response", id);
            pendingRequests.remove(id);
            return null;
        }

        return responses.remove(id);
    }

    private void refreshToolsList() {
        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "tools/list");
            request.put("id", nextId());

            String response = sendRequest(request);
            if (response != null) {
                JsonNode json = mapper.readTree(response);
                if (json.has("result")) {
                    JsonNode result = json.get("result");
                    if (result.has("tools")) {
                        JsonNode toolArray = result.get("tools");
                        for (JsonNode toolObj : toolArray) {
                            String toolName = toolObj.get("name").asText();
                            String desc = toolObj.has("description") ? toolObj.get("description").asText() : "";
                            String schema = toolObj.has("inputSchema") ? toolObj.get("inputSchema").toString() : "{}";
                            toolsCache.put(toolName, new Tool(toolName, desc, schema));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error refreshing tools list for {}", name, e);
        }
    }

    private int idCounter = 2;

    private int nextId() {
        synchronized (HttpMcpClient.class) {
            return idCounter++;
        }
    }

    @Override
    public Map<String, Tool> listTools() {
        return new HashMap<>(toolsCache);
    }

    @Override
    public String callTool(String toolName, String args) {
        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "tools/call");
            request.put("id", nextId());

            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            JsonNode arguments = mapper.readTree(args);
            params.set("arguments", arguments);
            request.set("params", params);

            String response = sendRequest(request);
            if (response != null) {
                JsonNode json = mapper.readTree(response);
                if (json.has("result")) {
                    JsonNode result = json.get("result");
                    if (result.has("content")) {
                        JsonNode content = result.get("content");
                        if (content.size() > 0) {
                            return content.get(0).get("text").asText();
                        }
                    }
                }
                if (json.has("error")) {
                    return "Error: " + json.get("error").get("message").asText();
                }
            }
        } catch (Exception e) {
            logger.error("Error calling tool {} on {}", toolName, name, e);
            return "Error: " + e.getMessage();
        }
        return "";
    }

    @Override
    public Map<String, Resource> listResources() {
        // Could be implemented similarly if needed
        return null;
    }

    @Override
    public String readResource(String uri) {
        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "resources/read");
            request.put("id", nextId());

            ObjectNode params = mapper.createObjectNode();
            params.put("uri", uri);
            request.set("params", params);

            String response = sendRequest(request);
            if (response != null) {
                JsonNode json = mapper.readTree(response);
                if (json.has("result")) {
                    JsonNode result = json.get("result");
                    if (result.has("contents")) {
                        JsonNode contents = result.get("contents");
                        if (contents.size() > 0) {
                            return contents.get(0).toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error reading resource {} from {}", uri, name, e);
        }
        return null;
    }

    public void close() {
        if (sseEventSource != null) {
            sseEventSource.cancel();
        }
        httpClient.dispatcher().executorService().shutdown();
        logger.info("HTTP/SSE MCP server {} disconnected", name);
    }
}
