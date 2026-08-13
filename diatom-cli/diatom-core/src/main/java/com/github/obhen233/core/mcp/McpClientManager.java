package com.github.obhen233.core.mcp;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.mcp.client.ExternalMcpClient;
import com.github.obhen233.core.mcp.client.HttpMcpClient;
import com.github.obhen233.core.mcp.server.BuildMcpServer;
import com.github.obhen233.core.mcp.server.CheckpointMcpServer;
import com.github.obhen233.core.mcp.server.FilesystemMcpServer;
import com.github.obhen233.core.mcp.server.SystemMcpServer;
import com.github.obhen233.core.tool.AuthorizedPathManager;
import com.github.obhen233.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class McpClientManager {
    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);
    private final Map<String, McpServer> serversMap = new ConcurrentHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    // Lazy initialization support for built-in servers
    private volatile boolean builtInServersInitialized = false;
    private final Object builtInServerLock = new Object();
    private String lazyWorkspaceDir;
    private AuthorizedPathManager lazyAuthManager;
    private boolean lazyAllowExternal;

    // TTL cache for getAllTools()
    private volatile Map<String, Tool> cachedTools;
    private volatile long lastToolsFetch;
    private static final long TOOLS_CACHE_TTL_MS = 5000;

    /**
     * Configure lazy initialization of built-in servers.
     * When set, built-in servers (filesystem, system) will be started on first access
     * rather than eagerly during construction, improving startup time.
     */
    public McpClientManager withLazyBuiltInServers(String workspaceDir, AuthorizedPathManager authManager, boolean allowExternal) {
        this.lazyWorkspaceDir = workspaceDir;
        this.lazyAuthManager = authManager;
        this.lazyAllowExternal = allowExternal;
        return this;
    }

    /**
     * Single accessor for the servers map.
     * Triggers lazy initialization of built-in servers on first read access,
     * avoiding the need for ensureBuiltInServersInitialized() calls in every method.
     * Methods that only write to the map (registerServer, connectServer) should
     * use serversMap directly to avoid circular lazy init.
     */
    private Map<String, McpServer> servers() {
        if (!builtInServersInitialized && lazyWorkspaceDir != null) {
            synchronized (builtInServerLock) {
                if (!builtInServersInitialized && lazyWorkspaceDir != null) {
                    startBuiltInServer("filesystem", lazyWorkspaceDir, lazyAuthManager, lazyAllowExternal);
                    startBuiltInServer("system", lazyWorkspaceDir);
                    builtInServersInitialized = true;
                    lazyWorkspaceDir = null;
                    lazyAuthManager = null;
                }
            }
        }
        return serversMap;
    }

    public void registerServer(McpServer server) {
        serversMap.put(server.getName(), server);
        cachedTools = null;
        lastToolsFetch = 0;
        logger.debug("Registered MCP server: {}", server.getName());
    }

    public void connectServer(String name, String command, String workspaceDir) {
        try {
            McpServer server = new ExternalMcpClient(command, workspaceDir);
            serversMap.put(name, server);
            cachedTools = null;
            lastToolsFetch = 0;
            logger.info("Connected to MCP server: {}", name);
        } catch (Exception e) {
            logger.error("Failed to connect to MCP server: {}", name, e);
        }
    }

    /**
     * Load and connect MCP servers from config files in ~/.diatom/mcpservers/
     * This is called during app initialization to auto-connect configured MCP servers.
     *
     * @param defaultWorkspaceDir Default workspace directory
     */
    public void loadAndConnectFromConfig(String defaultWorkspaceDir) {
        loadAndConnectFromConfig(null, defaultWorkspaceDir);
    }

    /**
     * Load and connect MCP servers from config files with project-specific config.
     *
     * @param projectDir Project directory (for project-level config), can be null
     * @param defaultWorkspaceDir Default workspace directory
     */
    public void loadAndConnectFromConfig(Path projectDir, String defaultWorkspaceDir) {
        try {
            McpServersConfig config = new McpServersConfig(null, projectDir);

            java.util.Map<String, McpServersConfig.McpServerConfigEntry> entries = config.loadConfigs();
            for (Map.Entry<String, McpServersConfig.McpServerConfigEntry> entry : entries.entrySet()) {
                connectFromConfig(entry.getValue(), defaultWorkspaceDir);
            }

            logger.info("Loaded and connected {} MCP server(s) from config", entries.size());
        } catch (Exception e) {
            logger.error("Failed to load MCP servers from config: {}", e.getMessage());
        }
    }

    /**
     * Connect a single MCP server from config entry
     */
    private void connectFromConfig(McpServersConfig.McpServerConfigEntry entry, String defaultWorkspaceDir) {
        String workspace = entry.getWorkspaceDir() != null ? entry.getWorkspaceDir() : defaultWorkspaceDir;

        try {
            if (entry.getType() == McpServersConfig.McpServerType.STDIO) {
                // Connect stdio-based MCP server
                String command = entry.buildCommandString();
                connectServer(entry.getName(), command, workspace);
            } else if (entry.getType() == McpServersConfig.McpServerType.HTTP) {
                // Connect HTTP/SSE-based MCP server
                connectHttpServer(entry.getName(), entry.getUrl(), entry.getMcpEndpoint(), entry.getSseEndpoint());
            }
        } catch (Exception e) {
            logger.error("Failed to connect MCP server '{}': {}", entry.getName(), e.getMessage());
        }
    }

    /**
     * Get loaded MCP server configurations for debugging/listing
     */
    public java.util.List<String> getLoadedServerNames() {
        return new java.util.ArrayList<>(servers().keySet());
    }

    public void startBuiltInServer(String name, String workspaceDir) {
        startBuiltInServer(name, workspaceDir, null, false);
    }

    public void startBuiltInServer(String name, String workspaceDir, AuthorizedPathManager authManager) {
        startBuiltInServer(name, workspaceDir, authManager, false);
    }

    public void startBuiltInServer(String name, String workspaceDir, AuthorizedPathManager authManager, boolean allowExternal) {
        try {
            if ("filesystem".equals(name)) {
                FilesystemMcpServer server = new FilesystemMcpServer(workspaceDir, authManager, allowExternal);
                registerServer(server);
                logger.info("Started built-in Filesystem MCP server (allowExternal={})", allowExternal);
            } else if ("system".equals(name)) {
                SystemMcpServer server = new SystemMcpServer();
                registerServer(server);
                logger.info("Started built-in System MCP server (HIGH RISK)");
            } else if ("checkpoint".equals(name)) {
                // Checkpoint server needs DatabaseManager - this will be initialized separately
                logger.info("Checkpoint MCP server will be initialized with DatabaseManager");
            } else if ("build".equals(name)) {
                BuildMcpServer server = new BuildMcpServer(workspaceDir);
                registerServer(server);
                logger.info("Started Build MCP server for Maven/Gradle projects");
            }
        } catch (Exception e) {
            logger.error("Failed to start built-in MCP server: {}", name, e);
        }
    }

    /**
     * Start checkpoint server with database manager
     */
    public void startCheckpointServer(DatabaseManager db) {
        try {
            CheckpointMcpServer server = new CheckpointMcpServer(db);
            registerServer(server);
            logger.info("Started Checkpoint MCP server with database");
        } catch (Exception e) {
            logger.error("Failed to start checkpoint MCP server", e);
        }
    }

    public void disconnectServer(String name) {
        McpServer server = servers().remove(name);
        Process process = processes.remove(name);
        if (process != null && process.isAlive()) {
            process.destroy();
        }
        if (server != null) {
            cachedTools = null;
            lastToolsFetch = 0;
            logger.info("Disconnected from MCP server: {}", name);
        }
    }

    /**
     * Get a connected MCP server by name
     */
    public McpServer getServer(String name) {
        return servers().get(name);
    }

    public Map<String, Tool> discoverTools(String serverName) {
        McpServer server = servers().get(serverName);
        if (server == null) {
            logger.warn("MCP server not found: {}", serverName);
            return null;
        }
        return server.listTools();
    }

    public Map<String, McpServer.Resource> discoverResources(String serverName) {
        McpServer server = servers().get(serverName);
        if (server == null) {
            logger.warn("MCP server not found: {}", serverName);
            return null;
        }
        return server.listResources();
    }

    public String readResource(String serverName, String uri) {
        McpServer server = servers().get(serverName);
        if (server == null) {
            return "Error: MCP server not found: " + serverName;
        }
        return server.readResource(uri);
    }

    public Map<String, McpServer.Prompt> discoverPrompts(String serverName) {
        McpServer server = servers().get(serverName);
        if (server == null) {
            logger.warn("MCP server not found: {}", serverName);
            return null;
        }
        return server.listPrompts();
    }

    public String callTool(String serverName, String toolName, String args) {
        McpServer server = servers().get(serverName);
        if (server == null) {
            return "Error: MCP server not found: " + serverName;
        }
        return server.callTool(toolName, args);
    }

    public String getPrompt(String serverName, String promptName, Map<String, String> args) {
        McpServer server = servers().get(serverName);
        if (server == null) {
            return "Error: MCP server not found: " + serverName;
        }
        McpServer.PromptResult result = server.getPrompt(promptName, args);
        return result != null ? result.getMessagesJson() : null;
    }

    public Map<String, Tool> getAllTools() {
        if (cachedTools != null && System.currentTimeMillis() - lastToolsFetch < TOOLS_CACHE_TTL_MS) {
            return cachedTools;
        }
        Map<String, Tool> allTools = new HashMap<>();
        for (McpServer server : servers().values()) {
            Map<String, Tool> tools = server.listTools();
            if (tools != null) {
                for (Map.Entry<String, Tool> entry : tools.entrySet()) {
                    Tool tool = entry.getValue();
                    // Set readableName if not already set
                    if (tool.getReadableName() == null || tool.getReadableName().isEmpty()) {
                        tool.setReadableName(toolNameToReadableName(entry.getKey()));
                    }
                    allTools.put(entry.getKey(), tool);
                }
            }
        }
        cachedTools = allTools;
        lastToolsFetch = System.currentTimeMillis();
        return allTools;
    }

    /**
     * Convert tool name to human-readable name.
     * Examples: read_file -> Read File, create_directory -> Create Directory
     */
    private String toolNameToReadableName(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return toolName;
        }
        // Replace underscores with spaces and capitalize each word
        String readable = toolName.replace("_", " ");
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : readable.toCharArray()) {
            if (capitalizeNext && Character.isLetter(c)) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    public Map<String, McpServer.Resource> getAllResources() {
        Map<String, McpServer.Resource> allResources = new HashMap<>();
        for (McpServer server : servers().values()) {
            Map<String, McpServer.Resource> resources = server.listResources();
            if (resources != null) {
                allResources.putAll(resources);
            }
        }
        return allResources;
    }

    /**
     * Find which server provides a tool and call it.
     * This enables routing to any MCP server (editor, filesystem, etc.)
     * without the caller needing to know which server owns which tool.
     */
    public String callToolByName(String toolName, String args) {
        for (Map.Entry<String, McpServer> entry : servers().entrySet()) {
            Map<String, Tool> tools = entry.getValue().listTools();
            if (tools != null && tools.containsKey(toolName)) {
                return entry.getValue().callTool(toolName, args);
            }
        }
        return "Error: Tool not found in any MCP server: " + toolName;
    }

    /**
     * Connect to an HTTP/SSE based MCP server
     *
     * @param name Server name for identification
     * @param baseUrl Base URL of the MCP server (e.g., http://localhost:8080)
     * @param mcpEndpoint MCP JSON-RPC endpoint (default: /mcp)
     * @param sseEndpoint SSE endpoint for server-initiated messages (default: /sse)
     */
    public void connectHttpServer(String name, String baseUrl, String mcpEndpoint, String sseEndpoint) {
        try {
            HttpMcpClient server = new HttpMcpClient(name, baseUrl, mcpEndpoint, sseEndpoint);
            serversMap.put(name, server);
            logger.info("Connected to HTTP/SSE MCP server: {} at {}", name, baseUrl);
        } catch (Exception e) {
            logger.error("Failed to connect to HTTP/SSE MCP server: {}", name, e);
        }
    }

    /**
     * Connect to an HTTP/SSE based MCP server with default endpoints
     */
    public void connectHttpServer(String name, String baseUrl) {
        connectHttpServer(name, baseUrl, "/mcp", "/sse");
    }

}