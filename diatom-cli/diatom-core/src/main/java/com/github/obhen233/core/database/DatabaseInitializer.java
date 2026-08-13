package com.github.obhen233.core.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Database Initializer
 *
 * Loads seed data into the database:
 * 1. System config seed data
 * 2. Command whitelist seed data (from JSON)
 * 3. Command rules (built-in)
 *
 * Initialization rules:
 * 1. If key doesn't exist in database, initialize with default value
 * 2. If key exists in database but not in properties, keep database value
 * 3. If key exists in both database and properties, use properties value
 */
public class DatabaseInitializer {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);
    private static final String CONFIG_FILE = "application.properties";

    private final DatabaseManager db;
    private final SystemConfigDao systemConfigDao;
    private CommandRulesDao commandRulesDao;
    private SourceCodeExtensionsDao sourceCodeExtensionsDao;

    public DatabaseInitializer(DatabaseManager db) {
        this.db = db;
        this.systemConfigDao = new SystemConfigDao(db);
    }

    /**
     * Initialize all seed data by loading from properties file.
     * Properties file priority: JAR同级 > ~/.diatom > classpath
     */
    public void initialize() {
        initializeSystemConfig();
        migrateOldKeys();
        initializeCommandRules();
        initializeSourceCodeExtensions();
    }

    /**
     * Load properties from multiple sources with priority:
     * 1. JAR同级目录 application.properties (highest)
     * 2. ~/.diatom/application.properties
     * 3. classpath:application.properties (lowest)
     */
    private Properties loadProperties() {
        Properties props = new Properties();

        // 1. JAR同级目录
        String jarDir = System.getProperty("diatom.jar.dir");
        if (jarDir != null) {
            Path jarConfig = Paths.get(jarDir, CONFIG_FILE);
            if (Files.exists(jarConfig)) {
                try (InputStream is = new FileInputStream(jarConfig.toFile())) {
                    props.load(is);
                    logger.info("Loaded properties from JAR dir: {}", jarConfig);
                    return props;
                } catch (Exception e) {
                    logger.warn("Failed to load from JAR dir: {}", e.getMessage());
                }
            }
        }

        // 2. ~/.diatom/application.properties
        Path userConfig = Paths.get(System.getProperty("user.home"), ".diatom", CONFIG_FILE);
        if (Files.exists(userConfig)) {
            try (InputStream is = new FileInputStream(userConfig.toFile())) {
                props.load(is);
                logger.info("Loaded properties from user dir: {}", userConfig);
                return props;
            } catch (Exception e) {
                logger.warn("Failed to load from user dir: {}", e.getMessage());
            }
        }

        // 3. classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
                logger.info("Loaded properties from classpath");
            }
        } catch (Exception e) {
            logger.warn("Failed to load from classpath: {}", e.getMessage());
        }

        return props;
    }

    /**
     * Initialize system config seed data
     *
     * 初始化规则:
     * 1. 如果数据库中已有该配置，跳过（不更新，即使与 properties 不同）
     * 2. 如果数据库中没有该配置：
     *    - properties 有值 → 用 properties 值初始化
     *    - properties 没值 → 用默认值初始化
     */
    private void initializeSystemConfig() {
        Properties props = loadProperties();
        List<SystemConfigDao.SystemConfig> seedConfigs = createSystemConfigSeedData();

        int initialized = 0;
        int skipped = 0;

        for (SystemConfigDao.SystemConfig seedConfig : seedConfigs) {
            SystemConfigDao.SystemConfig existing = systemConfigDao.findByKey(seedConfig.configKey);

            if (existing != null) {
                // 数据库已有该配置，跳过（即使与 properties 不同也不更新）
                skipped++;
                if(!"api.key".equals(seedConfig.configKey))
                    logger.debug("Skipping existing config: {} (value: {})", seedConfig.configKey, existing.configValue);
            } else {
                // 数据库没有该配置，初始化
                String initValue = seedConfig.defaultValue;
                String propsValue = props.getProperty(seedConfig.configKey);
                if (propsValue != null && !propsValue.trim().isEmpty()) {
                    initValue = propsValue.trim();
                }
                seedConfig.configValue = initValue;
                systemConfigDao.insert(seedConfig);
                initialized++;
                logger.info("Initialized new config: {} = {}", seedConfig.configKey, initValue);
            }
        }

        logger.info("System config initialization completed: {} new, {} existing skipped", initialized, skipped);
    }

    /**
     * Migrate old config keys to new standardized keys.
     * If an old key exists in DB but the new key does not, copy the value and delete/clear the old key.
     */
    private void migrateOldKeys() {
        Map<String, String[]> migrations = new LinkedHashMap<>();
        migrations.put("model",            new String[]{"api.model",            "CATEGORY_API"});
        migrations.put("model.max_tokens", new String[]{"api.max_tokens",       "CATEGORY_API"});
        migrations.put("model.context_window", new String[]{"api.context_window", "CATEGORY_API"});
        migrations.put("app.language",     new String[]{"agent.language",       "CATEGORY_AGENT"});
        migrations.put("streaming.enabled", new String[]{"api.streaming",       "CATEGORY_API"});
        migrations.put("api.base.url",      new String[]{"api.url",             "CATEGORY_API"});

        int migrated = 0;
        for (Map.Entry<String, String[]> entry : migrations.entrySet()) {
            String oldKey = entry.getKey();
            String newKey = entry.getValue()[0];

            SystemConfigDao.SystemConfig oldConfig = systemConfigDao.findByKey(oldKey);
            SystemConfigDao.SystemConfig newConfig = systemConfigDao.findByKey(newKey);

            if (oldConfig != null && newConfig == null) {
                // Old key exists but new key doesn't — migrate
                SystemConfigDao.SystemConfig migratedConfig = new SystemConfigDao.SystemConfig(
                    newKey, oldConfig.configValue, oldConfig.configType, getCategoryFromNewKey(newKey));
                migratedConfig.i18nKey = oldConfig.i18nKey;
                migratedConfig.defaultValue = oldConfig.defaultValue;
                migratedConfig.allowedValues = oldConfig.allowedValues;
                migratedConfig.minValue = oldConfig.minValue;
                migratedConfig.maxValue = oldConfig.maxValue;
                migratedConfig.pattern = oldConfig.pattern;
                migratedConfig.source = "migrated";
                systemConfigDao.insert(migratedConfig);
                // Delete old key
                systemConfigDao.delete(oldKey);
                migrated++;
                logger.info("Migrated config: {} -> {} (value: {})", oldKey, newKey, oldConfig.configValue);
            } else if (oldConfig != null && newConfig != null) {
                // Both exist — delete old key, keep new key
                systemConfigDao.delete(oldKey);
                logger.debug("Removed old config key: {} (new key {} already exists)", oldKey, newKey);
                migrated++;
            }
        }

        if (migrated > 0) {
            logger.info("Config migration completed: {} keys migrated", migrated);
        }
    }

    private String getCategoryFromNewKey(String newKey) {
        if (newKey.startsWith("api.")) return "api";
        if (newKey.startsWith("agent.")) return "agent";
        if (newKey.startsWith("workspace.")) return "workspace";
        if (newKey.startsWith("command.")) return "sandbox";
        if (newKey.startsWith("logging.")) return "logging";
        if (newKey.startsWith("cleanup.")) return "cleanup";
        if (newKey.startsWith("monitor.")) return "monitor";
        return "api";
    }

    /**
     * Create system config seed data
     */
    private List<SystemConfigDao.SystemConfig> createSystemConfigSeedData() {
        List<SystemConfigDao.SystemConfig> configs = new ArrayList<>();

        // Config categories
        String CATEGORY_API = "api";
        String CATEGORY_WORKSPACE = "workspace";
        String CATEGORY_AGENT = "agent";
        String CATEGORY_SANDBOX = "sandbox";
        String CATEGORY_LOGGING = "logging";
        String CATEGORY_CLEANUP = "cleanup";

        // ============ API configs ============
        configs.add(createConfig("api.key", "", "string", CATEGORY_API,
            "config.api.key", "", null, null, null, "^[A-Za-z0-9\\-_]{20,}$"));

        configs.add(createConfig("api.url", "https://api.openai.com", "string", CATEGORY_API,
            "config.api.url", "https://api.openai.com", null, null, null, "^https?://([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(/.*)?$"));

        configs.add(createConfig("api.endpoint", "", "string", CATEGORY_API,
            "config.api.endpoint", "", null, null, null, "^/[A-Za-z0-9]+"));

        configs.add(createConfig("api.format", "auto", "enum", CATEGORY_API,
            "config.api.format", "auto", "auto,openai,anthropic,responses", null, null, "^(?i)(openai|anthropic|responses|auto)$"));

        // Model configs (merged into API category with standardized keys)
        configs.add(createConfig("api.model", "gpt-4", "string", CATEGORY_API,
            "config.api.model", "gpt-4", null, null, null, "^[A-Za-z0-9][A-Za-z0-9\\-_\\.:]*$"));

        configs.add(createConfig("api.max_tokens", "8192", "int", CATEGORY_API,
            "config.api.max_tokens", "8192", null, 1024, 409600, "^[1-9][0-9]{0,9}$"));

        configs.add(createConfig("api.context_window", "200000", "int", CATEGORY_API,
            "config.api.context_window", "200000", null, 4096, 1048576, "^[1-9][0-9]{0,9}$"));

        configs.add(createConfig("api.streaming", "true", "boolean", CATEGORY_API,
            "config.api.streaming", "true", "true,false", null, null, "^(?i)(true|false)$"));

        // ============ Workspace configs ============
        configs.add(createConfig("workspace.dir", System.getProperty("user.dir"), "string", CATEGORY_WORKSPACE,
            "config.workspace.dir", System.getProperty("user.dir"), null, null, null, "^(?:[a-zA-Z]:\\\\|/|~)?(?:[^<>:\"|?*\\x00-\\x1f]+[\\\\/])*[^<>:\"|?*\\x00-\\x1f]*$"));

        configs.add(createConfig("filesystem.allow_external", "false", "boolean", CATEGORY_WORKSPACE,
            "config.filesystem.allow_external", "false", "true,false", null, null, "^(?i)(true|false)$"));

        // ============ Agent configs ============
        configs.add(createConfig("agent.max_steps", "10", "int", CATEGORY_AGENT,
            "config.agent.max_steps", "10", null, 1, 100, "^([1-9][0-9]?|100)$"));

        configs.add(createConfig("agent.language", "zh", "enum", CATEGORY_AGENT,
            "config.agent.language", "zh", "en,zh", null, null, "^(?i)(zh|en)$"));

        // ============ Sandbox configs ============
        configs.add(createConfig("command.sandbox.mode", "whitelist", "enum", CATEGORY_SANDBOX,
            "config.command.sandbox_mode", "whitelist", "whitelist,none", null, null, "^(?i)(whitelist|none)$"));

        configs.add(createConfig("command.timeout", "60", "int", CATEGORY_SANDBOX,
            "config.command.timeout", "60", null, 1, 3600, "^([1-9][0-9]{0,2}|[1-2][0-9]{3}|3[0-5][0-9]{2}|3600)$"));

        configs.add(createConfig("command.max_output_bytes", "1048576", "int", CATEGORY_SANDBOX,
            "config.command.max_output_bytes", "1048576", null, 1024, 104857600, "^[1-9][0-9]*$"));

        // Command whitelist
        configs.add(createConfig("command.whitelist", "mvn,git,npm,node,java,javac", "string", CATEGORY_SANDBOX,
            "config.command.whitelist", "mvn,git,npm,node,java,javac", null, null, null, null));

        // LLM Command Validation configs
        configs.add(createConfig("command.whitelist.llm_enabled", "true", "boolean", CATEGORY_SANDBOX,
            "config.command.whitelist_llm", "true", "true,false", null, null, "^(?i)(true|false)$"));

        configs.add(createConfig("command.dangerous.llm_enabled", "false", "boolean", CATEGORY_SANDBOX,
            "config.command.dangerous_llm", "false", "true,false", null, null, "^(?i)(true|false)$"));

        configs.add(createConfig("command.blocked.llm_enabled", "false", "boolean", CATEGORY_SANDBOX,
            "config.command.blocked_llm", "false", "true,false", null, null, "^(?i)(true|false)$"));

        // ============ Logging configs ============
        configs.add(createConfig("logging.audit.enabled", "true", "boolean", CATEGORY_LOGGING,
            "config.logging.audit_enabled", "true", null, null, null, "^(?i)(true|false)$"));

        configs.add(createConfig("logging.change.enabled", "false", "boolean", CATEGORY_LOGGING,
            "config.logging.change_enabled", "false", null, null, null, "^(?i)(true|false)$"));

        // ============ Gateway/Task configs ============
        configs.add(createConfig("task.timeout", "1800000", "int", CATEGORY_CLEANUP,
            "config.task.timeout", "1800000", null, 0, 86400000, "^([1-9][0-9]{0,6}|0)$"));

        configs.add(createConfig("task.timeout.grace", "30000", "int", CATEGORY_CLEANUP,
            "config.task.timeout.grace", "30000", null, 1000, 300000, "^[1-9][0-9]{1,5}$"));

        configs.add(createConfig("task.timeout.action", "suspend", "enum", CATEGORY_CLEANUP,
            "config.task.timeout.action", "suspend", "suspend,fail,notify_only", null, null,
            "^(?i)(suspend|fail|notify_only)$"));

        configs.add(createConfig("checkpoint.report.steps", "3", "int", CATEGORY_CLEANUP,
            "config.checkpoint.report.steps", "3", null, 1, 100, "^([1-9][0-9]?|100)$"));

        configs.add(createConfig("checkpoint.report.tokens", "2000", "int", CATEGORY_CLEANUP,
            "config.checkpoint.report.tokens", "2000", null, 100, 100000, "^[1-9][0-9]{2,5}$"));

        // ============ Cleanup configs ============
        configs.add(createConfig("cleanup.max_snapshots_per_task", "50", "int", CATEGORY_CLEANUP,
            "config.cleanup.max_snapshots_per_task", "50", null, 1, 500, "^([1-9][0-9]?|[1-4][0-9]{2}|500)$"));

        configs.add(createConfig("cleanup.max_checkpoints_per_task", "5", "int", CATEGORY_CLEANUP,
            "config.cleanup.max_checkpoints_per_task", "5", null, 1, 50, "^([1-9]|[1-4][0-9]|50)$"));

        configs.add(createConfig("cleanup.snapshot_retention_days", "7", "int", CATEGORY_CLEANUP,
            "config.cleanup.snapshot_retention_days", "7", null, 1, 365, "^([1-9][0-9]?|[12][0-9]{2}|3[0-5][0-9]|36[0-5])$"));

        configs.add(createConfig("cleanup.task_retention_days", "30", "int", CATEGORY_CLEANUP,
            "config.cleanup.task_retention_days", "30", null, 1, 365, "^([1-9][0-9]?|[12][0-9]{2}|3[0-5][0-9]|36[0-5])$"));

        configs.add(createConfig("cleanup.completed_task_retention_days", "7", "int", CATEGORY_CLEANUP,
            "config.cleanup.completed_task_retention_days", "7", null, 1, 365, "^([1-9][0-9]?|[12][0-9]{2}|3[0-5][0-9]|36[0-5])$"));

        configs.add(createConfig("cleanup.failed_task_retention_days", "30", "int", CATEGORY_CLEANUP,
            "config.cleanup.failed_task_retention_days", "30", null, 1, 365, "^([1-9][0-9]?|[12][0-9]{2}|3[0-5][0-9]|36[0-5])$"));

        configs.add(createConfig("cleanup.snapshot_interval", "5", "int", CATEGORY_CLEANUP,
            "config.cleanup.snapshot_interval", "5", null, 1, 100, "^([1-9][0-9]?|100)$"));

        // ============ Monitor configs ============
        configs.add(createConfig("monitor.enabled", "true", "boolean", "monitor",
            "config.monitor.enabled", "true", "true,false", null, null, "^(?i)(true|false)$"));

        configs.add(createConfig("monitor.login.username", "", "string", "monitor",
            "config.monitor.login.username", "", null, null, null, null));

        configs.add(createConfig("monitor.login.password", "", "string", "monitor",
            "config.monitor.login.password", "", null, null, null, null));

        configs.add(createConfig("monitor.language", "", "string", "monitor",
            "config.monitor.language", "", null, null, null, null));

        configs.add(createConfig("monitor.prefix", "", "string", "monitor",
            "config.monitor.prefix", "", null, null, null, null));

        return configs;
    }

    private SystemConfigDao.SystemConfig createConfig(String key, String value, String type,
            String category, String i18nKey, String defaultValue, String allowedValues,
            Integer minValue, Integer maxValue, String pattern) {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(key, value, type, category);
        config.i18nKey = i18nKey;
        config.defaultValue = defaultValue;
        config.allowedValues = allowedValues;
        config.minValue = minValue;
        config.maxValue = maxValue;
        config.pattern = pattern;
        config.source = "builtin";
        return config;
    }

    /**
     * Initialize command rules (built-in)
     *
     * Rules:
     * 1. If rule doesn't exist, insert built-in rule
     * 2. If rule exists (source=built-in), skip
     * 3. If rule exists (source=manual/auto-learned), keep existing
     */
    private void initializeCommandRules() {
        if (commandRulesDao == null) {
            commandRulesDao = new CommandRulesDao(db);
        }

        List<CommandRulesDao.CommandRule> builtinRules = createBuiltinRules();
        int inserted = 0;
        int skipped = 0;
        for (CommandRulesDao.CommandRule rule : builtinRules) {
            if (commandRulesDao.insertIfNotExists(rule)) {
                inserted++;
            } else {
                skipped++;
            }
        }

        logger.info("Command rules initialized: {} new inserted, {} existing skipped", inserted, skipped);
    }

    /**
     * Initialize source code extensions (built-in).
     * Seeds the source_code_extensions table with default extensions for ToolResultSummarizer.
     * Also supports override via application.properties: source.code.extensions=.java,.js,.jsp
     */
    private void initializeSourceCodeExtensions() {
        if (sourceCodeExtensionsDao == null) {
            sourceCodeExtensionsDao = new SourceCodeExtensionsDao(db);
        }

        // Check for override from application.properties
        Properties props = loadProperties();
        String overrideExtensions = props.getProperty("source.code.extensions");
        if (overrideExtensions != null && !overrideExtensions.trim().isEmpty()) {
            // If override is provided, use only the override extensions (skip built-in defaults)
            String[] exts = overrideExtensions.split(",");
            int inserted = 0;
            for (String ext : exts) {
                ext = ext.trim();
                if (!ext.isEmpty()) {
                    if (!ext.startsWith(".")) {
                        ext = "." + ext;
                    }
                    if (sourceCodeExtensionsDao.insertIfNotExists(ext, "built-in")) {
                        inserted++;
                    }
                }
            }
            logger.info("Source code extensions initialized from config override: {} extensions ({} total)",
                inserted, exts.length);
            return;
        }

        // Default built-in extensions
        String[] builtinExtensions = {
            ".java", ".kt", ".scala", ".py", ".js", ".ts", ".tsx", ".jsx", ".go", ".rs",
            ".c", ".cpp", ".cc", ".cxx", ".h", ".hpp", ".cs", ".rb", ".php", ".swift",
            ".vue", ".svelte", ".groovy", ".clj", ".ex", ".exs", ".erl", ".hs", ".ml",
            ".jsp", ".html", ".htm", ".xml", ".properties", ".tld", ".css", ".json",
            ".yaml", ".yml"
        };

        int inserted = 0;
        int skipped = 0;
        for (String ext : builtinExtensions) {
            if (sourceCodeExtensionsDao.insertIfNotExists(ext, "built-in")) {
                inserted++;
            } else {
                skipped++;
            }
        }

        logger.info("Source code extensions initialized: {} new inserted, {} existing skipped", inserted, skipped);
    }

    /**
     * Create built-in command rules
     */
    private List<CommandRulesDao.CommandRule> createBuiltinRules() {
        List<CommandRulesDao.CommandRule> rules = new ArrayList<>();

        // Agent mode rules (strict)
        // blocked
        for (String pattern : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs", "dd if=")) {
            CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule("agent", "blocked", pattern, "built-in");
            rules.add(rule);
        }

        // dangerous
        for (String pattern : Arrays.asList("rm -rf", "del /s")) {
            CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule("agent", "dangerous", pattern, "built-in");
            rules.add(rule);
        }

        // allowed — safe commands for agent mode
        for (String pattern : Arrays.asList(
            // Shell & scripting
            "cmd", "bash", "sh", "echo", "printf",
            // File reading
            "cat", "type", "head", "tail", "less", "more",
            // Directory & navigation
            "ls", "dir", "pwd",
            // Search
            "find", "grep", "where", "which",
            // System info & environment
            "env", "printenv", "set", "tasklist", "ps", "hostname", "date", "time", "whoami", "uname",
            // Network (read-only)
            "curl", "wget", "ping",
            // Development tools
            "mvn", "git", "java", "javac", "npm", "node", "go", "python", "python3", "docker", "docker-compose",
            // Archive
            "zip", "unzip", "tar", "gzip", "gunzip",
            // Text processing
            "sort", "uniq", "wc",
            // Display
            "clear", "cls"
        )) {
            CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule("agent", "allowed", pattern, "built-in");
            rules.add(rule);
        }

        // Terminal mode rules (more permissive)
        // blocked
        for (String pattern : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs")) {
            CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule("terminal", "blocked", pattern, "built-in");
            rules.add(rule);
        }

        // dangerous - command separators
        for (String pattern : Arrays.asList("&&", "||", ";", "$|", "`")) {
            CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule("terminal", "dangerous", pattern, "built-in");
            rules.add(rule);
        }

        // allowed - basic commands for terminal mode
        for (String pattern : Arrays.asList(
            "ls", "dir", "cat", "type", "echo", "pwd", "cd",
            "git", "mvn", "npm", "node", "python", "python3", "java", "javac", "go", "docker",
            "curl", "wget",
            "head", "tail", "less", "more",
            "grep", "find", "where", "which",
            "env", "printenv", "set",
            "ps", "tasklist",
            "zip", "unzip", "tar", "gzip",
            "sort", "uniq", "wc",
            "clear", "cls",
            "hostname", "date", "time", "whoami",
            "ping"
        )) {
            CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule("terminal", "allowed", pattern, "built-in");
            rules.add(rule);
        }

        return rules;
    }
}
