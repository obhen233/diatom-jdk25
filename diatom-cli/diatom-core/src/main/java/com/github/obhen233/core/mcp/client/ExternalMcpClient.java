package com.github.obhen233.core.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.github.obhen233.util.JsonUtils;

/**
 * stdio-based MCP client that communicates with an external process
 * via JSON-RPC over stdin/stdout.
 */
public class ExternalMcpClient implements McpServer {
    private static final Logger logger = LoggerFactory.getLogger(ExternalMcpClient.class);
    private static final int STDIO_READ_TIMEOUT_MS = 2000;

    private final Process process;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final ObjectMapper mapper = JsonUtils.getMapper();
    private int idCounter = 1;
    private String name;
    private String description = "";

    public ExternalMcpClient(String command, String workspaceDir) throws Exception {
        // Parse command string into arguments array
        // Supports commands like: npx -y @anthropic/mcp-server-filesystem /path
        // or: node /path/to/server.js /path
        String[] parts = parseCommand(command);
        if (parts.length == 0) {
            throw new IllegalArgumentException("Empty command for MCP server");
        }

        ProcessBuilder builder = new ProcessBuilder(parts);
        builder.directory(new File(workspaceDir));
        // Do NOT redirect stderr to stdout — stderr contains error/startup logs
        // that would pollute the MCP JSON-RPC protocol stream on stdout.
        builder.redirectErrorStream(false);

        this.process = builder.start();
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);

        // Consume stderr in a daemon thread to prevent buffer deadlock
        consumeStderr(process);

        this.name = "external";
        initialize();
    }

    /**
     * Consume stderr output in a daemon thread to prevent the stderr buffer
     * from filling up and blocking the process. Stderr is logged at debug level.
     */
    private void consumeStderr(Process process) {
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        logger.debug("[MCP stderr] {}", line);
                    }
                }
            } catch (IOException e) {
                // Process ended, ignore
            }
        }, "mcp-stderr-" + name);
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    /**
     * Parse command string into array, respecting quotes.
     * e.g., "npx -y @anthropic/mcp-server /path" -> ["npx", "-y", "@anthropic/mcp-server", "/path"]
     */
    private String[] parseCommand(String command) {
        java.util.List<String> args = new java.util.ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");
        java.util.regex.Matcher matcher = pattern.matcher(command);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                args.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                args.add(matcher.group(2));
            } else if (matcher.group(3) != null) {
                args.add(matcher.group(3));
            }
        }
        return args.toArray(new String[0]);
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getDescription() { return description; }

    private void initialize() throws Exception {
        ObjectNode initRequest = mapper.createObjectNode();
        initRequest.put("jsonrpc", "2.0");
        initRequest.put("method", "initialize");
        initRequest.put("id", idCounter++);
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "diatom-cli");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);
        initRequest.set("params", params);

        writer.println(mapper.writeValueAsString(initRequest));

        // Read initialize response with timeout, skipping non-JSON lines
        JsonNode json = readJsonRpcResponse();
        if (json == null) {
            throw new Exception("MCP server did not respond to initialize within " + STDIO_READ_TIMEOUT_MS + "ms");
        }
        if (json.has("error")) {
            throw new Exception("MCP initialize error: " + json.get("error"));
        }

        // Send notifications/initialized as required by MCP protocol
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        writer.println(mapper.writeValueAsString(notification));

        // Drain any leftover messages (e.g. tool/list_changed notifications)
        drainPendingMessages();
    }

    /**
     * Read a line with timeout by polling reader.ready().
     */
    private String readLineWithTimeout(int timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                return reader.readLine();
            }
            Thread.sleep(10);
        }
        // One last attempt in case ready() was false but data arrived
        if (reader.ready()) {
            return reader.readLine();
        }
        return null;
    }

    /**
     * Drain any pending messages from the reader buffer without blocking.
     */
    private void drainPendingMessages() {
        try {
            int drained = 0;
            while (reader.ready()) {
                reader.readLine();
                drained++;
            }
            if (drained > 0) {
                logger.debug("Drained {} pending message(s) from MCP server stdin", drained);
            }
        } catch (IOException e) {
            logger.debug("Error draining messages: {}", e.getMessage());
        }
    }

    /**
     * Read the next JSON-RPC response from the MCP server, skipping any non-JSON lines
     * (such as log output or error stack traces) that may have been printed to stdout.
     *
     * @return the parsed JSON-RPC response, or null if timeout/no valid response
     */
    private JsonNode readJsonRpcResponse() {
        long deadline = System.currentTimeMillis() + STDIO_READ_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (!reader.ready()) {
                    Thread.sleep(10);
                    continue;
                }
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                // Try to parse as JSON — if it fails, skip this line
                try {
                    JsonNode json = mapper.readTree(line);
                    if (json != null && json.isObject()) {
                        // Valid JSON object — treat as JSON-RPC response
                        return json;
                    }
                } catch (Exception e) {
                    // Not valid JSON, skip this line (likely a log/stack trace)
                    logger.debug("Skipping non-JSON output from MCP server '{}': {}", name, line);
                }
            } catch (IOException e) {
                logger.warn("Error reading from MCP server '{}'", name, e);
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // One final attempt
        try {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null) {
                    try {
                        return mapper.readTree(line);
                    } catch (Exception e) {
                        logger.debug("Skipping non-JSON output from MCP server '{}': {}", name, line);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Error reading from MCP server '{}'", name, e);
        }
        return null;
    }

    @Override
    public Map<String, Tool> listTools() {
        Map<String, Tool> tools = new HashMap<>();
        try {
            // Drain any pending server notifications before sending request
            drainPendingMessages();

            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "tools/list");
            request.put("id", idCounter++);

            writer.println(mapper.writeValueAsString(request));
            JsonNode json = readJsonRpcResponse();
            if (json != null && json.has("result")) {
                JsonNode result = json.get("result");
                if (result.has("tools")) {
                    JsonNode toolArray = result.get("tools");
                    for (JsonNode toolObj : toolArray) {
                        String toolName = toolObj.get("name").asText();
                        String desc = toolObj.has("description") ? toolObj.get("description").asText() : "";
                        String schema = toolObj.has("inputSchema") ? toolObj.get("inputSchema").toString() : "{}";
                        tools.put(toolName, new Tool(toolName, desc, schema));
                    }
                }
            }
            if (tools.isEmpty()) {
                logger.warn("MCP server '{}' returned no tools — possible connection or initialization issue", name);
            }
        } catch (Exception e) {
            logger.error("Error listing tools", e);
        }
        return tools;
    }

    @Override
    public String callTool(String toolName, String args) {
        try {
            // Drain any pending server notifications before sending request
            drainPendingMessages();

            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "tools/call");
            request.put("id", idCounter++);

            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            JsonNode arguments = mapper.readTree(args);
            params.set("arguments", arguments);
            request.set("params", params);

            writer.println(mapper.writeValueAsString(request));
            JsonNode json = readJsonRpcResponse();
            if (json != null) {
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
            logger.error("Error calling tool", e);
            return "Error: " + e.getMessage();
        }
        return "";
    }

    public void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }
}
