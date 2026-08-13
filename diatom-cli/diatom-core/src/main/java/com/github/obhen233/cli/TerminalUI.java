package com.github.obhen233.cli;

import com.github.obhen233.CoreApp;
import com.github.obhen233.cli.provider.*;
import com.github.obhen233.config.WorkspaceProjectResolver;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.util.PathUtils;
import com.github.obhen233.core.agent.TaskManager;
import com.github.obhen233.core.agent.ToolConfirmationException;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.agent.PlanSelectionException;
import com.github.obhen233.core.agent.CommandTimeoutException;
import com.github.obhen233.core.command.tools.ConfigTools;
import com.github.obhen233.core.database.ChangeLogDao;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.HistoryManager;
import com.github.obhen233.core.database.SnapshotDao;
import com.github.obhen233.core.database.SourceCodeExtensionsDao;
import com.github.obhen233.core.database.SystemConfigDao;
import com.github.obhen233.core.database.TaskCheckpointManager;
import com.github.obhen233.core.database.TaskDao;
import com.github.obhen233.core.skill.Skill;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.tool.ToolRegistry.UnauthorizedAccessException;
import com.github.obhen233.core.tool.AuthorizedPathManager;
import com.github.obhen233.core.tool.builtin.CommandTools;
import com.github.obhen233.core.mcp.McpColor;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.CoreCommandRegistry;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.command.CliCommandOutput;
import com.github.obhen233.util.I18n;
import com.github.obhen233.util.InstallPaths;
import com.github.obhen233.util.MarkdownUtils;
import com.github.obhen233.util.ProgressSpinner;
import org.jline.keymap.KeyMap;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TerminalUI {
    private static final Logger logger = LoggerFactory.getLogger(TerminalUI.class);
    private static final String NEWLINE = System.lineSeparator();

    private final ReActAgent agent;
    private final AuthorizedPathManager authManager;
    private final HistoryManager historyManager;
    private final DatabaseManager databaseManager;
    private final TaskCheckpointManager checkpointManager;
    private final CommandRulesDao commandRulesDao;
    private final SourceCodeExtensionsDao sourceCodeExtensionsDao;
    private final CoreCommandRegistry commandRegistry;
    private TaskManager taskManager;
    private SnapshotDao snapshotDao;
    private TaskDao taskDao;
    private ChangeLogDao changeLogDao;
    private ContextViewer contextViewer;
    private Terminal terminal;

    // Auto-approve whitelists for this session
    private final Set<String> approvedCommands = new HashSet<>();
    private final Set<String> approvedPaths = new HashSet<>();

    // Track if we're currently resuming from a checkpoint
    private boolean resumingFromCheckpoint = false;
    private String resumedTaskId = null;

    // Streaming mode flag
    private boolean streamingMode = true;

    // Track whether any content was streamed (to suppress blank newlines)
    private boolean streamingHadContent = false;

    private final com.github.obhen233.core.config.ConfigManager configManager;

    // Workspace/project resolution for CLI
    private final String workspaceDir;
    private final String projectName;

    // Current reader for timeout callback
    private LineReader currentReader;

    // Asynchronous execution infrastructure
    private AsyncAgentExecutor asyncExecutor;

    // Confirmation bridge — synchronizes worker thread (agent) with input thread (user prompt)
    private volatile boolean confirmationPending = false;
    private volatile boolean authorizationPending = false;
    private ToolConfirmationException pendingConfirmation;
    private UnauthorizedAccessException pendingAuthorization;
    private final Object confirmationLock = new Object();
    private String confirmationResult = "";
    // Prevents duplicate displayPrompt when worker thread already showed it
    private volatile boolean confirmationPromptShown = false;

    // Timeout callback state - protected by 'this' monitor
    private volatile boolean timeoutPending = false;
    private String timeoutCommand = "";
    private int timeoutElapsed = 0;
    private volatile boolean timeoutContinue = false;
    private final CountDownLatch timeoutLatch = new CountDownLatch(1);

    // ESC key interrupt state - protected by 'this' monitor
    private volatile boolean escKeyPressed = false;

    // Flag used by built-in commands (e.g. restart) that need to break the main loop
    private boolean breakOutOfLoop = false;

    // Static restart flag for graceful hot deployment (set by SelfUpdateTools or restart command)
    private static volatile boolean restartRequested = false;

    public static void setRestartRequested() {
        restartRequested = true;
    }

    public static boolean isRestartRequested() {
        return restartRequested;
    }

    /**
     * Check whether a self-update is pending (core or custom).
     */
    private boolean hasPendingUpdate() {
        try {
            Path appHome = InstallPaths.getInstallHome();
            Path corePendingMarker = appHome.resolve("core-update-pending.marker");
            Path customPendingMarker = appHome.resolve("custom").resolve("custom-update-pending.marker");
            return Files.exists(corePendingMarker) || Files.exists(customPendingMarker);
        } catch (Exception e) {
            logger.warn("Failed to check pending updates", e);
            return false;
        }
    }

    /**
     * Restart the application to apply pending updates.
     * Tries to use the launcher script (run.bat / run.sh) in the JAR directory so that
     * character encoding and other startup settings are preserved. Falls back to
     * launching java -Dfile.encoding=UTF-8 -jar <jar> directly if no script is found.
     * Returns false if no update is pending or if the current JAR path cannot be determined.
     */
    private boolean restartApplication() {
        if (!hasPendingUpdate()) {
            println(I18n.get("self_update_restart_cmd_no_update"));
            return false;
        }

        try {
            String jarPath = resolveRunningJarPath();
            if (jarPath == null || jarPath.isEmpty()) {
                println(I18n.get("error", I18n.get("self_update_restart_no_jar")));
                return false;
            }

            Path jarFile = Paths.get(jarPath);
            Path jarDir = jarFile.getParent();
            if (jarDir == null) {
                jarDir = Paths.get(".").toAbsolutePath().normalize();
            }

            // Always restart via java -jar to avoid depending on optional launcher scripts.
            // Force UTF-8 so Chinese output is not garbled in the new process.
            // MUST pass -Ddiatom.jar.dir so Bootstrap knows the correct JAR directory
            String javaHome = System.getProperty("java.home");
            String javaExe = Paths.get(javaHome, "bin", "java").toString();
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe,
                    "-Dfile.encoding=UTF-8",
                    "-Ddiatom.jar.dir=" + jarDir.toString(),
                    "-jar",
                    jarPath);
            pb.directory(jarDir.toFile());
            pb.inheritIO();
            pb.start();

            println(I18n.get("self_update_restarting"));
            restartRequested = true;
            return true;
        } catch (Exception e) {
            logger.error("Failed to restart application", e);
            println(I18n.get("error", e.getMessage()));
            return false;
        }
    }

    /**
     * Try to determine the path of the currently running JAR file.
     * Returns null if it cannot be determined.
     */
    private String resolveRunningJarPath() {
        // Priority 1: diatom.launcher.jar set by Bootstrap at startup
        // This is the correct launcher JAR (diatom-cli.jar), not the custom JAR
        String launcherJar = System.getProperty("diatom.launcher.jar");
        if (launcherJar != null && !launcherJar.isEmpty()) {
            Path launcherPath = Paths.get(launcherJar);
            if (Files.exists(launcherPath)) {
                return launcherPath.toAbsolutePath().toString();
            }
        }

        // In standalone JAR mode, CoreApp.class.getCodeSource() points to
        // lib/diatom-core-*.jar which has no Main-Class manifest.
        // The executable JAR (custom-current.jar) is always the first
        // entry in java.class.path set by Bootstrap.
        if (isStandaloneJarMode()) {
            String classPath = System.getProperty("java.class.path");
            if (classPath != null && !classPath.isEmpty()) {
                String firstEntry = classPath.split(java.io.File.pathSeparator)[0];
                if (firstEntry.toLowerCase().endsWith(".jar")) {
                    return firstEntry;
                }
            }
        }

        // Original logic: try CoreApp.class code source (works in IDE/dev mode)
        try {
            java.net.URL location = CoreApp.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                Path path = Paths.get(location.toURI());
                if (Files.exists(path) && !Files.isDirectory(path)) {
                    return path.toAbsolutePath().toString();
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to determine JAR path from code source", e);
        }

        // Fallback to classpath first entry
        try {
            String classPath = System.getProperty("java.class.path");
            if (classPath != null && !classPath.isEmpty()) {
                String firstEntry = classPath.split(java.io.File.pathSeparator)[0];
                if (firstEntry.toLowerCase().endsWith(".jar")) {
                    return firstEntry;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to determine JAR path from classpath", e);
        }
        return null;
    }

    public TerminalUI(ReActAgent agent, AuthorizedPathManager authManager) {
        this(agent, authManager, null, null, null, null, null, null, null, null, null);
    }

    public TerminalUI(ReActAgent agent, AuthorizedPathManager authManager, HistoryManager historyManager) {
        this(agent, authManager, historyManager, null, null, null, null, null, null, null, null);
    }

    public TerminalUI(ReActAgent agent, AuthorizedPathManager authManager, HistoryManager historyManager, TaskCheckpointManager checkpointManager) {
        this(agent, authManager, historyManager, null, checkpointManager, null, null, null, null, null, null);
    }

    public TerminalUI(ReActAgent agent, AuthorizedPathManager authManager, HistoryManager historyManager, TaskCheckpointManager checkpointManager, com.github.obhen233.core.config.ConfigManager configManager) {
        this(agent, authManager, historyManager, null, checkpointManager, configManager, null, null, null, null, null);
    }

    public TerminalUI(ReActAgent agent, AuthorizedPathManager authManager, HistoryManager historyManager, TaskCheckpointManager checkpointManager, com.github.obhen233.core.config.ConfigManager configManager, String workspaceDir) {
        this(agent, authManager, historyManager, null, checkpointManager, configManager, workspaceDir, null, null, null, null);
    }

    public TerminalUI(ReActAgent agent, AuthorizedPathManager authManager, HistoryManager historyManager, DatabaseManager databaseManager, TaskCheckpointManager checkpointManager, com.github.obhen233.core.config.ConfigManager configManager, String workspaceDir, TaskManager taskManager, SnapshotDao snapshotDao, TaskDao taskDao, ChangeLogDao changeLogDao) {
        this.agent = agent;
        this.authManager = authManager;
        this.historyManager = historyManager;
        this.databaseManager = databaseManager;
        this.checkpointManager = checkpointManager;
        this.configManager = configManager;
        this.taskManager = taskManager;
        this.snapshotDao = snapshotDao;
        this.taskDao = taskDao;
        this.changeLogDao = changeLogDao;
        this.workspaceDir = workspaceDir != null ? workspaceDir : PathUtils.getWorkingDir();
        String[] resolved = WorkspaceProjectResolver.resolve(this.workspaceDir);
        this.projectName = resolved[1];
        this.contextViewer = new ContextViewer();

        // Initialize command rules dao
        if (databaseManager != null) {
            this.commandRulesDao = new CommandRulesDao(databaseManager);
        } else {
            this.commandRulesDao = null;
        }

        // Initialize source code extensions dao
        if (databaseManager != null) {
            this.sourceCodeExtensionsDao = new SourceCodeExtensionsDao(databaseManager);
        } else {
            this.sourceCodeExtensionsDao = null;
        }

        // Initialize streaming mode from config
        if (this.configManager != null) {
            String streamingValue = this.configManager.get("api.streaming");
            if (streamingValue != null) {
                this.streamingMode = "true".equalsIgnoreCase(streamingValue);
            }
        }

        // Apply streaming consumer if enabled
        if (this.streamingMode && this.agent != null) {
            this.agent.setStreamingConsumer(new com.github.obhen233.core.http.AiHttpClient.StreamConsumer() {
                @Override
                public void onToken(String token) {
                    printStreamingToken(token);
                }
                @Override
                public void onComplete(String fullResponse) {
                    printStreamingComplete();
                }
                @Override
                public void onError(Throwable e) {
                    println("Streaming error: " + e.getMessage());
                }
            });
        }

        // Initialize command registry and providers
        this.commandRegistry = new CoreCommandRegistry();
        initializeCommandRegistry();

        // Initialize JLine terminal for interactive modes (CLI, Gateway CLI).
        // Server modes (Worker, Gateway daemon) have no terminal — skip.
        if (!isServerMode()) {
            try {
                this.terminal = org.jline.terminal.TerminalBuilder.builder()
                        .name("diatom")
                        .build();
            } catch (Exception e) {
                logger.debug("JLine terminal not available: {}", e.getMessage());
            }
        }
    }

    /**
     * Initialize command registry with SPI providers and agent context.
     * Falls back to manual registration if SPI loading fails so basic
     * commands (auth, streaming, config, mcp, deploy, etc.) always work.
     */
    private void initializeCommandRegistry() {
        // Load all CoreCommandProvider implementations via SpiLoader
        List<CoreCommandProvider> providers = SpiLoader.getAll(CoreCommandProvider.class);
        for (CoreCommandProvider provider : providers) {
            commandRegistry.register(provider);
        }

        // Fallback: manually register core providers when SPI metadata is missing
        // (e.g. due to shading/packaging issues). This ensures commands never
        // fall through to agent.run() and invoke the LLM unexpectedly.
        registerIfAbsent(new HelpCommandProvider());
        registerIfAbsent(new SkillsCommandProvider());
        registerIfAbsent(new ContextCommandProvider());

        AuthCommandProvider authProvider = new AuthCommandProvider();
        if (authManager != null) {
            authProvider.setAuthManager(authManager);
        }
        registerIfAbsent(authProvider);

        registerIfAbsent(new HistoryCommandProvider());
        registerIfAbsent(new TasksCommandProvider());
        registerIfAbsent(new SnapshotCommandProvider());

        ConfigCommandProvider configProvider = new ConfigCommandProvider();
        if (configManager != null) {
            configProvider.setConfigTools(new com.github.obhen233.core.command.tools.ConfigTools(configManager, null));
        }
        registerIfAbsent(configProvider);

        registerIfAbsent(new RulesCommandProvider());
        registerIfAbsent(new ExtensionCommandProvider());
        registerIfAbsent(new LogCommandProvider());
        registerIfAbsent(new StreamingCommandProvider());
        registerIfAbsent(new McpCommand());
        registerIfAbsent(new DeployCommandProvider());

        // Dev mode is only available when running from standalone JAR
        registerIfAbsent(new DevCommandProvider(isStandaloneJarMode()));

        // Inject dependencies into providers
        for (CoreCommandProvider provider : commandRegistry.getAll()) {
            // Inject ReActAgent
            if (provider instanceof AgentAware && this.agent != null) {
                ((AgentAware) provider).init(this.agent);
            }
            // Inject HistoryManager
            if (provider instanceof HistoryAware && this.historyManager != null) {
                ((HistoryAware) provider).initHistory(this.historyManager);
            }
            // Inject CommandRulesDao
            if (provider instanceof RulesAware && this.commandRulesDao != null) {
                ((RulesAware) provider).setCommandRulesDao(this.commandRulesDao);
            }
            // Inject SourceCodeExtensionsDao
            if (provider instanceof ExtensionAware && this.sourceCodeExtensionsDao != null) {
                ((ExtensionAware) provider).setSourceCodeExtensionsDao(this.sourceCodeExtensionsDao);
            }
            // Inject AuthorizedPathManager
            if (provider instanceof AuthAware && this.authManager != null) {
                ((AuthAware) provider).setAuthManager(this.authManager);
            }
            // Inject TaskCheckpointManager
            if (provider instanceof CheckpointAware && this.checkpointManager != null) {
                ((CheckpointAware) provider).initCheckpointManager(this.checkpointManager);
            }
            // Inject ContextViewer
            if (provider instanceof ContextAware && this.contextViewer != null) {
                ((ContextAware) provider).setContextViewer(this.contextViewer);
            }
            // Inject ConfigTools
            if (provider instanceof ConfigAware && this.configManager != null) {
                ConfigTools tools = new ConfigTools(this.configManager, null);
                ((ConfigAware) provider).setConfigTools(tools);
            }
            // Inject SnapshotDao
            if (provider instanceof SnapshotAware && this.snapshotDao != null) {
                ((SnapshotAware) provider).initSnapshotDao(this.snapshotDao);
            }
            // Inject TaskDao
            if (provider instanceof TaskDaoAware && this.taskDao != null) {
                ((TaskDaoAware) provider).initTaskDao(this.taskDao);
            }
            // Inject ChangeLogDao
            if (provider instanceof ChangeLogAware && this.changeLogDao != null) {
                ((ChangeLogAware) provider).initChangeLogDao(this.changeLogDao);
            }
            // Inject TaskManager
            if (provider instanceof TaskManagerAware && this.taskManager != null) {
                ((TaskManagerAware) provider).initTaskManager(this.taskManager);
            }
        }

        logger.info("TerminalUI command registry initialized with {} commands", commandRegistry.size());
    }

    private void registerIfAbsent(CoreCommandProvider provider) {
        if (!commandRegistry.isRegistered(provider.getCommandName())) {
            commandRegistry.register(provider);
        }
    }

    // Marker interfaces for dependency injection
    public interface AgentAware { void init(ReActAgent agent); }
    public interface HistoryAware { void initHistory(HistoryManager historyManager); }
    public interface RulesAware { void setCommandRulesDao(CommandRulesDao dao); }
    public interface ExtensionAware { void setSourceCodeExtensionsDao(SourceCodeExtensionsDao dao); }
    public interface AuthAware { void setAuthManager(AuthorizedPathManager authManager); }
    public interface CheckpointAware { void initCheckpointManager(TaskCheckpointManager checkpointManager); }
    public interface ContextAware { void setContextViewer(ContextViewer contextViewer); }
    public interface ConfigAware { void setConfigTools(ConfigTools configTools); }
    public interface SnapshotAware { void initSnapshotDao(SnapshotDao snapshotDao); }
    public interface TaskDaoAware { void initTaskDao(TaskDao taskDao); }
    public interface ChangeLogAware { void initChangeLogDao(ChangeLogDao changeLogDao); }
    public interface TaskManagerAware { void initTaskManager(TaskManager taskManager); }
    
    /**
     * Get the timeout callback for CommandTools.
     * On timeout, kills the command process automatically.
     * JLine reader is not thread-safe, so interactive timeout prompts
     * don't work correctly with the current architecture.
     */
    public CommandTools.TimeoutCallback getTimeoutCallback() {
        return (command, elapsedSeconds, process) -> {
            println("");
            println("========================================");
            println(I18n.get("timeout_warning_title"));
            println("========================================");
            println(I18n.get("timeout_command", command));
            println(I18n.get("timeout_elapsed", elapsedSeconds));
            println("");

            // Kill the process on timeout (no interactive prompt — JLine is not thread-safe)
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                println(I18n.get("timeout_cancelling"));
            }
            flushTerminal();
            return false;
        };
    }

    private String buildHelpText() {
        String lang = I18n.getLanguage();
        boolean standaloneMode = isStandaloneJarMode();
        if ("zh".equals(lang)) {
            return buildHelpZh(standaloneMode);
        } else {
            return buildHelpEn(standaloneMode);
        }
    }
    
    /**
     * Detect if running in standalone JAR mode (self-update enabled)
     */
    private boolean isStandaloneJarMode() {
        return "true".equals(System.getProperty("diatom.standalone.jar"));
    }

    /**
     * Append a group of commands with descriptions from the registry, using dynamic padding.
     */
    private void appendCmdGroup(StringBuilder sb, String groupName, String... cmdNames) {
        sb.append(groupName).append(":\n");
        int maxLen = 0;
        for (String name : cmdNames) {
            if (name.length() > maxLen) maxLen = name.length();
        }
        int pad = maxLen + 4;
        for (String name : cmdNames) {
            CoreCommandProvider provider = commandRegistry != null ? commandRegistry.get(name) : null;
            String desc = provider != null ? I18n.resolveTemplate(provider.getDescription()) : "";
            sb.append("  ").append(name);
            for (int i = name.length(); i < pad; i++) sb.append(" ");
            sb.append(desc).append("\n");
        }
    }

    private String buildHelpEn(boolean standaloneMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Diatom CLI - Help\n\n");
        sb.append("Basic Commands:\n");
        sb.append("  help                 Show help\n");
        sb.append("  exit / quit         Exit program\n");
        sb.append("  restart             Restart to apply pending updates\n");
        sb.append("  dev                 Enable development mode\n");
        sb.append("  exit dev / quit dev  Exit dev mode\n");
        sb.append("\n");
        appendCmdGroup(sb, "Context & History", "context", "history");
        sb.append("\n");
        sb.append("Task Commands:\n");
        sb.append("  tasks               List all tasks\n");
        sb.append("  task <id>          Show task details\n");
        sb.append("  task <id> resume   Resume task execution\n");
        sb.append("  task <id> cancel   Cancel a task\n");
        sb.append("  task <id> log      Show task change log\n");
        sb.append("  log                 Show current task change log\n");
        sb.append("  log --task <id>    Show specified task log\n");
        sb.append("\n");
        sb.append("Snapshot Commands:\n");
        sb.append("  snapshot <task>      List task snapshots\n");
        sb.append("  snapshot <task> <n>  Show snapshot details\n");
        sb.append("  snapshot <task> <n> diff     Compare snapshot with current\n");
        sb.append("  snapshot <task> <n> rollback Rollback to snapshot\n");
        sb.append("\n");
        sb.append("Config Commands:\n");
        sb.append("  config               List all configs\n");
        sb.append("  config list          List all configs\n");
        sb.append("  config list <cat>    List configs by category\n");
        sb.append("  config get <key>     Show config detail\n");
        sb.append("  config set <k> <v>   Set config value\n");
        sb.append("  config reset <key>   Reset config to default\n");
        if (commandRegistry != null && commandRegistry.get("rules") != null) {
            sb.append("  rules               ").append(I18n.resolveTemplate(commandRegistry.get("rules").getDescription())).append("\n");
        }
        if (commandRegistry != null && commandRegistry.get("extension") != null) {
            sb.append("  extension           ").append(I18n.resolveTemplate(commandRegistry.get("extension").getDescription())).append("\n");
        }
        sb.append("\n");
        appendCmdGroup(sb, "MCP Commands", "mcp");
        sb.append("\n");
        appendCmdGroup(sb, "Deploy Commands", "deploy");
        sb.append("\n");
        appendCmdGroup(sb, "Other Commands", "skills", "auth", "streaming");
        sb.append("\n");
        sb.append("Usage Examples:\n");
        sb.append("  Help me read pom.xml     Read pom.xml file\n");
        sb.append("  Create a Java skill     Create coding standard\n");
        sb.append("  Fix compilation error   Fix UserService.java\n");

        return sb.toString();
    }

    private String buildHelpZh(boolean standaloneMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Diatom CLI - 帮助文档\n\n");
        sb.append("基础命令:\n");
        sb.append("  help                 显示帮助信息\n");
        sb.append("  exit / quit         退出程序\n");
        sb.append("  restart             重启应用以应用待更新\n");
        sb.append("  dev                 启用开发模式(仅独立JAR)\n");
        sb.append("  exit dev / quit dev  退出开发模式\n");
        sb.append("\n");
        appendCmdGroup(sb, "上下文与历史", "context", "history");
        sb.append("\n");
        sb.append("任务命令:\n");
        sb.append("  tasks              列出所有任务\n");
        sb.append("  task <id>          显示任务详情\n");
        sb.append("  task <id> resume   继续执行任务\n");
        sb.append("  task <id> cancel   取消任务\n");
        sb.append("  task <id> log      显示任务改动日志\n");
        sb.append("  log                显示当前任务改动日志\n");
        sb.append("  log --task <id>    显示指定任务改动日志\n");
        sb.append("\n");
        sb.append("快照命令:\n");
        sb.append("  snapshot <task>     列出任务快照\n");
        sb.append("  snapshot <task> <n> 显示快照详情\n");
        sb.append("  snapshot <task> <n> diff    对比快照与当前\n");
        sb.append("  snapshot <task> <n> rollback 回滚到快照\n");
        sb.append("\n");
        sb.append("配置命令:\n");
        sb.append("  config              列出所有配置\n");
        sb.append("  config list         列出所有配置\n");
        sb.append("  config list <分类>   按分类列出配置\n");
        sb.append("  config get <键>     查看配置详情\n");
        sb.append("  config set <键> <值> 设置配置值\n");
        sb.append("  config reset <键>   重置配置为默认值\n");
        if (commandRegistry != null && commandRegistry.get("rules") != null) {
            sb.append("  rules              ").append(I18n.resolveTemplate(commandRegistry.get("rules").getDescription())).append("\n");
        }
        if (commandRegistry != null && commandRegistry.get("extension") != null) {
            sb.append("  extension          ").append(I18n.resolveTemplate(commandRegistry.get("extension").getDescription())).append("\n");
        }
        sb.append("\n");
        appendCmdGroup(sb, "MCP 命令", "mcp");
        sb.append("\n");
        appendCmdGroup(sb, "部署命令", "deploy");
        sb.append("\n");
        appendCmdGroup(sb, "其他命令", "skills", "auth", "streaming");
        sb.append("\n");
        sb.append("使用示例:\n");
        sb.append("  帮我读取 pom.xml    读取 pom.xml 文件\n");
        sb.append("  创建一个 Java 技能  创建代码规范技能\n");
        sb.append("  修复编译错误      修复 UserService.java\n");

        return sb.toString();
    }
    /**
     * Set the TaskManager for async execution support
     */
    public void setTaskManager(TaskManager taskManager) {
        this.taskManager = taskManager;
        reinjectCommandDependencies();
    }

    /**
     * Set the SnapshotDao for snapshot command provider
     */
    public void setSnapshotDao(SnapshotDao snapshotDao) {
        this.snapshotDao = snapshotDao;
        reinjectCommandDependencies();
    }

    /**
     * Set the TaskDao for task/snapshot command providers
     */
    public void setTaskDao(TaskDao taskDao) {
        this.taskDao = taskDao;
        reinjectCommandDependencies();
    }

    /**
     * Set the ChangeLogDao for task log command provider
     */
    public void setChangeLogDao(ChangeLogDao changeLogDao) {
        this.changeLogDao = changeLogDao;
        reinjectCommandDependencies();
    }

    private void reinjectCommandDependencies() {
        if (commandRegistry == null) return;
        for (CoreCommandProvider provider : commandRegistry.getAll()) {
            if (provider instanceof SnapshotAware && this.snapshotDao != null) {
                ((SnapshotAware) provider).initSnapshotDao(this.snapshotDao);
            }
            if (provider instanceof TaskDaoAware && this.taskDao != null) {
                ((TaskDaoAware) provider).initTaskDao(this.taskDao);
            }
            if (provider instanceof ChangeLogAware && this.changeLogDao != null) {
                ((ChangeLogAware) provider).initChangeLogDao(this.changeLogDao);
            }
            if (provider instanceof TaskManagerAware && this.taskManager != null) {
                ((TaskManagerAware) provider).initTaskManager(this.taskManager);
            }
        }
    }

    public void start() {
        try {
            Terminal terminal;
            if (this.terminal != null) {
                terminal = this.terminal;
            } else {
                terminal = TerminalBuilder.builder()
                        .name("diatom")
                        .build();
                this.terminal = terminal;
            }
            ProgressSpinner.setWriter(terminal.writer());

            // Configure history file for JLine
            Path historyFile = InstallPaths.getInstallHome().resolve("history");
            try {
                Files.createDirectories(historyFile.getParent());
            } catch (IOException e) {
                logger.warn("Failed to create history directory", e);
            }

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, historyFile.toFile())
                    .option(LineReader.Option.BRACKETED_PASTE, true)
                    .build();

            // Override widgets for collapsed paste block support:
            // - self-insert: auto-collapse long content to "[N chars]" placeholder
            // - backward-delete-char: one backspace deletes the entire block
            // - accept-line: expand placeholder to full content before submitting
            final java.util.concurrent.atomic.AtomicReference<String> collapsedPasteBlock = new java.util.concurrent.atomic.AtomicReference<>(null);
            final int PASTE_COLLAPSE_THRESHOLD = 200;
            java.util.Map<String, Widget> widgetMap = reader.getWidgets();

            // Override self-insert to auto-collapse long buffer content
            Widget defaultSelfInsert = widgetMap.get("self-insert");
            if (defaultSelfInsert != null) {
                widgetMap.put("self-insert", () -> {
                    String block = collapsedPasteBlock.get();
                    if (block != null) {
                        // Already collapsed: let char insert, then capture it into stored content
                        defaultSelfInsert.apply();
                        String buf = reader.getBuffer().toString();
                        String prefix = "[" + block.length() + " chars]";
                        if (buf.startsWith(prefix) && buf.length() > prefix.length()) {
                            block += buf.substring(prefix.length());
                            collapsedPasteBlock.set(block);
                        }
                        // Restore placeholder with updated count
                        String newPlaceholder = "[" + block.length() + " chars]";
                        reader.getBuffer().clear();
                        reader.getBuffer().write(newPlaceholder);
                        return true;
                    }
                    // Normal: insert char, then check threshold
                    boolean result = defaultSelfInsert.apply();
                    String buf = reader.getBuffer().toString();
                    if (buf.length() > PASTE_COLLAPSE_THRESHOLD) {
                        collapsedPasteBlock.set(buf);
                        reader.getBuffer().clear();
                        reader.getBuffer().write("[" + buf.length() + " chars]");
                        return true;
                    }
                    return result;
                });
            }

            // Override backward-delete-char to delete entire collapsed block
            Widget defaultBackwardDelete = widgetMap.get("backward-delete-char");
            if (defaultBackwardDelete != null) {
                widgetMap.put("backward-delete-char", () -> {
                    String buf = reader.getBuffer().toString();
                    String block = collapsedPasteBlock.get();
                    if (block != null && buf != null && buf.matches("\\[\\d+ chars\\]")) {
                        collapsedPasteBlock.set(null);
                        reader.getBuffer().clear();
                        return true;
                    }
                    // Also clear entire buffer if it's very long
                    if (buf.length() > PASTE_COLLAPSE_THRESHOLD) {
                        reader.getBuffer().clear();
                        return true;
                    }
                    return defaultBackwardDelete.apply();
                });
            }

            // Override accept-line to expand placeholder before submitting
            Widget defaultAcceptLine = widgetMap.get("accept-line");
            if (defaultAcceptLine != null) {
                widgetMap.put("accept-line", () -> {
                    String buf = reader.getBuffer().toString();
                    String block = collapsedPasteBlock.get();
                    if (block != null && buf != null && buf.matches("\\[\\d+ chars\\]")) {
                        collapsedPasteBlock.set(null);
                        reader.getBuffer().clear();
                        reader.getBuffer().write(block);
                    }
                    return defaultAcceptLine.apply();
                });
            }

            // ESC key interrupt: JLine's default emacs keymap has no standalone
            // ESC binding (ESC is only the prefix of arrow/meta sequences, e.g.
            // "\033[A"), so a bare ESC is silently swallowed while the agent is
            // running and the user can never interrupt execution. Bind ESC to a
            // widget that cancels the running agent (or clears the input line
            // when idle). A bare ESC is ambiguous with those ESC-sequences, so
            // lower the ambiguous-binding timeout to fire the widget promptly.
            KeyMap<Binding> mainKeyMap = reader.getKeyMaps().get(LineReader.MAIN);
            if (mainKeyMap != null) {
                mainKeyMap.setAmbiguousTimeout(150);
                mainKeyMap.bind((Widget) () -> {
                    if (asyncExecutor != null && asyncExecutor.isBusy()) {
                        // Interrupt the running agent (cancel() -> agent.requestInterrupt()).
                        // cancel() suppresses the {{user_interrupted}} output callback,
                        // so print the interrupt confirmation here.
                        asyncExecutor.cancel();
                        if (currentReader != null) {
                            currentReader.printAbove("\n" + I18n.get("esc_interrupt_exiting"));
                        } else {
                            println("\n" + I18n.get("esc_interrupt_exiting"));
                        }
                    } else {
                        // Idle: ESC clears the input line (standard shell behavior)
                        reader.getBuffer().clear();
                    }
                    return true;
                }, KeyMap.esc());
            }

            // Store reader reference for timeout callback
            this.currentReader = reader;

            // Load history from HistoryManager (SQLite) into JLine history on startup
            if (historyManager != null) {
                try {
                    List<String> recentCommands = historyManager.getRecentCommands(100);
                    // Fix: reverse list so newest is at end of JLine history
                    // JLine cursor starts at end, up arrow shows newer commands
                    Collections.reverse(recentCommands);
                    for (String cmd : recentCommands) {
                        reader.getHistory().add(cmd);
                    }
                    logger.info("Loaded {} commands into JLine history", recentCommands.size());
                } catch (Exception e) {
                    logger.warn("Failed to load history from HistoryManager", e);
                }
            }

            // Initialize async execution infrastructure
            asyncExecutor = new AsyncAgentExecutor(agent);
            asyncExecutor.setOutputConsumer(response -> {
                displayAgentResponse(response);
            });
            asyncExecutor.setConfirmationHandler(ex -> {
                pendingConfirmation = ex;
                confirmationPending = true;
                // Show confirmation prompt immediately from worker thread.
                // JLine's printAbove is thread-safe and displays text above the
                // prompt line, so the user sees it even during readLine.
                displayConfirmationPrompt(ex);
                confirmationPromptShown = true;
                synchronized (confirmationLock) {
                    try {
                        confirmationLock.wait();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                confirmationPending = false;
                confirmationPromptShown = false;
                return confirmationResult;
            });
            asyncExecutor.setAuthorizationHandler(ex -> {
                pendingAuthorization = ex;
                authorizationPending = true;
                // Show authorization prompt immediately from worker thread
                if (currentReader != null) {
                    String path = ex.getPath() != null ? ex.getPath() : "unknown";
                    currentReader.printAbove(I18n.get("auth_required") + "\n"
                        + I18n.get("auth_path_attempt", path) + "\n"
                        + I18n.get("auth_tool", ex.getToolName()));
                }
                synchronized (confirmationLock) {
                    try {
                        confirmationLock.wait();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                authorizationPending = false;
                return confirmationResult;
            });

            while (true) {
                String line;

                // Check if there's a timeout pending - handle it in main thread
                synchronized (this) {
                    if (timeoutPending) {
                        try {
                            // Read input in main thread (thread-safe for JLine)
                            String confirm = reader.readLine();
                            timeoutContinue = "c".equalsIgnoreCase(confirm.trim());
                        } catch (UserInterruptException e) {
                            // Ctrl+C during timeout - cancel the operation
                            Thread.interrupted();
                            timeoutContinue = false;
                            println("Operation cancelled");
                        } catch (Exception e) {
                            // On error, default to continue
                            timeoutContinue = true;
                        }
                        // Notify waiting callback thread to continue
                        this.notifyAll();
                        continue;
                    }
                }

                // Check for pending confirmation/authorization before reading next command
                synchronized (confirmationLock) {
                    if (confirmationPending && pendingConfirmation != null) {
                        confirmationResult = handleConfirmationInput(reader, pendingConfirmation);
                        // Clear before notifyAll() to prevent race condition: the main thread
                        // may loop back (via `continue`) and re-acquire the lock BEFORE the
                        // worker thread exits its own synchronized block and sets
                        // confirmationPending = false (which is outside the lock).
                        confirmationPending = false;
                        confirmationLock.notifyAll();
                        continue;
                    }
                    if (authorizationPending && pendingAuthorization != null) {
                        confirmationResult = handleAuthorizationInput(reader, pendingAuthorization);
                        // Same race condition fix: clear before notifyAll()
                        authorizationPending = false;
                        confirmationLock.notifyAll();
                        continue;
                    }
                }

                try {
                    line = reader.readLine("> ");
                } catch (UserInterruptException e) {
                    // Clear interrupted state and continue
                    Thread.interrupted();
                    continue;
                } catch (org.jline.reader.EndOfFileException e) {
                    break;
                }

                if (line == null) break;

                // Accumulate continuation lines from paste (dumb terminal lacks bracketed paste)
                if (!line.isEmpty()) {
                    StringBuilder accumulated = new StringBuilder(line);
                    try {
                        // Fast path: immediately available data (works on Unix pipes)
                        while (System.in.available() > 0) {
                            String nextLine = reader.readLine("");
                            if (nextLine == null) break;
                            accumulated.append("\n").append(nextLine);
                        }
                        // Fallback: on Windows, System.in.available() is unreliable for
                        // console input.  Use short timed polling to catch paste data
                        // that hasn't fully arrived yet (paste sends all chars within
                        // a few ms of readLine returning).
                        long deadline = System.currentTimeMillis() + 50;
                        while (System.currentTimeMillis() < deadline) {
                            if (System.in.available() > 0) {
                                String nextLine = reader.readLine("");
                                if (nextLine == null) break;
                                accumulated.append("\n").append(nextLine);
                                deadline = System.currentTimeMillis() + 50;
                            } else {
                                Thread.sleep(5);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    line = accumulated.toString();
                }

                // Collapse long pasted content display (Bracketed Paste mode keeps it as one input)
                if (line.length() > 200) {
                    try {
                        int termWidth = terminal.getWidth();
                        if (termWidth <= 0) termWidth = 80;
                        // Calculate lines occupied by prompt "> " + content
                        int lines = (2 + line.length() + termWidth - 1) / termWidth;
                        // Move cursor up and clear each occupied line, then rewrite with collapsed summary
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < lines; i++) {
                            sb.append("\033[A\033[2K\r");
                        }
                        sb.append("> [.... ").append(line.length()).append(" chars]\n");
                        terminal.writer().print(sb.toString());
                        terminal.writer().flush();
                    } catch (Exception ignored) {
                    }
                }

                String trimmed = line.trim();

                // === Pending confirmation check ===
                // When the worker thread sets confirmationPending DURING readLine,
                // the user's input from the "> " prompt may be their intended
                // confirmation response. Detect and handle this here to avoid
                // requiring the user to press Enter twice.
                // The worker thread already called displayConfirmationPrompt() immediately,
                // so we skip it here (confirmationPromptShown flag) to avoid duplicate output.
                if (confirmationPending) {
                    synchronized (confirmationLock) {
                        if (confirmationPending && pendingConfirmation != null) {
                            if (!confirmationPromptShown) {
                                displayConfirmationPrompt(pendingConfirmation);
                            }
                            if (isValidConfirmationResponse(trimmed)) {
                                processConfirmationResponse(trimmed, pendingConfirmation);
                                confirmationResult = trimmed;
                            } else {
                                confirmationResult = handleConfirmationInput(reader, pendingConfirmation);
                            }
                            confirmationPending = false;
                            confirmationLock.notifyAll();
                        }
                    }
                    continue;
                }

                // === Pending authorization check (same pattern as confirmation) ===
                if (authorizationPending) {
                    synchronized (confirmationLock) {
                        if (authorizationPending && pendingAuthorization != null) {
                            String lowerAuth = trimmed.toLowerCase();
                            if (lowerAuth.equals("y") || lowerAuth.equals("a") || lowerAuth.equals("n")) {
                                // User input is a valid authorization response — process directly
                                String path = pendingAuthorization.getPath() != null ? pendingAuthorization.getPath() : "unknown";
                                currentReader.printAbove(I18n.get("auth_required") + "\n"
                                    + I18n.get("auth_path_attempt", path) + "\n"
                                    + I18n.get("auth_tool", pendingAuthorization.getToolName()));
                                if ("y".equalsIgnoreCase(trimmed)) {
                                    authManager.authorize(path);
                                    currentReader.printAbove(I18n.get("auth_granted"));
                                } else if ("a".equalsIgnoreCase(trimmed)) {
                                    authManager.authorize(path);
                                    agent.setAutoApproveWrite(true);
                                    currentReader.printAbove(I18n.get("auth_granted_permanent", path));
                                } else {
                                    currentReader.printAbove(I18n.get("auth_denied"));
                                }
                                confirmationResult = trimmed;
                            } else {
                                confirmationResult = handleAuthorizationInput(reader, pendingAuthorization);
                            }
                            authorizationPending = false;
                            confirmationLock.notifyAll();
                        }
                    }
                    continue;
                }

                if (trimmed.isEmpty()) continue;

                // Check if build failure threshold exceeded - force user to confirm rollback or continue
                if (isBuildFailureThresholdExceeded()) {
                    println(I18n.get("self_update_build_threshold_exceeded"));
                    try {
                        String confirm = reader.readLine(I18n.get("self_update_build_threshold_choice") + " ");
                        if ("r".equalsIgnoreCase(confirm) || "rollback".equalsIgnoreCase(confirm)) {
                            // Trigger rollback
                            agent.run("rollback_sources");
                            continue;
                        }
                    } catch (Exception e) {
                        // Continue anyway
                    }
                }

                if (handleBuiltInCommand(trimmed, reader)) {
                    // The restart command needs to break the main loop after
                    // launching the new process, so we check the flag set by it.
                    if (breakOutOfLoop) {
                        break;
                    }
                    continue;
                }

                if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
                    println(I18n.get("goodbye"));
                    break;
                }

                // Handle background task submission (bg <command>)
                if (trimmed.toLowerCase().startsWith("bg ") && taskManager != null) {
                    String bgInput = trimmed.substring(3).trim();
                    if (!bgInput.isEmpty()) {
                        String taskId = agent.getCurrentTaskId();
                        if (taskId == null) {
                            agent.generateTaskId(bgInput);
                            taskId = agent.getCurrentTaskId();
                        }
                        taskId = taskManager.submitBackground(bgInput, agent, workspaceDir, taskId, new TaskManager.TaskCompletionCallback() {
                            @Override
                            public void onComplete(String tid, String result) {
                                println("\n[Task " + tid + " completed]");
                                println(stripMarkdown(result) + "\n");
                            }

                            @Override
                            public void onError(String tid, Throwable error) {
                                println("\n[Task " + tid + " failed: " + error.getMessage() + "]\n");
                            }
                        });
                        println("Task " + taskId + " submitted in background");
                        println("Use 'tasks' to list tasks, 'status " + taskId + "' to check progress");
                        if (historyManager != null) {
                            historyManager.saveCommand(trimmed, taskId, projectName);
                        }
                        continue;
                    }
                }

                try {
                    // Submit to async executor — returns immediately, output goes to consumer
                    asyncExecutor.submit(trimmed);

                    // Save command to history with task_id for checkpoint association
                    if (historyManager != null) {
                        historyManager.saveCommand(trimmed, agent.getCurrentTaskId(), projectName);
                    }
                } catch (Exception e) {
                    logger.error("Error submitting request to executor: {}", e.getMessage());
                    String errMsg = e.getMessage();
                    if (errMsg != null && (errMsg.contains("rate limit") || errMsg.contains("usage limit"))) {
                        println("\n" + errMsg);
                    } else {
                        println("\n" + I18n.get("error", errMsg != null ? errMsg : "Unknown error"));
                    }
                }
            }

            // Check for pending updates before exit and notify user
            checkPendingUpdatesBeforeExit();

            // Shutdown executors to allow JVM to exit cleanly
            if (asyncExecutor != null) {
                asyncExecutor.shutdown();
            }
            if (taskManager != null) {
                taskManager.shutdown();
            }
            if (agent != null) {
                agent.shutdown();
            }

            // Flush terminal before closing to ensure all output is written
            flushTerminal();

            // Close terminal (LineReader doesn't have a close method in JLine 3.x)
            println("Closing terminal...");
            terminal.close();
            println("Terminal closed.");

        } catch (IOException e) {
            logger.error("Failed to initialize terminal", e);
            println(I18n.get("error", e.getMessage()));
        }
        println("TerminalUI.start() exiting.");
    }

    /**
     * Resolve i18n {{key:param}} placeholders and print with appropriate color.
     * Detects SUCCESS/ERROR/INFO prefixes and applies colored output.
     */
    private void printResolved(String template) {
        String resolved = I18n.resolveTemplate(template);
        if (resolved.startsWith("SUCCESS ")) {
            println(McpColor.success(resolved.substring(8)));
        } else if (resolved.startsWith("ERROR ")) {
            println(McpColor.error(resolved.substring(6)));
        } else if (resolved.startsWith("INFO ")) {
            println(McpColor.info(resolved.substring(5)));
        } else {
            println(resolved);
        }
    }

    private boolean handleBuiltInCommand(String line, LineReader reader) {
        String lower = line.toLowerCase();
        String trimmed = line.trim();

        // Built-in help — runs before SPI to ensure full command list is shown
        if ("help".equals(lower) || "?".equals(lower)) {
            println(buildHelpText());
            return true;
        }

        // "exit dev" / "quit dev" are documented as built-in commands, but they
        // consist of two words. CoreCommandRegistry matches only the first token,
        // so "exit"/"quit" fall through to agent.run() and trigger an LLM call.
        // Route them directly to the dev command provider to avoid that.
        if ("exit dev".equals(lower) || "quit dev".equals(lower)) {
            CoreCommandProvider devProvider = commandRegistry.get("dev");
            if (devProvider != null) {
                CliCommandOutput devOutput = new CliCommandOutput();
                String result = devProvider.execute("exit", devOutput);
                if (result != null) {
                    printResolved(result);
                }
                return true;
            }
        }

        // "restart" is documented in the pending-update exit message as a way
        // to apply staged updates. It must not trigger the LLM.
        if ("restart".equals(lower)) {
            if (restartApplication()) {
                // Break out of the main loop so the JVM can exit; the new
                // process has already been started and will apply the update.
                breakOutOfLoop = true;
            }
            return true;
        }

        // Route task <id> subcommands through the tasks provider.
        if (lower.startsWith("task ")) {
            CliCommandOutput taskOutput = new CliCommandOutput();
            String taskResult = commandRegistry.execute("tasks " + trimmed.substring(5), taskOutput);
            if (taskResult != null) {
                printResolved(taskResult);
                return true;
            }
        }

        // Try SPI-based commands (mcp, config, etc.)
        CliCommandOutput output = new CliCommandOutput();
        String spiResult = commandRegistry.execute(trimmed, output);
        if (spiResult != null) {
            printResolved(spiResult);
            return true;
        }

        // skills commands
        if ("skills".equals(lower) || "skills list".equals(lower)) {
            println(I18n.get("skills_list"));
            if (agent != null && agent.getSkillManager() != null) {
                for (Skill skill : agent.getSkillManager().getSkills()) {
                    println("  " + skill.getName()
                        + " (v" + (skill.getVersion() != null ? skill.getVersion() : "1") + ")"
                        + (skill.isEnabled() ? "" : " [disabled]")
                        + (skill.getDescription() != null ? ": " + skill.getDescription() : ""));
                }
            }
            return true;
        }

        if ("skills reload".equals(lower)) {
            if (agent != null && agent.getSkillManager() != null) {
                agent.getSkillManager().reload();
                agent.invalidateProjectContext();
                println(I18n.get("skills_reloaded"));
            }
            return true;
        }

        // auth commands
        if ("auth list".equals(lower)) {
            java.util.Set<String> paths = authManager != null ? authManager.getAuthorizedPaths() : null;
            if (paths == null || paths.isEmpty()) {
                println(I18n.get("auth_list_empty"));
            } else {
                println(I18n.get("auth_list_title"));
                for (String p : paths) {
                    println("  " + p);
                }
            }
            return true;
        }

        if ("auth clear".equals(lower)) {
            try {
                String confirm = reader.readLine(I18n.get("auth_clear_confirm"));
                if ("y".equalsIgnoreCase(confirm)) {
                    if (authManager != null) authManager.clearAll();
                    println(I18n.get("auth_cleared"));
                } else {
                    println(I18n.get("canceled"));
                }
            } catch (Exception e) {
                println(I18n.get("canceled"));
            }
            return true;
        }

        // history clear
        if ("history clear".equals(lower)) {
            if (historyManager != null) {
                historyManager.clearAllHistory();
                println(I18n.get("history_cleared"));
            } else {
                println(I18n.get("history_not_available"));
            }
            return true;
        }

        if ("history".equals(lower)) {
            if (historyManager != null) {
                int size = historyManager.getHistorySize();
                println(I18n.get("history_size", size));
            }
            return true;
        }

        // Enhanced history commands
        if (lower.startsWith("history ") && !lower.startsWith("history stats") && !lower.startsWith("history export")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                if ("search".equals(parts[1])) {
                    // history search <keyword>
                    String query = parts.length > 2 ? trimmed.substring("history search".length()).trim() : "";
                    if (!query.isEmpty() && historyManager != null) {
                        List<String> results = historyManager.searchCommands(query, 20);
                        if (results.isEmpty()) {
                            println("No commands found matching: " + query);
                        } else {
                            println("\n=== Search Results ===");
                            for (String cmd : results) {
                                println("> " + truncateForDisplay(cmd, 80));
                            }
                        }
                    }
                    return true;
                } else {
                    // history <n> - show recent n commands
                    try {
                        int count = Integer.parseInt(parts[1]);
                        if (historyManager != null) {
                            List<HistoryManager.CommandRecord> records =
                                historyManager.getRecentCommandsWithStats(Math.min(count, 50));
                            println("\n=== Recent Commands ===");
                            for (HistoryManager.CommandRecord rec : records) {
                                String time = java.time.Instant.ofEpochMilli(rec.timestamp)
                                    .toString().replace("T", " ").substring(0, 19);
                                printf("[%s] %s%n", time, truncateForDisplay(rec.inputText, 60));
                                if (rec.modelName != null) {
                                    printf("  Model: %s | Tokens: %d | Duration: %.2fs%n",
                                        rec.modelName, rec.tokenCount + rec.responseTokenCount,
                                        rec.durationMs / 1000.0);
                                }
                            }
                        }
                        return true;
                    } catch (NumberFormatException e) {
                        // Not a number, let it fall through
                    }
                }
            }
            return true;
        }

        if ("history stats".equals(lower)) {
            if (historyManager != null) {
                HistoryManager.SessionStats stats = historyManager.getSessionStats();
                println("\n=== Session Statistics ===");
                println("Total commands: " + stats.totalCommands);
                println("Total request tokens: " + stats.totalTokens);
                println("Total response tokens: " + stats.totalResponseTokens);
                println("Total tokens: " + (stats.totalTokens + stats.totalResponseTokens));
                if (stats.totalCommands > 0) {
                    printf("Avg response time: %.2fs%n", stats.avgDurationMs / 1000.0);
                }
                if (stats.mostUsedModel != null) {
                    println("Most used model: " + stats.mostUsedModel);
                }
                println("");
            } else {
                println("History manager not available");
            }
            return true;
        }

        if (lower.startsWith("history export ")) {
            String filePath = trimmed.substring("history export".length()).trim();
            if (!filePath.isEmpty() && historyManager != null) {
                historyManager.exportHistory(filePath, 100);
                println("History exported to: " + filePath);
            } else {
                println("Usage: history export <file_path>");
            }
            return true;
        }

        if ("context".equals(lower)) {
            if (agent != null) {
                int msgCount = agent.getConversationHistory().size();
                int toolCount = agent.getAvailableTools().size();
                String taskId = agent.getCurrentTaskId();
                println("=== Context ===");
                println("Task: " + (taskId != null ? taskId : "(none)"));
                println("Messages: " + msgCount);
                println("Tools: " + toolCount);
                println(I18n.get("context.use_refresh"));
            }
            return true;
        }

        if ("context refresh".equals(lower) || "refresh context".equals(lower)) {
            agent.invalidateProjectContext();
            println(I18n.get("context_refreshed"));
            return true;
        }

        // Enhanced context commands
        if ("context messages".equals(lower)) {
            if (contextViewer != null) {
                String result = contextViewer.viewMessages(agent.getConversationHistory());
                println(result);
            } else {
                if (historyManager != null) historyManager.saveCommand(trimmed, agent.getCurrentTaskId(), projectName);
                agent.run("show_project_context");
            }
            return true;
        }

        if ("context tokens".equals(lower)) {
            if (contextViewer != null) {
                String result = contextViewer.viewTokenStats(agent.getConversationHistory());
                println(result);
            } else {
                println("Token counter not available");
            }
            return true;
        }

        if ("context tools".equals(lower)) {
            if (contextViewer != null) {
                String result = contextViewer.viewTools(agent.getAvailableTools());
                println(result);
            } else {
                println("Context viewer not available");
            }
            return true;
        }

        if ("version".equals(lower) || "--version".equals(lower) || "-v".equals(lower)) {
            println(I18n.get("version_info"));
            return true;
        }

        if ("undo".equals(lower) || "u".equals(lower)) {
            int count = agent.getSessionTracker().getUndoableCount();
            String result = agent.getSessionTracker().undoLastChange();
            println("\n" + result);
            println("剩余可撤销: " + agent.getSessionTracker().getUndoableCount() + " 项\n");
            return true;
        }

        if ("revert".equals(lower)) {
            String result = agent.getSessionTracker().revertChanges();
            println("\n" + result + "\n");
            return true;
        }

        if ("resume".equals(lower)) {
            // Resume functionality - show latest 3 checkpoints with summaries
            if (checkpointManager == null) {
                println("Checkpoint functionality not available (database not initialized)");
                return true;
            }

            List<TaskCheckpointManager.TaskCheckpoint> allCheckpoints = checkpointManager.listCheckpoints();

            if (allCheckpoints.isEmpty()) {
                println(I18n.get("checkpoint_list_empty"));
                return true;
            }

            // Get latest 3 checkpoints overall (sorted by updated_at DESC)
            int displayCount = Math.min(3, allCheckpoints.size());
            List<TaskCheckpointManager.TaskCheckpoint> displayCheckpoints = allCheckpoints.subList(0, displayCount);

            // Display checkpoint list with summaries
            println("\n" + I18n.get("checkpoint_resume_title"));
            println(String.format("%60s", "").replace(" ", "="));
            println(I18n.get("checkpoint_task_count", displayCheckpoints.size()));
            println("");

            for (int i = 0; i < displayCheckpoints.size(); i++) {
                TaskCheckpointManager.TaskCheckpoint cp = displayCheckpoints.get(i);
                String userInput = cp.getUserInput();
                if (userInput != null && userInput.length() > 60) {
                    userInput = userInput.substring(0, 60) + "...";
                }
                printf("  [%d] %s%n", i + 1, userInput != null ? userInput : "(no input)");
                printf("      Steps: %d | Updated: %s%n",
                    cp.getStepCount(),
                    java.time.Instant.ofEpochMilli(cp.getUpdatedAt()).toString().replace("T", " ").substring(0, 19));

                // Show message summary from conversation_history
                String summary = extractCheckpointSummary(cp);
                if (summary != null && !summary.isEmpty()) {
                    printf("      Summary: %s%n", summary);
                }
                println("");
            }

            println(I18n.get("checkpoint_resume_prompt"));

            String selection;
            try {
                selection = reader.readLine();
            } catch (Exception e) {
                selection = "";
            }

            if (selection == null || selection.trim().isEmpty()) {
                println(I18n.get("canceled"));
                return true;
            }

            // Parse selection
            int idx;
            try {
                idx = Integer.parseInt(selection.trim());
                if (idx < 1 || idx > displayCheckpoints.size()) {
                    println(I18n.get("checkpoint_resume_not_found"));
                    return true;
                }
            } catch (NumberFormatException e) {
                println("Invalid input, please enter a number");
                return true;
            }

            // Resume selected checkpoint
            TaskCheckpointManager.TaskCheckpoint selected = displayCheckpoints.get(idx - 1);
            String taskId = selected.getTaskId();

            println(I18n.get("checkpoint_resume_selected", idx));

            boolean success = agent.resumeFromCheckpoint(taskId);
            if (success) {
                resumingFromCheckpoint = true;
                resumedTaskId = taskId;
                agent.setAutoApproveWrite(true); // Auto-approve writes when resuming
                println(I18n.get("checkpoint_resume_success"));

                // Ask user for next input (to continue the task)
                println("");
                String continuedInput;
                try {
                    continuedInput = reader.readLine("> ");
                } catch (Exception e) {
                    continuedInput = "";
                }

                if (continuedInput != null && !continuedInput.trim().isEmpty()) {
                    try {
                        println(NEWLINE + I18n.get("thinking") + NEWLINE);
                        String response = agent.run(continuedInput);
                        String plainText = stripMarkdown(response);
                        println(NEWLINE + plainText + NEWLINE);

                        // Save to history with task_id
                        if (historyManager != null) {
                            historyManager.saveCommand(continuedInput, agent.getCurrentTaskId(), projectName);
                        }
                    } catch (Exception e) {
                        logger.error("Error resuming task", e);
                        println(I18n.get("error", e.getMessage()));
                    }
                }
            } else {
                println(I18n.get("checkpoint_resume_not_found"));
            }
            return true;
        }

        // Task management commands
        if ("tasks".equals(lower)) {
            if (taskManager == null) {
                println("Task manager not available (async execution not initialized)");
                return true;
            }
            List<TaskManager.Task> allTasks = taskManager.listTasks();
            if (allTasks.isEmpty()) {
                println(I18n.get("cli_tasks_empty"));
                return true;
            }

            println("\n" + I18n.get("cli_tasks_title"));
            println(String.format("%-15s %-10s %-12s %s", "ID", "Status", "Duration", "Input"));
            println(String.format("%-15s %-10s %-12s %s", "---", "------", "--------", "-----"));

            for (TaskManager.Task task : allTasks) {
                String input = task.userInput != null ? truncateForDisplay(task.userInput, 40) : "";
                printf("%-15s %-10s %-12s %s%n",
                        task.id, task.status.name(), task.getDurationFormatted(), input);
            }

            println("");
            printf("Total: %d tasks (%d running, %d pending)%n",
                    allTasks.size(), taskManager.getRunningCount(), taskManager.getPendingCount());
            return true;
        }

        // task <id> - show task details
        if (lower.startsWith("task ") && !lower.contains(" resume") && !lower.contains(" cancel") && !lower.contains(" log")) {
            if (taskManager == null) {
                println("Task manager not available");
                return true;
            }
            String taskId = line.substring(5).trim();
            TaskManager.Task task = taskManager.getTask(taskId);
            if (task == null) {
                println(I18n.get("cli_task_not_found", taskId));
                return true;
            }

            println("\n" + I18n.get("cli_task_detail"));
            println("ID: " + task.id);
            println("Status: " + task.status.name());
            println("Duration: " + task.getDurationFormatted());
            println("Input: " + (task.userInput != null ? task.userInput : "(none)"));

            if (task.result != null && !task.result.isEmpty()) {
                println("Result: " + truncateForDisplay(task.result, 200));
            }
            if (task.error != null && !task.error.isEmpty()) {
                println("Error: " + task.error);
            }
            println("");
            return true;
        }

        // task <id> resume - resume task execution
        if (lower.startsWith("task ") && lower.contains(" resume")) {
            if (taskManager == null) {
                println("Task manager not available");
                return true;
            }
            String taskId = line.substring(5, lower.indexOf(" resume")).trim();
            TaskManager.Task task = taskManager.getTask(taskId);
            if (task == null) {
                println(I18n.get("cli_task_not_found", taskId));
                return true;
            }
            println(I18n.get("cli_task_resume") + ": " + taskId);
            // Resume logic would be implemented here
            return true;
        }

        // task <id> cancel - cancel a task
        if (lower.startsWith("task ") && lower.contains(" cancel")) {
            if (taskManager == null) {
                println("Task manager not available");
                return true;
            }
            String taskId = line.substring(5, lower.indexOf(" cancel")).trim();
            boolean cancelled = taskManager.cancel(taskId);
            if (cancelled) {
                println(I18n.get("cli_task_cancel") + ": " + taskId);
            } else {
                println("Failed to cancel task " + taskId + " (not found or already done)");
            }
            return true;
        }

        // task <id> log - show task change log
        if (lower.startsWith("task ") && lower.contains(" log")) {
            if (taskManager == null) {
                println("Task manager not available");
                return true;
            }
            String taskId = line.substring(5, lower.indexOf(" log")).trim();
            TaskManager.Task task = taskManager.getTask(taskId);
            if (task == null) {
                println(I18n.get("cli_task_not_found", taskId));
                return true;
            }
            println("\n" + I18n.get("cli_task_log") + ": " + taskId);
            // Change log would be shown here
            println("Change log for task " + taskId + " would be displayed here");
            return true;
        }

        // log - show current task change log
        if ("log".equals(lower)) {
            println("\n" + I18n.get("cli_log_title"));
            // Current task change log would be shown here
            println("Current task change log would be displayed here");
            return true;
        }

        // log --task <id> - show specified task change log
        if (lower.startsWith("log --task ")) {
            String taskId = line.substring(11).trim();
            println("\n" + I18n.get("cli_log_title") + ": " + taskId);
            // Specified task change log would be shown here
            println("Change log for task " + taskId + " would be displayed here");
            return true;
        }

        // snapshot <task> - list task snapshots
        if (lower.startsWith("snapshot ")) {
            String[] parts = lower.split("\\s+");
            if (parts.length >= 2) {
                String taskId = parts[1];
                println("\n" + I18n.get("cli_snapshot_list") + ": " + taskId);
                if (parts.length == 2) {
                    // List snapshots for task
                    println(I18n.get("cli_snapshot_empty"));
                } else if (parts.length >= 3) {
                    // snapshot <task> <n> - show snapshot details or perform action
                    String action = parts.length > 3 ? parts[3] : "";
                    if ("rollback".equals(action)) {
                        println(I18n.get("cli_snapshot_rollback") + ": " + parts[2]);
                    } else if ("diff".equals(action)) {
                        println(I18n.get("cli_snapshot_diff") + ": " + parts[2]);
                    } else {
                        println(I18n.get("cli_snapshot_detail") + ": " + parts[2]);
                    }
                }
            }
            return true;
        }

        // context --compress - manually trigger context compression
        if ("context --compress".equals(lower)) {
            println(I18n.get("cli_context_compress"));
            // Context compression would be triggered here
            return true;
        }

        // context --history - show input history
        if ("context --history".equals(lower)) {
            println("\n" + I18n.get("cli_context_history"));
            if (historyManager != null) {
                List<HistoryManager.CommandRecord> records = historyManager.getRecentCommandsWithStats(20);
                for (HistoryManager.CommandRecord rec : records) {
                    String time = java.time.Instant.ofEpochMilli(rec.timestamp)
                        .toString().replace("T", " ").substring(0, 19);
                    printf("[%s] %s%n", time, truncateForDisplay(rec.inputText, 60));
                }
            } else {
                println("History manager not available");
            }
            return true;
        }

        // Remove old commands that are now handled by new structure
        // (status and cancel are now part of "task" command)

        // Config commands - list, get, set, reset
        if (lower.startsWith("config ")) {
            return handleConfigCommand(trimmed, lower);
        }
        if ("config".equals(lower)) {
            return handleConfigCommand("config list", "config list");
        }

        // Rules commands
        if (lower.startsWith("rules")) {
            return handleRulesCommand(trimmed, lower);
        }

        // Regular exit/quit - exit CLI
        if ("exit".equals(lower) || "quit".equals(lower)) {
            return false;
        }

        // Default: command not recognized
        return false;
    }

    /**
     * Mask sensitive configuration values (e.g., api.key) for display.
     * Shows only first 3 and last 4 characters, rest masked with *.
     * Example: sk-xxx...xxxx1234
     */
    private String maskSensitiveValue(String key, String value) {
        if (key == null || value == null || value.length() <= 7) {
            return "***";
        }
        // Only mask api.key by default
        if (!"api.key".equals(key)) {
            return value;
        }
        int totalLength = value.length();
        String prefix = value.substring(0, 3);
        String suffix = value.substring(totalLength - 4);
        int maskLength = totalLength - 7;
        // Java 8 doesn't have String.repeat(), use StringBuilder
        StringBuilder maskedChars = new StringBuilder();
        for (int i = 0; i < Math.max(3, maskLength); i++) {
            maskedChars.append('*');
        }
        return prefix + maskedChars.toString() + suffix;
    }

    /**
     * Handle config CLI commands: list, get, set, reset
     */
    private boolean handleConfigCommand(String trimmed, String lower) {
        if (configManager == null) {
            println("Config manager not available");
            return true;
        }

        String[] parts = trimmed.split("\\s+", 3);
        String subCmd = parts.length > 1 ? parts[1].toLowerCase() : "list";

        switch (subCmd) {
            case "list": {
                String category = parts.length > 2 ? parts[2] : null;
                java.util.List<SystemConfigDao.SystemConfig> configs;

                if (category != null && !category.isEmpty()) {
                    configs = configManager.getByCategory(category);
                    String displayName = configManager.getCategoryDisplayName(category);
                    println("\n=== " + displayName + " ===");
                } else {
                    configs = configManager.getAll();
                    println(I18n.get("config.list.header", "=== System Configuration (" + configs.size() + " items) ==="));
                }

                println("");
                String currentCategory = null;
                for (SystemConfigDao.SystemConfig config : configs) {
                    if (category == null && !config.category.equals(currentCategory)) {
                        currentCategory = config.category;
                        println("[" + configManager.getCategoryDisplayName(currentCategory) + "]");
                    }

                    // Show i18n label if available
                    String label = "";
                    if (config.i18nKey != null && !config.i18nKey.isEmpty()) {
                        label = " (" + I18n.get(config.i18nKey, config.i18nKey) + ")";
                    }

                    String effectiveValue = config.configValue != null ? maskSensitiveValue(config.configKey, config.configValue) : I18n.get("config.empty");
                    println("  " + config.configKey + " = " + effectiveValue + label);
                }
                println("");
                break;
            }
            case "get": {
                // Extract key from the remaining part after "config get "
                String remaining = trimmed.substring("config get".length()).trim();
                if (remaining.isEmpty()) {
                    println("Usage: config get <key>");
                    return true;
                }
                String key = remaining;
                SystemConfigDao.SystemConfig config = configManager.getConfig(key);
                if (config == null) {
                    println(I18n.get("config.error.key_not_found", "Config key not found: " + key));
                    return true;
                }
                String effectiveValue = config.configValue;
                println(config.configKey + " = " + (effectiveValue != null ? effectiveValue : I18n.get("config.empty")));
                if (config.i18nKey != null && !config.i18nKey.isEmpty()) {
                    println(I18n.get("config.label") + ": " + I18n.get(config.i18nKey, config.i18nKey));
                }
                break;
            }
            case "set": {
                // Extract key and value from the remaining part after "config set "
                String remaining = trimmed.substring("config set".length()).trim();
                int spaceIdx = remaining.indexOf(' ');
                if (spaceIdx < 0) {
                    println("Usage: config set <key> <value>");
                    return true;
                }
                String key = remaining.substring(0, spaceIdx);
                String value = remaining.substring(spaceIdx + 1).trim();
                String result = configManager.set(key, value);
                println(result);
                break;
            }
            case "reset": {
                // Extract key from the remaining part after "config reset "
                String remaining = trimmed.substring("config reset".length()).trim();
                if (remaining.isEmpty()) {
                    println("Usage: config reset <key>");
                    return true;
                }
                String key = remaining;
                String result = configManager.reset(key);
                println(result);
                break;
            }
            default:
                println("Usage: config list|get <key>|set <key> <value>|reset <key>");
                break;
        }
        return true;
    }

    /**
     * Handle rules CLI commands
     */
    private boolean handleRulesCommand(String trimmed, String lower) {
        if (commandRulesDao == null) {
            println("Rules not available (database not initialized)");
            return true;
        }

        String[] parts = trimmed.split("\\s+");
        String subCmd = parts.length > 1 ? parts[1].toLowerCase() : "list";

        switch (subCmd) {
            case "":
            case "list":
                handleRulesList(parts.length > 2 ? parts[2] : null);
                break;
            case "add":
                if (parts.length < 5) {
                    println(I18n.get("rules.usage"));
                    println("  mode: terminal, agent");
                    println("  type: allowed, blocked, dangerous");
                } else {
                    String mode = parts[2];
                    String type = parts[3];
                    String pattern = trimmed.substring(trimmed.indexOf(type) + type.length()).trim();
                    handleRulesAdd(mode, type, pattern);
                }
                break;
            case "remove":
                if (parts.length < 5) {
                    println(I18n.get("rules.usage"));
                } else {
                    String mode = parts[2];
                    String type = parts[3];
                    String pattern = trimmed.substring(trimmed.indexOf(type) + type.length()).trim();
                    handleRulesRemove(mode, type, pattern);
                }
                break;
            case "enable":
                if (parts.length < 3) {
                    println(I18n.get("rules.usage"));
                } else {
                    handleRulesEnable(parts[2]);
                }
                break;
            case "disable":
                if (parts.length < 3) {
                    println(I18n.get("rules.usage"));
                } else {
                    handleRulesDisable(parts[2]);
                }
                break;
            case "clear":
                if (parts.length < 3) {
                    println(I18n.get("rules.usage"));
                    println("  source: auto-learned, manual");
                } else {
                    handleRulesClear(parts[2]);
                }
                break;
            case "reset":
                handleRulesReset();
                break;
            default:
                println(I18n.get("rules.usage.detail"));
                break;
        }
        return true;
    }

    private void handleRulesList(String filter) {
        List<CommandRulesDao.CommandRule> rules;

        if (filter == null || filter.isEmpty()) {
            rules = commandRulesDao.findAll();
        } else if ("terminal".equals(filter) || "agent".equals(filter)) {
            rules = commandRulesDao.findByMode(filter);
        } else if ("allowed".equals(filter) || "blocked".equals(filter) || "dangerous".equals(filter)) {
            rules = commandRulesDao.findByType(filter);
        } else if ("built-in".equals(filter) || "manual".equals(filter) || "auto-learned".equals(filter)) {
            rules = commandRulesDao.findBySource(filter);
        } else {
            rules = commandRulesDao.findAll();
        }

        if (rules.isEmpty()) {
            println("\n=== Command Rules (0 rules) ===\n");
            return;
        }

        // Group by mode
        Map<String, Map<String, List<String>>> grouped = new LinkedHashMap<>();
        for (CommandRulesDao.CommandRule rule : rules) {
            grouped.computeIfAbsent(rule.mode, k -> new LinkedHashMap<>())
                  .computeIfAbsent(rule.type, k -> new ArrayList<>())
                  .add(rule.pattern + (rule.enabled ? "" : " [disabled]"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(I18n.get("rules.list.header")).append("\n\n");

        for (Map.Entry<String, Map<String, List<String>>> modeEntry : grouped.entrySet()) {
            sb.append("[").append(modeEntry.getKey()).append("]\n");
            for (Map.Entry<String, List<String>> typeEntry : modeEntry.getValue().entrySet()) {
                sb.append("  ").append(typeEntry.getKey()).append(" = ");
                sb.append(String.join(", ", typeEntry.getValue()));
                sb.append("\n");
            }
            sb.append("\n");
        }

        println(sb.toString());
    }

    private void handleRulesAdd(String mode, String type, String pattern) {
        if (!isValidMode(mode)) {
            println(I18n.get("rules.error.invalid_mode"));
            return;
        }
        if (!isValidType(type)) {
            println(I18n.get("rules.error.invalid_type"));
            return;
        }
        if (pattern == null || pattern.isEmpty()) {
            println(I18n.get("rules.error.pattern_empty"));
            return;
        }

        CommandRulesDao.CommandRule rule = new CommandRulesDao.CommandRule(mode, type, pattern, "manual");
        commandRulesDao.insert(rule);
        println(I18n.get("rules.add.success", mode, type, pattern));
    }

    private void handleRulesRemove(String mode, String type, String pattern) {
        if (!isValidMode(mode)) {
            println(I18n.get("rules.error.invalid_mode"));
            return;
        }
        if (!isValidType(type)) {
            println(I18n.get("rules.error.invalid_type"));
            return;
        }

        commandRulesDao.delete(mode, type, pattern);
        println(I18n.get("rules.remove.success", mode, type, pattern));
    }

    private void handleRulesEnable(String idStr) {
        try {
            long id = Long.parseLong(idStr);
            commandRulesDao.updateEnabled(id, true);
            println(I18n.get("rules.enable.success", id));
        } catch (NumberFormatException e) {
            println(I18n.get("rules.error.id_invalid", idStr));
        }
    }

    private void handleRulesDisable(String idStr) {
        try {
            long id = Long.parseLong(idStr);
            commandRulesDao.updateEnabled(id, false);
            println(I18n.get("rules.disable.success", id));
        } catch (NumberFormatException e) {
            println(I18n.get("rules.error.id_invalid", idStr));
        }
    }

    private void handleRulesClear(String source) {
        if (!"auto-learned".equals(source) && !"manual".equals(source)) {
            println(I18n.get("rules.error.invalid_source"));
            return;
        }

        int count = commandRulesDao.deleteBySource(source);
        println(I18n.get("rules.clear.success", source, count));
    }

    private void handleRulesReset() {
        // Delete all non-built-in rules
        int count = commandRulesDao.deleteNonBuiltin();
        // Re-initialize built-in rules
        List<CommandRulesDao.CommandRule> builtinRules = createBuiltinRules();
        for (CommandRulesDao.CommandRule rule : builtinRules) {
            commandRulesDao.insertIfNotExists(rule);
        }
        println(I18n.get("rules.reset.success", builtinRules.size()));
    }

    private boolean isValidMode(String mode) {
        return "terminal".equals(mode) || "agent".equals(mode);
    }

    private boolean isValidType(String type) {
        return "allowed".equals(type) || "blocked".equals(type) || "dangerous".equals(type);
    }

    private List<CommandRulesDao.CommandRule> createBuiltinRules() {
        List<CommandRulesDao.CommandRule> rules = new ArrayList<>();

        // Agent mode rules
        for (String p : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs", "dd if="))
            rules.add(new CommandRulesDao.CommandRule("agent", "blocked", p, "built-in"));
        for (String p : Arrays.asList("rm -rf", "del /s"))
            rules.add(new CommandRulesDao.CommandRule("agent", "dangerous", p, "built-in"));
        for (String p : Arrays.asList("mvn", "git", "java", "javac", "npm", "node", "go", "python", "python3"))
            rules.add(new CommandRulesDao.CommandRule("agent", "allowed", p, "built-in"));

        // Terminal mode rules
        for (String p : Arrays.asList("rm -rf /", "format", "fdisk", "mkfs"))
            rules.add(new CommandRulesDao.CommandRule("terminal", "blocked", p, "built-in"));
        for (String p : Arrays.asList("&&", "||", ";", "$|", "`"))
            rules.add(new CommandRulesDao.CommandRule("terminal", "dangerous", p, "built-in"));
        for (String p : Arrays.asList("ls", "dir", "cat", "echo", "pwd", "cd", "git", "mvn", "npm", "node", "python", "curl", "clear"))
            rules.add(new CommandRulesDao.CommandRule("terminal", "allowed", p, "built-in"));

        return rules;
    }

    /**
     * Extract command name from action string like "执行命令: sed -i 's/foo/bar/' file"
     * or "命令不在白名单，允许执行: powershell"
     */
    private String extractCommandFromAction(String action) {
        if (action == null) return null;
        // Pattern: "执行命令: sed ..." -> extract "sed"
        if (action.startsWith("执行命令:")) {
            String cmd = action.substring("执行命令:".length()).trim();
            // Get first word (the command)
            if (cmd.contains(" ")) {
                cmd = cmd.split("\\s+")[0];
            }
            return cmd.isEmpty() ? null : cmd;
        }
        // Pattern: "命令不在白名单，允许执行: powershell" -> extract "powershell"
        if (action.startsWith("命令不在白名单，允许执行:")) {
            String cmd = action.substring("命令不在白名单，允许执行:".length()).trim();
            // Get first word (the command)
            if (cmd.contains(" ")) {
                cmd = cmd.split("\\s+")[0];
            }
            return cmd.isEmpty() ? null : cmd;
        }
        return null;
    }

    /**
     * Format tool arguments into a clear, human-readable detail string for the confirmation prompt.
     * Falls back to null if args cannot be parsed or tool is unrecognized.
     */
    private String formatActionDetail(String toolName, String argsJson) {
        if (toolName == null || argsJson == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(argsJson);

            switch (toolName) {
                case "run_command": {
                    String cmd = node.has("cmd") ? node.get("cmd").asText() : "";
                    if (!cmd.isEmpty()) {
                        String args = node.has("args") ? node.get("args").asText() : "";
                        String fullCmd = args.isEmpty() ? cmd : cmd + " " + args;
                        return I18n.get("tool_confirm_run_command", fullCmd);
                    }
                    break;
                }
                case "write_file":
                case "replace_in_file": {
                    String path = node.has("path") ? node.get("path").asText() : "";
                    if (!path.isEmpty()) return toolName + ": " + path;
                    break;
                }
                case "search_files": {
                    String pattern = node.has("pattern") ? node.get("pattern").asText() : "";
                    if (!pattern.isEmpty()) return "search_files: " + pattern;
                    break;
                }
                case "delete_file": {
                    String path = node.has("path") ? node.get("path").asText() : "";
                    if (!path.isEmpty()) return "delete_file: " + path;
                    break;
                }
                case "create_directory": {
                    String path = node.has("path") ? node.get("path").asText() : "";
                    if (!path.isEmpty()) return "create_directory: " + path;
                    break;
                }
                case "compile_sources":
                    return "compile_sources (build custom module)";
                case "restart_application":
                    return "restart_application (apply update)";
                default:
                    break;
            }
        } catch (Exception e) {
            // Fall through to return null
        }
        return null;
    }

    /**
     * Extract the command string from a run_command arguments JSON.
     */
    private String extractCmdFromArgsJson(String argsJson) {
        if (argsJson == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(argsJson);
            String cmd = node.has("cmd") ? node.get("cmd").asText() : "";
            if (cmd.isEmpty()) return null;
            String args = node.has("args") ? node.get("args").asText() : "";
            return args.isEmpty() ? cmd : cmd + " " + args;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if build failure threshold (3) is exceeded.
     */
    private boolean isBuildFailureThresholdExceeded() {
        try {
            Path backupDir = InstallPaths.getBackupDir();
            Path countFile = backupDir.resolve(".build-failure-count");
            if (Files.exists(countFile)) {
                String count = new String(Files.readAllBytes(countFile), StandardCharsets.UTF_8).trim();
                return Integer.parseInt(count) >= 3;
            }
        } catch (Exception e) {
            logger.warn("Failed to check build failure threshold", e);
        }
        return false;
    }

    /**
     * Check for pending updates before exit and notify the user.
     * This is called when the user exits via 'quit' or 'exit'.
     */
    private void checkPendingUpdatesBeforeExit() {
        try {
            Path appHome = InstallPaths.getInstallHome();
            Path corePendingMarker = appHome.resolve("core-update-pending.marker");
            Path customPendingMarker = appHome.resolve("custom").resolve("custom-update-pending.marker");

            boolean hasPending = Files.exists(corePendingMarker) || Files.exists(customPendingMarker);
            if (!hasPending) {
                return;
            }

            println("\n" + I18n.get("self_update_pending_title"));
            println(I18n.get("self_update_pending_separator"));

            if (Files.exists(corePendingMarker)) {
                String version = "";
                try {
                    version = new String(Files.readAllBytes(corePendingMarker), StandardCharsets.UTF_8).trim();
                } catch (Exception e) {}
                println(I18n.get("self_update_core_pending_exit", version));
            }

            if (Files.exists(customPendingMarker)) {
                println(I18n.get("self_update_custom_pending_exit"));
            }

            println(I18n.get("self_update_pending_restart_tip"));
            println(I18n.get("self_update_pending_separator"));
        } catch (Exception e) {
            logger.warn("Failed to check pending updates on exit", e);
        }
    }

    /**
     * Extract a brief summary from checkpoint's conversation history
     */
    private String extractCheckpointSummary(TaskCheckpointManager.TaskCheckpoint cp) {
        try {
            List<String> history = cp.getConversationHistory();
            if (history == null || history.isEmpty()) {
                return null;
            }
            // Count messages by role
            int userCount = 0;
            int assistantCount = 0;
            int toolCount = 0;
            for (String msgJson : history) {
                if (msgJson.contains("\"role\":\"user\"")) userCount++;
                else if (msgJson.contains("\"role\":\"assistant\"")) assistantCount++;
                else if (msgJson.contains("\"role\":\"tool\"")) toolCount++;
            }
            return String.format("%d user msgs, %d assistant msgs, %d tool results",
                userCount, assistantCount, toolCount);
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * Strip markdown formatting from AI responses for terminal display.
     * Delegates to MarkdownUtils for comprehensive markdown-to-text conversion.
     */
    private String stripMarkdown(String text) {
        return MarkdownUtils.stripMarkdown(text);
    }

    /**
     * Print agent response from retry paths (tool confirmation, plan selection).
     * Respects streaming mode to avoid duplicate output.
     */
    private void printRetryResponse(String response) {
        if (response == null || response.isEmpty()) return;
        String resolved = I18n.resolveTemplate(response);
        if (!streamingMode) {
            String plainText = stripMarkdown(resolved);
            // Use printAbove to display agent response above the JLine prompt line,
            // keeping the input line visible at the bottom (chat-style UX).
            if (currentReader != null) {
                currentReader.printAbove(plainText);
            } else {
                println(NEWLINE + plainText + NEWLINE);
            }
        } else {
            // In streaming mode, tokens were already shown in real-time.
            // Only show system-generated messages (errors, summaries, token usage).
            for (String respLine : resolved.split("\n")) {
                String trimmedLine = respLine.trim();
                if (!trimmedLine.isEmpty() && !trimmedLine.equals("```")
                    && (trimmedLine.contains("Token") || trimmedLine.contains("token")
                        || trimmedLine.contains("Usage") || trimmedLine.contains("用量")
                        || trimmedLine.contains("执行超时") || trimmedLine.contains("timeout")
                        || trimmedLine.contains("loop") || trimmedLine.startsWith("Changes:")
                        || trimmedLine.contains("错误") || trimmedLine.contains("Error")
                        || trimmedLine.contains("API") || trimmedLine.contains("quota")
                        || trimmedLine.contains("usage limit") || trimmedLine.contains("rate limit")
                        || trimmedLine.contains("文件改动") || trimmedLine.contains("处改动")
                        || trimmedLine.contains("改动文件") || trimmedLine.startsWith("+ ")
                        || trimmedLine.startsWith("- ") || trimmedLine.startsWith("M ") || trimmedLine.contains("【"))) {
                    if (currentReader != null) {
                        currentReader.printAbove(trimmedLine);
                    } else {
                        println(trimmedLine);
                    }
                }
            }
        }
    }

    /**
     * Display the agent's response in the terminal (used as AsyncAgentExecutor output consumer).
     * Uses printAbove() to keep the JLine prompt line visible at the bottom.
     */
    private void displayAgentResponse(String response) {
        if (response == null) return;
        printRetryResponse(response);

        // Check if user interrupted the execution
        boolean wasInterrupted = response.contains("{{user_interrupted}}");
        if (wasInterrupted) {
            String cleanResponse = response.replace("{{user_interrupted}}", "").trim();
            String interruptMsg = I18n.get("esc_interrupt_exiting");
            if (currentReader != null) {
                currentReader.printAbove("\n" + interruptMsg);
                if (!cleanResponse.isEmpty()) {
                    currentReader.printAbove(stripMarkdown(cleanResponse));
                }
            } else {
                println("\n" + interruptMsg);
                if (!cleanResponse.isEmpty()) {
                    println(stripMarkdown(cleanResponse));
                }
            }
        }
    }

    /**
     * Handle pending tool confirmation in the main thread.
     * Prints prompts, reads user input, processes the response, and sets agent state.
     * Called from the main loop when a confirmation is pending from the executor worker.
     */
    private String handleConfirmationInput(LineReader reader, ToolConfirmationException currentEx) {
        logger.info("Tool confirmation needed: {} ({})", currentEx.getToolName(), currentEx.getAction());

        if (!confirmationPromptShown) {
            displayConfirmationPrompt(currentEx);
        } else {
            // Worker thread already displayed the prompt — just show the input line
            if (currentReader != null) {
                currentReader.printAbove("");
            }
        }

        String confirm;
        try {
            confirm = reader.readLine(I18n.get("confirm_prompt"));
        } catch (UserInterruptException uie) {
            Thread.interrupted();
            confirm = "n";
        } catch (Exception ex) {
            logger.warn("Failed to read confirmation input", ex);
            confirm = "n";
        }
        if (confirm == null) {
            confirm = "n";
        }

        String result = processConfirmationResponse(confirm, currentEx);
        if (result.isEmpty()) {
            return ""; // invalid — caller should retry
        }
        return result;
    }

    /**
     * Display the confirmation prompt text above the current prompt line.
     * Uses printAbove to keep the input prompt at the bottom.
     */
    private void displayConfirmationPrompt(ToolConfirmationException currentEx) {
        String toolName = currentEx.getToolName();
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.get("confirm_required")).append("\n");

        String actionDetail = formatActionDetail(toolName, currentEx.getArguments());
        if (actionDetail != null) {
            sb.append(I18n.get("confirm_action", actionDetail)).append("\n");
        } else {
            sb.append(I18n.get("confirm_action", currentEx.getAction())).append("\n");
        }

        // Build hint for command-specific approval
        String commandHint = "";
        if ("run_command".equals(toolName)) {
            try {
                String cmd = extractCmdFromArgsJson(currentEx.getArguments());
                if (cmd != null && !cmd.isEmpty()) {
                    String cmdName = cmd.contains(" ") ? cmd.split("\\s+")[0] : cmd;
                    commandHint = " (如 " + cmdName + ")";
                }
            } catch (Exception ex) { /* ignore */ }
        }

        sb.append(I18n.get("confirm_options", commandHint));

        String msg = sb.toString();
        if (currentReader != null) {
            currentReader.printAbove(msg);
        } else {
            terminal.writer().println(msg);
            terminal.writer().flush();
        }
    }

    /**
     * Process a confirmation response and set agent state for resume mode.
     * @param confirm the user's response (y/n/a/s/t/c)
     * @param currentEx the confirmation exception
     * @return the confirm string if valid, or "" if invalid
     */
    private String processConfirmationResponse(String confirm, ToolConfirmationException currentEx) {
        if (confirm == null) return "n";

        if ("y".equalsIgnoreCase(confirm)) {
            println(I18n.get("confirm_executing"));
            String cmdToApprove = extractCommandFromAction(currentEx.getAction());
            if (cmdToApprove != null) agent.addApprovedCommand(cmdToApprove);
            if (currentEx.getMessages() != null) {
                agent.setHistory(currentEx.getMessages());
            }
        } else if ("n".equalsIgnoreCase(confirm)) {
            println(I18n.get("confirm_skipping"));
            List<ChatMessage> msgs = currentEx.getMessages();
            if (msgs != null) {
                List<ChatMessage> modifiedMsgs = new ArrayList<>(msgs);
                String skipToolCallId = currentEx.getToolCallId();
                if (!modifiedMsgs.isEmpty() && skipToolCallId != null) {
                    ChatMessage lastMsg = modifiedMsgs.get(modifiedMsgs.size() - 1);
                    if ("assistant".equals(lastMsg.getRole()) && lastMsg.hasToolCalls()) {
                        ChatMessage skipResult = new ChatMessage("tool",
                            "SKIPPED_BY_USER: " + currentEx.getToolName(), skipToolCallId);
                        skipResult.setToolCallName(currentEx.getToolName());
                        modifiedMsgs.add(skipResult);
                    }
                }
                agent.setHistory(modifiedMsgs);
            }
        } else if ("a".equalsIgnoreCase(confirm)) {
            agent.setAutoApproveWrite(true);
            println(I18n.get("auto_approve_all_enabled"));
            if (currentEx.getMessages() != null) {
                agent.setHistory(currentEx.getMessages());
            }
        } else if ("s".equalsIgnoreCase(confirm)) {
            String cmdToApprove = extractCommandFromAction(currentEx.getAction());
            if (cmdToApprove != null) {
                agent.addApprovedCommand(cmdToApprove);
                println(I18n.get("auto_approve_cmd_enabled", cmdToApprove));
            } else {
                println(I18n.get("auto_approve_cmd_failed"));
            }
            if (currentEx.getMessages() != null) {
                agent.setHistory(currentEx.getMessages());
            }
        } else if ("t".equalsIgnoreCase(confirm)) {
            String currentTool = currentEx.getToolName();
            if (currentTool != null) {
                agent.addApprovedCommand(currentTool.toLowerCase());
                println(I18n.get("auto_approve_tool_enabled", currentTool));
            }
            if (currentEx.getMessages() != null) {
                agent.setHistory(currentEx.getMessages());
            }
        } else if ("c".equalsIgnoreCase(confirm)) {
            println(I18n.get("confirm_canceled"));
        } else {
            println(I18n.get("confirm_invalid"));
            return "";
        }

        return confirm;
    }

    /**
     * Check if the given input is a valid confirmation response.
     */
    private boolean isValidConfirmationResponse(String input) {
        if (input == null || input.isEmpty()) return false;
        String lower = input.trim().toLowerCase();
        return lower.equals("y") || lower.equals("n") || lower.equals("a")
            || lower.equals("s") || lower.equals("t") || lower.equals("c");
    }

    /**
     * Handle pending authorization in the main thread.
     */
    private String handleAuthorizationInput(LineReader reader, UnauthorizedAccessException e) {
        logger.info("Unauthorized access detected: {}", e.getMessage());
        String path = e.getPath() != null ? e.getPath() : "unknown";
        println(I18n.get("auth_required"));
        println(I18n.get("auth_path_attempt", path));
        println(I18n.get("auth_tool", e.getToolName()));

        String confirm;
        try {
            confirm = reader.readLine(I18n.get("auth_grant_question"));
        } catch (Exception ex) {
            confirm = "n";
        }

        if ("y".equalsIgnoreCase(confirm)) {
            authManager.authorize(path);
            println(I18n.get("auth_granted"));
        } else if ("a".equalsIgnoreCase(confirm)) {
            authManager.authorize(path);
            agent.setAutoApproveWrite(true);
            println(I18n.get("auth_granted_permanent", path));
        } else {
            println(I18n.get("auth_denied"));
        }

        return confirm;
    }

    private String truncateForDisplay(String text, int maxLength) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength) + "...";
    }

    /**
     * Check if streaming mode is enabled
     */
    public boolean isStreamingMode() {
        return streamingMode;
    }

    /**
     * Set streaming mode
     */
    public void setStreamingMode(boolean enabled) {
        this.streamingMode = enabled;
    }

    /**
     * Print streaming token to terminal with color
     */
    public void printStreamingToken(String token) {
        streamingHadContent = true;
        try {
            AttributedString styled = new AttributedString(token,
                AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
            if (terminal != null) {
                terminal.writer().write(styled.toAnsi());
                terminal.writer().flush();
            } else {
                println(styled.toAnsi());
                flushTerminal();
            }
        } catch (Exception e) {
            if (terminal != null) {
                terminal.writer().write(token);
                terminal.writer().flush();
            } else {
                println(token);
            }
        }
    }

    /**
     * Print streaming completion marker
     */
    public void printStreamingComplete() {
        if (!streamingHadContent) {
            return;
        }
        streamingHadContent = false;
        // Just ensure a trailing newline. Don't redraw the "> " prompt here —
        // it will naturally reappear when the main loop reaches readLine("> ").
        // Redrawing it mid-streaming would make "> " appear in the output content.
        if (terminal != null) {
            terminal.writer().write("\n");
            terminal.writer().flush();
        } else {
            println("");
        }
    }

    /**
     * Check if ESC key is pressed (non-blocking).
     * Returns true if ESC (ASCII 27) is detected in stdin.
     * Works on Linux, Mac, and Windows.
     */
    private boolean checkEscKeyPressed() {
        try {
            // Use System.in.available() to check if there's input without blocking
            if (System.in.available() > 0) {
                // Read the first byte to check if it's ESC
                int b = System.in.read();
                if (b == 27) { // ESC key
                    // Consume any remaining escape sequence bytes
                    while (System.in.available() > 0) {
                        System.in.read();
                    }
                    return true;
                } else {
                    // Not ESC - put it back (this is imperfect, but works for most cases)
                    // Actually, we can't unread, so we just return false
                    // The byte we read will be consumed by the next readLine
                }
            }
        } catch (Exception e) {
            // Ignore - just means we can't check
        }
        return false;
    }

    /**
     * Prompt user for confirmation when ESC is pressed during task execution.
     * Returns true if user wants to exit, false if they want to continue.
     */
    private boolean promptEscInterrupt() {
        println("");
        println(I18n.get("esc_interrupt_title"));
        print(I18n.get("esc_interrupt_confirm"));
        flushTerminal();

        try {
            // Read a single character
            int ch = System.in.read();
            // Consume remaining newline
            if (System.in.available() > 0) {
                System.in.read();
            }

            if (ch == 'y' || ch == 'Y') {
                println(I18n.get("esc_interrupt_exiting"));
                return true;
            } else {
                println(I18n.get("esc_interrupt_continuing"));
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void println(String msg) {
        terminalWrite(w -> w.println(msg), msg);
    }

    private void print(String msg) {
        terminalWrite(w -> w.print(msg), msg);
    }

    private void printf(String format, Object... args) {
        if (terminal != null) {
            terminal.writer().printf(format, args);
            terminal.writer().flush();
        } else if (!isServerMode()) {
            System.out.printf(format, args);
        }
    }

    private void flushTerminal() {
        if (terminal != null) {
            terminal.writer().flush();
        } else if (!isServerMode()) {
            System.out.flush();
        }
    }

    private static boolean isServerMode() {
        String role = System.getProperty("diatom.role");
        return "worker".equals(role) || "gateway-daemon".equals(role);
    }

    /**
     * Execute an action on the terminal writer if terminal is available.
     * Server modes (worker, gateway-daemon) → log to file (response goes via API).
     * Interactive modes (CLI, gateway CLI) → System.out fallback if terminal unavailable.
     */
    private void terminalWrite(java.util.function.Consumer<java.io.PrintWriter> action, String msg) {
        if (terminal != null) {
            java.io.PrintWriter w = terminal.writer();
            action.accept(w);
            w.flush();
        } else if (isServerMode()) {
            logger.info(msg);
        } else {
            System.out.println(msg);
        }
    }

}
