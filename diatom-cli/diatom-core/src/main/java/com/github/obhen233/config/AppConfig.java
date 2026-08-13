package com.github.obhen233.config;

import com.github.obhen233.core.adapter.ProviderRegistry;
import com.github.obhen233.util.ApiUrlUtils;
import com.github.obhen233.util.InstallPaths;
import com.github.obhen233.util.WorkspaceDirResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    private final Properties props = new Properties();
    private String loadedFromPath;
    private String profile;
    private static final String PROPERTIES_FILE = "application.properties";
    private static final String YAML_FILE = "application.yml";
    private static final String YAML_ALT_FILE = "application.yaml";

    public AppConfig() {
        this(null);
    }

    public AppConfig(String profile) {
        this.profile = profile;
        loadConfig();
    }

    /**
     * Load configuration with dual-format (YAML + properties) support.
     * <p>
     * Priority (highest to lowest):
     * 1. JAR-dir application.yml > JAR-dir application.properties
     * 2. {jarDir}/.diatom/application.yml > {jarDir}/.diatom/application.properties (install-level)
     * 3. classpath application.yml > classpath application.properties
     * <p>
     * When both YAML and properties exist at the same level, YAML keys override properties keys.
     */
    private void loadConfig() {
        Path jarDir = getJarDirectory();

        // === Priority 1: JAR同级目录 ===
        if (jarDir != null) {
            // Try YAML first (higher priority than properties at same level)
            Map<String, String> jarYaml = loadYamlIfExists(jarDir.resolve(YAML_FILE));
            if (jarYaml.isEmpty()) {
                jarYaml = loadYamlIfExists(jarDir.resolve(YAML_ALT_FILE));
            }
            if (!jarYaml.isEmpty()) {
                jarYaml.forEach(props::setProperty);
                loadedFromPath = jarDir.resolve(YAML_FILE).toString();
                logger.debug("Loaded config from JAR dir YAML: {}", loadedFromPath);
            }

            // Then load properties (YAML keys will override properties if both exist,
            // but since YAML is loaded first, properties won't overwrite existing YAML keys)
            Path jarProps = jarDir.resolve(PROPERTIES_FILE);
            if (Files.exists(jarProps)) {
                try (InputStream is = Files.newInputStream(jarProps)) {
                    Properties jarProp = new Properties();
                    jarProp.load(is);
                    jarProp.forEach((k, v) -> {
                        if (!props.containsKey(k)) {
                            props.setProperty((String) k, (String) v);
                        }
                    });
                    if (loadedFromPath == null) {
                        loadedFromPath = jarProps.toString();
                    }
                    logger.debug("Loaded config from JAR dir props: {}", jarProps);
                } catch (Exception e) {
                    logger.warn("Failed to load config from JAR dir: {}", e.getMessage());
                }
            }

        }

        // === Priority 2: {jarDir}/.diatom/ （安装级配置） ===
        Path installDir = InstallPaths.getInstallHome();
        loadConfigFromDir(installDir, "install dir");

        // === Priority 3: classpath 资源 ===
        // YAML first
        Map<String, String> cpYaml = YamlConfigLoader.loadFlatFromClasspath(YAML_FILE);
        if (cpYaml.isEmpty()) {
            cpYaml = YamlConfigLoader.loadFlatFromClasspath(YAML_ALT_FILE);
        }
        if (!cpYaml.isEmpty()) {
            cpYaml.forEach((k, v) -> {
                if (!props.containsKey(k)) {
                    props.setProperty(k, v);
                }
            });
            if (loadedFromPath == null) {
                loadedFromPath = "classpath:" + YAML_FILE;
            }
            logger.debug("Loaded config from classpath YAML");
        }

        // Then properties
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is != null) {
                Properties cpProp = new Properties();
                cpProp.load(is);
                cpProp.forEach((k, v) -> {
                    if (!props.containsKey(k)) {
                        props.setProperty((String) k, (String) v);
                    }
                });
                if (loadedFromPath == null) {
                    loadedFromPath = "classpath:" + PROPERTIES_FILE;
                }
                logger.debug("Loaded config from classpath properties");
            }
        } catch (Exception e) {
            logger.warn("Failed to load config from classpath: {}", e.getMessage());
        }

        // === Profile-specific config (application-{profile}.properties/yml/yaml) ===
        // Loaded after base config so profile values override base values at the same level.
        if (profile != null && !profile.trim().isEmpty()) {
            loadProfileConfig();
        }

        // Bridge diatom.database.* keys to System properties (standalone property bridge).
        // HibernateConfig only reads System.getProperty("diatom.database.*"); this makes
        // standalone CLI users able to configure the DB directly in application.properties.
        exportDatabaseProps();
    }

    /**
     * Export {@code diatom.database.*} keys to System properties.
     *
     * <p>standalone CLI 的属性桥接：{@link com.github.obhen233.core.database.HibernateConfig}
     * 只读 {@code System.getProperty("diatom.database.*")}，而本类把配置文件读进自身
     * {@code props} 并不导出。此方法在配置加载后把 {@code diatom.database.*} 键桥接到
     * System 属性，使 CLI 用户可直接在 {@code application.properties} / {@code application.yml}
     * 里配置数据库，体验与 springboot-starter 一致。</p>
     *
     * <p>优先级：已存在的 System 属性（如 {@code -Ddiatom.database.url=...} JVM 参数）
     * 不被覆盖；空值跳过。</p>
     */
    public void exportDatabaseProps() {
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith("diatom.database.")) {
                continue;
            }
            if (System.getProperty(name) != null) {
                continue; // 显式 System 属性（-D 等）优先
            }
            String value = props.getProperty(name);
            if (value != null && !value.trim().isEmpty()) {
                System.setProperty(name, value);
                logger.info("Bridged config key {} to system property", name);
            }
        }
    }

    /**
     * Load profile-specific configuration from the same locations as base config.
     * Profile values override base config values.
     */
    private void loadProfileConfig() {
        Path jarDir = getJarDirectory();

        // Priority 1: JAR-dir profile config
        if (jarDir != null) {
            loadProfileFromDir(jarDir);
        }

        // Priority 2: {jarDir}/.diatom/ profile config
        Path installDir = InstallPaths.getInstallHome();
        if (installDir != null) {
            loadProfileFromDir(installDir);
        }

        // Priority 3: classpath profile config
        String yamlName = "application-" + profile + ".yml";
        Map<String, String> cpYaml = YamlConfigLoader.loadFlatFromClasspath(yamlName);
        if (cpYaml.isEmpty()) {
            cpYaml = YamlConfigLoader.loadFlatFromClasspath("application-" + profile + ".yaml");
        }
        if (!cpYaml.isEmpty()) {
            cpYaml.forEach((k, v) -> {
                if (!props.containsKey(k)) {
                    props.setProperty(k, v);
                }
            });
            logger.info("Loaded profile [{}] config from classpath: {}", profile, yamlName);
        }

        String propsName = "application-" + profile + ".properties";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(propsName)) {
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                p.forEach((k, v) -> {
                    if (!props.containsKey(k)) {
                        props.setProperty((String) k, (String) v);
                    }
                });
                logger.info("Loaded profile [{}] config from classpath: {}", profile, propsName);
            }
        } catch (Exception e) {
            logger.warn("Failed to load profile config from classpath: {}", e.getMessage());
        }
    }

    /**
     * Load profile YAML and properties from a specific directory.
     * Profile YAML overrides profile properties at the same level.
     */
    private void loadProfileFromDir(Path dir) {
        if (dir == null) return;

        // Profile YAML first (highest priority within this level)
        String yamlName = "application-" + profile + ".yml";
        Map<String, String> yaml = loadYamlIfExists(dir.resolve(yamlName));
        if (yaml.isEmpty()) {
            yaml = loadYamlIfExists(dir.resolve("application-" + profile + ".yaml"));
        }
        if (!yaml.isEmpty()) {
            yaml.forEach((k, v) -> {
                if (!props.containsKey(k)) {
                    props.setProperty(k, v);
                }
            });
            logger.debug("Loaded profile [{}] config from: {}/{}", profile, dir, yamlName);
        }

        // Profile properties (only for keys not already set by profile YAML at same level)
        String propsName = "application-" + profile + ".properties";
        Path propsPath = dir.resolve(propsName);
        if (Files.exists(propsPath)) {
            try (InputStream is = Files.newInputStream(propsPath)) {
                Properties p = new Properties();
                p.load(is);
                p.forEach((k, v) -> {
                    if (!props.containsKey(k)) {
                        props.setProperty((String) k, (String) v);
                    }
                });
                logger.debug("Loaded profile [{}] config from: {}", profile, propsPath);
            } catch (Exception e) {
                logger.warn("Failed to load profile config from {}: {}", propsPath, e.getMessage());
            }
        }
    }

    /**
     * Try loading YAML then properties from the given config directory.
     * YAML keys take priority over properties at the same level.
     */
    private void loadConfigFromDir(Path configDir, String label) {
        if (configDir == null) return;

        // YAML first
        Map<String, String> yaml = loadYamlIfExists(configDir.resolve(YAML_FILE));
        if (yaml.isEmpty()) {
            yaml = loadYamlIfExists(configDir.resolve(YAML_ALT_FILE));
        }
        if (!yaml.isEmpty()) {
            yaml.forEach((k, v) -> {
                if (!props.containsKey(k)) {
                    props.setProperty(k, v);
                }
            });
            loadedFromPath = configDir.resolve(YAML_FILE).toString();
            logger.debug("Loaded config from {} YAML: {}", label, loadedFromPath);
        }

        // Then properties (only for keys not already set)
        Path propsPath = configDir.resolve(PROPERTIES_FILE);
        if (Files.exists(propsPath)) {
            try (InputStream is = Files.newInputStream(propsPath)) {
                Properties dirProp = new Properties();
                dirProp.load(is);
                dirProp.forEach((k, v) -> {
                    if (!props.containsKey(k)) {
                        props.setProperty((String) k, (String) v);
                    }
                });
                if (loadedFromPath == null) {
                    loadedFromPath = propsPath.toString();
                }
                logger.debug("Loaded config from {} props: {}", label, propsPath);
            } catch (Exception e) {
                logger.warn("Failed to load config from {}: {}", label, e.getMessage());
            }
        }
    }

    /**
     * Try to load a YAML file and return flattened key-value pairs.
     */
    private Map<String, String> loadYamlIfExists(Path yamlPath) {
        if (yamlPath != null && Files.exists(yamlPath)) {
            return YamlConfigLoader.loadFlat(yamlPath);
        }
        return Collections.emptyMap();
    }

    public String getLoadedFromPath() {
        return loadedFromPath;
    }

    /**
     * Get the active profile name, or null if no profile is active.
     */
    public String getProfile() {
        return profile;
    }

    private Path getJarDirectory() {
        // 优先使用Bootstrap设置的diatom.jar.dir属性
        String jarDirProp = System.getProperty("diatom.jar.dir");
        if (jarDirProp != null) {
            return Paths.get(jarDirProp);
        }

        //  fallback：通过类加载位置推断
        try {
            String path = AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            Path jarPath = Paths.get(path);
            if (jarPath.getFileName().toString().endsWith(".jar")) {
                return jarPath.getParent();
            }
        } catch (Exception e) {
            // Not running from JAR
        }
        return null;
    }

    public void syncToUserDir() {
        try {
            Path jarDir = getJarDirectory();
            Path installDir = InstallPaths.getInstallHome();
            Files.createDirectories(installDir);
            Path installConfig = installDir.resolve(PROPERTIES_FILE);

            // 1. JAR 同级目录有配置文件 → 同步到安装级
            if (jarDir != null) {
                Path jarSideConfig = jarDir.resolve(PROPERTIES_FILE);
                if (Files.exists(jarSideConfig)) {
                    if (!Files.exists(installConfig) || !isSameConfig(jarSideConfig, installConfig)) {
                        Files.copy(jarSideConfig, installConfig, StandardCopyOption.REPLACE_EXISTING);
                        logger.info("Config synced from JAR directory: {} -> {}", jarSideConfig, installConfig);
                    }
                    return;
                }
            }

            // 2. 都没有配置文件，在安装级创建默认配置
            if (!Files.exists(installConfig)) {
                if (props.isEmpty()) {
                    props.setProperty("api.key", "");
                    props.setProperty("api.url", "https://api.openai.com/v1");
                    props.setProperty("api.model", "gpt-4");
                    props.setProperty("workspace.dir", "${user.dir}");
                    props.setProperty("agent.max_steps", "10");
                }
                Files.createDirectories(installDir);
                props.store(new FileOutputStream(installConfig.toFile()), "Diatom CLI Config");
                logger.info("Config created at: {}", installConfig);
            }
        } catch (Exception e) {
            logger.warn("Failed to sync config: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 比较两个配置文件是否相同（忽略注释和顺序）
     */
    private boolean isSameConfig(Path config1, Path config2) throws Exception {
        Properties p1 = new Properties();
        Properties p2 = new Properties();

        try (InputStream is1 = Files.newInputStream(config1);
             InputStream is2 = Files.newInputStream(config2)) {
            p1.load(is1);
            p2.load(is2);
        }

        // 比较所有属性
        if (p1.size() != p2.size()) {
            return false;
        }

        for (String key : p1.stringPropertyNames()) {
            String value1 = p1.getProperty(key);
            String value2 = p2.getProperty(key);
            if (value1 == null || !value1.equals(value2)) {
                return false;
            }
        }

        return true;
    }

    // =============================================
    // Standardized getters (new key names)
    // =============================================

    public String getApiKey() {
        return props.getProperty("api.key", "");
    }

    public String getBaseUrl() {
        String url = props.getProperty("api.url");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        url = props.getProperty("api.base.url");
        if (url != null && !url.isEmpty()) {
            logger.warn("Config key 'api.base.url' is deprecated, use 'api.url' instead");
            return url;
        }
        return "https://api.openai.com";
    }

    /**
     * 获取API端点路径，可在配置文件中通过 api.endpoint 指定。
     * 例如: /v1/chat/completions 或 /v1/chat/messages
     * 未配置时返回 null，由 ProviderRegistry 根据模型名自动推断。
     */
    public String getEndpoint() {
        String endpoint = props.getProperty("api.endpoint");
        if (endpoint == null) {
            return null;
        }
        // 去除首尾空白和引号
        endpoint = endpoint.trim();
        if ((endpoint.startsWith("\"") && endpoint.endsWith("\""))
                || (endpoint.startsWith("'") && endpoint.endsWith("'"))) {
            endpoint = endpoint.substring(1, endpoint.length() - 1).trim();
        }
        return endpoint.isEmpty() ? null : endpoint;
    }

    /**
     * Gets the full API URL including endpoint, resolved from baseUrl + model.
     * <p>
     * Priority: api.endpoint (config) > api.format=responses → /v1/responses
     *           > ProviderRegistry (model-based lookup)
     */
    public String getApiUrl() {
        String endpoint = getEndpoint();
        if (endpoint != null && !endpoint.isEmpty()) {
            return getBaseUrl() + endpoint;
        }
        if ("responses".equals(getApiFormat())) {
            return ApiUrlUtils.openaiResponsesUrl(getBaseUrl());
        }
        return ProviderRegistry.resolveEndpoint(getModel(), getBaseUrl());
    }

    /**
     * Gets the full API URL with strict mode (throws on unknown model).
     */
    public String getApiUrl(boolean strict) {
        String endpoint = getEndpoint();
        if (endpoint != null && !endpoint.isEmpty()) {
            return getBaseUrl() + endpoint;
        }
        if ("responses".equals(getApiFormat())) {
            return ApiUrlUtils.openaiResponsesUrl(getBaseUrl());
        }
        return ProviderRegistry.resolveEndpoint(getModel(), getBaseUrl(), strict);
    }

    /**
     * 获取 API 格式配置。
     * 可选值: auto (默认), openai, anthropic
     * auto 模式下根据模型名和 endpoint 自动检测。
     */
    public String getApiFormat() {
        String format = props.getProperty("api.format", "auto");
        if (format != null) {
            format = format.trim().toLowerCase();
            if (format.startsWith("\"") || format.startsWith("'")) {
                format = format.replaceAll("[\"']", "").trim();
            }
        }
        return (format == null || format.isEmpty()) ? "auto" : format;
    }

    // =============================================
    // Standardized model-related getters
    // =============================================

    /**
     * 获取模型名称。先读新 key {@code api.model}，再回退读旧 key {@code model}。
     */
    public String getModel() {
        String model = props.getProperty("api.model");
        if (model != null && !model.isEmpty()) {
            return model;
        }
        model = props.getProperty("model");
        if (model != null && !model.isEmpty()) {
            logger.warn("Config key 'model' is deprecated, use 'api.model' instead");
            return model;
        }
        return "gpt-4";
    }

    /**
     * Standardized getter for api.model.
     */
    public String getApiModel() {
        return props.getProperty("api.model", "gpt-4");
    }

    /**
     * 模型输出最大 token 数。先读新 key {@code api.max_tokens}，再回退读旧 key {@code model.max_tokens}。
     * 默认 8192。
     */
    public int getMaxTokens() {
        String val = props.getProperty("api.max_tokens");
        if (val != null && !val.isEmpty()) {
            return Integer.parseInt(val);
        }
        val = props.getProperty("model.max_tokens");
        if (val != null && !val.isEmpty()) {
            logger.warn("Config key 'model.max_tokens' is deprecated, use 'api.max_tokens' instead");
            return Integer.parseInt(val);
        }
        return 8192;
    }

    /**
     * Standardized getter for api.max_tokens.
     */
    public int getApiMaxTokens() {
        return Integer.parseInt(props.getProperty("api.max_tokens", "8192"));
    }

    /**
     * 模型上下文窗口大小（token 数）。
     * 先读新 key {@code api.context_window}，再回退读旧 key {@code model.context_window}。
     * 默认 200000。
     */
    public int getContextWindow() {
        String val = props.getProperty("api.context_window");
        if (val != null && !val.isEmpty()) {
            return Integer.parseInt(val);
        }
        val = props.getProperty("model.context_window");
        if (val != null && !val.isEmpty()) {
            logger.warn("Config key 'model.context_window' is deprecated, use 'api.context_window' instead");
            return Integer.parseInt(val);
        }
        return 200000;
    }

    /**
     * Standardized getter for api.context_window.
     */
    public int getApiContextWindow() {
        return Integer.parseInt(props.getProperty("api.context_window", "200000"));
    }

    // =============================================
    // Language & streaming (standardized)
    // =============================================

    /**
     * 获取语言设置: en=English, zh=Chinese
     * 先读新 key {@code agent.language}，再回退读旧 key {@code app.language}。
     */
    public String getLanguage() {
        String val = props.getProperty("agent.language");
        if (val != null && !val.isEmpty()) {
            return val;
        }
        val = props.getProperty("app.language");
        if (val != null && !val.isEmpty()) {
            logger.warn("Config key 'app.language' is deprecated, use 'agent.language' instead");
            return val;
        }
        return "zh";
    }

    /**
     * 是否启用流式输出。先读新 key {@code api.streaming}，再回退读旧 key {@code streaming.enabled}。
     */
    public boolean isStreamingEnabled() {
        String val = props.getProperty("api.streaming");
        if (val != null && !val.isEmpty()) {
            return Boolean.parseBoolean(val);
        }
        val = props.getProperty("streaming.enabled");
        if (val != null && !val.isEmpty()) {
            logger.warn("Config key 'streaming.enabled' is deprecated, use 'api.streaming' instead");
            return Boolean.parseBoolean(val);
        }
        return true;
    }

    /**
     * Standardized getter for api.streaming.
     */
    public boolean getApiStreamingEnabled() {
        return Boolean.parseBoolean(props.getProperty("api.streaming", "true"));
    }

    // =============================================
    // Workspace
    // =============================================

    public String getWorkspaceDir() {
        return WorkspaceDirResolver.resolve(this);
    }

    public int getMaxSteps() {
        return Integer.parseInt(props.getProperty("agent.max_steps", "10"));
    }

    /**
     * 是否允许访问工作区外的资源
     * true = 允许访问任意路径
     * false = 需要授权或仅限工作区 (默认)
     */
    public boolean isAllowExternalResources() {
        return Boolean.parseBoolean(props.getProperty("filesystem.allow_external", "false"));
    }

    // === 命令沙箱配置 ===

    /**
     * 沙箱模式: whitelist=白名单模式, none=无限制
     */
    public String getCommandSandboxMode() {
        return props.getProperty("command.sandbox.mode", "whitelist");
    }

    /**
     * 是否使用白名单模式
     */
    public boolean isCommandWhitelistMode() {
        return "whitelist".equalsIgnoreCase(getCommandSandboxMode());
    }

    /**
     * 获取允许执行的命令列表
     */
    public java.util.Set<String> getCommandWhitelist() {
        String whitelist = props.getProperty("command.whitelist", "mvn,git,npm,node,java,javac");
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String cmd : whitelist.split(",")) {
            String trimmed = cmd.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }

    /**
     * 命令最大执行时间(秒)
     */
    public int getCommandTimeout() {
        return Integer.parseInt(props.getProperty("command.timeout", "60"));
    }

    /**
     * 命令输出最大字节数
     */
    public int getCommandMaxOutputBytes() {
        return Integer.parseInt(props.getProperty("command.max_output_bytes", "1048576"));
    }

    // === 历史记录配置 ===

    /**
     * 历史记录最大保存条数，默认100
     */
    public int getHistoryMaxSize() {
        return Integer.parseInt(props.getProperty("command.history.max_size", "100"));
    }

    // === 日志配置 ===

    /**
     * 是否开启审计日志 (audit*.log)
     * 默认开启
     */
    public boolean isAuditLogEnabled() {
        return Boolean.parseBoolean(props.getProperty("logging.audit.enabled", "true"));
    }

    /**
     * 是否开启文件修改存储日志 (change*.log)
     * 默认关闭
     */
    public boolean isChangeLogEnabled() {
        return Boolean.parseBoolean(props.getProperty("logging.change.enabled", "false"));
    }

    // === 清理策略配置 ===

    /**
     * 每个任务最多保留快照数量
     * 默认 50
     */
    public int getMaxSnapshotsPerTask() {
        return Integer.parseInt(props.getProperty("cleanup.max_snapshots_per_task", "50"));
    }

    /**
     * 每个任务最多保留检查点数量
     * 默认 5
     */
    public int getMaxCheckpointsPerTask() {
        return Integer.parseInt(props.getProperty("cleanup.max_checkpoints_per_task", "5"));
    }

    /**
     * 快照保留天数
     * 默认 7 天
     */
    public int getSnapshotRetentionDays() {
        return Integer.parseInt(props.getProperty("cleanup.snapshot_retention_days", "7"));
    }

    /**
     * 任务总保留天数
     * 默认 30 天
     */
    public int getTaskRetentionDays() {
        return Integer.parseInt(props.getProperty("cleanup.task_retention_days", "30"));
    }

    /**
     * 已完成任务保留天数
     * 默认 7 天
     */
    public int getCompletedTaskRetentionDays() {
        return Integer.parseInt(props.getProperty("cleanup.completed_task_retention_days", "7"));
    }

    /**
     * 失败任务保留天数
     * 默认 30 天
     */
    public int getFailedTaskRetentionDays() {
        return Integer.parseInt(props.getProperty("cleanup.failed_task_retention_days", "30"));
    }

    /**
     * 快照创建间隔（操作次数）
     * 默认 5
     */
    public int getSnapshotInterval() {
        return Integer.parseInt(props.getProperty("cleanup.snapshot_interval", "5"));
    }

    // === 任务超时配置 ===

    /**
     * 任务总超时（毫秒），0 表示无限制
     * 默认 1800000（30 分钟）
     */
    public long getTaskTimeoutMs() {
        return Long.parseLong(props.getProperty("task.timeout", "1800000"));
    }

    /**
     * 超时后宽限期（毫秒），等待 Worker 保存 checkpoint
     * 默认 30000（30 秒）
     */
    public long getTaskTimeoutGraceMs() {
        return Long.parseLong(props.getProperty("task.timeout.grace", "30000"));
    }

    /**
     * 超时后的处理策略
     * suspend | fail | notify_only
     */
    public String getTaskTimeoutAction() {
        return props.getProperty("task.timeout.action", "suspend");
    }

    /**
     * 每 N 步上报一次 checkpoint
     * 默认 3
     */
    public int getCheckpointReportSteps() {
        return Integer.parseInt(props.getProperty("checkpoint.report.steps", "3"));
    }

    /**
     * 每消耗 N token 上报一次 checkpoint
     * 默认 2000
     */
    public int getCheckpointReportTokens() {
        return Integer.parseInt(props.getProperty("checkpoint.report.tokens", "2000"));
    }

    /**
     * Get a generic property value by key.
     * @param key the property key
     * @param defaultValue fallback if not set
     * @return the property value, or defaultValue
     */
    public String getProperty(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    /**
     * Set a property value.
     * This allows ConfigProvider to add or override configuration.
     * @param key the property key
     * @param value the property value
     */
    public void setProperty(String key, String value) {
        props.setProperty(key, value);
    }

}
