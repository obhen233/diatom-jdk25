package com.github.obhen233.starter;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.config.SystemInfo;
import com.github.obhen233.config.WorkspaceProvider;
import com.github.obhen233.core.CoreInitializer;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.adapter.OpenAIAdapter;
import com.github.obhen233.core.adapter.ResponsesAdapter;
import com.github.obhen233.core.adapter.AnthropicAdapter;
import com.github.obhen233.core.command.tools.ConfigTools;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.database.DatabaseInitializer;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.core.database.HibernateDatabaseManager;
import com.github.obhen233.core.database.HistoryManager;
import com.github.obhen233.core.database.TaskCheckpointManager;
import com.github.obhen233.core.database.ContextCacheManager;
import com.github.obhen233.core.engine.CommandPermissionEngine;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.knowledge.CommandKnowledgeManager;
import com.github.obhen233.core.mcp.McpClientManager;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.tool.AuthorizedPathManager;
import com.github.obhen233.core.tool.ToolRegistry;
import com.github.obhen233.core.tool.ToolRegistryCenter;
import com.github.obhen233.core.tool.builtin.CommandTools;
import com.github.obhen233.spi.AppLifecycleHook;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.ToolRegistrar;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Paths;

/**
 * 共享核心自动配置。
 *
 * <p>所有模式（standard / gateway / worker / adapter）都需要的通用 Bean：
 * <ul>
 *   <li>I18n / AppConfig / SystemInfo</li>
 *   <li>数据库（Hibernate + HikariCP）</li>
 *   <li>HTTP 客户端（AiHttpClient）和模型适配器（ModelAdapter）</li>
 *   <li>Skill / SystemPrompt / MCP / ProjectIndexer</li>
 *   <li>ToolRegistry / CommandTools / CommandPermissionEngine</li>
 *   <li>HistoryManager / TaskCheckpointManager / ContextCacheManager</li>
 * </ul>
 *
 * 不包含 ReActAgent — 它由各模式配置各自创建。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(DiatomProperties.class)
public class SharedAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(SharedAutoConfiguration.class);

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkspacePathProvider workspacePathProvider;

    private String getWorkspacePath() {
        if (workspacePathProvider != null) {
            String path = workspacePathProvider.getWorkspacePath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
        }
        return new AppConfig().getWorkspaceDir();
    }

    /**
     * Determine whether the mode requires the IDE to maintain manual AI URL/key/model config.
     * api/adapter handle AI through their own mechanisms -> no manual config needed.
     * All other modes (standard/gateway/gateway:*) -> manual config required.
     */
    private static boolean requiresManualAiConfig(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return true; // default: standard
        }
        String m = mode.trim().toLowerCase();
        return !"api".equals(m) && !"adapter".equals(m);
    }

    // ========== I18n & Config ==========

    @Bean
    @ConditionalOnMissingBean
    public static I18nInitializer i18nInitializer(DiatomProperties properties) {
        return new I18nInitializer(properties.getAgent().getLanguage());
    }

    @Bean
    @ConditionalOnMissingBean
    public AppConfig appConfig() {
        return new AppConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.github.obhen233.starter.ide.IdeModeCapabilities ideModeCapabilities(DiatomProperties properties) {
        String mode = properties.getMode();
        return new com.github.obhen233.starter.ide.IdeModeCapabilities(mode, requiresManualAiConfig(mode));
    }

    // ========== Infrastructure ==========

    @Bean
    @ConditionalOnMissingBean
    public SystemInfo systemInfo() {
        return new SystemInfo();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthorizedPathManager authorizedPathManager() {
        AuthorizedPathManager authManager = new AuthorizedPathManager();
        String diatomDir = System.getProperty("user.home") + File.separator + ".diatom";
        authManager.authorize(diatomDir);
        return authManager;
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillManager skillManager() {
        return new SkillManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public SystemPromptManager systemPromptManager() {
        return new SystemPromptManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProjectIndexer projectIndexer(AppConfig appConfig) {
        return new ProjectIndexer(getWorkspacePath());
    }

    // ========== Database ==========

    @Bean
    @ConditionalOnMissingBean
    public HibernateConfig hibernateConfig(DiatomProperties properties) {
        DiatomProperties.Database dbProps = properties.getDatabase();
        setSystemPropertyIfNotEmpty("diatom.database.url", dbProps.getUrl());
        setSystemPropertyIfNotEmpty("diatom.database.username", dbProps.getUsername());
        setSystemPropertyIfNotEmpty("diatom.database.password", dbProps.getPassword());
        if (dbProps.getPoolSize() != null) {
            System.setProperty("diatom.database.pool-size", String.valueOf(dbProps.getPoolSize()));
        }
        setSystemPropertyIfNotEmpty("diatom.database.hibernatedialect", dbProps.getDialect());
        setSystemPropertyIfNotEmpty("diatom.database.driver", dbProps.getDriver());
        return new HibernateConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseManager databaseManager(HibernateConfig config) {
        try {
            DatabaseManager db = new HibernateDatabaseManager(config);
            db.initialize();
            logger.info("Hibernate database initialized (type={})", config.getDbType());
            return db;
        } catch (Exception e) {
            logger.error("Failed to initialize Hibernate database, history/checkpoints disabled: {}", e.getMessage());
            return null;
        }
    }

    private static void setSystemPropertyIfNotEmpty(String key, String value) {
        if (value != null && !value.isEmpty()) {
            System.setProperty(key, value);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public com.github.obhen233.core.config.ConfigManager configManager(DatabaseManager db) {
        if (db == null) {
            logger.warn("ConfigManager not created because DatabaseManager is null");
            return null;
        }
        return new com.github.obhen233.core.config.ConfigManager(db);
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseInitializer databaseInitializer(DatabaseManager db) {
        if (db == null) {
            logger.warn("DatabaseInitializer not created because DatabaseManager is null");
            return null;
        }
        DatabaseInitializer initializer = new DatabaseInitializer(db);
        initializer.initialize();
        return initializer;
    }

    @Bean
    @ConditionalOnMissingBean
    public SystemConfigInitializer systemConfigInitializer(DatabaseManager db) {
        if (db == null) {
            logger.warn("SystemConfigInitializer not created because DatabaseManager is null");
            return null;
        }
        SystemConfigInitializer initializer = new SystemConfigInitializer(db);
        initializer.registerExtensions();
        return initializer;
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigManagerInitializer configManagerInitializer(
            com.github.obhen233.core.config.ConfigManager configManager) {
        if (configManager == null) {
            logger.warn("ConfigManagerInitializer not created because ConfigManager is null");
            return null;
        }
        ConfigManagerInitializer initializer = new ConfigManagerInitializer(configManager);
        initializer.loadFromDatabase();
        return initializer;
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandKnowledgeManager commandKnowledgeManager(DatabaseManager db) {
        if (db == null) {
            logger.warn("CommandKnowledgeManager not created because DatabaseManager is null");
            return null;
        }
        CommandKnowledgeManager manager = new CommandKnowledgeManager(db);
        manager.loadFromDatabase();
        manager.loadSeedData();
        logger.info("CommandKnowledgeManager initialized with {} commands", manager.getStats().total);
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandPermissionEngine commandPermissionEngine(CommandKnowledgeManager knowledgeManager) {
        if (knowledgeManager == null) {
            logger.warn("CommandPermissionEngine not created because CommandKnowledgeManager is null");
            return null;
        }
        return new CommandPermissionEngine(knowledgeManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public HistoryManager historyManager(DatabaseManager db) {
        if (db == null) return null;
        return new HistoryManager(db, 100, getWorkspacePath());
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskCheckpointManager taskCheckpointManager(DatabaseManager db) {
        if (db == null) return null;
        return new TaskCheckpointManager(db);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandRulesInitializer commandRulesInitializer(DatabaseManager db) {
        CommandRulesInitializer initializer = new CommandRulesInitializer(db);
        initializer.initialize();
        return initializer;
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextCacheManager contextCacheManager(DatabaseManager db, ProjectIndexer projectIndexer) {
        if (db == null) return null;
        ContextCacheManager cache = new ContextCacheManager(db);
        projectIndexer.setContextCache(cache);
        return cache;
    }

    // ========== Workspace / Project ==========

    @Bean
    @ConditionalOnMissingBean
    public WorkspaceProvider workspaceProvider() {
        return new com.github.obhen233.config.DefaultWorkspaceProvider();
    }

    // ========== MCP ==========

    @Bean
    @ConditionalOnMissingBean
    public McpClientManager mcpClientManager(AppConfig appConfig, AuthorizedPathManager authManager) {
        boolean allowExternal = appConfig.isAllowExternalResources();
        return new McpClientManager()
                .withLazyBuiltInServers(getWorkspacePath(), authManager, allowExternal);
    }

    @Bean
    @ConditionalOnMissingBean
    public McpConfigInitializer mcpConfigInitializer(McpClientManager mcpManager) {
        return new McpConfigInitializer(mcpManager, getWorkspacePath());
    }

    // ========== HTTP & Model ==========

    @Bean
    @ConditionalOnMissingBean
    public AiHttpClient aiHttpClient(AppConfig appConfig, DiatomProperties properties) {
        AiHttpClient httpClient;

        if (properties.getIde().isEnabled()) {
            logger.info("IDE mode enabled, AiHttpClient will be synced by IdeAiConfigService");
            httpClient = new AiHttpClient("", "https://api.openai.com");
        } else {
            boolean isAnthropic = CoreInitializer.detectAnthropicFormat(appConfig);
            if (isAnthropic) {
                httpClient = new AiHttpClient(appConfig.getApiKey(),
                        appConfig.getBaseUrl(), AiHttpClient.AuthStyle.ANTHROPIC);
            } else {
                httpClient = new AiHttpClient(appConfig.getApiKey(), appConfig.getBaseUrl());
            }
        }

        String userAgent = properties.getApp().getUserAgent();
        if (userAgent != null && !userAgent.isEmpty()) {
            httpClient.setUserAgent(userAgent);
        }

        return httpClient;
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelAdapter modelAdapter(AppConfig appConfig,
                                     java.util.Optional<AiConfigProvider> configProvider) {
        if (configProvider.isPresent()) {
            AiConfigProvider provider = configProvider.get();
            String model = provider.getModel();
            String format = provider.getFormat();
            if ("responses".equalsIgnoreCase(format)) {
                return new ResponsesAdapter(model, appConfig.getMaxTokens());
            }
            boolean isAnthropic_ = "anthropic".equalsIgnoreCase(format)
                    || ("auto".equalsIgnoreCase(format) && model != null && model.toLowerCase().contains("claude"));
            if (isAnthropic_) {
                return new AnthropicAdapter(model, appConfig.getMaxTokens());
            }
            return new OpenAIAdapter(model, appConfig.getMaxTokens());
        }
        boolean isAnthropic = CoreInitializer.detectAnthropicFormat(appConfig);
        if (isAnthropic) {
            return new AnthropicAdapter(appConfig.getModel(), appConfig.getMaxTokens());
        }
        if (CoreInitializer.detectResponsesFormat(appConfig)) {
            return new ResponsesAdapter(appConfig.getModel(), appConfig.getMaxTokens());
        }
        return new OpenAIAdapter(appConfig.getModel(), appConfig.getMaxTokens());
    }

    // ========== Command Tools ==========

    @Bean
    @ConditionalOnMissingBean
    public CommandTools.Config commandConfig(AppConfig appConfig, SystemInfo systemInfo,
                                              DatabaseManager dbManager) {
        CommandTools.Config config = new CommandTools.Config()
                .setAllowedCommands(appConfig.getCommandWhitelist())
                .setTimeoutSeconds(appConfig.getCommandTimeout())
                .setMaxOutputBytes(appConfig.getCommandMaxOutputBytes())
                .setAllowAll(!appConfig.isCommandWhitelistMode())
                .setWorkingDir(getWorkspacePath())
                .setShellType(systemInfo.getShellType())
                .setShellPath(systemInfo.getDetectedShell())
                .setMavenPath(systemInfo.getDetectedMaven())
                .setPythonPath(systemInfo.getDetectedPython())
                .setGitPath(systemInfo.getDetectedGitPath());
        if (dbManager != null) {
            config.setDatabaseManager(dbManager);
        }
        return config;
    }

    // ========== Tool Registry ==========

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistryCenter toolRegistryCenter(
            AppConfig appConfig,
            AuthorizedPathManager authManager,
            CommandTools.Config commandConfig,
            SkillManager skillManager,
            SystemPromptManager promptManager,
            com.github.obhen233.core.config.ConfigManager configManager,
            DatabaseManager dbManager) {
        ToolRegistryCenter.Config registryConfig = new ToolRegistryCenter.Config()
                .setWorkspaceDir(getWorkspacePath())
                .setAuthManager(authManager)
                .setCommandConfig(commandConfig)
                .setStandaloneMode(true)
                .setSkillManager(skillManager)
                .setPromptManager(promptManager)
                .setConfigManager(configManager)
                .setDbManager(dbManager);

        ToolRegistryCenter center = ToolRegistryCenter.createStandard(registryConfig);
        logger.info("Tool registry initialized with standard tools");

        for (ToolRegistrar registrar : SpiLoader.getAll(ToolRegistrar.class)) {
            try {
                registrar.registerTools(center.getRegistry());
                logger.info("Registered tools from: {}", registrar.getClass().getName());
            } catch (Exception e) {
                logger.warn("Failed to register tools from {}: {}",
                        registrar.getClass().getName(), e.getMessage());
            }
        }

        return center;
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ToolRegistryCenter center) {
        return center.getRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.github.obhen233.core.command.tools.ConfigTools configTools(
            com.github.obhen233.core.config.ConfigManager configManager,
            DatabaseManager databaseManager) {
        return new com.github.obhen233.core.command.tools.ConfigTools(configManager, databaseManager);
    }

    // ========== Helper Classes ==========

    /**
     * Initializes I18n at application startup.
     */
    public static class I18nInitializer {
        public I18nInitializer(String language) {
            I18n.init(language);
            logger.info("I18n initialized with language: {}", language);
        }
    }

    /**
     * Loads config from database after DatabaseInitializer.
     */
    public static class ConfigManagerInitializer {
        private final com.github.obhen233.core.config.ConfigManager configManager;

        public ConfigManagerInitializer(com.github.obhen233.core.config.ConfigManager configManager) {
            this.configManager = configManager;
        }

        public void loadFromDatabase() {
            if (configManager != null) {
                configManager.loadFromDatabase();
                logger.info("ConfigManager.loadFromDatabase() called");
            }
        }
    }

    /**
     * Registers SystemConfigProvider SPI extensions.
     */
    public static class SystemConfigInitializer {
        private final DatabaseManager dbManager;

        public SystemConfigInitializer(DatabaseManager dbManager) {
            this.dbManager = dbManager;
        }

        public void registerExtensions() {
            CoreInitializer.registerSystemConfigExtensions(dbManager);
            logger.info("SystemConfigProvider extensions registered");
        }
    }

    /**
     * Loads and connects external MCP servers from config files.
     */
    public static class McpConfigInitializer {
        public McpConfigInitializer(McpClientManager mcpManager, String workspacePath) {
            if (mcpManager == null) {
                logger.warn("McpClientManager not available, skipping external MCP server loading");
                return;
            }
            try {
                mcpManager.loadAndConnectFromConfig(Paths.get(workspacePath), workspacePath);
                logger.info("External MCP servers loaded from config");
            } catch (Exception e) {
                logger.error("Failed to load external MCP servers from config: {}", e.getMessage());
            }
        }
    }
}
