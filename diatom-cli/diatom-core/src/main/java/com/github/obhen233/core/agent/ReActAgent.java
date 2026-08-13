package com.github.obhen233.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.adapter.ProviderRegistry;
import com.github.obhen233.core.agent.checkpoint.CheckpointManager;
import com.github.obhen233.core.agent.context.ContextManager;
import com.github.obhen233.core.agent.context.ExplorationBudget;
import com.github.obhen233.core.agent.context.ToolResultSummarizer;
import com.github.obhen233.core.agent.loop.*;
import com.github.obhen233.core.agent.plan.PlanGenerator;
import com.github.obhen233.core.agent.tool.ToolExecutor;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.database.TaskCheckpointManager;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.mcp.McpClientManager;
import com.github.obhen233.core.mcp.McpConnectionTracker;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.session.SessionTracker;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.core.tool.ToolRegistry;
import com.github.obhen233.core.tool.ToolRegistry.UnauthorizedAccessException;
import com.github.obhen233.config.SystemInfo;
import com.github.obhen233.spi.Cache;
import com.github.obhen233.spi.CacheFactory;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.ToolSecurityProvider;
import com.github.obhen233.spi.impl.DefaultToolSecurityProvider;
import com.github.obhen233.spi.impl.GuavaCacheFactory;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.github.obhen233.util.JsonUtils;

/**
 * ReAct Agent - Terminal AI programming assistant.
 * This is the main facade class that orchestrates all sub-modules.
 */
