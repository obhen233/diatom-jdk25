package com.github.obhen233.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import com.github.obhen233.util.JsonUtils;

/**
 * MCP Servers Configuration
 * Supports loading MCP server configs from three levels:
 *
 * Level 1 - Global User:       ~/.diatom/mcpservers/
 *   User-wide MCP servers, effective for all projects.
 *
 * Level 2 - Self-Update:        {jar}/.diatom/mcpservers/
 *   MCP servers for self-update functionality.
 *
 * Level 3 - Project Local:      {project}/.diatom/mcpservers/
 *   Project-specific MCP servers.
 *
 * Priority: Project Local > Self-Update > Global User
 * Same server name at higher level overrides lower level.
 *
 * Configuration format (similar to Claude Code):
 * {
 *   "mcpServers": {
 *     "server-name": {
 *       "command": "npx",
 *       "args": ["-y", "@anthropic/mcp-server"],
 *       "env": { "KEY": "value" },
 *       "workspaceDir": "/optional/workspace"
 *     }
 *   }
 * }
 *
 * Or for HTTP/SSE servers:
 * {
 *   "mcpServers": {
 *     "server-name": {
 *       "url": "http://localhost:8080",
 *       "mcpEndpoint": "/mcp",
 *       "sseEndpoint": "/sse"
 *     }
 *   }
 * }
 */
public class McpServersConfig {
    private static final Logger logger = LoggerFactory.getLogger(McpServersConfig.class);
    private static final String MCP_SERVERS_DIR = "mcpservers";

    private final Path globalDir;      // ~/.diatom/mcpservers
    private final Path jarDir;         // {jar}/.diatom/mcpservers
    private final Path projectDir;     // {project}/.diatom/mcpservers (can be null)
    private final ObjectMapper mapper = JsonUtils.getMapper();

    public McpServersConfig() {
        this(null, null);
    }

    public McpServersConfig(Path globalDir, Path projectDir) {
        this.globalDir = globalDir != null ? globalDir : McpServersConfig.getDefaultGlobalDir();
        this.projectDir = projectDir;
        this.jarDir = McpServersConfig.getDefaultJarDir();
    }

    private static Path getDefaultGlobalDir() {
        return Paths.get(System.getProperty("user.home"), ".diatom", MCP_SERVERS_DIR);
    }

