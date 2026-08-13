package com.github.obhen233.cli;

import com.github.obhen233.cli.TerminalUI;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.mcp.*;
import com.github.obhen233.util.PathUtils;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * MCP command handler for CLI.
 * Implements CoreCommandProvider for SPI-based command discovery.
 *
 * Commands:
 * - mcp list [--global]           : List all MCP servers
 * - mcp add [--global]            : Add a new MCP server (interactive)
 * - mcp remove <name> [--global]  : Remove an MCP server
 * - mcp disable <name> [--global]  : Disable an MCP server
 * - mcp enable <name> [--global]   : Enable an MCP server
 * - mcp reload [--global]          : Hot reload (new servers only)
 * - mcp reload --all [--global]    : Hot reload (all servers)
 */
public class McpCommand implements CoreCommandProvider, TerminalUI.AgentAware {
    private static final Logger logger = LoggerFactory.getLogger(McpCommand.class);

    private static final int CONNECTION_TIMEOUT_SECONDS = 10;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_BASE_MS = 1000; // 1 second base delay for exponential backoff

    // Instance field for agent (set via init())
    private ReActAgent agent;

    /**
     * Handle MCP command.
     *
     * @param input Command input (after "mcp " prefix)
     * @param agent ReActAgent instance
     * @return true if handled, false otherwise
     * @deprecated Use instance execute() method with CommandOutput instead
     */
    @Deprecated
    public static boolean handle(String input, ReActAgent agent) {
        if (agent == null) {
            System.out.println(McpColor.error("Agent not initialized"));
            return true;
        }

        String[] parts = input.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            printHelp();
            return true;
        }

        String cmd = parts[0].toLowerCase();
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

