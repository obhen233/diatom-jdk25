package com.github.obhen233.quarkus.runtime.kernel;

import com.github.obhen233.cli.provider.ConfigCommandProvider;
import com.github.obhen233.cli.provider.RulesCommandProvider;
import com.github.obhen233.config.AppConfig;
import com.github.obhen233.config.SystemInfo;
import com.github.obhen233.core.CoreInitializer;
import com.github.obhen233.core.adapter.AnthropicAdapter;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.adapter.OpenAIAdapter;
import com.github.obhen233.core.adapter.ResponsesAdapter;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.command.tools.ConfigTools;
import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.ContextCacheManager;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.core.database.HibernateDatabaseManager;
import com.github.obhen233.core.database.HistoryManager;
import com.github.obhen233.core.database.TaskCheckpointManager;
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
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import com.github.obhen233.quarkus.runtime.internal.PluginResourceScanner;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.CoreCommandRegistry;
import com.github.obhen233.spi.PluginClassLoader;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.ToolRegistrar;
import com.github.obhen233.util.I18n;
import org.jboss.logging.Logger;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 共享组件图，镜像 Spring Boot starter 的 {@code SharedAutoConfiguration}。
 *
 * <p>所有模式（standard / gateway / worker / child / adapter）都需要的通用组件：
 * I18n / AppConfig / SystemInfo / AuthorizedPathManager / Skill / SystemPrompt / ProjectIndexer /
 * 数据库（Hibernate + HikariCP，可空降级）/ ConfigManager / History / TaskCheckpoint /
 * ContextCache / CommandKnowledge / CommandPermission / MCP / AiHttpClient / ModelAdapter /
 * CommandTools.Config / ToolRegistryCenter + ToolRegistrar SPI / ConfigTools / CoreCommandRegistry。
 *
 * <p>不包含 ReActAgent —— 由各模式 Bootstrap 各自创建。
 */
public class SharedComponents {

    private static final Logger LOGGER = Logger.getLogger(SharedComponents.class);

    // ===== 始终可用 =====
    public final AppConfig appConfig;
    public final SystemInfo systemInfo;
    public final AuthorizedPathManager authorizedPathManager;
    public final SkillManager skillManager;
    public final SystemPromptManager systemPromptManager;
    public final ProjectIndexer projectIndexer;
    public final McpClientManager mcpClientManager;
    public final AiHttpClient aiHttpClient;
    public final ModelAdapter modelAdapter;
    public final CommandTools.Config commandConfig;
    public final ToolRegistryCenter toolRegistryCenter;
    public final ToolRegistry toolRegistry;
    public final CoreCommandRegistry coreCommandRegistry;

    // ===== 可空（数据库失败时降级为 null）=====
    public final HibernateConfig hibernateConfig;
    public final DatabaseManager databaseManager;
    public final ConfigManager configManager;
    public final HistoryManager historyManager;
    public final TaskCheckpointManager taskCheckpointManager;
    public final ContextCacheManager contextCacheManager;
    public final CommandKnowledgeManager commandKnowledgeManager;
    public final CommandPermissionEngine commandPermissionEngine;
    public final ConfigTools configTools;

    public final String workspacePath;

    private final DiatomRuntimeConfig config;
    private final PluginResourceScanner pluginScanner = new PluginResourceScanner();

