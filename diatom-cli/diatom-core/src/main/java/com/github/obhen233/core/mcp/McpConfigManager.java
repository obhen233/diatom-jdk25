package com.github.obhen233.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP Configuration Manager.
 * Handles loading, saving, and hot-reloading of MCP server configurations.
 *
 * Configuration directory structure:
 * - Global: ~/.diatom/mcpservers/
 * - Project: {project}/.diatom/mcpservers/
 *
 * Each file can contain single MCP or multiple MCPs (legacy format).
 */
public class McpConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(McpConfigManager.class);

    private static final String MCP_SERVERS_DIR = "mcpservers";
    private static final String CONFIG_DIR_NAME = ".diatom";

    private final Path globalDir;
    private final Path projectDir;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public McpConfigManager() {
        this(null);
    }

    public McpConfigManager(Path projectDir) {
        this.globalDir = Paths.get(System.getProperty("user.home"), CONFIG_DIR_NAME, MCP_SERVERS_DIR);
        this.projectDir = projectDir;
        this.mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        try {
            ensureDirExists(globalDir);
            if (projectDir != null) {
                Path projMcpsDir = projectDir.resolve(CONFIG_DIR_NAME).resolve(MCP_SERVERS_DIR);
                ensureDirExists(projMcpsDir);
            }
        } catch (IOException e) {
            logger.error("Failed to initialize MCP config directories", e);
        }
    }

    private void ensureDirExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            logger.info("Created MCP servers config directory: {}", dir);
        }
    }

    // ==================== Loading ====================

    /**
     * Load all MCP server configs from both global and project directories.
     * Project local configs override global configs with the same name.
     *
     * @return Map of server name -> config
     */
    public Map<String, McpServerConfig> loadConfigs() {
        return loadConfigs(true, true);
    }

    /**
     * Load MCP server configs with options for global/project.
     *
     * @param includeGlobal Include global configs
     * @param includeProject Include project configs
     * @return Map of server name -> config
     */
    public Map<String, McpServerConfig> loadConfigs(boolean includeGlobal, boolean includeProject) {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        Set<String> seenServers = new HashSet<>();

        // Load in priority order: global (lowest) -> project (highest)
        List<Path> dirs = getConfigDirsInPriorityOrder(includeGlobal, includeProject);

        for (Path dir : dirs) {
            if (dir == null || !Files.exists(dir)) {
                continue;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path path : stream) {
                    try {
                        Map<String, McpServerConfig> loaded = loadConfigFile(path);
                        for (Map.Entry<String, McpServerConfig> entry : loaded.entrySet()) {
                            String name = entry.getKey();
                            if (!seenServers.contains(name)) {
                                result.put(name, entry.getValue());
                                logger.debug("Loaded MCP server '{}' from: {}", name, path);
                            }
                            seenServers.add(name);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to load MCP config from {}: {}", path, e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to read MCP servers directory {}: {}", dir, e.getMessage());
            }
        }

        logger.info("Loaded {} MCP server config(s)", result.size());
        return result;
    }

    /**
     * Load configs from a single directory.
     */
    public Map<String, McpServerConfig> loadConfigsFromDir(Path dir) {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();

        if (dir == null || !Files.exists(dir)) {
            return result;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path path : stream) {
                try {
                    result.putAll(loadConfigFile(path));
                } catch (Exception e) {
                    logger.error("Failed to load MCP config from {}: {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to read MCP servers directory {}: {}", dir, e.getMessage());
        }

        return result;
    }

    /**
     * Load configs from a single file.
     * Supports both single-MCP and multi-MCP formats.
     */
    private Map<String, McpServerConfig> loadConfigFile(Path path) {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();

        try {
            String content = new String(Files.readAllBytes(path));
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(content);

            if (root.has("mcpServers")) {
                // Multi-MCP format
                configs.putAll(McpServerConfig.fromMultiFile(path));
            } else {
                // Single-MCP format
                McpServerConfig config = McpServerConfig.fromSingleFile(path);
                configs.put(config.getName(), config);
            }
        } catch (IOException e) {
            logger.error("Failed to read config file {}: {}", path, e.getMessage());
        }

        return configs;
    }

    /**
     * Get config directories in priority order (lowest to highest)
     */
    private List<Path> getConfigDirsInPriorityOrder(boolean includeGlobal, boolean includeProject) {
        List<Path> dirs = new ArrayList<>();

        if (includeGlobal) {
            dirs.add(globalDir);
        }
        if (includeProject && projectDir != null) {
            Path projDir = projectDir.resolve(CONFIG_DIR_NAME).resolve(MCP_SERVERS_DIR);
            if (Files.exists(projDir)) {
                dirs.add(projDir);
            }
        }

        return dirs;
    }

    // ==================== CRUD Operations ====================

    /**
     * Add a new MCP server configuration.
     * Saves to project local if projectDir is set, otherwise global.
     *
     * @param config The MCP server configuration
     * @param isGlobal Save to global directory instead of project local
     */
    public void addConfig(McpServerConfig config, boolean isGlobal) throws IOException {
        Path targetDir = isGlobal ? globalDir : getProjectConfigDir();
        Path targetFile = targetDir.resolve(config.getName() + ".json");

        // If file already exists, overwrite
        String json = mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(config);
        Files.write(targetFile, json.getBytes());

        config.setSourceFile(targetFile);
        config.setGlobal(isGlobal);

        logger.info("Added MCP server '{}' to: {}", config.getName(), targetFile);
    }

    /**
     * Remove an MCP server configuration.
     * First disconnects if connected, then deletes the config file.
     *
     * @param name Server name
     * @param isGlobal Remove from global directory
     * @return true if removed, false if not found
     */
    public boolean removeConfig(String name, boolean isGlobal) throws IOException {
        Path searchDir = isGlobal ? globalDir : getProjectConfigDir();

        if (!Files.exists(searchDir)) {
            return false;
        }

        // Find and delete the config file
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(searchDir, "*.json")) {
            for (Path file : stream) {
                Map<String, McpServerConfig> configs = loadConfigFile(file);
                if (configs.containsKey(name)) {
                    Files.delete(file);
                    logger.info("Removed MCP server '{}' from: {}", name, file);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get a specific MCP server configuration.
     *
     * @param name Server name
     * @return The config, or null if not found
     */
    public McpServerConfig getConfig(String name) {
        Map<String, McpServerConfig> configs = loadConfigs();
        return configs.get(name);
    }

    /**
     * Disable an MCP server (sets enabled=false in config).
     *
     * @param name Server name
     * @param isGlobal Operate on global directory
     * @return true if disabled, false if not found
     */
    public boolean disableConfig(String name, boolean isGlobal) throws IOException {
        McpServerConfig config = getConfigInDir(name, isGlobal);
        if (config == null) {
            return false;
        }
        config.setEnabled(false);
        saveConfig(config, isGlobal);
        logger.info("Disabled MCP server '{}'", name);
        return true;
    }

    /**
     * Enable an MCP server (sets enabled=true in config).
     *
     * @param name Server name
     * @param isGlobal Operate on global directory
     * @return true if enabled, false if not found
     */
    public boolean enableConfig(String name, boolean isGlobal) throws IOException {
        McpServerConfig config = getConfigInDir(name, isGlobal);
        if (config == null) {
            return false;
        }
        config.setEnabled(true);
        saveConfig(config, isGlobal);
        logger.info("Enabled MCP server '{}'", name);
        return true;
    }

    /**
     * Get config in specific directory (global or project).
     */
    private McpServerConfig getConfigInDir(String name, boolean isGlobal) {
        Path searchDir = isGlobal ? globalDir : getProjectConfigDir();
        if (!Files.exists(searchDir)) {
            return null;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(searchDir, "*.json")) {
            for (Path file : stream) {
                Map<String, McpServerConfig> configs = loadConfigFile(file);
                if (configs.containsKey(name)) {
                    McpServerConfig config = configs.get(name);
                    config.setSourceFile(file);
                    config.setGlobal(isGlobal);
                    return config;
                }
            }
        } catch (IOException e) {
            logger.error("Error searching for config: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Save config to file.
     */
    private void saveConfig(McpServerConfig config, boolean isGlobal) throws IOException {
        Path targetDir = isGlobal ? globalDir : getProjectConfigDir();
        String json = mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(config);
        Files.write(config.getSourceFile(), json.getBytes());
    }

    // ==================== Listing ====================

    /**
     * List all MCP servers with their status.
     *
     * @param connectionTracker Connection tracker to get status
     * @return List of MCP server info
     */
    public List<McpServerInfo> listServers(McpConnectionTracker connectionTracker) {
        return listServers(connectionTracker, true, true);
    }

    /**
     * List MCP servers with options.
     *
     * @param connectionTracker Connection tracker to get status
     * @param includeGlobal Include global configs
     * @param includeProject Include project configs
     * @return List of MCP server info
     */
    public List<McpServerInfo> listServers(McpConnectionTracker connectionTracker,
                                           boolean includeGlobal, boolean includeProject) {
        List<McpServerInfo> result = new ArrayList<>();
        Map<String, McpServerConfig> configs = loadConfigs(includeGlobal, includeProject);
        Set<String> connectedNames = connectionTracker.getConnectedNames();
        Map<String, String> errors = connectionTracker.getServerErrors();

        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            String name = entry.getKey();
            McpServerConfig config = entry.getValue();

            McpServerInfo info = new McpServerInfo();
            info.name = name;
            info.type = config.getType().name();
            info.description = config.getDescription();
            info.source = config.isGlobal() ? "global" : "project";
            info.enabled = config.isEnabled();
            info.connected = connectedNames.contains(name);

            if (!info.connected && errors.containsKey(name)) {
                info.error = errors.get(name);
            }

            result.add(info);
        }

        return result;
    }

    /**
     * MCP Server info for listing
     */
    public static class McpServerInfo {
        public String name;
        public String type;
        public String description;
        public String source;
        public boolean enabled;
        public boolean connected;
        public String error;
    }

    // ==================== Helper Methods ====================

    private Path getProjectConfigDir() {
        if (projectDir == null) {
            return globalDir;
        }
        return projectDir.resolve(CONFIG_DIR_NAME).resolve(MCP_SERVERS_DIR);
    }

    public Path getGlobalDir() {
        return globalDir;
    }

    public Path getProjectDir() {
        return projectDir != null ? projectDir.resolve(CONFIG_DIR_NAME).resolve(MCP_SERVERS_DIR) : null;
    }
}