    private static Path getDefaultJarDir() {
        try {
            String classPath = System.getProperty("java.class.path");
            String[] paths = classPath.split(File.pathSeparator);
            Path customLibDir = Paths.get(System.getProperty("user.home"), ".diatom", "custom", "lib");
            for (String path : paths) {
                if (path.endsWith(".jar")) {
                    // Skip custom-current.jar - it's the bootstrap wrapper, not a diatom core jar
                    if (path.contains("custom-current")) {
                        continue;
                    }
                    File jarFile = new File(path);
                    Path jarDir = jarFile.getParentFile() != null ? jarFile.getParentFile().toPath() : null;
                    if (jarDir != null) {
                        // Skip if JAR is in ~/.diatom/custom/lib - we don't want to create .diatom/mcpservers there
                        if (jarDir.equals(customLibDir) || jarDir.startsWith(customLibDir)) {
                            continue;
                        }
                        return jarDir.resolve(".diatom").resolve(MCP_SERVERS_DIR);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not detect JAR location: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Load all MCP server configurations from all levels.
     * Project-level configs override global configs with same name.
     *
     * @return Map of server name -> config entry (highest priority wins)
     */
    public Map<String, McpServerConfigEntry> loadConfigs() {
        Map<String, McpServerConfigEntry> result = new LinkedHashMap<>();
        Set<String> seenServers = new HashSet<>();

        // Load in priority order: global (lowest) -> jar -> project (highest)
        List<Path> dirs = getConfigDirsInPriorityOrder();

        for (Path dir : dirs) {
            if (dir == null || !Files.exists(dir)) {
                continue;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path path : stream) {
                    try {
                        Map<String, McpServerConfigEntry> loaded = loadConfigFile(path);
                        for (Map.Entry<String, McpServerConfigEntry> entry : loaded.entrySet()) {
                            String name = entry.getKey();
                            // Only add if not already seen (lower priority was already added)
                            if (!seenServers.contains(name)) {
                                result.put(name, entry.getValue());
                                logger.debug("Loaded MCP server '{}' from: {}", name, path);
                            } else {
                                logger.debug("Skipping MCP server '{}' from {} (already loaded from higher priority)",
                                        name, path);
                            }
                        }
                        seenServers.addAll(loaded.keySet());
                    } catch (Exception e) {
                        logger.error("Failed to load MCP config from {}: {}", path, e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to read MCP servers directory {}: {}", dir, e.getMessage());
            }
        }

        logger.info("Loaded {} MCP server config(s) from {} directories", result.size(), dirs.size());
        return result;
    }

    /**
     * Get config directories in priority order (lowest to highest)
     */
    private List<Path> getConfigDirsInPriorityOrder() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(globalDir);   // Lowest priority
        if (jarDir != null && !jarDir.equals(globalDir)) {
            dirs.add(jarDir);
        }
        if (projectDir != null && Files.exists(projectDir)) {
            dirs.add(projectDir);  // Highest priority
        }
        return dirs;
    }

    /**
     * Load configurations from a single JSON file.
     * Supports two formats:
     * 1. Multi-server: { "mcpServers": { "name": { ... } } }
     * 2. Single-server: { "name": "...", "command": "...", ... }  (name from filename)
     */
    private Map<String, McpServerConfigEntry> loadConfigFile(Path path) {
        Map<String, McpServerConfigEntry> configs = new LinkedHashMap<>();

        try {
            ObjectNode root = (ObjectNode) mapper.readTree(path.toFile());

            if (root.has("mcpServers")) {
                // Multi-server format: { "mcpServers": { ... } }
                ObjectNode servers = (ObjectNode) root.get("mcpServers");
                servers.fieldNames().forEachRemaining(fieldName -> {
                    try {
                        ObjectNode serverConfig = (ObjectNode) servers.get(fieldName);
                        McpServerConfigEntry entry = parseServerConfig(fieldName, serverConfig);
                        if (entry != null) {
                            entry.setSourcePath(path.toString());
                            configs.put(fieldName, entry);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to parse MCP server config '{}': {}", fieldName, e.getMessage());
                    }
                });
            } else if (root.has("command") || root.has("url")) {
                // Single-server format (used by mcp add): { "command": "...", ... }
                // Derive server name from filename (remove .json suffix)
                String fileName = path.getFileName().toString();
                String name = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
                McpServerConfigEntry entry = parseServerConfig(name, root);
                if (entry != null) {
                    entry.setSourcePath(path.toString());
                    configs.put(name, entry);
                    logger.debug("Loaded single MCP server '{}' from: {}", name, path);
                }
            } else {
                logger.warn("Config file {} has neither 'mcpServers' nor 'command'/'url' field, skipping", path);
            }

        } catch (IOException e) {
            logger.error("Failed to read config file {}: {}", path, e.getMessage());
        }

        return configs;
    }

    /**
     * Parse a single server configuration entry
     */
    private McpServerConfigEntry parseServerConfig(String name, ObjectNode config) {
        McpServerConfigEntry entry = new McpServerConfigEntry();
        entry.setName(name);

        // Check if it's an HTTP/SSE server or stdio server
        if (config.has("url")) {
            // HTTP/SSE server
            entry.setType(McpServerType.HTTP);
            entry.setUrl(config.get("url").asText());
            entry.setMcpEndpoint(config.has("mcpEndpoint") ? config.get("mcpEndpoint").asText() : "/mcp");
            entry.setSseEndpoint(config.has("sseEndpoint") ? config.get("sseEndpoint").asText() : "/sse");
        } else if (config.has("command")) {
            // Stdio server
            entry.setType(McpServerType.STDIO);
            entry.setCommand(config.get("command").asText());

            // Parse args array
            List<String> args = new ArrayList<>();
            if (config.has("args")) {
                config.get("args").forEach(node -> args.add(node.asText()));
            }
            entry.setArgs(args);

            // Parse env vars
            if (config.has("env")) {
                config.get("env").fields().forEachRemaining(field ->
                    entry.getEnv().put(field.getKey(), field.getValue().asText()));
            }

            // Optional workspace dir
            if (config.has("workspaceDir")) {
                entry.setWorkspaceDir(config.get("workspaceDir").asText());
            }
        } else {
            logger.warn("MCP server '{}' has neither 'url' nor 'command' field, skipping", name);
            return null;
        }

        return entry;
    }

    /**
     * Ensure the mcpservers directory exists at a given path
     */
    public static void ensureConfigDirExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            logger.info("Created MCP servers config directory: {}", dir);
        }
    }

    /**
     * Get the default global config directory
     */
    public Path getGlobalDir() {
        return globalDir;
    }

    /**
     * Get the JAR location config directory
     */
    public Path getJarDir() {
        return jarDir;
    }

    /**
     * Get the project config directory
     */
    public Path getProjectDir() {
        return projectDir;
    }

    /**
     * MCP Server type
     */
    public enum McpServerType {
        STDIO,  // Process-based via stdin/stdout
        HTTP    // HTTP/SSE based
    }

    /**
     * MCP Server configuration entry
     */
    public static class McpServerConfigEntry {
        private String name;
        private McpServerType type;
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        private String workspaceDir;
        private String url;
        private String mcpEndpoint = "/mcp";
        private String sseEndpoint = "/sse";
        private String sourcePath;  // Track where this config came from

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public McpServerType getType() { return type; }
        public void setType(McpServerType type) { this.type = type; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }

        public Map<String, String> getEnv() { return env; }

        public String getWorkspaceDir() { return workspaceDir; }
        public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getMcpEndpoint() { return mcpEndpoint; }
        public void setMcpEndpoint(String mcpEndpoint) { this.mcpEndpoint = mcpEndpoint; }

        public String getSseEndpoint() { return sseEndpoint; }
        public void setSseEndpoint(String sseEndpoint) { this.sseEndpoint = sseEndpoint; }

        public String getSourcePath() { return sourcePath; }
        public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

        /**
         * Build the full command string for stdio servers
         */
        public String buildCommandString() {
            if (command == null) return null;
            StringBuilder sb = new StringBuilder(command);
            for (String arg : args) {
                sb.append(" ").append(arg.contains(" ") ? "\"" + arg + "\"" : arg);
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return "McpServerConfigEntry{" +
                    "name='" + name + '\'' +
                    ", type=" + type +
                    ", command='" + command + '\'' +
                    ", url='" + url + '\'' +
                    ", sourcePath='" + sourcePath + '\'' +
                    '}';
        }
    }
}
