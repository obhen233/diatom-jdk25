package com.github.obhen233.core.tool;

import com.github.obhen233.core.command.tools.ConfigTools;
import com.github.obhen233.config.SystemInfo;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.tool.builtin.*;
import com.github.obhen233.core.workspace.WorkspaceRegistry;
import com.github.obhen233.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified tool registration center.
 * Centralizes all tool registration logic to avoid duplication and ensure consistency.
 */
public class ToolRegistryCenter {
    private static final Logger logger = LoggerFactory.getLogger(ToolRegistryCenter.class);

    private final ToolRegistry registry;
    private final List<ToolProvider> providers = new ArrayList<>();
    private final Map<String, Tool> toolMetadata = new HashMap<>();
    private ConfigTools configTools;

    public ToolRegistryCenter() {
        this.registry = new ToolRegistry();
    }

    /**
     * Set ConfigTools instance for direct access.
     * Called by ConfigToolsProvider during registration.
     */
    public void setConfigTools(ConfigTools configTools) {
        this.configTools = configTools;
    }

    /**
     * Get ConfigTools instance for direct access.
     */
    public ConfigTools getConfigTools() {
        if (configTools != null) {
            return configTools;
        }
        // Try to get from registry after initialization
        Object instance = registry.getToolInstance("config_list");
        if (instance instanceof ConfigTools) {
            configTools = (ConfigTools) instance;
        }
        return configTools;
    }
    
    /**
     * Set timeout callback on CommandTools after initialization.
     * This allows setting the callback after TerminalUI is created.
     */
    public void setTimeoutCallback(CommandTools.TimeoutCallback callback) {
        // Access the instances map from registry
        // The CommandTools instance is stored in the registry
        Object cmdToolsInstance = registry.getToolInstance("run_command");
        if (cmdToolsInstance instanceof CommandTools) {
            ((CommandTools) cmdToolsInstance).setTimeoutCallback(callback);
            logger.info("Timeout callback set on CommandTools");
        } else {
            logger.warn("Could not find CommandTools instance to set timeout callback");
        }
    }

    /**
     * Set command knowledge manager on CommandTools after initialization.
     * This enables dynamic command permission checking via the knowledge base.
     */
    public void setKnowledgeManager(com.github.obhen233.core.knowledge.CommandKnowledgeManager manager) {
        Object cmdToolsInstance = registry.getToolInstance("run_command");
        if (cmdToolsInstance instanceof CommandTools) {
            ((CommandTools) cmdToolsInstance).setKnowledgeManager(manager);
            logger.info("Command knowledge manager set on CommandTools");
        } else {
            logger.warn("Could not find CommandTools instance to set knowledge manager");
        }
    }

    /**
     * Set config manager on ConfigTools after initialization.
     * This enables config management tools.
     */
    public void setConfigManager(com.github.obhen233.core.config.ConfigManager manager) {
        Object configToolsInstance = registry.getToolInstance("config_list");
        if (configToolsInstance instanceof ConfigTools) {
            // ConfigTools constructor handles this internally
            logger.info("ConfigTools instance found");
        } else {
            logger.warn("Could not find ConfigTools instance to set config manager");
        }
    }

    public ToolRegistryCenter registerProvider(ToolProvider provider) {
        providers.add(provider);
        return this;
    }

    public void initializeAll() {
        for (ToolProvider provider : providers) {
            try {
                provider.registerTools(registry);
                logger.info("Registered tools from: {}", provider.getName());
            } catch (Exception e) {
                logger.error("Failed to register tools from {}: {}", provider.getName(), e.getMessage());
            }
        }
    }

    public ToolRegistry getRegistry() {
        return registry;
    }

    public Map<String, Tool> getToolDefinitions() {
        return registry.getToolDefinitions();
    }

    /**
     * Create a standard tool registry with core tools
     */
    public static ToolRegistryCenter createStandard(Config config) {
        ToolRegistryCenter center = new ToolRegistryCenter();
        
        CoreToolsProvider coreProvider = new CoreToolsProvider(
            config.workspaceDir,
            config.authManager,
            config.commandConfig,
            config.workspaceRegistry
        );
        if (config.timeoutCallback != null) {
            coreProvider.setTimeoutCallback(config.timeoutCallback);
        }
        center.registerProvider(coreProvider);

        center.registerProvider(new SourceTreeToolsProvider());

        // System tools are always registered (check_self_update and similar)
        center.registerProvider(new SystemToolsProvider());

        // Search tools for project symbol/content search
        center.registerProvider(new SearchToolsProvider());

        // Workspace management tools
        if (config.workspaceRegistry != null) {
            center.registerProvider(new WorkspaceToolsProvider(
                config.workspaceRegistry, config.allowExternalResources));
        }

        // Self-update tools are registered in standalone JAR mode
        // but each tool method checks development mode at runtime
        if (config.standaloneMode) {
            center.registerProvider(new SelfUpdateToolsProvider(
                config.skillManager,
                config.promptManager
            ));
        }

        // Config tools for configuration management
        if (config.configManager != null) {
            center.registerProvider(new ConfigToolsProvider(config.configManager, config.dbManager));
        }

        center.initializeAll();
        return center;
    }