    public SharedComponents(DiatomRuntimeConfig config) {
        this.config = config;

        // 1. AppConfig（自动加载 {jarDir}/.diatom/application.properties + classpath）
        this.appConfig = new AppConfig();
        if (config.app().language() != null && !config.app().language().isEmpty()) {
            appConfig.setProperty("agent.language", config.app().language());
        }
        // 1b. 将用户显式设置的 diatom.api.* 映射进 AppConfig（LLM 链路只读 AppConfig）
        applyApiConfig();

        // 2. 插件类加载器 + SPI 加载（必须在 ToolRegistryCenter 之前）
        initPluginClassLoader();
        CoreInitializer.initI18n(appConfig);
        CoreInitializer.loadSpiExtensions(appConfig);

        // 3. 工作区路径
        this.workspacePath = config.app().workspaceDir()
                .filter(s -> !s.isEmpty())
                .orElse(appConfig.getWorkspaceDir());

        // 4. 基础设施
        this.systemInfo = new SystemInfo();
        this.authorizedPathManager = new AuthorizedPathManager();
        this.authorizedPathManager.authorize(
                System.getProperty("user.home") + File.separator + ".diatom");
        this.skillManager = new SkillManager();
        this.systemPromptManager = new SystemPromptManager();
        this.projectIndexer = new ProjectIndexer(workspacePath);

        // 5. 数据库（可空降级）
        applyDatabaseSystemProperties();
        this.hibernateConfig = new HibernateConfig();
        this.databaseManager = initDatabaseManager();
        this.configManager = initConfigManager();
        this.historyManager = databaseManager != null
                ? new HistoryManager(databaseManager, 100, workspacePath) : null;
        this.taskCheckpointManager = databaseManager != null
                ? new TaskCheckpointManager(databaseManager) : null;
        this.contextCacheManager = initContextCache();
        this.commandKnowledgeManager = initCommandKnowledge();
        this.commandPermissionEngine = commandKnowledgeManager != null
                ? new CommandPermissionEngine(commandKnowledgeManager) : null;
        this.configTools = new ConfigTools(configManager, databaseManager);

        // 6. MCP
        this.mcpClientManager = new McpClientManager()
                .withLazyBuiltInServers(workspacePath, authorizedPathManager,
                        appConfig.isAllowExternalResources());
        initMcp();

        // 7. HTTP 客户端 + 模型适配器
        this.aiHttpClient = CoreInitializer.createHttpClient(appConfig);
        config.app().userAgent().ifPresent(ua -> {
            if (!ua.isEmpty()) {
                aiHttpClient.setUserAgent(ua);
            }
        });
        this.modelAdapter = buildModelAdapter();

        // 8. 命令工具配置
        this.commandConfig = buildCommandConfig();

        // 9. 工具注册中心（标准工具 + ToolRegistrar SPI）
        this.toolRegistryCenter = buildToolRegistryCenter();
        this.toolRegistry = toolRegistryCenter.getRegistry();

        // 10. Core 命令注册中心
        this.coreCommandRegistry = buildCoreCommandRegistry();
    }