public class ReActAgent {
    private static final Logger logger = LoggerFactory.getLogger(ReActAgent.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();
    private static final int DEFAULT_MAX_STEPS = 30;
    private static final int DEFAULT_MAX_RETRY = 5;

    private final AiHttpClient httpClient;
    private final ModelAdapter adapter;
    private final ToolRegistry registry;
    private final SkillManager skillManager;
    private final SystemPromptManager promptManager;
    private final ProjectIndexer projectIndexer;
    private final McpClientManager mcpManager;
    private final McpConnectionTracker mcpConnectionTracker;
    private final int maxSteps;
    private final int maxRetry;
    private final String baseUrl;
    private final String model;
    private final List<ChatMessage> history = new ArrayList<>();
    private final SessionTracker sessionTracker;
    private final SystemInfo systemInfo;

    // History limits to prevent unbounded growth
    private static final int MAX_HISTORY_MESSAGES = 100;
    private static final int MAX_HISTORY_TOKENS = 8000;

    // Core tools always sent in every step (step 1+) for maximum responsiveness.
    // These are information-gathering tools needed at any step.
    private static final Set<String> ALWAYS_SEND_TOOLS = new HashSet<>(Arrays.asList(
        "read_file", "search_files", "run_command", "list_files",
        "get_source_tree", "search_symbols", "list_directory", "grep", "glob"
    ));

    // Exploration-only tools that are removed from ALWAYS_SEND_TOOLS after step 3
    // to save ~800-1600 tokens/step. They remain available via toolsUsedInCurrentRun
    // if the LLM already used them, but are no longer always advertised.
    private static final Set<String> EXPLORATION_TOOLS = new HashSet<>(Arrays.asList(
        "get_source_tree", "list_files", "list_directory", "search_files"
    ));

    // Tools used in the current run (reset each run() call).
    // Step 0 sends ALL tools; step 1+ sends ALWAYS_SEND_TOOLS ∪ toolsUsedInCurrentRun.
    private final Set<String> toolsUsedInCurrentRun = new HashSet<>();
    // Track which tools were used in which step for sliding-window pruning.
    // Used to reduce metadata size by dropping tools last used N+ steps ago.
    private final Map<Integer, Set<String>> toolsByStep = new HashMap<>();

    // Sub-modules
    private final ToolExecutor toolExecutor;
    private final ContextManager contextManager;
    private final LoopDetector loopDetector;
    private final AgentLoopController loopController = new DefaultAgentLoopController();
    private final ToolResultSummarizer summarizer;
    private CheckpointManager checkpointManager;
    private int currentStepCount;
    private final PlanGenerator planGenerator;
    private AgentLoop agentLoop;
    private final ExplorationBudget explorationBudget;
    private AiHttpClient.StreamConsumer streamingConsumer;

    // Token usage tracking - shared with agent loop via array reference
    private final long[] tokenUsage = new long[3]; // [0]=promptTokens, [1]=completionTokens, [2]=totalTokens

    // Permission state (delegated to DefaultPermissionChecker)
    public DefaultPermissionChecker permissionChecker;

    // Request cache (abstracted via SPI, default Guava)
    private final Cache<String, CachedResponse> requestCache;

    // Resume mode: set by setHistory() when resuming after confirmation,
    // run() will skip adding user message and continue the agent loop
    private boolean resumeMode = false;

    /** 增量式历史摘要，跨 run() 调用累积，用于保持长期上下文 */
    private String incrementalSummary;

    // Session ID for request cache isolation (different sessions have independent caches)
    private volatile String sessionId = null;

    // Interrupt flag - set by TerminalUI when user presses ESC/Ctrl+C during agent execution
    // Use AtomicBoolean so LoopContext can share the same reference
    private final java.util.concurrent.atomic.AtomicBoolean interruptRequested = new java.util.concurrent.atomic.AtomicBoolean(false);

    public void requestInterrupt() {
        this.interruptRequested.set(true);
    }

    public void resetInterrupt() {
        this.interruptRequested.set(false);
    }

    private static class CachedResponse {
        final String result;
        final long cachedAt;
        CachedResponse(String result) {
            this.result = result;
            this.cachedAt = System.currentTimeMillis();
        }
    }

    public ReActAgent(AiHttpClient httpClient, ModelAdapter adapter, ToolRegistry registry,
                      SkillManager skillManager, SystemPromptManager promptManager, ProjectIndexer projectIndexer) {
        this(httpClient, adapter, registry, skillManager, promptManager, projectIndexer, null);
    }

    public ReActAgent(AiHttpClient httpClient, ModelAdapter adapter, ToolRegistry registry,
                      SkillManager skillManager, SystemPromptManager promptManager, ProjectIndexer projectIndexer,
                      McpClientManager mcpManager) {
        this(httpClient, adapter, registry, skillManager, promptManager, projectIndexer, mcpManager, "gpt-4", "https://api.openai.com");
    }

    public ReActAgent(AiHttpClient httpClient, ModelAdapter adapter, ToolRegistry registry,
                      SkillManager skillManager, SystemPromptManager promptManager, ProjectIndexer projectIndexer,
                      McpClientManager mcpManager, String model, String baseUrl) {
        this(httpClient, adapter, registry, skillManager, promptManager, projectIndexer, mcpManager, model, baseUrl,
             DEFAULT_MAX_RETRY);
    }

    public ReActAgent(AiHttpClient httpClient, ModelAdapter adapter, ToolRegistry registry,
                      SkillManager skillManager, SystemPromptManager promptManager, ProjectIndexer projectIndexer,
                      McpClientManager mcpManager, String model, String baseUrl, Duration toolTimeout, SystemInfo systemInfo,
                      int contextWindow) {
        this(httpClient, adapter, registry, skillManager, promptManager, projectIndexer, mcpManager, model, baseUrl,
             DEFAULT_MAX_RETRY, contextWindow);
    }

    public ReActAgent(AiHttpClient httpClient, ModelAdapter adapter, ToolRegistry registry,
                      SkillManager skillManager, SystemPromptManager promptManager, ProjectIndexer projectIndexer,
                      McpClientManager mcpManager, String model, String baseUrl, int maxRetry) {
        this(httpClient, adapter, registry, skillManager, promptManager, projectIndexer, mcpManager,
             model, baseUrl, maxRetry, 200000);
    }

    public ReActAgent(AiHttpClient httpClient, ModelAdapter adapter, ToolRegistry registry,
                      SkillManager skillManager, SystemPromptManager promptManager, ProjectIndexer projectIndexer,
                      McpClientManager mcpManager, String model, String baseUrl, int maxRetry,
                      int contextWindow) {
        this.httpClient = httpClient;
        this.adapter = adapter;
        this.registry = registry;
        this.skillManager = skillManager;
        this.promptManager = promptManager;
        this.projectIndexer = projectIndexer;
        this.mcpManager = mcpManager;
        this.mcpConnectionTracker = new McpConnectionTracker();
        this.maxSteps = DEFAULT_MAX_STEPS;
        this.maxRetry = maxRetry;
        this.model = model;
        this.baseUrl = baseUrl;
        this.systemInfo = new SystemInfo();
        this.sessionTracker = new SessionTracker();

        // Initialize sub-modules
        this.toolExecutor = new ToolExecutor(
            registry, mcpManager, sessionTracker,
            Duration.ofSeconds(30), maxRetry
        );

        this.contextManager = new ContextManager(
            promptManager, skillManager, projectIndexer, systemInfo, contextWindow
        );

        this.loopDetector = new LoopDetector();
        this.planGenerator = new PlanGenerator();
        this.explorationBudget = new ExplorationBudget();
        this.summarizer = new ToolResultSummarizer();
        // Adjust exploration budget based on workspace size
        this.explorationBudget.adjustBudgetForWorkspace(systemInfo.getUserDir());

        // Initialize permission checker and request cache
        ToolSecurityProvider securityProvider = SpiLoader.getFirst(ToolSecurityProvider.class,
            new DefaultToolSecurityProvider());
        this.permissionChecker = new DefaultPermissionChecker(registry, securityProvider,
            projectIndexer, toolExecutor, explorationBudget);
        CacheFactory cacheFactory = SpiLoader.getFirst(CacheFactory.class, new GuavaCacheFactory());
        this.requestCache = cacheFactory.create("requestCache",
            new CacheFactory.CacheConfig()
                .setMaxSize(100)
                .setExpireAfterWriteMillis(300_000));

        // checkpointManager initialized as null, set via setCheckpointManager()
    }

    /**
     * Determine if atomic mode is required based on current adapter's model type.
     * MiniMax and Claude require atomic tool calls (all results in one response);
     * OpenAI-style models can use iterative mode.
     * This is evaluated dynamically at runtime so model switching (via adapter.setModel())
     * is reflected in the agent's behavior.
     */
    private boolean isAtomicMode() {
        return adapter.getModelType() == ModelAdapter.ModelType.CLAUDE
            || adapter.getModelType() == ModelAdapter.ModelType.MINIMAX;
    }

    /**
     * Ensure the agent loop implementation matches the current model type.
     * If the model was switched at runtime (e.g., via IdeAiConfigService.syncToHttpClient()),
     * the agent loop may need to be recreated to match atomic vs iterative mode.
     * This is called at the start of each run().
     */
    private void ensureAgentLoop(ModelAdapter adapter) {
        boolean shouldBeAtomic = isAtomicMode();
        boolean isAtomic = this.agentLoop instanceof AtomicAgentLoop;
        if (shouldBeAtomic == isAtomic) {
            return; // Already correct, no change needed
        }
        logger.info("Model type changed (atomic={} → {}), recreating agent loop",
            isAtomic, shouldBeAtomic);
        if (shouldBeAtomic) {
            this.agentLoop = new AtomicAgentLoop(httpClient, adapter, toolExecutor, this.permissionChecker, tokenUsage, summarizer);
        } else {
            this.agentLoop = new IterativeAgentLoop(httpClient, adapter, toolExecutor, this.permissionChecker, tokenUsage, summarizer);
        }
        // Preserve streaming consumer if set
        if (streamingConsumer != null && this.agentLoop instanceof AtomicAgentLoop) {
            ((AtomicAgentLoop) this.agentLoop).setStreamingConsumer(streamingConsumer);
        }
    }

    public void setCheckpointManager(TaskCheckpointManager checkpointManager) {
        this.checkpointManager = new CheckpointManager(checkpointManager);
    }

    /**
     * Set the command permission engine for knowledge-based command permission checking.
     * This enables smarter command classification beyond hardcoded patterns.
     */
    public void setCommandPermissionEngine(com.github.obhen233.core.engine.CommandPermissionEngine engine) {
        if (this.toolExecutor != null) {
            this.toolExecutor.setCommandPermissionEngine(engine);
        }
    }

    /**
     * Set streaming consumer for real-time token output.
     * When set, the agent will use streaming mode to output tokens as they arrive.
     */
    public void setStreamingConsumer(AiHttpClient.StreamConsumer streamingConsumer) {
        this.streamingConsumer = streamingConsumer;
        if (this.agentLoop instanceof AtomicAgentLoop) {
            ((AtomicAgentLoop) this.agentLoop).setStreamingConsumer(streamingConsumer);
        }
    }

    /**
     * Set a status callback that gets invoked during API calls.
     * The callback receives "generating" when the API call starts and null when it completes.
     * This enables the IDE to show progress feedback during long model inference periods.
     */
    public void setStatusCallback(java.util.function.Consumer<String> callback) {
        if (this.agentLoop instanceof IterativeAgentLoop) {
            ((IterativeAgentLoop) this.agentLoop).setStatusCallback(callback);
        } else if (this.agentLoop instanceof AtomicAgentLoop) {
            ((AtomicAgentLoop) this.agentLoop).setStatusCallback(callback);
        }
    }

    /**
     * Returns the effective base URL at runtime.
     * Prefers the construction-time baseUrl (which is already the full API URL
     * resolved by AppConfig/CoreInitializer), falling back to AiHttpClient's
     * baseUrl for dynamic updates in IDE mode.
     *
     * <p>In Anthropic mode the full URL (e.g. .../v1/messages) is passed as
     * this.baseUrl, while httpClient only stores the provider base URL. Using
     * the full URL here ensures the correct endpoint is requested.</p>
     */
    private String getEffectiveBaseUrl() {
        if (this.baseUrl != null && !this.baseUrl.isEmpty()) {
            return this.baseUrl;
        }
        return httpClient != null ? httpClient.getBaseUrl() : null;
    }

    public String run(String userInput) {
        // Reset interrupt flag at start of each run() for consistency
        resetInterrupt();

        // Ensure agent loop matches current model (supports runtime model switching)
        ensureAgentLoop(adapter);

        if (checkpointManager != null && checkpointManager.getCurrentTaskId() == null) {
            checkpointManager.generateTaskId(userInput);
        }

        // Check request cache first - use sessionId for cache isolation
        String requestCacheKey = "req_" + (sessionId != null ? sessionId + "_" : "") + userInput;
        try {
            CachedResponse cached = requestCache.get(requestCacheKey);
            if (cached != null) {
                logger.info("Request cache hit for input hash: {}", requestCacheKey);
                return cached.result;
            }
        } catch (Exception e) {
            // Cache miss, proceed normally
        }

        sessionTracker.clear();
        explorationBudget.reset();
        tokenUsage[0] = 0; // promptTokens
        tokenUsage[1] = 0; // completionTokens
        tokenUsage[2] = 0; // totalTokens
        toolsUsedInCurrentRun.clear();

        // Set task context so SessionTracker can persist snapshots and change logs
        try {
            String workspacePath = projectIndexer.getContext().getProjectPath().toString();
            String taskId = checkpointManager != null ? checkpointManager.getCurrentTaskId() : null;
            sessionTracker.setTaskContext(taskId, 0, workspacePath);
        } catch (Exception e) {
            logger.warn("Failed to set session tracker task context", e);
        }

        long runStartTime = System.currentTimeMillis();

        // The baseUrl passed to the agent is already the full API URL (including
        // endpoint path when applicable), resolved by AppConfig/CoreInitializer.
        String effectiveBaseUrl = getEffectiveBaseUrl();

        // effectiveBaseUrl already contains the complete endpoint path; use it directly.
        String endpoint = effectiveBaseUrl;
        logger.info("Using API endpoint: {}", endpoint);
        logger.info("run() called: history.size={}, resumeMode={}, userInput={}",
            history.size(), resumeMode, userInput.length() > 100 ? userInput.substring(0, 100) + "..." : userInput);

        // Initialize or refresh system message with current skills context
        List<String> involvedFiles = contextManager.getInvolvedFiles(userInput);
        ChatMessage systemMsg = contextManager.buildSystemMessage(userInput, involvedFiles);

        if (history.isEmpty()) {
            history.add(systemMsg);
        } else {
            // Replace system message to pick up newly loaded skills or skills that
            // weren't available when the conversation started (e.g. on resume).
            history.set(0, systemMsg);
        }

        // Add user input to history - skip if in resume mode (messages already restored from confirmation)
        if (!resumeMode) {
            boolean userMsgExists = history.stream()
                .anyMatch(m -> "user".equals(m.getRole()) && m.getContent() != null
                    && m.getContent().trim().equals(userInput.trim()));
            if (!userMsgExists) {
                history.add(new ChatMessage("user", userInput));
            }
        } else {
            logger.info("Resume mode: skipping user message addition, history.size={}", history.size());
        }

        // Process conversation
        List<ChatMessage> messages = new ArrayList<>(history);

        // Save initial checkpoint
        if (checkpointManager != null && checkpointManager.getCurrentTaskId() != null) {
            Map<String, Object> agentState = buildAgentState();
            checkpointManager.saveCheckpoint(userInput, messages, 0, agentState);
        }

        // Skip compression/truncation in resume mode — messages are fresh from the
        // ToolConfirmationException and already valid. Re-processing them would waste
        // time and can produce "Invalid tool chain" errors from truncateContext.
        if (!resumeMode) {
            messages = contextManager.compressContext(messages);
            messages = contextManager.truncateContext(messages);
            messages = contextManager.cleanupIncompleteToolChains(messages);
        }

        // If context compression was ever triggered, append incremental summary to
        // system prompt for cross-run context. At this point the cache is already
        // invalidated by the compression, so the extra ~250 tokens of summary don't
        // add extra cost. The summary is truncated to 1000 chars, keeping newest runs.
        if (contextManager.getCompressionCount() > 0
            && incrementalSummary != null && !incrementalSummary.isEmpty()
            && !messages.isEmpty()) {
            String truncated = truncateSummary(incrementalSummary, 1000);
            String sysContent = messages.get(0).getContent();
            sysContent += "\n\n## Previous Task Summaries\n\n" + truncated;
            messages.get(0).setContent(sysContent);
        }

        // Merge tools - use TreeMap for stable ordering (required for DeepSeek context cache)
        Map<String, Tool> allTools = new TreeMap<>(registry.getToolDefinitions());
        if (mcpManager != null) {
            Map<String, Tool> mcpTools = mcpManager.getAllTools();
            allTools = contextManager.mergeTools(allTools, mcpTools);
            // Exclude all MCP tools from frequency-based loop detection (#3).
            // MCP tools (browser, database, filesystem, build, external servers, etc.)
            // are legitimately called with diverse arguments in development workflows.
            // Exact-match (#1) and similarity (#2) checks still catch real loops.
            loopDetector.addExcludedTools(mcpTools.keySet());
        }

        loopDetector.reset();

        String projectPath;
        try {
            projectPath = projectIndexer.getContext().getProjectPath().toString();
        } catch (Exception e) {
            projectPath = "";
        }

        // Build LoopContext and delegate to loop controller
        LoopContext context = new LoopContext(
            agentLoop, toolExecutor, contextManager, loopDetector,
            checkpointManager, explorationBudget, sessionTracker,
            registry, mcpManager, permissionChecker,
            allTools, userInput, endpoint, maxSteps, maxRetry, projectPath,
            messages, tokenUsage, toolsUsedInCurrentRun, toolsByStep,
            incrementalSummary, resumeMode, interruptRequested,
            streamingConsumer, null,
            ALWAYS_SEND_TOOLS, EXPLORATION_TOOLS
        );

        try {
            String result = loopController.runLoop(context);

            // Sync mutable state back from context
            messages = context.getMessages();
            incrementalSummary = context.getIncrementalSummary();

            // Sync history for subsequent calls
            history.clear();
            history.addAll(messages);

            // Cache successful result
            boolean hasToolResults = messages.stream().anyMatch(m -> "tool".equals(m.getRole()));
            if (hasToolResults) {
                try {
                    requestCache.put(requestCacheKey, new CachedResponse(result));
                } catch (Exception e) {
                    logger.debug("Failed to cache request result", e);
                }
            }

            return result;
        } catch (ToolConfirmationException e) {
            // Save current messages to history so retry doesn't re-generate same content
            messages = context.getMessages();
            history.clear();
            history.addAll(messages);
            throw e;
        }
    }

    /**
     * Run agent with additional context.
     * The context is prepended to the user input as system-level context.
     *
     * @param userInput the user input
     * @param context additional context map (e.g., file tree, selected text, etc.)
     * @return the agent response
     */
    public String runWithContext(String userInput, Map<String, Object> context) {
        if (context != null && !context.isEmpty()) {
            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("\n\n[Additional Context]\n");
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                contextBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            contextBuilder.append("[/Additional Context]\n\n");
            userInput = contextBuilder.toString() + userInput;
        }
        return run(userInput);
    }

    public void setAutoApproveWrite(boolean autoApprove) {
        permissionChecker.setAutoApproveWrite(autoApprove);
    }

    /**
     * Set the approval strategy resolver (replaces old boolean autoApproveWrite).
     * Configures both PermissionChecker and all downstream components.
     */
    public void setApprovalStrategyResolver(com.github.obhen233.core.security.ApprovalStrategyResolver resolver) {
        permissionChecker.setApprovalStrategyResolver(resolver);
    }

    public com.github.obhen233.core.security.ApprovalStrategyResolver getApprovalStrategyResolver() {
        return permissionChecker.getApprovalStrategyResolver();
    }

    public void addApprovedCommand(String command) {
        permissionChecker.addApprovedCommand(command);
    }

    public void clearApprovedCommands() {
        permissionChecker.clearApprovedCommands();
    }

    /**
     * Set workspace directory for resolving project-prefixed file paths (IDE mode).
     * Delegates to ToolExecutor for proper original-content capture before file writes.
     */
    public void setWorkspaceDir(String workspaceDir) {
        if (toolExecutor != null) {
            toolExecutor.setWorkspaceDir(workspaceDir);
        }
    }

    public SessionTracker getSessionTracker() {
        return sessionTracker;
    }

    public com.github.obhen233.core.agent.tool.ToolExecutor getToolExecutor() {
        return toolExecutor;
    }

    /**
     * Set a listener for real-time file change notifications.
     * Delegates to SessionTracker, which fires after every tool write operation.
     */
    public void setFileChangeListener(com.github.obhen233.core.session.FileChangeListener listener) {
        if (sessionTracker != null) {
            sessionTracker.setFileChangeListener(listener);
        }
    }

    /**
     * Set whether to include ProjectIndexer project context in the system prompt.
     * Set to false when the caller provides its own project context (e.g. IDE mode).
     */
    public void setIncludeProjectContext(boolean include) {
        contextManager.setIncludeProjectContext(include);
    }

    public SkillManager getSkillManager() {
        return skillManager;
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public void invalidateProjectContext() {
        if (projectIndexer != null) {
            projectIndexer.invalidate();
        }
    }

    public String getCurrentTaskId() {
        return checkpointManager != null ? checkpointManager.getCurrentTaskId() : null;
    }

    public String generateTaskId(String userInput) {
        return checkpointManager != null ? checkpointManager.generateTaskId(userInput) : null;
    }

    public void clearHistory() {
        history.clear();
    }

    /**
     * Get the current step count in the current run() execution.
     */
    public int getCurrentStepCount() {
        return currentStepCount;
    }

    /**
     * Get the checkpoint manager instance for manual checkpoint operations.
     */
    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    /**
     * Get the incremental summary accumulated across run() calls.
     */
    public String getIncrementalSummary() {
        return incrementalSummary;
    }

    /**
     * Set session ID for request cache isolation.
     * Different sessions will have independent caches, preventing cross-session cache pollution.
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
        logger.debug("Session ID set: {}", sessionId);
    }

    /**
     * Set conversation history for resuming after confirmation.
     * This allows the agent to continue from where it left off.
     */
    public void setHistory(List<ChatMessage> messages) {
        history.clear();
        if (messages != null) {
            history.addAll(messages);
        }
        resumeMode = true; // Mark as resume mode so run() continues instead of starting fresh
        logger.info("setHistory called with {} messages, history size now: {}, resumeMode=true",
            messages != null ? messages.size() : 0, history.size());
    }

    /**
     * Inject user feedback into the conversation history.
     * The message is added as a "user"-role message so the agent sees it
     * on the next LLM call and can adapt its behavior accordingly.
     * <p>
     * This is used by the IDE's intervention mechanism: when the user sees
     * a file change diff they want to correct, their feedback is injected
     * here and the agent responds to it in subsequent steps.
     *
     * @param feedback the user's feedback text (injected as a user message)
     */
    public void addFeedbackMessage(String feedback) {
        appendUserMessage(feedback);
    }

    /**
     * Append a user-role message to the conversation history.
     * Custom commands can use this to provide external data for the next agent turn.
     *
     * @param message user message content
     */
    public void appendUserMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;
        history.add(new ChatMessage("user", message));
        logger.info("User message appended to history (history.size={}): {}",
            history.size(), message.length() > 80 ? message.substring(0, 80) + "..." : message);
    }

    /**
     * Get conversation history for context visualization
     */
    public List<ChatMessage> getConversationHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Get available tools for context visualization
     */
    public Map<String, Tool> getAvailableTools() {
        Map<String, Tool> tools = new HashMap<>(registry.getToolDefinitions());
        if (mcpManager != null) {
            Map<String, Tool> mcpTools = mcpManager.getAllTools();
            if (mcpTools != null) {
                for (Map.Entry<String, Tool> entry : mcpTools.entrySet()) {
                    if (!tools.containsKey(entry.getKey())) {
                        tools.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return tools;
    }

    public boolean resumeFromCheckpoint(String taskId) {
        if (checkpointManager == null) {
            logger.warn("No checkpoint manager configured, cannot resume");
            return false;
        }

        Map<String, Object> agentState = new HashMap<>();
        List<ChatMessage> restoredHistory = new ArrayList<>();

        if (!checkpointManager.resumeFromCheckpoint(taskId, restoredHistory, agentState)) {
            return false;
        }

        // Restore agent state
        history.clear();
        history.addAll(restoredHistory);

        // Restore session state
        if (agentState.containsKey("autoApproveWrite")) {
            boolean autoApproveWrite = (Boolean) agentState.get("autoApproveWrite");
            permissionChecker.setAutoApproveWrite(autoApproveWrite);
        }
        if (agentState.containsKey("approvedCommands")) {
            @SuppressWarnings("unchecked")
            List<String> approved = (List<String>) agentState.get("approvedCommands");
            for (String cmd : approved) {
                permissionChecker.addApprovedCommand(cmd);
            }
        }

        // Restore exploration budget state if available
        if (agentState.containsKey("explorationUsed")) {
            int explorationUsed = ((Number) agentState.get("explorationUsed")).intValue();
            this.explorationBudget.setUsedCount(explorationUsed);
        }
        if (agentState.containsKey("guidanceAdded")) {
            boolean guidanceAdded = (Boolean) agentState.get("guidanceAdded");
            this.explorationBudget.setGuidanceAdded(guidanceAdded);
        }

        // Restore token usage
        if (agentState.containsKey("promptTokens")) {
            this.tokenUsage[0] = ((Number) agentState.get("promptTokens")).longValue();
        }
        if (agentState.containsKey("completionTokens")) {
            this.tokenUsage[1] = ((Number) agentState.get("completionTokens")).longValue();
        }
        if (agentState.containsKey("totalTokens")) {
            this.tokenUsage[2] = ((Number) agentState.get("totalTokens")).longValue();
        }

        // Clear session tracker for fresh start
        sessionTracker.clear();
        
        // Reset loop detector
        loopDetector.reset();

        // Restore incremental summary
        if (checkpointManager != null) {
            this.incrementalSummary = checkpointManager.getCurrentLlmSummary();
            if (this.incrementalSummary != null) {
                logger.info("Restored incremental summary from checkpoint ({} chars)", this.incrementalSummary.length());
            }
        }

        logger.info("Successfully resumed checkpoint: {} ({} messages, autoApproveWrite={}, explorationUsed={})",
            taskId, history.size(), permissionChecker.isAutoApproveWrite(), explorationBudget.getUsedCount());
        return true;
    }

    /**
     * Build and save incremental summary after a run() completes.
     * Called at each normal exit point of run().
     */

    /**
     * Build agent state map for checkpoint saving
     */
    public Map<String, Object> buildAgentState() {
        Map<String, Object> state = new HashMap<>();
        state.put("autoApproveWrite", permissionChecker.isAutoApproveWrite());
        state.put("explorationUsed", explorationBudget.getUsedCount());
        state.put("guidanceAdded", explorationBudget.isGuidanceAdded());
        state.put("promptTokens", tokenUsage[0]);
        state.put("completionTokens", tokenUsage[1]);
        state.put("totalTokens", tokenUsage[2]);
        return state;
    }

    /**
     * Truncate summary text to maxChars, keeping the newest runs.
     * The summary is structured as "## Run N" sections.
     * Drops old runs first, keeping the most recent ones within the limit.
     */
    static String truncateSummary(String summary, int maxChars) {
        if (summary == null || summary.length() <= maxChars) return summary;
        // Split by "## Run" boundaries
        String[] runs = summary.split("(?=\\n## Run )", -1);
        if (runs.length <= 1) {
            // No run structure — just hard truncate at last sentence boundary
            String cut = summary.substring(0, maxChars);
            int lastPeriod = Math.max(cut.lastIndexOf('。'), cut.lastIndexOf('.'));
            if (lastPeriod > maxChars / 2) {
                return cut.substring(0, lastPeriod + 1) + "..(truncated)";
            }
            return cut + "..(truncated)";
        }
        // Keep newest runs from the end
        StringBuilder result = new StringBuilder();
        for (int i = runs.length - 1; i >= 0; i--) {
            String run = runs[i];
            if (result.length() + run.length() <= maxChars) {
                result.insert(0, run);
            } else if (result.length() == 0) {
                // Even the newest run is too long — truncate it
                String cut = run.substring(0, maxChars);
                int lastPeriod = Math.max(cut.lastIndexOf('。'), cut.lastIndexOf('.'));
                if (lastPeriod > maxChars / 2) {
                    result.append(cut, 0, lastPeriod + 1).append("..(truncated)");
                } else {
                    result.append(cut).append("..(truncated)");
                }
                break;
            } else {
                break;
            }
        }
        return result.toString();
    }

    /**
     * Run with streaming output (non-blocking)
     * @param userInput The user input
     * @param callback Callback for streaming tokens
     */
    public void runStreaming(String userInput, StreamingCallback callback) {
        // For now, fall back to synchronous run
        // Full streaming implementation would require changes to the agent loop
        try {
            String result = run(userInput);
            if (callback != null) {
                callback.onComplete(result);
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.onError(e);
            }
        }
    }

    /**
     * Shutdown the agent and all its resources (ToolExecutor, etc.)
     * Called when the application is exiting to ensure clean shutdown.
     */
    public void shutdown() {
        toolExecutor.shutdown();
    }

    /**
     * Streaming callback interface
     */
    public interface StreamingCallback {
        void onToken(String token);
        void onComplete(String fullResponse);
        void onError(Throwable e);
    }
}