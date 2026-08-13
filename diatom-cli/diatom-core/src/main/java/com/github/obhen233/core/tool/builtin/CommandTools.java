package com.github.obhen233.core.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.database.CommandRulesDao;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.knowledge.CommandKnowledgeManager;
import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.core.agent.CommandTimeoutException;
import com.github.obhen233.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.obhen233.util.JsonUtils;

public class CommandTools {
    private static final Logger logger = LoggerFactory.getLogger(CommandTools.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String NEWLINE = System.lineSeparator();

    private final Set<String> allowedCommands;
    private final int timeoutSeconds;
    private final int maxOutputBytes;
    private final boolean whitelistMode;
    private final String workingDir;
    private final String shellType;
    private final String shellPath;
    private final String mavenPath;
    private final String pythonPath;
    private final String nodePath;
    private final String gitPath;
    
    // Timeout callback - set by TerminalUI to handle timeout confirmation
    private TimeoutCallback timeoutCallback;

    // Command knowledge manager for dynamic permission checking
    private CommandKnowledgeManager knowledgeManager;

    // Command validator for static rule validation
    private CommandValidator commandValidator;

    // Executor for async LLM learning to avoid blocking tool execution thread pool
    private final ExecutorService learningExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CommandTools() {
        this(new Config());
    }

    public CommandTools(Set<String> allowedCommands, int timeoutSeconds, int maxOutputBytes) {
        this.allowedCommands = new HashSet<>(allowedCommands);
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
        this.whitelistMode = !allowedCommands.isEmpty();
        this.workingDir = PathUtils.getWorkingDir();
        this.shellType = "native";
        this.shellPath = null;
        this.mavenPath = null;
        this.pythonPath = null;
        this.nodePath = null;
        this.gitPath = null;
    }

    public CommandTools(Config config) {
        this.allowedCommands = config.allowAll ? new HashSet<>() : new HashSet<>(config.allowedCommands);
        this.timeoutSeconds = config.timeoutSeconds;
        this.maxOutputBytes = config.maxOutputBytes;
        this.whitelistMode = !config.allowAll;
        this.workingDir = config.workingDir;
        this.shellType = config.shellType != null ? config.shellType : "native";
        this.shellPath = config.shellPath;
        this.mavenPath = config.mavenPath;
        this.pythonPath = config.pythonPath;
        this.nodePath = config.nodePath;
        this.gitPath = config.gitPath;

        // Initialize CommandValidator
        if (config.databaseManager != null) {
            this.commandValidator = new CommandValidator("agent", whitelistMode, config.databaseManager);
        } else {
            // Fallback to built-in rules
            this.commandValidator = new CommandValidator.Builder()
                    .mode("agent")
                    .whitelistMode(whitelistMode)
                    .loadBuiltin(true)
                    .build();
        }

        // Sync configured paths to shared ProcessEnvironment
        if (mavenPath != null) {
            ProcessEnvironment.setMavenPath(mavenPath);
        }
        if (pythonPath != null) {
            ProcessEnvironment.setPythonPath(pythonPath);
        }
        if (nodePath != null) {
            ProcessEnvironment.setNodePath(nodePath);
        }
        if (gitPath != null) {
            ProcessEnvironment.setGitPath(gitPath);
        }
    }
    
    /**
     * Set callback for timeout confirmation.
     * This allows the UI to intercept timeout and ask user for confirmation.
     */
    public void setTimeoutCallback(TimeoutCallback callback) {
        this.timeoutCallback = callback;
    }

    /**
     * Set the command knowledge manager for dynamic permission checking.
     * When set, commands not in the local whitelist will be checked against
     * the knowledge base, and dangerous commands will be blocked even in allowAll mode.
     */
    public void setKnowledgeManager(CommandKnowledgeManager manager) {
        this.knowledgeManager = manager;
    }

    /**
     * Callback interface for timeout confirmation
     */
    public interface TimeoutCallback {
        /**
         * Called when a command times out.
         * @param command The command that timed out
         * @param elapsedSeconds How long the command has been running
         * @param process The running process (still alive)
         * @return true to continue waiting, false to kill the process
         */
        boolean onTimeout(String command, int elapsedSeconds, Process process);
    }

    @ToolMethod(name = "run_command",
                description = "Execute a whitelisted command. The command must be in the allowed list. Use list_allowed_commands to see available commands.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"cmd\": {\"type\": \"string\"}, \"args\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                riskLevel = "high",
                confirmationTemplate = "tool_confirm_run_command",
                riskDescriptionTemplate = "tool_dangerous_command")
    public String runCommand(String argsJson) {
        try {
            String cmd;
            String args = "";

            if (argsJson == null || argsJson.trim().isEmpty()) {
                return "Error: command required";
            }

            String trimmed = argsJson.trim();
            if (trimmed.startsWith("{")) {
                // Check for truncated JSON (API may return incomplete JSON like "{")
                if (!isValidJson(trimmed)) {
                    logger.warn("Truncated JSON detected in run_command: {}", trimmed);
                    return "Error: truncated JSON arguments from API (likely token limit reached). Please retry with shorter input.";
                }
                JsonNode obj = mapper.readTree(argsJson);
                JsonNode cmdNode = obj.get("cmd");
                if (cmdNode == null || cmdNode.isNull()) {
                    return "Error: 'cmd' parameter is required";
                }
                cmd = cmdNode.asText().trim();
                args = obj.has("args") && !obj.get("args").isNull() ? obj.get("args").asText() : "";
            } else {
                cmd = trimmed;
            }

            String cmdBasename = extractBasename(cmd);
            String cmdLower = cmdBasename.toLowerCase();
            String fullCmd = args.isEmpty() ? cmd : cmd + " " + args;

            // Check knowledge base first
            boolean needsLlmLearning = false;
            if (knowledgeManager != null) {
                CommandKnowledgeManager.CommandPermission perm = knowledgeManager.getCommandPermission(fullCmd);
                if (perm.isDenied()) {
                    throw new CommandNotWhitelistedException(cmd, args, "Command denied by knowledge base: " + perm.permission);
                }
                // Track if this command needs LLM learning (UNSURE with low confidence)
                needsLlmLearning = perm.isUnsure() && perm.confidence < 70;
            }

            // Auto-learn unknown commands BEFORE execution via LLM
            if (needsLlmLearning && knowledgeManager != null) {
                logger.info("Unknown command detected: {}, triggering LLM classification before execution...", fullCmd);
                // Asynchronous learning - don't block thread pool
                // Note: learnCommandWithLlm should be thread-safe or knowledgeManager
                // should handle concurrent access internally
                final String cmdToLearn = fullCmd;
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return knowledgeManager.learnCommandWithLlm(cmdToLearn);
                    } catch (Exception e) {
                        logger.warn("Async LLM learning failed for: {}", cmdToLearn, e);
                        return false;
                    }
                }, learningExecutor);
            }