    /** 逆序关停共享组件。 */
    public void close() {
        try {
            if (skillManager != null) {
                skillManager.stopFileWatcher();
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to stop skill file watcher: %s", e.getMessage());
        }
        try {
            pluginScanner.cleanup();
        } catch (Exception e) {
            LOGGER.warnf("Failed to cleanup plugin scanner: %s", e.getMessage());
        }
        if (HibernateConfig.isInitialized()) {
            try {
                HibernateConfig.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("Failed to shutdown Hibernate: %s", e.getMessage());
            }
        }
    }

    // ===================== 内部装配 =====================

    private void initPluginClassLoader() {
        try {
            Path jarDir = Paths.get(System.getProperty("diatom.jar.dir",
                    System.getProperty("user.dir", ".")));

            List<Path> dirs = new ArrayList<>();
            // 配置的额外插件路径
            if (config.plugin().paths().isPresent()) {
                for (String p : config.plugin().paths().get()) {
                    if (p == null || p.trim().isEmpty()) continue;
                    dirs.add(Paths.get(p.trim()));
                }
            }
            // PluginResourceScanner 扫描结果
            try {
                dirs.addAll(pluginScanner.discoverPluginDirs(jarDir));
            } catch (Exception e) {
                LOGGER.warnf("Plugin discovery failed: %s", e.getMessage());
            }
            // 默认插件目录
            dirs.addAll(PluginClassLoader.getDefaultPluginDirs(jarDir));

            PluginClassLoader.init(dirs.toArray(new Path[0]));
            LOGGER.info("PluginClassLoader initialized");
        } catch (Exception e) {
            LOGGER.warnf("PluginClassLoader init failed: %s", e.getMessage());
        }
    }

    private void applyDatabaseSystemProperties() {
        setSystemPropertyIfAbsent("diatom.database.url",
                config.database().url().orElse(null));
        setSystemPropertyIfAbsent("diatom.database.username",
                config.database().username().orElse(null));
        setSystemPropertyIfAbsent("diatom.database.password",
                config.database().password().orElse(null));
        if (config.database().poolSize().isPresent()) {
            setSystemPropertyIfAbsent("diatom.database.pool-size",
                    String.valueOf(config.database().poolSize().get()));
        }
        setSystemPropertyIfAbsent("diatom.database.hibernatedialect",
                config.database().dialect().orElse(null));
        setSystemPropertyIfAbsent("diatom.database.driver",
                config.database().driver().orElse(null));
    }

    private static void setSystemPropertyIfAbsent(String key, String value) {
        if (value != null && !value.isEmpty() && System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private DatabaseManager initDatabaseManager() {
        try {
            DatabaseManager db = new HibernateDatabaseManager(hibernateConfig);
            db.initialize();
            LOGGER.infof("Hibernate database initialized (type=%s)", hibernateConfig.getDbType());
            return db;
        } catch (Exception e) {
            LOGGER.errorf("Failed to initialize Hibernate database, "
                    + "history/checkpoints disabled: %s", e.getMessage());
            return null;
        }
    }

    private ConfigManager initConfigManager() {
        if (databaseManager == null) {
            LOGGER.warn("ConfigManager not created because DatabaseManager is null");
            return null;
        }
        try {
            CoreInitializer.registerSystemConfigExtensions(databaseManager);
            ConfigManager cm = new ConfigManager(databaseManager);
            cm.loadFromDatabase();
            return cm;
        } catch (Exception e) {
            LOGGER.warnf("ConfigManager init failed: %s", e.getMessage());
            return null;
        }
    }

    private ContextCacheManager initContextCache() {
        if (databaseManager == null) {
            return null;
        }
        try {
            ContextCacheManager cache = new ContextCacheManager(databaseManager);
            projectIndexer.setContextCache(cache);
            return cache;
        } catch (Exception e) {
            LOGGER.warnf("ContextCacheManager init failed: %s", e.getMessage());
            return null;
        }
    }

    private CommandKnowledgeManager initCommandKnowledge() {
        if (databaseManager == null) {
            return null;
        }
        try {
            CommandKnowledgeManager manager = new CommandKnowledgeManager(databaseManager);
            manager.loadFromDatabase();
            manager.loadSeedData();
            return manager;
        } catch (Exception e) {
            LOGGER.warnf("CommandKnowledgeManager init failed: %s", e.getMessage());
            return null;
        }
    }

    private void initMcp() {
        try {
            mcpClientManager.loadAndConnectFromConfig(Paths.get(workspacePath), workspacePath);
        } catch (Exception e) {
            LOGGER.errorf("Failed to load external MCP servers from config: %s", e.getMessage());
        }
    }

    /**
     * 将 {@code diatom.api.*} 映射进 {@link AppConfig}，让 LLM 链路（AiHttpClient/ModelAdapter）
     * 真正读到 quarkus 配置。只映射用户显式设置的值（与 quarkus 默认值比较），
     * 避免用 quarkus 默认值覆盖 {@code {jarDir}/.diatom/application.properties} 里的文件配置。
     */
    private void applyApiConfig() {
        DiatomRuntimeConfig.Api api = config.api();
        api.key().ifPresent(k -> {
            if (!k.isEmpty()) appConfig.setProperty("api.key", k);
        });
        api.endpoint().ifPresent(e -> {
            if (!e.isEmpty()) appConfig.setProperty("api.endpoint", e);
        });
        // AppConfig.getBaseUrl() 优先读 api.url，故 base-url 映射到 api.url
        if (!"https://api.openai.com".equals(api.baseUrl())) {
            appConfig.setProperty("api.url", api.baseUrl());
        }
        if (!"auto".equals(api.format())) {
            appConfig.setProperty("api.format", api.format());
        }
        if (!"gpt-4".equals(api.model())) {
            appConfig.setProperty("api.model", api.model());
        }
        if (api.maxTokens() != 8192) {
            appConfig.setProperty("api.max_tokens", String.valueOf(api.maxTokens()));
        }
        if (api.contextWindow() != 200000) {
            appConfig.setProperty("api.context_window", String.valueOf(api.contextWindow()));
        }
    }

    private ModelAdapter buildModelAdapter() {
        boolean isAnthropic = CoreInitializer.detectAnthropicFormat(appConfig);
        if (isAnthropic) {
            return new AnthropicAdapter(appConfig.getModel(), appConfig.getMaxTokens());
        }
        if (CoreInitializer.detectResponsesFormat(appConfig)) {
            return new ResponsesAdapter(appConfig.getModel(), appConfig.getMaxTokens());
        }
        return new OpenAIAdapter(appConfig.getModel(), appConfig.getMaxTokens());
    }

    private CommandTools.Config buildCommandConfig() {
        CommandTools.Config cfg = new CommandTools.Config()
                .setAllowedCommands(appConfig.getCommandWhitelist())
                .setTimeoutSeconds(appConfig.getCommandTimeout())
                .setMaxOutputBytes(appConfig.getCommandMaxOutputBytes())
                .setAllowAll(!appConfig.isCommandWhitelistMode())
                .setWorkingDir(workspacePath)
                .setShellType(systemInfo.getShellType())
                .setShellPath(systemInfo.getDetectedShell())
                .setMavenPath(systemInfo.getDetectedMaven())
                .setPythonPath(systemInfo.getDetectedPython())
                .setGitPath(systemInfo.getDetectedGitPath());
        if (databaseManager != null) {
            cfg.setDatabaseManager(databaseManager);
        }
        return cfg;
    }

    private ToolRegistryCenter buildToolRegistryCenter() {
        ToolRegistryCenter.Config registryConfig = new ToolRegistryCenter.Config()
                .setWorkspaceDir(workspacePath)
                .setAuthManager(authorizedPathManager)
                .setCommandConfig(commandConfig)
                .setStandaloneMode(true)
                .setSkillManager(skillManager)
                .setPromptManager(systemPromptManager)
                .setConfigManager(configManager)
                .setDbManager(databaseManager);

        ToolRegistryCenter center = ToolRegistryCenter.createStandard(registryConfig);
        LOGGER.info("Tool registry initialized with standard tools");

        for (ToolRegistrar registrar : SpiLoader.getAll(ToolRegistrar.class)) {
            try {
                registrar.registerTools(center.getRegistry());
                LOGGER.infof("Registered tools from: %s", registrar.getClass().getName());
            } catch (Exception e) {
                LOGGER.warnf("Failed to register tools from %s: %s",
                        registrar.getClass().getName(), e.getMessage());
            }
        }
        return center;
    }

    private CoreCommandRegistry buildCoreCommandRegistry() {
        CoreCommandRegistry registry = new CoreCommandRegistry();
        for (CoreCommandProvider provider : SpiLoader.getAll(CoreCommandProvider.class)) {
            try {
                registry.register(provider);
            } catch (Exception e) {
                LOGGER.warnf("Failed to register core command %s: %s",
                        provider.getClass().getName(), e.getMessage());
            }
        }
        // 注入命令依赖（镜像 starter ConfigCommandInitializer / RulesCommandInitializer）
        for (CoreCommandProvider provider : registry.getAll()) {
            if (provider instanceof ConfigCommandProvider && configTools != null) {
                ((ConfigCommandProvider) provider).setConfigTools(configTools);
            } else if (provider instanceof RulesCommandProvider && databaseManager != null) {
                ((RulesCommandProvider) provider).setCommandRulesDao(new CommandRulesDao(databaseManager));
            }
        }
        return registry;
    }

    /**
     * 用 {@link ReActAgent} 初始化所有 CoreCommandProvider
     * （镜像 starter {@code CoreCommandConfiguration.CoreCommandInitializer}）。
     * standard / worker 模式构建 agent 后调用；gateway / adapter 模式无 agent 时返回空操作，
     * config / rules / help 等不依赖 agent 的命令不受影响。
     */
    public void initCoreCommandAgent(ReActAgent agent) {
        if (coreCommandRegistry == null || agent == null) {
            return;
        }
        for (CoreCommandProvider provider : coreCommandRegistry.getAll()) {
            try {
                provider.init(agent);
            } catch (Exception e) {
                LOGGER.warnf("Failed to init core command %s with agent: %s",
                        provider.getCommandName(), e.getMessage());
            }
        }
    }
}