    /**
     * Configuration for tool registration
     */
    public static class Config {
        private String workspaceDir;
        private AuthorizedPathManager authManager;
        private CommandTools.Config commandConfig;
        private boolean standaloneMode;
        private Object skillManager;
        private Object promptManager;
        private CommandTools.TimeoutCallback timeoutCallback;
        private com.github.obhen233.core.config.ConfigManager configManager;
        private com.github.obhen233.core.database.DatabaseManager dbManager;
        private WorkspaceRegistry workspaceRegistry;
        private boolean allowExternalResources;

        public Config setWorkspaceDir(String dir) {
            this.workspaceDir = dir;
            return this;
        }

        public Config setAuthManager(AuthorizedPathManager manager) {
            this.authManager = manager;
            return this;
        }

        public Config setCommandConfig(CommandTools.Config config) {
            this.commandConfig = config;
            return this;
        }

        public Config setStandaloneMode(boolean standalone) {
            this.standaloneMode = standalone;
            return this;
        }

        public Config setSkillManager(Object manager) {
            this.skillManager = manager;
            return this;
        }

        public Config setPromptManager(Object manager) {
            this.promptManager = manager;
            return this;
        }

        public Config setTimeoutCallback(CommandTools.TimeoutCallback callback) {
            this.timeoutCallback = callback;
            return this;
        }

        public Config setConfigManager(com.github.obhen233.core.config.ConfigManager manager) {
            this.configManager = manager;
            return this;
        }

        public Config setDbManager(com.github.obhen233.core.database.DatabaseManager manager) {
            this.dbManager = manager;
            return this;
        }

        public Config setWorkspaceRegistry(WorkspaceRegistry registry) {
            this.workspaceRegistry = registry;
            return this;
        }

        public Config setAllowExternalResources(boolean allow) {
            this.allowExternalResources = allow;
            return this;
        }
    }

    /**
     * Interface for tool providers
     */
    public interface ToolProvider {
        String getName();
        void registerTools(ToolRegistry registry);
    }

    /**
     * Core tools provider (FileTools, CommandTools)
     */
    public static class CoreToolsProvider implements ToolProvider {
        private final String workspaceDir;
        private final AuthorizedPathManager authManager;
        private final CommandTools.Config commandConfig;
        private final WorkspaceRegistry workspaceRegistry;
        private CommandTools.TimeoutCallback timeoutCallback;

        public CoreToolsProvider(String workspaceDir, AuthorizedPathManager authManager,
                                 CommandTools.Config commandConfig) {
            this(workspaceDir, authManager, commandConfig, null);
        }

        public CoreToolsProvider(String workspaceDir, AuthorizedPathManager authManager,
                                 CommandTools.Config commandConfig,
                                 WorkspaceRegistry workspaceRegistry) {
            this.workspaceDir = workspaceDir;
            this.authManager = authManager;
            this.commandConfig = commandConfig;
            this.workspaceRegistry = workspaceRegistry;
        }
        
        public CoreToolsProvider setTimeoutCallback(CommandTools.TimeoutCallback callback) {
            this.timeoutCallback = callback;
            return this;
        }

        @Override
        public String getName() {
            return "CoreTools";
        }

        @Override
        public void registerTools(ToolRegistry registry) {
            registry.scanObject(new FileTools(workspaceDir, authManager, workspaceRegistry));
            logger.debug("Registered FileTools");

            if (commandConfig != null) {
                CommandTools commandTools = new CommandTools(commandConfig);
                if (timeoutCallback != null) {
                    commandTools.setTimeoutCallback(timeoutCallback);
                }
                registry.scanObject(commandTools);
                logger.debug("Registered CommandTools with timeout={}s, shell={}", 
                    commandConfig.getTimeoutSeconds(), 
                    commandConfig.getShellType());
            }
        }
    }

    /**
     * Source tree tools provider
     */
    public static class SourceTreeToolsProvider implements ToolProvider {
        @Override
        public String getName() {
            return "SourceTreeTools";
        }

