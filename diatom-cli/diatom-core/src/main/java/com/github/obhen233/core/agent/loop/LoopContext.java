package com.github.obhen233.core.agent.loop;

import com.github.obhen233.core.agent.checkpoint.CheckpointManager;
import com.github.obhen233.core.agent.context.ContextManager;
import com.github.obhen233.core.agent.context.ExplorationBudget;
import com.github.obhen233.core.agent.tool.ToolExecutor;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.mcp.McpClientManager;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.session.SessionTracker;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.core.tool.ToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 循环上下文 —— 包含一次 run() 调用所需的所有依赖和状态。
 * 由 ReActAgent 构建并传入 {@link AgentLoopController#runLoop(LoopContext)}。
 */
public class LoopContext {

    // === 依赖（不变） ===
    private final AgentLoop agentLoop;
    private final ToolExecutor toolExecutor;
    private final ContextManager contextManager;
    private final LoopDetector loopDetector;
    private final CheckpointManager checkpointManager;
    private final ExplorationBudget explorationBudget;
    private final SessionTracker sessionTracker;
    private final ToolRegistry toolRegistry;
    private final McpClientManager mcpManager;
    private final DefaultPermissionChecker permissionChecker;
    private final Map<String, Tool> allTools;

    // === 运行参数 ===
    private final String userInput;
    private final String endpoint;
    private final int maxSteps;
    private final int maxRetry;
    private final String projectPath;

    // === 可变状态 ===
    private List<ChatMessage> messages;
    private final long[] tokenUsage;
    private final Set<String> toolsUsedInCurrentRun;
    private final Map<Integer, Set<String>> toolsByStep;
    private String incrementalSummary;
    private boolean resumeMode;
    private final AtomicBoolean interruptRequested;

    // === 回调 ===
    private final AiHttpClient.StreamConsumer streamingConsumer;
    private final java.util.function.Consumer<String> statusCallback;

    // === 常量 ===
    private final Set<String> alwaysSendTools;
    private final Set<String> explorationTools;

    public LoopContext(
            AgentLoop agentLoop,
            ToolExecutor toolExecutor,
            ContextManager contextManager,
            LoopDetector loopDetector,
            CheckpointManager checkpointManager,
            ExplorationBudget explorationBudget,
            SessionTracker sessionTracker,
            ToolRegistry toolRegistry,
            McpClientManager mcpManager,
            DefaultPermissionChecker permissionChecker,
            Map<String, Tool> allTools,
            String userInput,
            String endpoint,
            int maxSteps,
            int maxRetry,
            String projectPath,
            List<ChatMessage> messages,
            long[] tokenUsage,
            Set<String> toolsUsedInCurrentRun,
            Map<Integer, Set<String>> toolsByStep,
            String incrementalSummary,
            boolean resumeMode,
            AtomicBoolean interruptRequested,
            AiHttpClient.StreamConsumer streamingConsumer,
            java.util.function.Consumer<String> statusCallback,
            Set<String> alwaysSendTools,
            Set<String> explorationTools) {
        this.agentLoop = agentLoop;
        this.toolExecutor = toolExecutor;
        this.contextManager = contextManager;
        this.loopDetector = loopDetector;
        this.checkpointManager = checkpointManager;
        this.explorationBudget = explorationBudget;
        this.sessionTracker = sessionTracker;
        this.toolRegistry = toolRegistry;
        this.mcpManager = mcpManager;
        this.permissionChecker = permissionChecker;
        this.allTools = allTools;
        this.userInput = userInput;
        this.endpoint = endpoint;
        this.maxSteps = maxSteps;
        this.maxRetry = maxRetry;
        this.projectPath = projectPath;
        this.messages = messages;
        this.tokenUsage = tokenUsage;
        this.toolsUsedInCurrentRun = toolsUsedInCurrentRun;
        this.toolsByStep = toolsByStep;
        this.incrementalSummary = incrementalSummary;
        this.resumeMode = resumeMode;
        this.interruptRequested = interruptRequested;
        this.streamingConsumer = streamingConsumer;
        this.statusCallback = statusCallback;
        this.alwaysSendTools = alwaysSendTools;
        this.explorationTools = explorationTools;
    }

    // === Getters ===

    public AgentLoop getAgentLoop() { return agentLoop; }
    public ToolExecutor getToolExecutor() { return toolExecutor; }
    public ContextManager getContextManager() { return contextManager; }
    public LoopDetector getLoopDetector() { return loopDetector; }
    public CheckpointManager getCheckpointManager() { return checkpointManager; }
    public ExplorationBudget getExplorationBudget() { return explorationBudget; }
    public SessionTracker getSessionTracker() { return sessionTracker; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public McpClientManager getMcpManager() { return mcpManager; }
    public DefaultPermissionChecker getPermissionChecker() { return permissionChecker; }
    public Map<String, Tool> getAllTools() { return allTools; }
    public String getUserInput() { return userInput; }
    public String getEndpoint() { return endpoint; }
    public int getMaxSteps() { return maxSteps; }
    public int getMaxRetry() { return maxRetry; }
    public String getProjectPath() { return projectPath; }
    public List<ChatMessage> getMessages() { return messages; }
    public long[] getTokenUsage() { return tokenUsage; }
    public Set<String> getToolsUsedInCurrentRun() { return toolsUsedInCurrentRun; }
    public Map<Integer, Set<String>> getToolsByStep() { return toolsByStep; }
    public String getIncrementalSummary() { return incrementalSummary; }
    public boolean isResumeMode() { return resumeMode; }
    public AtomicBoolean getInterruptRequested() { return interruptRequested; }
    public AiHttpClient.StreamConsumer getStreamingConsumer() { return streamingConsumer; }
    public java.util.function.Consumer<String> getStatusCallback() { return statusCallback; }
    public Set<String> getAlwaysSendTools() { return alwaysSendTools; }
    public Set<String> getExplorationTools() { return explorationTools; }

    // === Setters for mutable state ===

    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
    public void setIncrementalSummary(String incrementalSummary) { this.incrementalSummary = incrementalSummary; }
    public void setResumeMode(boolean resumeMode) { this.resumeMode = resumeMode; }
}
