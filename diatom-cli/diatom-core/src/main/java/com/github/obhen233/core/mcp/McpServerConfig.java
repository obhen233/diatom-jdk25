package com.github.obhen233.core.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server configuration entity.
 * Supports both single-MCP-per-file and multi-MCP-per-file formats.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {

    public enum Type {
        STDIO,
        HTTP
    }

    private String name;
    private String description;

    @JsonProperty("type")
    private Type type = Type.STDIO;

    // STDIO
    private String command;
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new HashMap<>();
    private String workspaceDir;

    // HTTP
    private String url;
    @JsonProperty("mcpEndpoint")
    private String mcpEndpoint = "/mcp";
    @JsonProperty("sseEndpoint")
    private String sseEndpoint = "/sse";

    // Enable/disable
    @JsonProperty("enabled")
    private boolean enabled = true;

    // Source tracking (not serialized)
    private transient Path sourceFile;
    private transient boolean isGlobal;

    public McpServerConfig() {}

    public McpServerConfig(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    // ==================== Factory Methods ====================

    /**
     * Parse config from a single-MCP JSON file.
     * The file should contain direct config fields (name, command, etc.)
     */
    public static McpServerConfig fromSingleFile(Path file) throws IOException {
        String content = new String(Files.readAllBytes(file));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        McpServerConfig config = mapper.readValue(content, McpServerConfig.class);

        // If name is not in file, use filename (without .json)
        if (config.name == null || config.name.isEmpty()) {
            String filename = file.getFileName().toString();
            int dotIndex = filename.lastIndexOf('.');
            config.name = dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
        }

        // Determine type from fields
        if (config.url != null && !config.url.isEmpty()) {
            config.type = Type.HTTP;
        } else {
            config.type = Type.STDIO;
        }

        config.sourceFile = file;
        return config;
    }

    /**
     * Parse configs from a multi-MCP JSON file.
     * The file should contain {"mcpServers": {...}}
     */
    public static Map<String, McpServerConfig> fromMultiFile(Path file) throws IOException {
        Map<String, McpServerConfig> result = new HashMap<>();

        String content = new String(Files.readAllBytes(file));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(content);

        if (!root.has("mcpServers")) {
            return result;
        }

        com.fasterxml.jackson.databind.JsonNode serversNode = root.get("mcpServers");
        if (serversNode == null || !serversNode.isObject()) {
            return result;
        }

        ObjectNode servers = (ObjectNode) serversNode;
        servers.fields().forEachRemaining(entry -> {
            try {
                String serverName = entry.getKey();
                ObjectNode serverNode = (ObjectNode) entry.getValue();
                McpServerConfig config = mapper.treeToValue(serverNode, McpServerConfig.class);
                config.name = serverName;

                // Determine type from fields
                if (serverNode.has("url")) {
                    config.type = Type.HTTP;
                } else {
                    config.type = Type.STDIO;
                }

                config.sourceFile = file;
                result.put(serverName, config);
            } catch (Exception e) {
                // Skip invalid entries
            }
        });

        return result;
    }

    // ==================== Serialization ====================

    /**
     * Convert to JSON string (single-MCP format)
     */
    public String toJson() throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper.writeValueAsString(this);
    }

    /**
     * Build command string for STDIO servers
     */
    public String buildCommandString() {
        if (command == null) return null;
        StringBuilder sb = new StringBuilder(command);
        if (args != null) {
            for (String arg : args) {
                sb.append(" ");
                if (arg.contains(" ") || arg.contains("\"")) {
                    sb.append("\"").append(arg.replace("\"", "\\\"")).append("\"");
                } else {
                    sb.append(arg);
                }
            }
        }
        return sb.toString();
    }

    // ==================== Getters and Setters ====================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }

    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getMcpEndpoint() { return mcpEndpoint; }
    public void setMcpEndpoint(String mcpEndpoint) { this.mcpEndpoint = mcpEndpoint; }

    public String getSseEndpoint() { return sseEndpoint; }
    public void setSseEndpoint(String sseEndpoint) { this.sseEndpoint = sseEndpoint; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Path getSourceFile() { return sourceFile; }
    public void setSourceFile(Path sourceFile) { this.sourceFile = sourceFile; }

    public boolean isGlobal() { return isGlobal; }
    public void setGlobal(boolean global) { isGlobal = global; }

    @Override
    public String toString() {
        return "McpServerConfig{" +
                "name='" + name + '\'' +
                ", type=" + type +
                ", command='" + command + '\'' +
                ", url='" + url + '\'' +
                ", sourceFile=" + sourceFile +
                ", isGlobal=" + isGlobal +
                '}';
    }
}
