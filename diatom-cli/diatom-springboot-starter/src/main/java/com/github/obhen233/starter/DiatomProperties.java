package com.github.obhen233.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Diatom AI programming assistant.
 * Maps diatom.* properties from Spring Boot's application.properties/yml.
 *
 * <pre>
 * diatom.api.key=sk-xxx
 * diatom.api.base-url=https://api.example.com
 * diatom.api.model=gpt-4
 * diatom.api.format=auto
 * diatom.api.max-tokens=8192
 * diatom.agent.language=zh
 * diatom.app.workspace-dir=${user.dir}
 * diatom.agent.max-steps=10
 * diatom.command.whitelist=mvn,git,npm,node
 * diatom.command.sandbox-mode=whitelist
 * diatom.command.timeout=60
 * diatom.core.upgrade-enabled=true
 * diatom.core.upgrade-policy=prompt
 * </pre>
 */
@ConfigurationProperties(prefix = "diatom")
public class DiatomProperties {

    /** 运行模式：standard / gateway / worker / adapter */
    private String mode = "standard";

    private Api api = new Api();
    private App app = new App();
    private Agent agent = new Agent();
    private Command command = new Command();
    private Core core = new Core();
    private Ide ide = new Ide();
    private Database database = new Database();
    private Plugin plugin = new Plugin();

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }
    public App getApp() { return app; }
    public void setApp(App app) { this.app = app; }
    public Agent getAgent() { return agent; }
    public void setAgent(Agent agent) { this.agent = agent; }
    public Command getCommand() { return command; }
    public void setCommand(Command command) { this.command = command; }
    public Core getCore() { return core; }
    public void setCore(Core core) { this.core = core; }
    public Ide getIde() { return ide; }
    public void setIde(Ide ide) { this.ide = ide; }
    public Database getDatabase() { return database; }
    public void setDatabase(Database database) { this.database = database; }
    public Plugin getPlugin() { return plugin; }
    public void setPlugin(Plugin plugin) { this.plugin = plugin; }

    /**
     * API configuration.
     */
    public static class Api {
        /** API key for authentication */
        private String key = "";
        /** API base URL */
        private String baseUrl = "https://api.openai.com";
        /** API endpoint path (e.g., /anthropic) */
        private String endpoint = "";
        /** Model name (e.g., gpt-4, claude-3) */
        private String model = "gpt-4";
        /** API format: auto, openai, anthropic */
        private String format = "auto";
        /** Maximum tokens for model response */
        private int maxTokens = 8192;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    /**
     * Application configuration.
     */
    public static class App {
        /** Workspace directory */
        private String workspaceDir = "${user.dir}";
        /** Custom User-Agent header (e.g., "Diatom-CLI/1.0.0 (IntelliJ IDEA)") */
        private String userAgent = "";

        public String getWorkspaceDir() { return workspaceDir; }
        public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    }

    /**
     * Agent configuration.
     */
    public static class Agent {
        /** Maximum agent execution steps */
        private int maxSteps = 10;
        /** Agent language (en, zh) */
        private String language = "zh";

        public int getMaxSteps() { return maxSteps; }
        public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
    }

    /**
     * Command sandbox configuration.
     */
    public static class Command {
        /** Sandbox mode: whitelist, none */
        private String sandboxMode = "whitelist";
        /** Allowed command whitelist */
        private List<String> whitelist = new ArrayList<>();
        /** Command execution timeout in seconds */
        private int timeout = 60;

        public String getSandboxMode() { return sandboxMode; }
        public void setSandboxMode(String sandboxMode) { this.sandboxMode = sandboxMode; }
        public List<String> getWhitelist() { return whitelist; }
        public void setWhitelist(List<String> whitelist) { this.whitelist = whitelist; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }

        public String getWhitelistAsString() {
            return String.join(",", whitelist);
        }
    }

    /**
     * Core upgrade configuration.
     */
    public static class Core {
        /** Enable core upgrade check on startup */
        private boolean upgradeEnabled = true;
        /** Upgrade policy: prompt, auto, disable */
        private String upgradePolicy = "prompt";

        public boolean isUpgradeEnabled() { return upgradeEnabled; }
        public void setUpgradeEnabled(boolean upgradeEnabled) { this.upgradeEnabled = upgradeEnabled; }
        public String getUpgradePolicy() { return upgradePolicy; }
        public void setUpgradePolicy(String upgradePolicy) { this.upgradePolicy = upgradePolicy; }
    }

    /**
     * IDE integration configuration.
     * When enabled, AI settings can be dynamically synced from the IDE at runtime.
     */
    public static class Ide {
        /** Enable IDE integration mode */
        private boolean enabled = false;
        /** IDE API URL override */
        private String apiUrl = "";
        /** IDE API token override */
        private String apiToken = "";
        /** IDE model override */
        private String model = "";
        /** IDE workspace path override */
        private String workspacePath = "";
        /** IDE language setting */
        private String language = "zh";
        /** Enable IDE WebSocket terminal */
        private boolean terminalEnabled = false;
        /** WebSocket terminal path */
        private String terminalPath = "/ide/terminal";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiToken() { return apiToken; }
        public void setApiToken(String apiToken) { this.apiToken = apiToken; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getWorkspacePath() { return workspacePath; }
        public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public boolean isTerminalEnabled() { return terminalEnabled; }
        public void setTerminalEnabled(boolean terminalEnabled) { this.terminalEnabled = terminalEnabled; }
        public String getTerminalPath() { return terminalPath; }
        public void setTerminalPath(String terminalPath) { this.terminalPath = terminalPath; }
    }

    /**
     * Database configuration for diatom-core (Hibernate).
     * <p>
     * When SQLite (default), the database file is created at {@code {workspace}/.diatom/diatom.db}
     * automatically and no connection parameters are needed.
     * <p>
     * For other databases, at minimum set {@code url}:
     * <pre>
     * diatom.database.url=jdbc:postgresql://localhost:5432/diatom
     * diatom.database.username=myuser
     * diatom.database.password=mypass
     * diatom.database.pool-size=10
     * </pre>
     */
    public static class Database {
        /** JDBC URL (e.g., jdbc:postgresql://localhost:5432/diatom) */
        private String url = "";
        /** Database username */
        private String username = "";
        /** Database password */
        private String password = "";
        /** HikariCP maximum pool size (default: 2 for SQLite, 10 for others) */
        private Integer poolSize = null;
        /** Hibernate dialect class name (optional, auto-detected if not set) */
        private String dialect = "";
        /** JDBC driver class name (optional, auto-detected from URL if not set) */
        private String driver = "";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Integer getPoolSize() { return poolSize; }
        public void setPoolSize(Integer poolSize) { this.poolSize = poolSize; }
        public String getDialect() { return dialect; }
        public void setDialect(String dialect) { this.dialect = dialect; }
        public String getDriver() { return driver; }
        public void setDriver(String driver) { this.driver = driver; }
    }

    /**
     * Plugin configuration.
     * <p>
     * 插件 JAR 搜索路径。默认搜索 ~/.diatom/plugins/ 和
     * {workspace-dir}/.diatom/plugins/（由 diatam.jar.dir 决定）。
     * 通过 paths 属性可添加额外搜索目录。
     * <pre>
     * diatom.plugin.paths=/opt/diatom-plugins,${user.dir}/lib/plugins
     * </pre>
     */
    public static class Plugin {
        /** Additional plugin directories (comma-separated paths) */
        private List<String> paths = new ArrayList<>();

        public List<String> getPaths() { return paths; }
        public void setPaths(List<String> paths) { this.paths = paths; }
    }
}