            // Check whitelist (if enabled)
            if (whitelistMode && !isCommandAllowed(cmdLower, cmd, args)) {
                throw new CommandNotWhitelistedException(cmd, args, getAllowedCommandsList());
            }

            return executeCommand(cmd, args);
        } catch (CommandNotWhitelistedException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error running command", e);
            return "Error: " + e.getMessage();
        }
    }
    
    private boolean isCommandAllowed(String cmdBasename, String fullCmd, String args) {
        // Use CommandValidator for static rule validation
        if (commandValidator != null) {
            String fullCommand = args.isEmpty() ? fullCmd : fullCmd + " " + args;
            CommandValidator.ValidationResult result = commandValidator.validate(fullCommand);
            if (result.isDenied()) {
                logger.debug("Command {} denied by CommandValidator: {}", fullCommand, result.getReason());
                return false;
            }
        }

        // First check local whitelist
        if (allowedCommands.contains(cmdBasename) || allowedCommands.contains(fullCmd.toLowerCase())) {
            return true;
        }

        // If knowledge manager is set, check knowledge base
        if (knowledgeManager != null) {
            String fullCommand = args.isEmpty() ? fullCmd : fullCmd + " " + args;
            CommandKnowledgeManager.CommandPermission perm = knowledgeManager.getCommandPermission(fullCommand);
            if (perm.isDenied()) {
                // Knowledge base explicitly denied this command
                return false;
            }
            if (perm.isAllowed()) {
                // Check for dangerous arguments even if command is allowed
                if (containsDangerousArgument(fullCommand)) {
                    logger.warn("Command {} contains dangerous arguments, denying", fullCommand);
                    return false;
                }
                return true;
            }
        }

        // Check pipe commands in local whitelist
        String fullCommand = args.isEmpty() ? fullCmd : fullCmd + " " + args;
        if (fullCommand.contains("|")) {
            String[] pipeParts = fullCommand.split("\\|");
            for (String part : pipeParts) {
                String trimmedPart = part.trim();
                String[] tokens = trimmedPart.split("\\s+");
                if (tokens.length > 0) {
                    String pipeCmd = extractBasename(tokens[0]).toLowerCase();
                    // Check for dangerous arguments in this pipe segment
                    if (containsDangerousArgument(trimmedPart)) {
                        logger.warn("Pipe segment contains dangerous arguments: {}", trimmedPart);
                        return false;
                    }
                    // Check local whitelist first
                    if (!allowedCommands.contains(pipeCmd) && !allowedCommands.contains(tokens[0].toLowerCase())) {
                        // Check knowledge base if available
                        if (knowledgeManager == null || !knowledgeManager.isAllowed(tokens[0])) {
                            logger.debug("Pipe command not whitelisted: {}", pipeCmd);
                            return false;
                        }
                    }
                }
            }
            logger.debug("All pipe commands are whitelisted");
            return true;
        }

        return false;
    }

    /**
     * Check if command contains dangerous argument patterns
     */
    private boolean containsDangerousArgument(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String lower = command.toLowerCase();
        // Check for dangerous rm patterns
        if (lower.contains("rm -rf") && (lower.contains("*") || lower.contains("/") ||
            lower.contains("-rf /") || lower.contains("-rf /home") ||
            lower.contains("-rf /var") || lower.contains("-rf /etc") ||
            lower.contains("-rf /usr") || lower.contains("-rf /bin"))) {
            return true;
        }
        // Check other dangerous patterns
        if (lower.contains("del /s") || lower.contains("format ") ||
            lower.contains("dd if=") || lower.contains("fdisk") || lower.contains("mkfs")) {
            return true;
        }
        return false;
    }

    /**
     * Check if a string is valid (complete) JSON object.
     * Used to detect truncated JSON from API responses.
     */
    private boolean isValidJson(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        String trimmed = str.trim();
        // Must start with { and end with }
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return false;
        }
        // Check for balanced braces by counting
        int braceCount = 0;
        boolean inString = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '"' && (i == 0 || trimmed.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
            }
        }
        return braceCount == 0;
    }

    @ToolMethod(name = "list_allowed_commands",
                description = "List all commands that are allowed to execute. Use this to check available commands before using run_command.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {}}",
                readOnly = true)
    public String listAllowedCommands(String argsJson) {
        if (!whitelistMode) {
            return "Sandbox mode is disabled. All commands are allowed.";
        }
        return "Allowed commands: " + getAllowedCommandsList();
    }

    private String getAllowedCommandsList() {
        if (allowedCommands == null || allowedCommands.isEmpty()) {
            return "(none)";
        }
        List<String> sorted = new ArrayList<>(allowedCommands);
        Collections.sort(sorted);
        return String.join(", ", sorted);
    }

    private String extractBasename(String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return cmd;
        }

        String basename = cmd;

        // First, find the end of the command name (first space or end of string)
        int firstSpace = cmd.indexOf(' ');
        if (firstSpace > 0) {
            basename = cmd.substring(0, firstSpace);
        }

        // If there was a path prefix, extract just the command name
        int lastSep = Math.max(basename.lastIndexOf('/'), basename.lastIndexOf('\\'));
        if (lastSep >= 0 && lastSep < basename.length() - 1) {
            basename = basename.substring(lastSep + 1);
        }

        // Remove extension on Windows (.exe, .cmd, .bat)
        if (IS_WINDOWS) {
            String lowerBasename = basename.toLowerCase();
            if (lowerBasename.endsWith(".exe")) {
                basename = basename.substring(0, basename.length() - 4);
            } else if (lowerBasename.endsWith(".cmd")) {
                basename = basename.substring(0, basename.length() - 4);
            } else if (lowerBasename.endsWith(".bat")) {
                basename = basename.substring(0, basename.length() - 4);
            }
        }

        return basename;
    }

    private String executeCommand(String cmd, String args) throws IOException {
        if (IS_WINDOWS && isPowerShellCommand(cmd)) {
            logger.warn("PowerShell command detected: {}", cmd);
        }

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(workingDir));

        // Windows cmd.exe doesn't expand ~, so we need to do it manually
        String expandedCmd = IS_WINDOWS ? expandHomeDir(cmd) : cmd;
        String expandedArgs = IS_WINDOWS ? expandHomeDir(args) : args;

        String fullCommand = expandedArgs.isEmpty() ? expandedCmd : expandedCmd + " " + expandedArgs;

        if (IS_WINDOWS) {
            // Windows: always use cmd.exe, NOT bash.
            // Bash interprets Windows path backslashes (\) as escape characters,
            // corrupting paths like C:\Users\1\.diatom\... into C:Users1.diatom...
            pb.command("cmd.exe", "/c", fullCommand);
        } else if (shellPath != null && !"native".equals(shellType)) {
            pb.command(shellPath, "-c", fullCommand);
        } else {
            pb.command("bash", "-c", fullCommand);
        }

        // Use shared environment configuration from ProcessEnvironment
        ProcessEnvironment.configureEnvironment(pb);

        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

        logger.info("Executing command: {} (timeout: {}s, maxOutput: {} bytes)",
                    cmd, timeoutSeconds, maxOutputBytes);

        long startTime = System.currentTimeMillis();

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return "Error: Failed to start process: " + e.getMessage();
        }

        // Use AtomicReference for output stream to allow concurrent access
        AtomicReference<ByteArrayOutputStream> outputStreamRef = new AtomicReference<>(
            new ByteArrayOutputStream(maxOutputBytes + 1)
        );
        AtomicReference<Integer> totalBytesRef = new AtomicReference<>(0);
        AtomicReference<Boolean> outputExceededRef = new AtomicReference<>(false);
        
        // Start output reader thread
        Thread outputThread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            int bytesRead;
            try (InputStream processOutput = process.getInputStream()) {
                while ((bytesRead = processOutput.read(buffer)) != -1) {
                    int total = totalBytesRef.get() + bytesRead;
                    totalBytesRef.set(total);
                    if (total > maxOutputBytes) {
                        outputExceededRef.set(true);
                        break;
                    }
                    ByteArrayOutputStream os = outputStreamRef.get();
                    synchronized (os) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            } catch (IOException e) {
                // Process was killed, ignore
            }
        }, "CommandOutputReader-" + cmd);
        outputThread.setDaemon(true);
        outputThread.start();

        // Wait for process with timeout and optional continuation
        while (true) {
            boolean finished;
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return "Error: Command interrupted";
            }

            if (finished) {
                break; // Process completed successfully
            }

            // Timeout occurred
            int elapsed = (int) ((System.currentTimeMillis() - startTime) / 1000);
            
            // Check if output exceeded limit
            if (outputExceededRef.get()) {
                process.destroyForcibly();
                String truncated = outputStreamRef.get().toString();
                return "Error: Output exceeded " + maxOutputBytes + " bytes limit (killed process)\n\n" +
                       "Partial output:\n" + truncated + "\n... (truncated)";
            }
            
            // Check if we have a callback to ask user
            if (timeoutCallback != null) {
                logger.info("Command timeout after {}s, asking user for confirmation", elapsed);
                boolean shouldContinue = timeoutCallback.onTimeout(fullCommand, elapsed, process);
                
                if (!shouldContinue) {
                    // User chose to cancel
                    process.destroyForcibly();
                    String partialOutput = outputStreamRef.get().toString();
                    return "Error: Command cancelled by user after " + elapsed + " seconds\n\n" +
                           "Partial output:\n" + partialOutput;
                }
                // User chose to continue - loop back and wait another timeout period
                logger.info("User chose to continue, waiting another {} seconds", timeoutSeconds);
                continue;
            }
            
            // No callback - kill process and return error
            process.destroyForcibly();
            return "Error: Command timeout after " + elapsed + " seconds (limit: " + timeoutSeconds + "s)";
        }

        // Wait for output thread to finish
        try {
            outputThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode = process.exitValue();
        String output = outputStreamRef.get().toString();
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;

        boolean hasError = exitCode != 0 || containsErrorPattern(output);

        StringBuilder result = new StringBuilder();
        
        if (hasError) {
            result.append("=== COMMAND FAILED ===").append(NEWLINE);
            result.append("Command: ").append(cmd).append(NEWLINE);
            result.append("Exit code: ").append(exitCode).append(NEWLINE);
            result.append("Execution time: ").append(elapsed).append("s").append(NEWLINE);
            result.append("========================").append(NEWLINE).append(NEWLINE);
            
            if (containsNotFoundError(output)) {
                result.append("[ERROR] Command not found: ").append(cmd).append(NEWLINE);
                result.append("The command '").append(extractBasename(cmd)).append("' was not found in PATH.").append(NEWLINE);
                result.append("Please check if the tool is installed and accessible.").append(NEWLINE).append(NEWLINE);
            }
            
            result.append("Output:").append(NEWLINE);
            result.append(output);
        } else {
            result.append("=== Command Execution Summary ===").append(NEWLINE);
            result.append("Command: ").append(cmd).append(NEWLINE);
            result.append("Exit code: ").append(exitCode).append(NEWLINE);
            result.append("Execution time: ").append(elapsed).append("s").append(NEWLINE);
            result.append("Output size: ").append(output.length()).append(" bytes").append(NEWLINE);
            result.append("================================").append(NEWLINE).append(NEWLINE);
            result.append(output);
        }

        return result.toString();
    }

    private boolean containsErrorPattern(String output) {
        if (output == null || output.isEmpty()) return false;
        String lower = output.toLowerCase();
        return lower.contains("error:") 
            || lower.contains("failed")
            || lower.contains("exception")
            || lower.contains("not found")
            || lower.contains("not recognized")
            || lower.contains("command not found")
            || lower.contains("no such file")
            || lower.contains("permission denied")
            || lower.contains("access denied")
            || lower.contains("unable to")
            || lower.contains("cannot find")
            || lower.contains("cannot open");
    }

    private boolean containsNotFoundError(String output) {
        if (output == null || output.isEmpty()) return false;
        String lower = output.toLowerCase();
        return lower.contains("not found")
            || lower.contains("not recognized")
            || lower.contains("command not found")
            || lower.contains("is not recognized as an internal or external command");
    }

    private String getSafePath() {
        String pathSep = IS_WINDOWS ? ";" : ":";
        StringBuilder safePath = new StringBuilder();
        Set<String> addedPaths = new HashSet<>();

        java.util.function.Consumer<String> addPath = path -> {
            if (path != null && !path.isEmpty()) {
                String normalized = IS_WINDOWS ? path.replace('/', '\\').replaceAll("\\\\+$", "") 
                                               : path.replace('\\', '/').replaceAll("/+$", "");
                if (!addedPaths.contains(normalized)) {
                    if (safePath.length() > 0) {
                        safePath.append(pathSep);
                    }
                    safePath.append(normalized);
                    addedPaths.add(normalized);
                }
            }
        };

        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            addPath.accept(javaHome + (IS_WINDOWS ? "\\bin" : "/bin"));
            
            java.io.File javaHomeDir = new java.io.File(javaHome);
            java.io.File parentDir = javaHomeDir.getParentFile();
            if (parentDir != null && IS_WINDOWS) {
                java.io.File parentBin = new java.io.File(parentDir, "bin");
                if (parentBin.exists() && new java.io.File(parentBin, "javac.exe").exists()) {
                    addPath.accept(parentBin.getAbsolutePath());
                }
            }
        }

        if (mavenPath != null) {
            java.io.File f = new java.io.File(mavenPath);
            addPath.accept(f.isDirectory() ? mavenPath : f.getParent());
        }
        if (pythonPath != null) {
            java.io.File f = new java.io.File(pythonPath);
            addPath.accept(f.isDirectory() ? pythonPath : f.getParent());
        }
        if (nodePath != null) {
            java.io.File f = new java.io.File(nodePath);
            addPath.accept(f.isDirectory() ? nodePath : f.getParent());
        }
        if (gitPath != null) {
            java.io.File f = new java.io.File(gitPath);
            addPath.accept(f.isDirectory() ? gitPath : f.getParent());
        }

        if (IS_WINDOWS) {
            addPath.accept("C:\\Windows\\System32");
            addPath.accept("C:\\Windows");
            String[] unixPaths = {
                "C:\\Program Files\\Git\\bin",
                "C:\\Program Files\\Git\\usr\\bin",
                "C:\\Program Files (x86)\\Git\\bin",
                "C:\\MinGW64\\bin",
                "C:\\cygwin64\\bin"
            };
            for (String unixPath : unixPaths) {
                if (new java.io.File(unixPath).exists()) {
                    addPath.accept(unixPath);
                }
            }
        } else {
            addPath.accept("/usr/local/bin");
            addPath.accept("/usr/bin");
            addPath.accept("/bin");
            addPath.accept("/opt/homebrew/bin");
            addPath.accept("/usr/local/maven/bin");
        }

        logger.debug("Safe PATH constructed: {}", safePath);
        return safePath.toString();
    }

    private boolean isPowerShellCommand(String cmd) {
        if (cmd == null) return false;
        String lower = cmd.toLowerCase();
        return lower.contains("powershell") || lower.contains("pwsh") || lower.startsWith("ps ");
    }

    /**
     * Expand home directory shortcut (~) to actual user home path on Windows.
     * cmd.exe doesn't expand ~ like bash does.
     * Validates that expanded path stays within user home directory.
     */
    private String expandHomeDir(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        String userHome = System.getProperty("user.home");
        Path normalizedHome = Paths.get(userHome).toAbsolutePath().normalize();
        String expanded = path;

        // Only process ~ at the start of path
        if (path.startsWith("~")) {
            if (path.equals("~")) {
                return userHome;
            } else if (path.startsWith("~/")) {
                expanded = userHome + path.substring(1);
            }
        }
        // Also handle %USERPROFILE% style
        if (path.contains("%USERPROFILE%")) {
            expanded = path.replace("%USERPROFILE%", userHome);
        }

        // Validate resulting path stays within user home
        if (!expanded.equals(path)) {
            Path expandedPath = Paths.get(expanded).toAbsolutePath().normalize();
            if (!expandedPath.startsWith(normalizedHome)) {
                throw new SecurityException("Path escapes home directory after expansion: " + path + " -> " + expanded);
            }
        }
        return expanded;
    }

    public static class CommandNotWhitelistedException extends RuntimeException {
        private final String cmd;
        private final String args;
        private final String allowedCommands;

        public CommandNotWhitelistedException(String cmd, String args, String allowedCommands) {
            super("Command not allowed: " + cmd + " is not in the whitelist. Allowed commands: " + allowedCommands);
            this.cmd = cmd;
            this.args = args;
            this.allowedCommands = allowedCommands;
        }

        public String getCmd() { return cmd; }
        public String getArgs() { return args; }
        public String getAllowedCommands() { return allowedCommands; }
    }

    public static class Config {
        private Set<String> allowedCommands = new HashSet<>(Arrays.asList(
            "mvn", "git", "java", "javac", "npm", "node", "go", "python", "python3"
        ));
        private int timeoutSeconds = 60;
        private int maxOutputBytes = 1048576;
        private boolean allowAll = false;
        private String workingDir = PathUtils.getWorkingDir();
        private String shellType = "native";
        private String shellPath = null;
        private String mavenPath = null;
        private String pythonPath = null;
        private String nodePath = null;
        private String gitPath = null;
        private DatabaseManager databaseManager = null;

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public String getShellType() { return shellType; }
        public String getShellPath() { return shellPath; }
        public String getMavenPath() { return mavenPath; }
        public String getPythonPath() { return pythonPath; }
        public String getNodePath() { return nodePath; }
        public String getGitPath() { return gitPath; }
        public String getWorkingDir() { return workingDir; }
        public int getMaxOutputBytes() { return maxOutputBytes; }
        public boolean isAllowAll() { return allowAll; }
        public Set<String> getAllowedCommands() { return allowedCommands; }
        public DatabaseManager getDatabaseManager() { return databaseManager; }

        public Config setAllowedCommands(Set<String> commands) {
            this.allowedCommands = commands;
            return this;
        }

        public Config setTimeoutSeconds(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        public Config setMaxOutputBytes(int bytes) {
            this.maxOutputBytes = bytes;
            return this;
        }

        public Config setAllowAll(boolean allowAll) {
            this.allowAll = allowAll;
            return this;
        }

        public Config setWorkingDir(String dir) {
            this.workingDir = dir;
            return this;
        }

        public Config setShellType(String shellType) {
            this.shellType = shellType;
            return this;
        }

        public Config setShellPath(String shellPath) {
            this.shellPath = shellPath;
            return this;
        }

        public Config setMavenPath(String mavenPath) {
            this.mavenPath = mavenPath;
            return this;
        }

        public Config setPythonPath(String pythonPath) {
            this.pythonPath = pythonPath;
            return this;
        }

        public Config setNodePath(String nodePath) {
            this.nodePath = nodePath;
            return this;
        }

        public Config setGitPath(String gitPath) {
            this.gitPath = gitPath;
            return this;
        }

        public Config setDatabaseManager(DatabaseManager db) {
            this.databaseManager = db;
            return this;
        }
    }
}
