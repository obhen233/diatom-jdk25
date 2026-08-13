package com.github.obhen233.core.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.agent.context.CommandCircuitBreaker;
import com.github.obhen233.core.agent.context.FileReadTracker;
import com.github.obhen233.core.agent.context.ToolResultSummarizer;
import com.github.obhen233.core.engine.CommandPermissionEngine;
import com.github.obhen233.core.mcp.McpClientManager;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.session.SessionTracker;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.core.tool.ToolRegistry;
import com.github.obhen233.core.tool.ToolRegistry.UnauthorizedAccessException;
import com.github.obhen233.spi.Cache;
import com.github.obhen233.spi.CacheFactory;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.impl.GuavaCacheFactory;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.github.obhen233.util.JsonUtils;

public class ToolExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    /**
     * Cross-thread session context for MCP tool execution.
     * Set by the calling thread (e.g. TerminalWebSocketHandler) before tool execution,
     * propagated to the executor thread in executeToolWithTimeout.
     * Used by ProgressPublisher to find the current session ID across threads.
     */
    public static final ThreadLocal<String> CURRENT_SESSION = new ThreadLocal<>();

    private final ToolRegistry registry;
    private final McpClientManager mcpManager;
    private final SessionTracker sessionTracker;
    private final Duration defaultTimeout;
    private final int maxRetry;
    private final ExecutorService executor;

    // Tool-specific timeouts (tool name -> timeout in seconds)
    private final Map<String, Duration> toolTimeouts = new HashMap<>();

    // Default timeouts by tool category
    private static final Duration FILE_OPERATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);  // Commands may take longer
    private static final Duration COMPILE_TIMEOUT = Duration.ofSeconds(300);  // Compilation can be slow
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    // Tool result cache: key = toolName|argsHash, value = result (abstracted via SPI, default Guava)
    private final Cache<String, String> toolResultCache;

    // Path-level cache index: path → set of cache keys that involve this path
    private final Map<String, Set<String>> pathToCacheKeys = new ConcurrentHashMap<>();

    // File read tracker for deduplication
    private final FileReadTracker fileReadTracker;
    
    // Tool result summarizer for content compression and timestamp annotation
    private final ToolResultSummarizer resultSummarizer;
    
    // Circuit breaker for command execution fault tolerance
    private final CommandCircuitBreaker circuitBreaker;

    // Command permission engine (optional, can be set later for knowledge-based permission checking)
    private CommandPermissionEngine permissionEngine;

    // Read-only tools that can be cached
    private static final Set<String> CACHEABLE_TOOLS = new HashSet<>(Arrays.asList(
        "read_file", "read_multiple_files", "list_directory", "search_files",
        "grep", "glob", "get_file_info", "stat", "cat",
        "get_source_tree"
    ));

    // Approval strategy resolver for this agent instance
    private com.github.obhen233.core.security.ApprovalStrategyResolver strategyResolver =
        new com.github.obhen233.core.security.ApprovalStrategyResolver(
            com.github.obhen233.core.security.SandboxLevel.WORKSPACE,
            com.github.obhen233.core.security.ApprovalPolicy.ASK);
    // Auto-approve specific commands (e.g., sed, awk)
    private final Set<String> approvedCommands = new HashSet<>();

    // Workspace directory for resolving project-prefixed paths (IDE mode)
    // e.g., when path is "projectName/src/main/java/X.java", resolves against workspaceDir/projectName/...
    private String workspaceDir;

    public ToolExecutor(ToolRegistry registry, McpClientManager mcpManager,
                       SessionTracker sessionTracker, Duration toolTimeout, int maxRetry) {
        this.registry = registry;
        this.mcpManager = mcpManager;
        this.sessionTracker = sessionTracker;
        this.defaultTimeout = toolTimeout != null ? toolTimeout : DEFAULT_TIMEOUT;
        this.maxRetry = maxRetry;
        this.fileReadTracker = new FileReadTracker();
        this.resultSummarizer = new ToolResultSummarizer();
        this.circuitBreaker = new CommandCircuitBreaker();
        // Use a bounded queue to prevent thread pool starvation:
        // - SynchronousQueue + CallerRunsPolicy causes tasks to run on the calling thread,
        //   which can deadlock when the calling thread is waiting on future.get()
        // - LinkedBlockingQueue allows up to 16 pending tasks before rejecting
        // - AbortPolicy ensures rejected tasks throw immediately rather than blocking
        this.executor = new ThreadPoolExecutor(
            4, 8, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(16),
            new ThreadPoolExecutor.AbortPolicy()
        );

        // Initialize tool result cache via SPI (default Guava)
        CacheFactory cacheFactory = SpiLoader.getFirst(CacheFactory.class, new GuavaCacheFactory());
        this.toolResultCache = cacheFactory.create("toolResult",
            new CacheFactory.CacheConfig()
                .setMaxSize(500)
                .setExpireAfterWriteMillis(120_000));

        // Initialize tool-specific timeouts
        initializeToolTimeouts();
    }

    /**
     * Initialize default timeouts for different tool types
     */
    private void initializeToolTimeouts() {
        // File operations - relatively quick
        toolTimeouts.put("read_file", FILE_OPERATION_TIMEOUT);
        toolTimeouts.put("write_file", FILE_OPERATION_TIMEOUT);
        toolTimeouts.put("replace_in_file", FILE_OPERATION_TIMEOUT);
        toolTimeouts.put("list_directory", FILE_OPERATION_TIMEOUT);
        toolTimeouts.put("list_files", FILE_OPERATION_TIMEOUT);
        toolTimeouts.put("delete_file", FILE_OPERATION_TIMEOUT);
        toolTimeouts.put("create_directory", FILE_OPERATION_TIMEOUT);
        toolTimeouts.put("exists", FILE_OPERATION_TIMEOUT);

        // Search operations - can take longer
        toolTimeouts.put("search_symbols", SEARCH_TIMEOUT);
        toolTimeouts.put("search_files", SEARCH_TIMEOUT);
        toolTimeouts.put("search_references", SEARCH_TIMEOUT);
        toolTimeouts.put("grep", SEARCH_TIMEOUT);
        toolTimeouts.put("glob", SEARCH_TIMEOUT);

        // Command execution - can take much longer
        toolTimeouts.put("run_command", COMMAND_TIMEOUT);

        // Compilation - can be very slow for large projects
        toolTimeouts.put("compile_sources", COMPILE_TIMEOUT);

        // Library downloads - Maven Central can be slow
        toolTimeouts.put("add_lib", Duration.ofSeconds(120));
    }

    /**
     * Get timeout for a specific tool
     */
    private Duration getTimeoutForTool(String toolName) {
        return toolTimeouts.getOrDefault(toolName, defaultTimeout);
    }

    /**
     * Set custom timeout for a specific tool
     */
    public void setToolTimeout(String toolName, Duration timeout) {
        toolTimeouts.put(toolName, timeout);
        logger.debug("Set custom timeout for tool {}: {}s", toolName, timeout.getSeconds());
    }

    public FileReadTracker getFileReadTracker() {
        return fileReadTracker;
    }
    
    public ToolResultSummarizer getResultSummarizer() {
        return resultSummarizer;
    }
    
    public CommandCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Set the command permission engine for knowledge-based permission checking.
     * This enables smarter command classification beyond the hardcoded patterns.
     */
    public void setCommandPermissionEngine(CommandPermissionEngine engine) {
        this.permissionEngine = engine;
    }

    /**
     * Set the SourceCodeExtensionsDao for ToolResultSummarizer.
     * Enables dynamic extension loading for source code file detection.
     */
    public void setSourceCodeExtensionsDao(com.github.obhen233.core.database.SourceCodeExtensionsDao dao) {
        this.resultSummarizer.setSourceCodeExtensionsDao(dao);
    }

    public void setApprovalStrategyResolver(com.github.obhen233.core.security.ApprovalStrategyResolver resolver) {
        this.strategyResolver = resolver;
    }

    public com.github.obhen233.core.security.ApprovalStrategyResolver getApprovalStrategyResolver() {
        return strategyResolver;
    }

    /**
     * Backward-compatible setter — maps old boolean to AUTO/ASK policy.
     */
    public void setAutoApproveWrite(boolean autoApprove) {
        com.github.obhen233.core.security.ApprovalPolicy policy = autoApprove
            ? com.github.obhen233.core.security.ApprovalPolicy.AUTO
            : com.github.obhen233.core.security.ApprovalPolicy.ASK;
        this.strategyResolver = new com.github.obhen233.core.security.ApprovalStrategyResolver(
            com.github.obhen233.core.security.SandboxLevel.WORKSPACE, policy);
    }

    public boolean isAutoApproveWrite() {
        return strategyResolver.isWriteAutoApprovedWithinWorkspace();
    }

    public void addApprovedCommand(String command) {
        this.approvedCommands.add(command.toLowerCase());
    }

    public void clearApprovedCommands() {
        this.approvedCommands.clear();
    }

    /**
     * Set workspace directory for resolving project-prefixed paths (IDE mode).
     * When a tool returns a path like "projectName/src/main/java/X.java",
     * the original file content lookup will try to resolve it against workspaceDir.
     */
    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    public String getWorkspaceDir() {
        return workspaceDir;
    }

    public Set<String> getApprovedCommands() {
        return new HashSet<>(approvedCommands);
    }

    /**
     * Execute a single tool call with timeout
     */
    public String executeToolWithTimeout(ToolCall tc, Map<String, Tool> allTools) throws Exception {
        // Check circuit breaker for command tools
        if ("run_command".equals(tc.getName())) {
            if (!circuitBreaker.shouldAllowExecution()) {
                logger.warn("Command execution blocked by circuit breaker");
                return circuitBreaker.getCircuitOpenMessage();
            }
        }
        
        // Check summarizer cache first (returns summary for repeated references)
        String summarizedCached = resultSummarizer.getCachedResult(tc.getName(), tc.getArguments());
        if (summarizedCached != null) {
            logger.info("Tool result summary cache hit: {}", tc.getName());
            return summarizedCached;
        }
        
        // Check cache first for read-only tools
        String cachedResult = getCacheableResult(tc.getName(), tc.getArguments());
        if (cachedResult != null) {
            logger.info("Tool cache hit: {}", tc.getName());
            // Process through summarizer for timestamp annotation or summary
            return resultSummarizer.processResult(tc.getName(), tc.getArguments(), cachedResult);
        }

        // Check if file was already read and unchanged (deduplication)
        String path = extractPathFromArgs(tc.getArguments());
        if (isFileReadTool(tc.getName()) && path != null) {
            if (fileReadTracker.isFileReadAndUnchanged(path)) {
                String skipMessage = fileReadTracker.getSkipMessage(path);
                logger.info("File {} already read and unchanged, skipping redundant read", path);
                return skipMessage != null ? skipMessage : "[File already read, content unchanged]";
            }
        }

        // Capture original content before execution for tracking
        String originalContent = null;
        if (path != null) {
            originalContent = readFileContentIfExists(path, tc.getName());
        }

        final String finalPath = path;
        final String finalOriginalContent = originalContent;

        // Capture session context from calling thread (e.g. aiExecutor thread)
        // for propagation to the executor thread — needed by ProgressPublisher
        final String callerSessionId = CURRENT_SESSION.get();

        Future<String> future;
        try {
            future = executor.submit(() -> {
            // Propagate session context to this executor thread
            if (callerSessionId != null) {
                CURRENT_SESSION.set(callerSessionId);
            }
            try {
                String result;
                if (mcpManager != null && isMcpTool(tc.getName(), allTools)) {
                    result = mcpManager.callToolByName(tc.getName(), tc.getArguments());
                    // Check for permission errors in MCP result - only check at start to avoid false positives
                    if (result != null && (result.startsWith("Access denied") || result.startsWith("Error: Access denied"))) {
                        String errorPath = extractPathFromArgs(tc.getArguments());
                        throw new ToolRegistry.UnauthorizedAccessException(
                            "Access denied for tool: " + tc.getName() + ", result: " + result,
                            errorPath != null ? errorPath : "",
                            tc.getName());
                    }
                } else {
                    result = registry.execute(tc.getName(), tc.getArguments());
                }

                // Track file changes with original content
                recordFileChange(tc.getName(), tc.getArguments(), finalPath, finalOriginalContent);

                // Cache read-only tool results
                cacheToolResult(tc.getName(), tc.getArguments(), result);

                // Track file reads for deduplication
                trackFileRead(tc.getName(), tc.getArguments(), result);
                
                // Track command errors for circuit breaker
                if ("run_command".equals(tc.getName()) && result != null && result.startsWith("Error:")) {
                    CommandCircuitBreaker.ErrorInfo errorInfo = circuitBreaker.trackError(
                        extractCmdFromArgs(tc.getArguments()), 
                        result
                    );
                    
                    // If circuit was just tripped, return structured error message
                    if (errorInfo.circuitTripped) {
                        return circuitBreaker.getCircuitOpenMessage();
                    }
                } else if ("run_command".equals(tc.getName())) {
                    // Success - reset circuit breaker
                    circuitBreaker.recordSuccess();
                }
                
                // Process result through summarizer for timestamp annotation and summary
                String processedResult = resultSummarizer.processResult(tc.getName(), tc.getArguments(), result);

                return processedResult;
            } catch (Exception e) {
                if (e instanceof com.github.obhen233.core.tool.builtin.FileTools.UnauthorizedPathException) {
                    com.github.obhen233.core.tool.builtin.FileTools.UnauthorizedPathException upEx =
                        (com.github.obhen233.core.tool.builtin.FileTools.UnauthorizedPathException) e;
                    throw new ToolRegistry.UnauthorizedAccessException(
                        upEx.getMessage(), upEx.getRequestedPath(), tc.getName());
                }
                if (e instanceof ToolRegistry.UnauthorizedAccessException) {
                    throw (ToolRegistry.UnauthorizedAccessException) e;
                }
                if (e instanceof com.github.obhen233.core.tool.builtin.CommandTools.CommandNotWhitelistedException) {
                    com.github.obhen233.core.tool.builtin.CommandTools.CommandNotWhitelistedException cne =
                        (com.github.obhen233.core.tool.builtin.CommandTools.CommandNotWhitelistedException) e;
                    // autoApproveWrite can't bypass command whitelist
                    if (approvedCommands.contains(cne.getCmd().toLowerCase())) {
                        logger.info("Command {} was individually approved, proceeding", cne.getCmd());
                        if (sessionTracker != null) {
                            sessionTracker.recordCommandApproved(cne.getCmd());
                        }
                        return "Command approved: " + cne.getCmd();
                    }
                    // Allow read-only commands through
                    if (isReadOnlyCommand(cne.getCmd())) {
                        logger.info("Command {} is read-only, proceeding", cne.getCmd());
                        if (sessionTracker != null) {
                            sessionTracker.recordCommandApproved(cne.getCmd());
                        }
                        return "Command approved (read-only): " + cne.getCmd();
                    }
                    // Return error message to the model instead of throwing exception
                    // This allows the model to try alternative commands
                    String allowedList = cne.getAllowedCommands() != null ? cne.getAllowedCommands() : "(unknown)";
                    logger.warn("Command {} not in whitelist, returning error to model", cne.getCmd());
                    return "ERROR: 命令 '" + cne.getCmd() + "' 不在白名单中。\n\n" +
                           "可用命令列表: " + allowedList + "\n\n" +
                           "请使用 list_allowed_commands 工具查看所有可用命令，或尝试使用上述列表中的替代命令完成任务。";
                }
                throw e;
            } finally {
                // Cleanup session context on executor thread
                if (callerSessionId != null) {
                    CURRENT_SESSION.remove();
                }
            }
        });
        } catch (RejectedExecutionException e) {
            throw new RuntimeException("Tool execution rejected - all threads busy: " + tc.getName(), e);
        }

        try {
            Duration timeout = getTimeoutForTool(tc.getName());
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            Duration timeout = getTimeoutForTool(tc.getName());
            throw new RuntimeException("Tool execution timeout after " + timeout.getSeconds() + " seconds: " + tc.getName());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolConfirmationException) {
                throw (ToolConfirmationException) cause;
            }
            if (cause instanceof UnauthorizedAccessException) {
                throw (UnauthorizedAccessException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("Tool execution failed: " + cause.getMessage(), cause);
        }
    }

    /**
     * Execute a tool call with retry logic
     */
    public String executeWithRetry(ToolCall tc, Map<String, Tool> allTools,
                                   List<ChatMessage> messages, String endpoint,
                                   RetryCallback retryCallback) throws Exception {
        return executeWithRetry(tc, allTools, messages, endpoint, retryCallback, null);
    }

    /**
     * Execute a tool call with retry logic and optional interrupt check.
     *
     * @param interruptCheck if non-null and returns true, execution is interrupted immediately
     */
    public String executeWithRetry(ToolCall tc, Map<String, Tool> allTools,
                                   List<ChatMessage> messages, String endpoint,
                                   RetryCallback retryCallback,
                                   BooleanSupplier interruptCheck) throws Exception {
        int retryCount = 0;
        String lastError = null;

        while (true) {
            // Check for user interrupt before each retry attempt
            if (interruptCheck != null && interruptCheck.getAsBoolean()) {
                logger.info("User interrupt detected in executeWithRetry for tool: {}", tc.getName());
                return "{{user_interrupted}}";
            }

            try {
                return executeToolWithTimeout(tc, allTools);
            } catch (ToolConfirmationException e) {
                throw e;
            } catch (UnauthorizedAccessException e) {
                throw e;
            } catch (Exception e) {
                retryCount++;
                lastError = e.getMessage();
                logger.warn("Tool {} failed (attempt {}/{}): {}", tc.getName(), retryCount, maxRetry, lastError);

                if (retryCount >= maxRetry) {
                    // In non-interactive mode, return structured error message instead of throwing exception
                    // This allows the model to choose an alternative plan automatically
                    String plans = generateAlternativePlans(tc.getName(), tc.getArguments(), lastError, allTools);
                    logger.info("Tool execution failed after {} retries, returning plan options to model", maxRetry);
                    return "ERROR: Tool execution failed after " + maxRetry + " attempts.\n\n" + plans + "\n\n" +
                           "请选择一个方案继续执行，或使用 PLAN_JAVA（纯 Java 实现，无需 Shell）。\n" +
                           "Please select a plan to continue, or use PLAN_JAVA (pure Java, no Shell dependency).";
                }

                // For timeout errors, don't call the retry callback (which asks the model for an
                // alternative tool). Just retry the same tool — it may succeed on a subsequent
                // attempt (e.g. if the index was being built). Calling the callback would
                // corrupt the tool chain by inserting new assistant messages mid-execution.
                if (lastError != null && lastError.contains("timeout after")) {
                    logger.info("Tool {} timed out, retrying (attempt {}/{})", tc.getName(), retryCount, maxRetry);
                    continue;
                }

                // Ask model for alternative implementation
                logger.info("Requesting alternative implementation from model...");
                messages = retryCallback.requestAlternative(tc, lastError, allTools, messages, endpoint);

                // Check if model provided new tool calls
                ChatMessage lastMsg = messages.get(messages.size() - 1);
                if (lastMsg.hasToolCalls()) {
                    tc = lastMsg.getToolCalls().get(0);
                    logger.info("Using alternative tool: {}", tc.getName());
                }
            }
        }
    }

    private String getToolCacheKey(String toolName, String argsJson) {
        return toolName + "|" + argsJson;
    }

    private boolean isCacheableTool(String toolName) {
        return CACHEABLE_TOOLS.contains(toolName);
    }

    /**
     * Check if a tool is read-only (no side effects).
     * Used by AtomicAgentLoop for parallel execution of read-only tools.
     */
    public boolean isReadOnlyTool(String toolName) {
        return isCacheableTool(toolName);
    }

    private String getCacheableResult(String toolName, String argsJson) {
        if (!isCacheableTool(toolName)) {
            return null;
        }
        try {
            return toolResultCache.get(getToolCacheKey(toolName, argsJson));
        } catch (Exception e) {
            return null;
        }
    }

    private void cacheToolResult(String toolName, String argsJson, String result) {
        if (!isCacheableTool(toolName) || result == null) {
            return;
        }
        try {
            String cacheKey = getToolCacheKey(toolName, argsJson);
            toolResultCache.put(cacheKey, result);

            // Index by path for targeted invalidation
            String path = extractPathFromArgs(argsJson);
            if (path != null) {
                pathToCacheKeys.computeIfAbsent(path, k -> ConcurrentHashMap.newKeySet()).add(cacheKey);
            }
        } catch (Exception e) {
            logger.debug("Failed to cache tool result", e);
        }
    }

    public void invalidateCacheForPath(String path) {
        if (path == null) return;
        try {
            Set<String> keys = pathToCacheKeys.remove(path);
            if (keys != null && !keys.isEmpty()) {
                toolResultCache.invalidateAll(keys);
                logger.debug("Invalidated {} cache entries for path: {}", keys.size(), path);
            }
            // Also invalidate any cache entries whose path starts with the changed path
            // (e.g., invalidating "src/" should also invalidate "src/main/java/App.java")
            // Use snapshot of keys to avoid ConcurrentModificationException
            List<String> pathsToClean = new ArrayList<>();
            Set<String> snapshot = new HashSet<>(pathToCacheKeys.keySet());
            for (String p : snapshot) {
                if (p.startsWith(path) || path.startsWith(p)) {
                    Set<String> cachedKeys = pathToCacheKeys.get(p);
                    if (cachedKeys != null) {
                        toolResultCache.invalidateAll(cachedKeys);
                    }
                    pathsToClean.add(p);
                }
            }
            for (String p : pathsToClean) {
                pathToCacheKeys.remove(p);
            }
        } catch (Exception e) {
            logger.debug("Failed to invalidate cache", e);
        }
    }

    private String readFileContentIfExists(String path, String toolName) {
        if (path == null || toolName == null) return null;
        if (!toolName.equals("write_file") && !toolName.equals("replace_in_file") && !toolName.equals("delete_file")) {
            return null;
        }

        // Try 1: resolve path as-is (works for CLI mode where CWD is the project dir)
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get(path);
            if (java.nio.file.Files.exists(filePath)) {
                return new String(java.nio.file.Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            logger.debug("Could not read original file content: {}", path);
        }

        // Try 2: resolve against workspaceDir (IDE mode, path = "projectName/relative/path")
        if (workspaceDir != null && !workspaceDir.isEmpty()) {
            try {
                java.nio.file.Path wsPath = java.nio.file.Paths.get(workspaceDir, path);
                if (java.nio.file.Files.exists(wsPath)) {
                    return new String(java.nio.file.Files.readAllBytes(wsPath), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                logger.debug("Could not read file content via workspaceDir: {}/{}", workspaceDir, path);
            }
        }
        return null;
    }

    private void recordFileChange(String toolName, String argsJson, String path, String originalContent) {
        if (path == null || sessionTracker == null) return;

        // Invalidate tool cache when files are modified
        invalidateCacheForPath(path);

        // Invalidate file read tracking when files are modified
        if ("write_file".equals(toolName) || "replace_in_file".equals(toolName) || 
            "delete_file".equals(toolName)) {
            fileReadTracker.invalidateFile(path);
            logger.debug("Invalidated file read tracking for: {}", path);
        }

        switch (toolName) {
            case "write_file": {
                String newContent = extractNewContentFromArgs(argsJson);
                // Skip tracking if content hasn't actually changed
                if (originalContent != null && newContent != null && originalContent.equals(newContent)) {
                    logger.debug("Skipping change tracking for {}: write_file content unchanged", path);
                    break;
                }
                sessionTracker.recordFileCreated(path);
                if (originalContent != null) {
                    sessionTracker.recordOriginalContent(path, originalContent);
                    sessionTracker.recordNewContent(path, newContent);
                    sessionTracker.recordChange("MODIFY", path, originalContent, newContent);
                } else {
                    sessionTracker.recordNewContent(path, newContent);
                    sessionTracker.recordChange("CREATE", path, null, newContent);
                }
                break;
            }
            case "replace_in_file": {
                String newContent = readFileContentIfExists(path, "read_for_diff");
                // Skip tracking if content hasn't actually changed
                if (originalContent != null && newContent != null && originalContent.equals(newContent)) {
                    logger.debug("Skipping change tracking for {}: replace_in_file content unchanged", path);
                    break;
                }
                sessionTracker.recordFileModified(path);
                if (originalContent != null) {
                    sessionTracker.recordOriginalContent(path, originalContent);
                    sessionTracker.recordNewContent(path, newContent);
                    sessionTracker.recordChange("MODIFY", path, originalContent, newContent);
                }
                break;
            }
            case "delete_file":
                sessionTracker.recordFileDeleted(path);
                if (originalContent != null) {
                    sessionTracker.recordDeletedContent(path, originalContent);
                    sessionTracker.recordChange("DELETE", path, originalContent, null);
                }
                break;
            case "create_directory":
                sessionTracker.recordFileCreated(path);
                sessionTracker.recordChange("CREATE", path, null, null);
                break;
        }
    }

    /**
     * Track file reads for deduplication.
     * When a file is read, record it so we can skip redundant reads.
     */
    private void trackFileRead(String toolName, String argsJson, String result) {
        if (result == null || result.isEmpty()) return;
        
        // Only track successful read operations
        if (!isFileReadTool(toolName)) return;
        if (result.startsWith("Error:") || result.startsWith("ERROR:")) return;
        
        String path = extractPathFromArgs(argsJson);
        if (path == null) return;
        
        // Check if file was already read and unchanged
        if (fileReadTracker.isFileReadAndUnchanged(path)) {
            logger.debug("File {} already read and unchanged", path);
            return;
        }
        
        // Record this file read
        fileReadTracker.recordFileRead(path, result);
        logger.debug("Recorded file read: {}", path);
    }

    /**
     * Check if tool is a file reading tool
     */
    private boolean isFileReadTool(String toolName) {
        return "read_file".equals(toolName) || "read_multiple_files".equals(toolName);
    }

    private String extractNewContentFromArgs(String argsJson) {
        if (argsJson == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(argsJson);
            if (node.has("content")) {
                return node.get("content").asText();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isMcpTool(String toolName, Map<String, Tool> allTools) {
        // Check if tool is from MCP (any server, not just filesystem)
        // by verifying it's in the MCP manager's tools
        if (mcpManager == null) return false;
        Map<String, Tool> mcpTools = mcpManager.getAllTools();
        return mcpTools != null && mcpTools.containsKey(toolName);
    }

    private String extractPathFromArgs(String argsJson) {
        if (argsJson == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(argsJson);
            if (node.has("path")) {
                return node.get("path").asText();
            }
            if (node.has("cmd")) {
                return null;
            }
        } catch (Exception e) {
            logger.debug("Failed to extract path from args: {}", argsJson);
        }
        return null;
    }
    
    /**
     * Extract command name from tool arguments (for run_command tool)
     */
    private String extractCmdFromArgs(String argsJson) {
        if (argsJson == null) return "unknown";
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(argsJson);
            if (node.has("cmd")) {
                return node.get("cmd").asText();
            }
        } catch (Exception e) {
            logger.debug("Failed to extract cmd from args: {}", argsJson);
        }
        return "unknown";
    }

    private String generateAlternativePlans(String toolName, String args, String error, Map<String, Tool> allTools) {
        return "Command execution failed after " + maxRetry + " attempts.\n\n" +
               "Tool: " + toolName + "\n" +
               "Args: " + args + "\n" +
               "Error: " + error + "\n\n" +
               "Please try a different approach or use alternative tools to accomplish the task.\n" +
               "Consider using PLAN_JAVA (pure Java implementation, no Shell dependency) if applicable.";
    }

    // Read-only command detection
    /**
     * Check if a command is read-only (public for use by ReActAgent)
     * Uses CommandPermissionEngine if available for smarter detection
     */
    public boolean isReadOnlyCommand(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return false;
        }

        // Use permission engine if available
        if (permissionEngine != null) {
            return permissionEngine.isReadOnlyCommand(cmd);
        }

        String cleanCmd = cmd.trim();
        String lowerCmd = cleanCmd.toLowerCase();

        if (containsDangerousPattern(lowerCmd)) {
            return false;
        }

        if (containsDangerousPipeline(lowerCmd)) {
            return false;
        }

        if (lowerCmd.startsWith("powershell ") || lowerCmd.startsWith("pwsh ")) {
            return isPowerShellReadOnly(cleanCmd);
        }

        String cmdName = extractCmdName(cleanCmd).toLowerCase();

        if ("git".equals(cmdName) || lowerCmd.startsWith("git ")) {
            return isGitReadOnly(lowerCmd);
        }

        if ("npm".equals(cmdName) || lowerCmd.startsWith("npm ")) {
            return isNpmReadOnly(lowerCmd);
        }

        if ("mvn".equals(cmdName) || lowerCmd.startsWith("mvn ")) {
            return isMavenReadOnly(lowerCmd);
        }

        if ("docker".equals(cmdName) || lowerCmd.startsWith("docker ")) {
            return isDockerReadOnly(lowerCmd);
        }

        if ("cmd".equals(cmdName) && lowerCmd.startsWith("cmd /c ")) {
            String innerCmd = lowerCmd.substring(8).trim();
            if (innerCmd.contains("|")) {
                String[] pipeParts = innerCmd.split("\\|");
                for (String part : pipeParts) {
                    String partCmdName = part.trim().split("\\s+")[0];
                    if (isDangerousPipelineCommand(partCmdName)) {
                        return false;
                    }
                }
                if (isReadOnlyCommandChain(innerCmd)) {
                    return true;
                }
            }
            return isReadOnlyCommand(innerCmd);
        }

        return matchesReadOnlyPattern(cmdName, lowerCmd);
    }

    private boolean isDangerousPipelineCommand(String cmdName) {
        if (cmdName == null) return false;
        String lower = cmdName.toLowerCase();
        String[] dangerous = {"rm", "del", "erase", "format", "rd", "rmdir", "mkfs", "dd", "fdisk"};
        for (String d : dangerous) {
            if (lower.equals(d) || lower.endsWith("\\" + d) || lower.endsWith("/" + d)) {
                return true;
            }
        }
        return false;
    }

    private String extractCmdName(String cmd) {
        String[] parts = cmd.trim().split("\\s+");
        String rawName = parts[0];
        if (rawName.contains("/")) {
            return rawName.substring(rawName.lastIndexOf('/') + 1);
        }
        return rawName;
    }

    private boolean containsDangerousPattern(String cmd) {
        if (cmd.contains("powershell") || cmd.contains("pwsh") || cmd.startsWith("ps ")) {
            if (isPowerShellReadOnly(cmd)) {
                return false;
            }
            return true;
        }
        if (cmd.contains(" && ") || cmd.contains(" || ") || cmd.contains("; ")) {
            if (!isReadOnlyCommandChain(cmd)) {
                return true;
            }
        }
        if (cmd.contains(" > ") || cmd.contains(" >> ") || cmd.contains(" 2>") || cmd.contains(" &>")) {
            return true;
        }
        if (cmd.contains("$(") || cmd.contains("`") || cmd.contains("${")) {
            return true;
        }
        if ((cmd.contains("curl ") || cmd.contains("wget ")) &&
            (cmd.contains(" | ") || cmd.contains(" && ") || cmd.contains("sh ") || cmd.contains("bash "))) {
            return true;
        }
        if ((cmd.contains("rm -rf") || cmd.contains("rm -r") || cmd.contains("del /s") || cmd.contains("rmdir /s"))
            && !cmd.contains("-l") && !cmd.contains("--no-preserve-root")) {
            return true;
        }
        if (cmd.contains("format ") || cmd.contains("dd if=")) {
            return true;
        }
        if (cmd.matches(".*\\|[^|].*sh$") || cmd.matches(".*\\|[^|].*bash$") || cmd.matches(".*\\|[^|].*cmd$")) {
            return true;
        }
        return false;
    }

    private boolean containsDangerousPipeline(String cmd) {
        if (cmd.endsWith("| sh") || cmd.endsWith("| bash") || cmd.endsWith("| cmd") || cmd.endsWith("| powershell")) {
            return true;
        }
        String[] dangerousCmds = {"rm ", "del ", "format ", "dd ", "mkfs", "fdisk"};
        for (String dangerous : dangerousCmds) {
            if (cmd.contains("| " + dangerous)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPowerShellReadOnly(String cmd) {
        String lower = cmd.toLowerCase();
        if (lower.contains("tail") || lower.contains("head") || lower.contains("cat ") ||
            lower.contains("grep") || lower.contains("sed ") || lower.contains("awk ") ||
            lower.contains("ls ") || lower.contains("dir ") || lower.contains("find ") ||
            lower.contains("wc ") || lower.contains("sort ") || lower.contains("uniq ") ||
            lower.contains("tac ") || lower.contains("more ") || lower.contains("less ")) {
            if (containsPowerShellWriteOp(lower)) {
                return false;
            }
            if (!lower.contains(" > ") && !lower.contains(" >> ") &&
                !lower.contains("| set-") && !lower.contains("| out-") &&
                !lower.contains("del ") && !lower.contains("rm ") && !lower.contains("remove-")) {
                return true;
            }
        }

        String[] unixReadOnly = {"tail", "head", "cat", "grep", "sed", "awk", "find", "ls", "dir", "wc", "sort", "uniq"};
        for (String unixCmd : unixReadOnly) {
            if (lower.contains(unixCmd) && !lower.contains("invoke-")) {
                if (containsPowerShellWriteOp(lower)) {
                    return false;
                }
                return true;
            }
        }

        String[] readOnlyVerbs = {"get-", "test-", "resolve-", "split-", "join-", "convert-", "where-", "foreach-", "sort-", "group-", "measure-", "select-", "compare-", "out-"};
        for (String verb : readOnlyVerbs) {
            if (lower.contains(verb)) {
                if (containsPowerShellWriteOp(lower)) {
                    return false;
                }
                return true;
            }
        }
        String[] writeVerbs = {"set-", "new-", "remove-", "delete-", "add-", "clear-", "copy-", "move-", "rename-", "invoke-", "start-", "stop-", "restart-"};
        for (String verb : writeVerbs) {
            if (lower.contains(verb)) {
                return false;
            }
        }
        if (lower.contains("get-content") || lower.contains("gc ")) {
            if (lower.contains("-skip") || lower.contains("-first") || lower.contains("-last")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPowerShellWriteOp(String lower) {
        String[] writeOps = {"set-content", "sc ", "add-content", "ac ", "remove-item", "ri ", "del ", "rm ",
            "rename-item", "rni ", "move-item", "mi ", "copy-item", "cp ", "new-item", "ni ",
            "invoke-expression", "iex", "invoke-command", "icm"};
        for (String op : writeOps) {
            if (lower.contains(op)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGitReadOnly(String cmd) {
        String[] readOnly = {"status", "log", "show", "diff", "blame", "branch -a", "branch -r", "tag", "stash list", "reflog", "cherry-pick", "revert"};
        for (String op : readOnly) {
            if (cmd.contains(op)) {
                return true;
            }
        }
        String[] writeOps = {"commit", "push", "pull", "fetch", "merge", "rebase", "checkout", "reset", "restore", "add ", "rm ", "mv ", "branch -d", "branch -D", "tag -d", "stash pop", "stash drop"};
        for (String op : writeOps) {
            if (cmd.contains(op)) {
                return false;
            }
        }
        return false;
    }

    private boolean isNpmReadOnly(String cmd) {
        String[] readOnly = {"list", "ls", "view", "info", "search", "outdated", "ping", "owner", "repo", "docs", "home"};
        for (String op : readOnly) {
            if (cmd.contains(op)) {
                return true;
            }
        }
        String[] writeOps = {"install", "uninstall", "remove", "publish", "login", "logout", "adduser", "update", "audit", "rebuild", "pack", "link"};
        for (String op : writeOps) {
            if (cmd.contains(op)) {
                return false;
            }
        }
        return true;
    }

    private boolean isMavenReadOnly(String cmd) {
        String[] writeOps = {"clean", "compile", "package", "install", "deploy", "test", "verify", "site"};
        for (String op : writeOps) {
            if (cmd.contains(op)) {
                return false;
            }
        }
        return true;
    }

    private boolean isDockerReadOnly(String cmd) {
        String[] writeOps = {"rm", "rmi", "pull", "push", "run", "commit", "build", "tag", "network rm", "volume rm"};
        for (String op : writeOps) {
            if (cmd.contains(op)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesReadOnlyPattern(String cmdName, String lowerCmd) {
        String[] readOnlyCmds = {
            "cat", "tac", "head", "tail", "less", "more", "nl", "wc",
            "grep", "egrep", "fgrep", "rg", "ack", "ag", "findstr",
            "ls", "dir", "ll", "la", "tree", "pwd",
            "find", "locate", "which", "where", "file", "stat", "readlink",
            "whoami", "hostname", "uname", "date", "time", "uptime", "id", "groups",
            "ps", "top", "free", "df", "du", "netstat", "ss", "lsof",
            "type",
        };

        for (String readOnly : readOnlyCmds) {
            if (cmdName.equals(readOnly) || cmdName.endsWith("/" + readOnly)) {
                return true;
            }
        }

        if (cmdName.equals("curl") || cmdName.equals("wget") || cmdName.endsWith("/curl") || cmdName.endsWith("/wget")) {
            return isCurlWgetReadOnly(lowerCmd);
        }

        return false;
    }

    private boolean isCurlWgetReadOnly(String cmd) {
        String[] writeFlags = {"-x post", "-x put", "-x delete", "-d ", "--data", "--data-binary", "--data-urlencode",
                                "-o ", "--output", "-t ", "--upload-file"};
        for (String flag : writeFlags) {
            if (cmd.contains(flag)) {
                return false;
            }
        }
        return true;
    }

    private boolean isReadOnlyCommandChain(String cmd) {
        String[] separators = {" && ", " || ", ";", "|"};
        String lastPart = cmd;

        for (String sep : separators) {
            if (cmd.contains(sep)) {
                String[] parts = cmd.split(Pattern.quote(sep));
                lastPart = parts[parts.length - 1].trim();
            }
        }

        String lowerLast = lastPart.toLowerCase();
        if (lowerLast.startsWith("cd ") || lowerLast.startsWith("cd/")) {
            return true;
        }
        String[] readOnlyParts = {"tail", "head", "cat", "grep", "find", "ls", "dir", "wc", "sed", "awk", "findstr"};
        for (String part : readOnlyParts) {
            if (lowerLast.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Callback interface for retry operations
     */
    public interface RetryCallback {
        List<ChatMessage> requestAlternative(ToolCall failedTc, String error,
                                            Map<String, Tool> allTools,
                                            List<ChatMessage> messages, String endpoint) throws Exception;
    }

    /**
     * Exception classes for tool execution
     */
    public static class ToolConfirmationException extends RuntimeException {
        private final String toolName;
        private final String arguments;
        private final String action;

        public ToolConfirmationException(String toolName, String arguments, String action) {
            super("需要确认: " + action);
            this.toolName = toolName;
            this.arguments = arguments;
            this.action = action;
        }

        public String getToolName() { return toolName; }
        public String getArguments() { return arguments; }
        public String getAction() { return action; }
    }

    public static class PlanSelectionException extends RuntimeException {
        private final String planA;
        private final String planB;

        public PlanSelectionException(String planA, String planB) {
            super("需要选择方案: Plan A 或 Plan B");
            this.planA = planA;
            this.planB = planB;
        }

        public String getPlanA() { return planA; }
        public String getPlanB() { return planB; }
    }

    /**
     * Shutdown the executor service gracefully
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}