        try {
            switch (cmd) {
                case "list":
                    return handleList(args, agent);
                case "add":
                    return handleAdd(args, agent);
                case "remove":
                case "delete":
                case "rm":
                    return handleRemove(args, agent);
                case "disable":
                    return handleDisable(args, agent);
                case "enable":
                    return handleEnable(args, agent);
                case "reload":
                    return handleReload(args, agent);
                case "help":
                case "--help":
                case "-h":
                    printHelp();
                    return true;
                default:
                    System.out.println(McpColor.error("Unknown MCP command: " + cmd));
                    printHelp();
                    return true;
            }
        } catch (Exception e) {
            System.out.println(McpColor.error("Error: " + e.getMessage()));
            logger.error("MCP command error", e);
            return true;
        }
    }

    // ==================== CoreCommandProvider Implementation ====================

    @Override
    public String getCommandName() {
        return "mcp";
    }

    @Override
    public String getDescription() {
        return "{{cli.mcp.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.mcp.help}}";
    }

    @Override
    public void init(ReActAgent agent) {
        this.agent = agent;
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (agent == null) {
            return "ERROR {{mcp.error.agent_not_initialized}}";
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return getHelpText();
        }

        String cmd = parts[0].toLowerCase();
        String[] argParts = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

        try {
            switch (cmd) {
                case "list":
                    return handleList(argParts, agent, output);
                case "add":
                    return handleAdd(argParts, agent, output);
                case "remove":
                case "delete":
                case "rm":
                    return handleRemove(argParts, agent, output);
                case "disable":
                    return handleDisable(argParts, agent, output);
                case "enable":
                    return handleEnable(argParts, agent, output);
                case "reload":
                    return handleReload(argParts, agent, output);
                case "help":
                case "--help":
                case "-h":
                    return getHelpText();
                default:
                    return "ERROR {{mcp.error.unknown_command}} " + cmd + "\n" + getHelpText();
            }
        } catch (Exception e) {
            return "ERROR {{mcp.error.execution}} " + e.getMessage();
        }
    }

    private String getHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("MCP Commands\n");
        sb.append(McpColor.repeat("-", 40) + "\n");
        sb.append("  mcp list [--global]           List all MCP servers\n");
        sb.append("  mcp add [--global] [--force]   Add a new MCP server (interactive)\n");
        sb.append("  mcp remove <name> [--global]    Remove an MCP server\n");
        sb.append("  mcp disable <name> [--global]  Disable an MCP server\n");
        sb.append("  mcp enable <name> [--global]   Enable an MCP server\n");
        sb.append("  mcp reload [--global]          Hot reload (new servers only)\n");
        sb.append("  mcp reload --all [--global]   Hot reload (all servers)\n");
        sb.append("\n");
        sb.append("Options:\n");
        sb.append("  --global  Use global scope instead of project local\n");
        sb.append("  --force  Force save config even if connection fails (for add)\n");
        sb.append("\n");
        sb.append("Examples:\n");
        sb.append("  mcp list                    # List project MCP servers\n");
        sb.append("  mcp add                     # Add MCP server to project\n");
        sb.append("  mcp add --force            # Add server without testing connection\n");
        sb.append("  mcp disable github          # Disable 'github' server\n");
        sb.append("  mcp enable github           # Enable 'github' server\n");
        sb.append("  mcp reload                  # Connect new servers\n");
        sb.append("  mcp reload --all            # Reconnect all servers");
        return sb.toString();
    }

    // ==================== list command ====================

    private static boolean handleList(String[] args, ReActAgent agent) {
        boolean global = hasFlag(args, "--global");

        McpConfigManager configManager = getConfigManager(agent);
        McpConnectionTracker tracker = getConnectionTracker(agent);

        List<McpConfigManager.McpServerInfo> servers = configManager.listServers(tracker, !global, !global);

        if (servers.isEmpty()) {
            System.out.println(McpColor.info("No MCP servers configured"));
            System.out.println(McpColor.blue("  Tip: Run 'mcp add' to add an MCP server"));
            return true;
        }

        System.out.println("\nMCP Servers:");
        System.out.println(McpColor.repeat("─", 60));

        for (McpConfigManager.McpServerInfo info : servers) {
            String typeTag = "[" + info.type + "]";
            String sourceTag = info.source.equals("global") ? "[global]" : "[project]";

            // Build status string with enabled status
            String statusStr;
            if (!info.enabled) {
                statusStr = McpColor.dim("[disabled]");
            } else if (info.connected) {
                statusStr = McpColor.cyan("connected");
            } else if (info.error != null) {
                statusStr = McpColor.yellow("error");
            } else {
                statusStr = McpColor.dim("disconnected");
            }

            System.out.println("  " + info.name + " " +
                    McpColor.dim(typeTag) + " " +
                    McpColor.dim(sourceTag) + " " +
                    statusStr);

            if (info.description != null && !info.description.isEmpty()) {
                System.out.println("    " + McpColor.dim(info.description));
            }

            // Show error if any
            if (info.error != null) {
                System.out.println("    " + McpColor.red("Error: " + info.error));
            }

            // Show disabled notice
            if (!info.enabled) {
                System.out.println("    " + McpColor.yellow("Server is disabled, use 'mcp enable " + info.name + "' to enable"));
            }
        }

        System.out.println(McpColor.repeat("─", 60));

        // Count stats
        long connectedCount = servers.stream().filter(s -> s.connected).count();
        long disabledCount = servers.stream().filter(s -> !s.enabled).count();
        long errorCount = servers.stream().filter(s -> s.error != null && s.enabled).count();

        if (errorCount > 0) {
            System.out.println("\n" + McpColor.yellow("⚠ " + errorCount + " server(s) have errors"));
            System.out.println(McpColor.blue("  Tip: Run 'mcp reload' to retry"));
        }

        if (disabledCount > 0) {
            System.out.println("\n" + McpColor.dim("ℹ " + disabledCount + " server(s) disabled"));
            System.out.println(McpColor.blue("  Tip: Run 'mcp enable <name>' to enable a server"));
        }

        return true;
    }

    private String handleList(String[] args, ReActAgent agent, CommandOutput output) {
        boolean global = hasFlag(args, "--global");

        McpConfigManager configManager = getConfigManager(agent);
        McpConnectionTracker tracker = getConnectionTracker(agent);

        List<McpConfigManager.McpServerInfo> servers = configManager.listServers(tracker, !global, !global);

        StringBuilder sb = new StringBuilder();

        if (servers.isEmpty()) {
            sb.append("INFO {{mcp.info.no_servers_configured}}\n");
            sb.append("INFO {{mcp.info.tip_add_server}}");
            return sb.toString();
        }

        sb.append("\nMCP Servers:\n");
        sb.append(McpColor.repeat("-", 60) + "\n");

        for (McpConfigManager.McpServerInfo info : servers) {
            String typeTag = "[" + info.type + "]";
            String sourceTag = info.source.equals("global") ? "[global]" : "[project]";

            // Build status string with enabled status
            String statusStr;
            if (!info.enabled) {
                statusStr = McpColor.dim("[disabled]");
            } else if (info.connected) {
                statusStr = McpColor.cyan("connected");
            } else if (info.error != null) {
                statusStr = McpColor.yellow("error");
            } else {
                statusStr = McpColor.dim("disconnected");
            }

            sb.append("  " + info.name + " " +
                    McpColor.dim(typeTag) + " " +
                    McpColor.dim(sourceTag) + " " +
                    statusStr + "\n");

            if (info.description != null && !info.description.isEmpty()) {
                sb.append("    " + McpColor.dim(info.description) + "\n");
            }

            // Show error if any
            if (info.error != null) {
                sb.append("    Error: " + info.error + "\n");
            }

            // Show disabled notice
            if (!info.enabled) {
                sb.append("    Server is disabled, use 'mcp enable " + info.name + "' to enable\n");
            }
        }

        sb.append(McpColor.repeat("-", 60) + "\n");

        // Count stats
        long connectedCount = servers.stream().filter(s -> s.connected).count();
        long disabledCount = servers.stream().filter(s -> !s.enabled).count();
        long errorCount = servers.stream().filter(s -> s.error != null && s.enabled).count();

        if (errorCount > 0) {
            sb.append("\nWARNING {{mcp.warn.servers_have_errors}} " + errorCount + "\n");
            sb.append("INFO {{mcp.info.tip_reload}}\n");
        }

        if (disabledCount > 0) {
            sb.append("\nINFO {{mcp.info.servers_disabled}} " + disabledCount + "\n");
            sb.append("INFO {{mcp.info.tip_enable}}\n");
        }

        return sb.toString();
    }

    // ==================== add command ====================

    private static boolean handleAdd(String[] args, ReActAgent agent) throws IOException {
        boolean global = hasFlag(args, "--global");
        boolean force = hasFlag(args, "--force");

        System.out.println("\n" + McpColor.bold("Add MCP Server") + " (" + (global ? "global" : "project") + ")");
        System.out.println(McpColor.repeat("─", 40));

        Scanner scanner = new Scanner(System.in);

        // Server name
        System.out.print("? Server name: ");
        String name = scanner.nextLine().trim();
        if (name.isEmpty()) {
            System.out.println(McpColor.error("Server name is required"));
            return true;
        }
        if (!isValidName(name)) {
            System.out.println(McpColor.error("Server name must be alphanumeric (underscores/hyphens allowed)"));
            return true;
        }

        // Check if server already exists
        McpConfigManager configManager = getConfigManager(agent);
        if (configManager.getConfig(name) != null) {
            System.out.println(McpColor.error("MCP server '" + name + "' already exists"));
            System.out.println(McpColor.blue("  Use 'mcp remove " + name + "' first or choose a different name"));
            return true;
        }

        // Type
        System.out.println("? Type:");
        System.out.println("  (1) STDIO  - Command-based server (e.g., npx, java -jar)");
        System.out.println("  (2) HTTP   - HTTP/SSE based server");
        System.out.print("  Enter choice (1/2): ");
        String typeInput = scanner.nextLine().trim();
        boolean isHttp = typeInput.equals("2");

        McpServerConfig config = new McpServerConfig();
        config.setName(name);
        config.setType(isHttp ? McpServerConfig.Type.HTTP : McpServerConfig.Type.STDIO);

        if (isHttp) {
            // HTTP server
            System.out.print("? URL (e.g., http://localhost:8080): ");
            String url = scanner.nextLine().trim();
            if (url.isEmpty()) {
                System.out.println(McpColor.error("URL is required"));
                return true;
            }
            config.setUrl(url);

            System.out.print("? MCP Endpoint (default: /mcp): ");
            String mcpEndpoint = scanner.nextLine().trim();
            if (!mcpEndpoint.isEmpty()) {
                config.setMcpEndpoint(mcpEndpoint);
            }

            System.out.print("? SSE Endpoint (default: /sse): ");
            String sseEndpoint = scanner.nextLine().trim();
            if (!sseEndpoint.isEmpty()) {
                config.setSseEndpoint(sseEndpoint);
            }
        } else {
            // STDIO server
            System.out.print("? Command (e.g., npx, java): ");
            String command = scanner.nextLine().trim();
            if (command.isEmpty()) {
                System.out.println(McpColor.error("Command is required"));
                return true;
            }
            config.setCommand(command);

            System.out.print("? Args (space-separated, e.g., -y @anthropic/mcp-server-filesystem /tmp): ");
            String argsLine = scanner.nextLine().trim();
            if (!argsLine.isEmpty()) {
                List<String> argList = Arrays.asList(argsLine.split("\\s+"));
                config.setArgs(new ArrayList<>(argList));
            }

            // Env vars
            Map<String, String> env = new HashMap<>();
            System.out.println(McpColor.dim("? Env vars (KEY=value, empty to finish)"));
            while (true) {
                System.out.print("  ");
                String envLine = scanner.nextLine().trim();
                if (envLine.isEmpty()) {
                    break;
                }
                int eqIndex = envLine.indexOf('=');
                if (eqIndex > 0) {
                    String key = envLine.substring(0, eqIndex).trim();
                    String value = envLine.substring(eqIndex + 1).trim();
                    env.put(key, value);
                } else {
                    System.out.println(McpColor.yellow("  Invalid format, use KEY=value"));
                }
            }
            if (!env.isEmpty()) {
                config.setEnv(env);
            }

            System.out.print("? Workspace dir (optional, Enter to skip): ");
            String workspaceDir = scanner.nextLine().trim();
            if (!workspaceDir.isEmpty()) {
                config.setWorkspaceDir(workspaceDir);
            }
        }

        // Description (optional)
        System.out.print("? Description (optional): ");
        String description = scanner.nextLine().trim();
        if (!description.isEmpty()) {
            config.setDescription(description);
        }

        // Try to connect first, before saving config
        System.out.println("\n" + McpColor.info("Testing connection (with retry)..."));
        boolean connectionSuccess = false;
        String connectionError = null;

        try {
            McpClientManager mcpClientManager = getMcpClientManager(agent);
            if (mcpClientManager != null) {
                McpConnectionTracker tracker = getConnectionTracker(agent);

                ConnectionResult result = connectWithRetry(name, config, mcpClientManager);
                connectionSuccess = result.success;
                connectionError = result.error;

                if (connectionSuccess) {
                    // Get description from server if not provided
                    if (description == null || description.isEmpty()) {
                        McpServer server = mcpClientManager.getServer(name);
                        if (server != null && server.getDescription() != null) {
                            config.setDescription(server.getDescription());
                        }
                    }
                    tracker.markConnected(name, mcpClientManager.getServer(name));
                }
            }
        } catch (Exception e) {
            connectionError = e.getMessage();
        }

        if (connectionSuccess) {
            // Connection succeeded - save config
            System.out.println(McpColor.success("Connected successfully"));
            System.out.println("\n" + McpColor.info("Saving configuration..."));
            configManager.addConfig(config, global);
            Path configPath = global
                    ? configManager.getGlobalDir().resolve(name + ".json")
                    : configManager.getProjectDir().resolve(name + ".json");
            System.out.println(McpColor.success("Configuration saved to: " + configPath));
        } else {
            // Connection failed
            System.out.println(McpColor.error("Connection failed: " + connectionError));

            if (force) {
                // --force flag: save config anyway
                System.out.println("\n" + McpColor.warning("Saving configuration anyway (--force)..."));
                configManager.addConfig(config, global);
                Path configPath = global
                        ? configManager.getGlobalDir().resolve(name + ".json")
                        : configManager.getProjectDir().resolve(name + ".json");
                System.out.println(McpColor.success("Configuration saved to: " + configPath));
                System.out.println(McpColor.blue("  Run 'mcp reload' to retry connection"));
            } else {
                // Ask user
                System.out.println("\n" + McpColor.yellow("Do you want to save the configuration anyway? (y/N)"));
                System.out.print("  ");
                String answer = scanner.nextLine().trim().toLowerCase();
                if (answer.equals("y") || answer.equals("yes")) {
                    configManager.addConfig(config, global);
                    Path configPath = global
                            ? configManager.getGlobalDir().resolve(name + ".json")
                            : configManager.getProjectDir().resolve(name + ".json");
                    System.out.println(McpColor.success("Configuration saved to: " + configPath));
                    System.out.println(McpColor.blue("  Run 'mcp reload' to retry connection"));
                } else {
                    System.out.println(McpColor.info("Configuration not saved"));
                }
            }
        }

        return true;
    }

    // ==================== add command (output-aware) ====================
    // Note: handleAdd returns error as interactive mode is not supported in IDE context

    private String handleAdd(String[] args, ReActAgent agent, CommandOutput output) {
        return "ERROR {{mcp.error.interactive_mode_not_supported}}";
    }

    // ==================== remove command ====================

    private static boolean handleRemove(String[] args, ReActAgent agent) throws IOException {
        boolean global = hasFlag(args, "--global");

        // Get server name (first non-flag argument)
        String name = null;
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                name = arg;
                break;
            }
        }

        if (name == null || name.isEmpty()) {
            System.out.println(McpColor.error("Server name is required"));
            System.out.println("Usage: mcp remove <name> [--global]");
            return true;
        }

        // Disconnect first
        McpClientManager mcpClientManager = getMcpClientManager(agent);
        if (mcpClientManager != null) {
            System.out.println(McpColor.info("Disconnecting '" + name + "'..."));
            mcpClientManager.disconnectServer(name);
            System.out.println(McpColor.success("Disconnected"));
        }

        // Remove config
        McpConfigManager configManager = getConfigManager(agent);
        System.out.println(McpColor.info("Removing configuration..."));

        if (configManager.removeConfig(name, global)) {
            System.out.println(McpColor.success("Configuration removed"));
        } else {
            System.out.println(McpColor.warning("Configuration not found (may be in " +
                    (global ? "project" : "global") + " scope)"));
        }

        return true;
    }

    private String handleRemove(String[] args, ReActAgent agent, CommandOutput output) throws IOException {
        boolean global = hasFlag(args, "--global");

        // Get server name (first non-flag argument)
        String name = null;
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                name = arg;
                break;
            }
        }

        if (name == null || name.isEmpty()) {
            return "ERROR {{mcp.error.server_name_required}}\nUsage: mcp remove <name> [--global]";
        }

        StringBuilder sb = new StringBuilder();

        // Disconnect first
        McpClientManager mcpClientManager = getMcpClientManager(agent);
        if (mcpClientManager != null) {
            sb.append("INFO {{mcp.info.disconnecting}} '" + name + "'...\n");
            mcpClientManager.disconnectServer(name);
            sb.append("SUCCESS {{mcp.success.disconnected}}\n");
        }

        // Remove config
        McpConfigManager configManager = getConfigManager(agent);
        sb.append("INFO {{mcp.info.removing_configuration}}\n");

        if (configManager.removeConfig(name, global)) {
            sb.append("SUCCESS {{mcp.success.configuration_removed}}\n");
        } else {
            sb.append("WARNING {{mcp.warn.configuration_not_found}} (may be in " +
                    (global ? "project" : "global") + " scope)\n");
        }

        return sb.toString();
    }

    // ==================== disable command ====================

    private static boolean handleDisable(String[] args, ReActAgent agent) throws IOException {
        boolean global = hasFlag(args, "--global");

        // Get server name (first non-flag argument)
        String name = null;
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                name = arg;
                break;
            }
        }

        if (name == null || name.isEmpty()) {
            System.out.println(McpColor.error("Server name is required"));
            System.out.println("Usage: mcp disable <name> [--global]");
            return true;
        }

        McpConfigManager configManager = getConfigManager(agent);
        McpClientManager mcpClientManager = getMcpClientManager(agent);

        // Check if server exists
        McpServerConfig config = configManager.getConfig(name);
        if (config == null) {
            System.out.println(McpColor.error("MCP server '" + name + "' not found"));
            return true;
        }

        // Disconnect if connected
        if (mcpClientManager != null) {
            System.out.println(McpColor.info("Disconnecting '" + name + "'..."));
            mcpClientManager.disconnectServer(name);
        }

        // Disable the config
        if (configManager.disableConfig(name, global)) {
            System.out.println(McpColor.success("MCP server '" + name + "' disabled"));
            System.out.println(McpColor.blue("  Use 'mcp enable " + name + "' to re-enable"));
        } else {
            System.out.println(McpColor.error("Failed to disable server"));
        }

        return true;
    }

    private String handleDisable(String[] args, ReActAgent agent, CommandOutput output) throws IOException {
        boolean global = hasFlag(args, "--global");

        // Get server name (first non-flag argument)
        String name = null;
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                name = arg;
                break;
            }
        }

        if (name == null || name.isEmpty()) {
            return "ERROR {{mcp.error.server_name_required}}\nUsage: mcp disable <name> [--global]";
        }

        StringBuilder sb = new StringBuilder();

        McpConfigManager configManager = getConfigManager(agent);
        McpClientManager mcpClientManager = getMcpClientManager(agent);

        // Check if server exists
        McpServerConfig config = configManager.getConfig(name);
        if (config == null) {
            return "ERROR {{mcp.error.server_not_found}} '" + name + "'";
        }

        // Disconnect if connected
        if (mcpClientManager != null) {
            sb.append("INFO {{mcp.info.disconnecting}} '" + name + "'...\n");
            mcpClientManager.disconnectServer(name);
        }

        // Disable the config
        if (configManager.disableConfig(name, global)) {
            sb.append("SUCCESS {{mcp.success.server_disabled}} '" + name + "'\n");
            sb.append("INFO {{mcp.info.tip_enable_server}} '" + name + "'");
        } else {
            sb.append("ERROR {{mcp.error.failed_to_disable_server}}\n");
        }

        return sb.toString();
    }

    // ==================== enable command ====================

    private static boolean handleEnable(String[] args, ReActAgent agent) throws IOException {
        boolean global = hasFlag(args, "--global");

        // Get server name (first non-flag argument)
        String name = null;
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                name = arg;
                break;
            }
        }

        if (name == null || name.isEmpty()) {
            System.out.println(McpColor.error("Server name is required"));
            System.out.println("Usage: mcp enable <name> [--global]");
            return true;
        }

        McpConfigManager configManager = getConfigManager(agent);

        // Enable the config
        if (configManager.enableConfig(name, global)) {
            System.out.println(McpColor.success("MCP server '" + name + "' enabled"));
            System.out.println(McpColor.blue("  Use 'mcp reload' to connect"));
        } else {
            System.out.println(McpColor.error("MCP server '" + name + "' not found"));
        }

        return true;
    }

    private String handleEnable(String[] args, ReActAgent agent, CommandOutput output) throws IOException {
        boolean global = hasFlag(args, "--global");

        // Get server name (first non-flag argument)
        String name = null;
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                name = arg;
                break;
            }
        }

        if (name == null || name.isEmpty()) {
            return "ERROR {{mcp.error.server_name_required}}\nUsage: mcp enable <name> [--global]";
        }

        StringBuilder sb = new StringBuilder();

        McpConfigManager configManager = getConfigManager(agent);

        // Enable the config
        if (configManager.enableConfig(name, global)) {
            sb.append("SUCCESS {{mcp.success.server_enabled}} '" + name + "'\n");
            sb.append("INFO {{mcp.info.tip_reload}}");
        } else {
            sb.append("ERROR {{mcp.error.server_not_found}} '" + name + "'");
        }

        return sb.toString();
    }

    // ==================== reload command ====================

    private static boolean handleReload(String[] args, ReActAgent agent) {
        boolean global = hasFlag(args, "--global");
        boolean reloadAll = hasFlag(args, "--all");

        McpConfigManager configManager = getConfigManager(agent);
        McpClientManager mcpClientManager = getMcpClientManager(agent);
        McpConnectionTracker tracker = getConnectionTracker(agent);

        if (reloadAll) {
            return handleReloadAll(configManager, mcpClientManager, tracker, global);
        } else {
            return handleReloadIncremental(configManager, mcpClientManager, tracker, global);
        }
    }

    private static boolean handleReloadIncremental(McpConfigManager configManager,
                                                   McpClientManager mcpClientManager,
                                                   McpConnectionTracker tracker,
                                                   boolean global) {
        System.out.println("\n" + McpColor.info("Scanning for MCP servers..."));

        // Get current configs
        Map<String, McpServerConfig> currentConfigs = configManager.loadConfigs(!global, !global);

        if (currentConfigs.isEmpty()) {
            System.out.println(McpColor.info("No MCP servers configured"));
            return true;
        }

        // Find new servers (not connected AND not failed)
        Set<String> connectedNames = tracker.getConnectedNames();
        Set<String> failedNames = tracker.getServerErrors().keySet();
        Set<String> newServers = new HashSet<>(currentConfigs.keySet());
        newServers.removeAll(connectedNames);
        newServers.removeAll(failedNames);

        if (newServers.isEmpty()) {
            System.out.println(McpColor.info("No new servers to connect"));
            return true;
        }

        System.out.println("Found " + currentConfigs.size() + " configured server(s), " +
                newServers.size() + " new");

        // Connect new servers
        System.out.println("\n" + McpColor.info("Connecting new servers..."));

        int connected = 0;
        int failed = 0;

        for (String name : newServers) {
            McpServerConfig config = currentConfigs.get(name);

            // Skip disabled servers
            if (!config.isEnabled()) {
                System.out.println("  " + name + "... " + McpColor.dim("skipped (disabled)"));
                continue;
            }

            System.out.print("  " + name + "... ");

            ConnectionResult result = connectWithRetry(name, config, mcpClientManager);
            if (result.success) {
                tracker.markConnected(name, mcpClientManager.getServer(name));
                System.out.println(McpColor.success("connected"));
                connected++;
            } else {
                tracker.markFailed(name, result.error);
                System.out.println(McpColor.error("failed: " + result.error));
                failed++;
            }
        }

        System.out.println("\n" + McpColor.info("Summary: ") +
                McpColor.green(connected + " connected") +
                ", " +
                (failed > 0 ? McpColor.red(failed + " failed") : McpColor.dim(failed + " failed")));

        return true;
    }

    private static boolean handleReloadAll(McpConfigManager configManager,
                                           McpClientManager mcpClientManager,
                                           McpConnectionTracker tracker,
                                           boolean global) {
        System.out.println("\n" + McpColor.info("Full reload: disconnecting all servers..."));

        // Disconnect all
        Set<String> connectedNames = tracker.getConnectedNames();
        for (String name : connectedNames) {
            try {
                mcpClientManager.disconnectServer(name);
            } catch (Exception e) {
                // Ignore
            }
        }
        tracker.clear();

        // Reconnect all
        Map<String, McpServerConfig> configs = configManager.loadConfigs(!global, !global);

        if (configs.isEmpty()) {
            System.out.println(McpColor.info("No MCP servers configured"));
            return true;
        }

        System.out.println("Found " + configs.size() + " configured server(s)");

        System.out.println("\n" + McpColor.info("Connecting servers..."));

        int connected = 0;
        int failed = 0;

        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            String name = entry.getKey();
            McpServerConfig config = entry.getValue();

            // Skip disabled servers
            if (!config.isEnabled()) {
                System.out.println("  " + name + "... " + McpColor.dim("skipped (disabled)"));
                continue;
            }

            System.out.print("  " + name + "... ");

            ConnectionResult result = connectWithRetry(name, config, mcpClientManager);
            if (result.success) {
                tracker.markConnected(name, mcpClientManager.getServer(name));
                System.out.println(McpColor.success("connected"));
                connected++;
            } else {
                tracker.markFailed(name, result.error);
                System.out.println(McpColor.error("failed: " + result.error));
                failed++;
            }
        }

        System.out.println("\n" + McpColor.info("Summary: ") +
                McpColor.green(connected + " connected") +
                ", " +
                (failed > 0 ? McpColor.red(failed + " failed") : McpColor.dim(failed + " failed")));

        return true;
    }

    // ==================== reload command (output-aware) ====================

    private String handleReload(String[] args, ReActAgent agent, CommandOutput output) {
        boolean global = hasFlag(args, "--global");
        boolean reloadAll = hasFlag(args, "--all");

        McpConfigManager configManager = getConfigManager(agent);
        McpClientManager mcpClientManager = getMcpClientManager(agent);
        McpConnectionTracker tracker = getConnectionTracker(agent);

        if (reloadAll) {
            return handleReloadAll(configManager, mcpClientManager, tracker, global, output);
        } else {
            return handleReloadIncremental(configManager, mcpClientManager, tracker, global, output);
        }
    }

    private String handleReloadIncremental(McpConfigManager configManager,
                                                   McpClientManager mcpClientManager,
                                                   McpConnectionTracker tracker,
                                                   boolean global,
                                                   CommandOutput output) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nINFO {{mcp.info.scanning_for_servers}}\n");

        // Get current configs
        Map<String, McpServerConfig> currentConfigs = configManager.loadConfigs(!global, !global);

        if (currentConfigs.isEmpty()) {
            sb.append("INFO {{mcp.info.no_servers_configured}}");
            return sb.toString();
        }

        // Find new servers (not connected AND not failed)
        Set<String> connectedNames = tracker.getConnectedNames();
        Set<String> failedNames = tracker.getServerErrors().keySet();
        Set<String> newServers = new HashSet<>(currentConfigs.keySet());
        newServers.removeAll(connectedNames);
        newServers.removeAll(failedNames);

        if (newServers.isEmpty()) {
            sb.append("INFO {{mcp.info.no_new_servers}}");
            return sb.toString();
        }

        sb.append("Found " + currentConfigs.size() + " configured server(s), " +
                newServers.size() + " new\n");

        // Connect new servers
        sb.append("\nINFO {{mcp.info.connecting_new_servers}}\n");

        int connected = 0;
        int failed = 0;

        for (String name : newServers) {
            McpServerConfig config = currentConfigs.get(name);

            // Skip disabled servers
            if (!config.isEnabled()) {
                sb.append("  " + name + "... " + McpColor.dim("skipped (disabled)") + "\n");
                continue;
            }

            sb.append("  " + name + "... ");

            ConnectionResult result = connectWithRetry(name, config, mcpClientManager);
            if (result.success) {
                tracker.markConnected(name, mcpClientManager.getServer(name));
                sb.append("SUCCESS {{mcp.success.connected}}\n");
                connected++;
            } else {
                tracker.markFailed(name, result.error);
                sb.append("ERROR {{mcp.error.failed}} " + result.error + "\n");
                failed++;
            }
        }

        sb.append("\nINFO {{mcp.info.summary}}: " +
                connected + " connected" +
                ", " +
                (failed > 0 ? failed + " failed" : McpColor.dim(failed + " failed")));

        return sb.toString();
    }

    private String handleReloadAll(McpConfigManager configManager,
                                           McpClientManager mcpClientManager,
                                           McpConnectionTracker tracker,
                                           boolean global,
                                           CommandOutput output) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nINFO {{mcp.info.full_reload_disconnecting}}\n");

        // Disconnect all
        Set<String> connectedNames = tracker.getConnectedNames();
        for (String name : connectedNames) {
            try {
                mcpClientManager.disconnectServer(name);
            } catch (Exception e) {
                // Ignore
            }
        }
        tracker.clear();

        // Reconnect all
        Map<String, McpServerConfig> configs = configManager.loadConfigs(!global, !global);

        if (configs.isEmpty()) {
            sb.append("INFO {{mcp.info.no_servers_configured}}");
            return sb.toString();
        }

        sb.append("Found " + configs.size() + " configured server(s)\n");
        sb.append("\nINFO {{mcp.info.connecting_servers}}\n");

        int connected = 0;
        int failed = 0;

        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            String name = entry.getKey();
            McpServerConfig config = entry.getValue();

            // Skip disabled servers
            if (!config.isEnabled()) {
                sb.append("  " + name + "... " + McpColor.dim("skipped (disabled)") + "\n");
                continue;
            }

            sb.append("  " + name + "... ");

            ConnectionResult result = connectWithRetry(name, config, mcpClientManager);
            if (result.success) {
                tracker.markConnected(name, mcpClientManager.getServer(name));
                sb.append("SUCCESS {{mcp.success.connected}}\n");
                connected++;
            } else {
                tracker.markFailed(name, result.error);
                sb.append("ERROR {{mcp.error.failed}} " + result.error + "\n");
                failed++;
            }
        }

        sb.append("\nINFO {{mcp.info.summary}}: " +
                connected + " connected" +
                ", " +
                (failed > 0 ? failed + " failed" : McpColor.dim(failed + " failed")));

        return sb.toString();
    }

    // ==================== Utility ====================

    private static void printHelp() {
        System.out.println("\n" + McpColor.bold("MCP Commands"));
        System.out.println(McpColor.repeat("─", 40));
        System.out.println("  " + McpColor.cyan("mcp list") + " [--global]           List all MCP servers");
        System.out.println("  " + McpColor.cyan("mcp add") + " [--global] [--force]   Add a new MCP server (interactive)");
        System.out.println("  " + McpColor.cyan("mcp remove") + " <name> [--global]    Remove an MCP server");
        System.out.println("  " + McpColor.cyan("mcp disable") + " <name> [--global]  Disable an MCP server");
        System.out.println("  " + McpColor.cyan("mcp enable") + " <name> [--global]   Enable an MCP server");
        System.out.println("  " + McpColor.cyan("mcp reload") + " [--global]          Hot reload (new servers only)");
        System.out.println("  " + McpColor.cyan("mcp reload --all") + " [--global]   Hot reload (all servers)");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  " + McpColor.dim("--global") + "  Use global scope instead of project local");
        System.out.println("  " + McpColor.dim("--force") + "  Force save config even if connection fails (for add)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mcp list                    # List project MCP servers");
        System.out.println("  mcp add                     # Add MCP server to project");
        System.out.println("  mcp add --force            # Add server without testing connection");
        System.out.println("  mcp disable github          # Disable 'github' server");
        System.out.println("  mcp enable github           # Enable 'github' server");
        System.out.println("  mcp reload                  # Connect new servers");
        System.out.println("  mcp reload --all            # Reconnect all servers");
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidName(String name) {
        return name.matches("^[a-zA-Z][a-zA-Z0-9_-]*$");
    }

    /**
     * Connect to an MCP server with retry and exponential backoff.
     *
     * @param name Server name
     * @param config Server configuration
     * @param mcpClientManager MCP client manager
     * @return ConnectionResult with success status and error message if failed
     */
    private static ConnectionResult connectWithRetry(String name, McpServerConfig config,
                                                     McpClientManager mcpClientManager) {
        int attempts = 0;
        long delayMs = RETRY_DELAY_BASE_MS;

        while (attempts < MAX_RETRIES) {
            attempts++;
            try {
                if (config.getType() == McpServerConfig.Type.STDIO) {
                    String commandStr = config.buildCommandString();
                    String workspace = config.getWorkspaceDir() != null
                            ? config.getWorkspaceDir()
                            : PathUtils.getWorkingDir();
                    mcpClientManager.connectServer(name, commandStr, workspace);
                } else {
                    mcpClientManager.connectHttpServer(name, config.getUrl(),
                            config.getMcpEndpoint(), config.getSseEndpoint());
                }
                return new ConnectionResult(true, null);
            } catch (Exception e) {
                if (attempts >= MAX_RETRIES) {
                    return new ConnectionResult(false, e.getMessage());
                }
                try {
                    Thread.sleep(delayMs);
                    delayMs *= 2; // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new ConnectionResult(false, e.getMessage());
                }
            }
        }
        return new ConnectionResult(false, "Max retries exceeded");
    }

    /**
     * Result of a connection attempt with retry
     */
    private static class ConnectionResult {
        final boolean success;
        final String error;

        ConnectionResult(boolean success, String error) {
            this.success = success;
            this.error = error;
        }
    }

    // ==================== Agent Access ====================

    private static McpConfigManager getConfigManager(ReActAgent agent) {
        // Get project dir from agent's workspace or use null for global only
        String workspace = getWorkspaceDir(agent);
        Path projectDir = Paths.get(workspace);
        return new McpConfigManager(projectDir);
    }

    private static McpClientManager getMcpClientManager(ReActAgent agent) {
        try {
            java.lang.reflect.Field field = ReActAgent.class.getDeclaredField("mcpManager");
            field.setAccessible(true);
            return (McpClientManager) field.get(agent);
        } catch (Exception e) {
            logger.error("Failed to get mcpManager from agent", e);
            return null;
        }
    }

    private static McpConnectionTracker getConnectionTracker(ReActAgent agent) {
        try {
            java.lang.reflect.Field field = ReActAgent.class.getDeclaredField("mcpConnectionTracker");
            field.setAccessible(true);
            McpConnectionTracker tracker = (McpConnectionTracker) field.get(agent);
            if (tracker == null) {
                tracker = new McpConnectionTracker();
                field.set(agent, tracker);
            }
            return tracker;
        } catch (Exception e) {
            logger.error("Failed to get mcpConnectionTracker from agent", e);
            return new McpConnectionTracker();
        }
    }

    private static String getWorkspaceDir(ReActAgent agent) {
        try {
            java.lang.reflect.Field field = ReActAgent.class.getDeclaredField("workspaceDir");
            field.setAccessible(true);
            return (String) field.get(agent);
        } catch (Exception e) {
            return PathUtils.getWorkingDir();
        }
    }
}