        @Override
        public void registerTools(ToolRegistry registry) {
            registry.scanObject(new SourceTreeTools());
            logger.debug("Registered SourceTreeTools");
        }
    }

    /**
     * System tools provider (always registered - check_self_update and other system tools)
     */
    public static class SystemToolsProvider implements ToolProvider {
        @Override
        public String getName() {
            return "SystemTools";
        }

        @Override
        public void registerTools(ToolRegistry registry) {
            registry.scanObject(new SystemTools());
            logger.debug("Registered SystemTools");
        }
    }

    /**
     * Self-update tools provider (only for standalone JAR mode)
     */
    public static class SelfUpdateToolsProvider implements ToolProvider {
        private final Object skillManager;
        private final Object promptManager;

        public SelfUpdateToolsProvider(Object skillManager, Object promptManager) {
            this.skillManager = skillManager;
            this.promptManager = promptManager;
        }

        @Override
        public String getName() {
            return "SelfUpdateTools";
        }

        @Override
        public void registerTools(ToolRegistry registry) {
            try {
                if (skillManager != null && promptManager != null) {
                    Class<?> skillMgrClass = Class.forName("com.github.obhen233.core.skill.SkillManager");
                    Class<?> promptMgrClass = Class.forName("com.github.obhen233.core.skill.SystemPromptManager");
                    
                    java.lang.reflect.Constructor<?> constructor = SelfUpdateTools.class.getConstructor(
                        skillMgrClass, promptMgrClass
                    );
                    Object selfUpdateTools = constructor.newInstance(skillManager, promptManager);
                    registry.scanObject(selfUpdateTools);
                    
                    logger.debug("Registered SelfUpdateTools with SkillManager and SystemPromptManager");
                }
            } catch (Exception e) {
                registry.scanObject(new SelfUpdateTools());
                logger.debug("Registered SelfUpdateTools (fallback mode)");
            }

            registry.scanObject(new JarManager());
            registry.scanObject(new CustomVersionManager());
            logger.debug("Registered JarManager and CustomVersionManager");
        }
    }

    /**
     * MCP tools provider
     */
    public static class McpToolsProvider implements ToolProvider {
        private final Object mcpManager;
        private final String serverName;

        public McpToolsProvider(Object mcpManager, String serverName) {
            this.mcpManager = mcpManager;
            this.serverName = serverName;
        }

        @Override
        public String getName() {
            return "McpTools[" + serverName + "]";
        }

        @Override
        public void registerTools(ToolRegistry registry) {
            logger.debug("MCP tools for server '{}' will be discovered dynamically", serverName);
        }
    }

    /**
     * Config tools provider for system configuration management
     */
    public static class ConfigToolsProvider implements ToolProvider {
        private final com.github.obhen233.core.config.ConfigManager configManager;
        private final com.github.obhen233.core.database.DatabaseManager dbManager;

        public ConfigToolsProvider(com.github.obhen233.core.config.ConfigManager configManager,
                                   com.github.obhen233.core.database.DatabaseManager dbManager) {
            this.configManager = configManager;
            this.dbManager = dbManager;
        }

        @Override
        public String getName() {
            return "ConfigTools";
        }

        @Override
        public void registerTools(ToolRegistry registry) {
            ConfigTools configTools = new ConfigTools(configManager, dbManager);
            registry.scanObject(configTools);
            logger.debug("Registered ConfigTools");
        }
    }

    /**
     * Search tools provider (search_symbols - independent of workspace/project type)
     */
    public static class SearchToolsProvider implements ToolProvider {

        public SearchToolsProvider() {
        }

        @Override
        public String getName() {
            return "SearchTools";
        }

        @Override
        public void registerTools(ToolRegistry registry) {
            registry.scanObject(new SearchTools(PathUtils.getWorkingDir()));
            logger.debug("Registered SearchTools (search_symbols)");
        }
    }

    /**
     * Workspace tools provider (add_workspace, list_workspaces, remove_workspace)
     */
    public static class WorkspaceToolsProvider implements ToolProvider {
        private final WorkspaceRegistry workspaceRegistry;
        private final boolean allowExternalResources;

        public WorkspaceToolsProvider(WorkspaceRegistry workspaceRegistry, boolean allowExternalResources) {
            this.workspaceRegistry = workspaceRegistry;
            this.allowExternalResources = allowExternalResources;
        }

        @Override
        public String getName() {
            return "WorkspaceTools";
        }

        @Override
        public void registerTools(ToolRegistry registry) {
            registry.scanObject(new WorkspaceTools(workspaceRegistry, allowExternalResources));
            logger.debug("Registered WorkspaceTools");
        }
    }
}
