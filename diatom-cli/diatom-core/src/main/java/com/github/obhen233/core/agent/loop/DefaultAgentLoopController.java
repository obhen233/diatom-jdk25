package com.github.obhen233.core.agent.loop;

import com.github.obhen233.core.agent.ToolConfirmationException;
import com.github.obhen233.core.agent.checkpoint.CheckpointManager;
import com.github.obhen233.core.agent.context.ContextManager;
import com.github.obhen233.core.agent.context.ExplorationBudget;
import com.github.obhen233.core.agent.tool.ToolExecutor;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.session.SessionTracker;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.core.tool.ToolRegistry;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认主循环实现。
 * 从 {@code ReActAgent.run()} 提取的主循环编排逻辑，
 * 包括多步执行、选择性工具发送、错误恢复、检查点集成。
 */
public class DefaultAgentLoopController implements AgentLoopController {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentLoopController.class);

    @Override
    public String runLoop(LoopContext context) {
        List<ChatMessage> messages = context.getMessages();
        Map<String, Tool> allTools = context.getAllTools();
        String endpoint = context.getEndpoint();
        String userInput = context.getUserInput();
        long[] tokenUsage = context.getTokenUsage();
        SessionTracker sessionTracker = context.getSessionTracker();
        CheckpointManager checkpointManager = context.getCheckpointManager();
        ContextManager contextManager = context.getContextManager();
        LoopDetector loopDetector = context.getLoopDetector();
        ExplorationBudget explorationBudget = context.getExplorationBudget();
        AtomicBoolean interruptRequested = context.getInterruptRequested();
        long runStartTime = System.currentTimeMillis();

        int currentMaxSteps = context.getMaxSteps();

        for (int step = 0; step < currentMaxSteps; step++) {
            // Check for user interrupt (ESC/Ctrl+C pressed during execution)
            if (interruptRequested.get()) {
                logger.info("User interrupted at step {}/{}", step + 1, currentMaxSteps);
                String partialResult = "{{user_interrupted}}";
                if (sessionTracker.hasChanges()) {
                    partialResult += "\n\n" + sessionTracker.buildSummary();
                }
                saveIncrementalSummary(context, messages, userInput, runStartTime);
                pruneHistoryIfNeeded(messages);
                logTokenUsage(tokenUsage);
                sessionTracker.flushAuditLog();
                return partialResult;
            }

            sessionTracker.setTaskContext(getCurrentTaskId(context), step, context.getProjectPath());

            // Reset resumeMode at the start of first step after restoration
            boolean wasResumeMode = false;
            if (step == 0 && context.isResumeMode()) {
                context.setResumeMode(false);
                wasResumeMode = true;
                logger.info("First step in resume mode - cleared resumeMode flag");
            }

            try {
                // Auto-expand steps if needed
                int remainingSteps = currentMaxSteps - step;
                if (remainingSteps <= 5 && step > 0) {
                    ChatMessage stepEndMsg = messages.getLast();
                    boolean hasPendingToolResults = "tool".equals(stepEndMsg.getRole());
                    if (hasPendingToolResults) {
                        logger.info("Auto-expanding steps from {} to {} (only {} remaining, pending tool results to process)",
                            currentMaxSteps, currentMaxSteps + 10, remainingSteps);
                        currentMaxSteps += 10;
                    }
                }

                // In resume mode at step 0, if last message has pending tool_calls,
                // execute them directly instead of calling LLM (they were already approved)
                if (wasResumeMode && !messages.isEmpty()) {
                    messages = executePendingTools(context, messages, allTools, endpoint);
                    // Track tools used in resume mode for selective tool sending
                    trackResumeTools(context, messages);
                    // Continue to next step to call LLM with updated messages
                    continue;
                }

                // Per-step context compression: prevent token limit exceed errors
                if (step > 0 && contextManager.needsCompression(messages)) {
                    logger.info("Step {}: compressing context before LLM call", step + 1);
                    messages = contextManager.compressContext(messages);
                    logger.info("Step {}: context compressed, messages={}", step + 1, messages.size());
                }
                // Message count truncation (independent of token-based compression threshold)
                if (step > 0) {
                    messages = contextManager.truncateContext(messages);
                    messages = contextManager.cleanupIncompleteToolChains(messages);
                }

                logger.info("Step {}/{}: Calling LLM...", step + 1, currentMaxSteps);

                // Selective tool sending: Step 0 sends ALL tools so LLM learns full capability.
                // Step 1+ sends only core tools + tools used so far, saving 2k-5k tokens per step.
                // Step 3+ prunes tools that haven't been used in the last 3 steps (sliding window),
                // and removes exploration-only tools from ALWAYS_SEND_TOOLS.
                Map<String, Tool> stepTools;
                if (step == 0) {
                    stepTools = allTools;
                    logger.debug("Step 0: sending all {} tools", allTools.size());
                } else {
                    if (step >= 3) {
                        pruneToolsByStep(context, step);
                    }
                    stepTools = buildFilteredTools(allTools, context.getToolsUsedInCurrentRun(),
                        context.getAlwaysSendTools(), context.getExplorationTools(), step);
                    logger.debug("Step {}: sending {} tools ({} always + {} from used)",
                        step + 1, stepTools.size(), context.getAlwaysSendTools().size(),
                        context.getToolsUsedInCurrentRun().size());
                }

                // Execute one step using the agent loop
                messages = context.getAgentLoop().executeStep(messages, stepTools, endpoint);

                // Track tools used in this step for selective tool sending in subsequent steps
                Set<String> currentStepTools = new HashSet<>();
                for (int i = messages.size() - 1; i >= 0; i--) {
                    ChatMessage m = messages.get(i);
                    if ("assistant".equals(m.getRole()) && m.hasToolCalls()) {
                        for (ToolCall tc : m.getToolCalls()) {
                            context.getToolsUsedInCurrentRun().add(tc.getName());
                            currentStepTools.add(tc.getName());
                        }
                        break;
                    }
                }
                // Record tools by step for sliding-window pruning
                context.getToolsByStep().put(step, currentStepTools);

                // Post-execution truncation: prevent tool results from bloating messages
                if (step > 0) {
                    messages = contextManager.truncateContext(messages);
                    messages = contextManager.cleanupIncompleteToolChains(messages);
                }

                // Check for consecutive tool failures — if stuck, inject "ask user" message
                checkToolResults(messages, loopDetector);

                // Check exploration budget and understanding detection
                ChatMessage lastMsg = messages.getLast();
                String reasoningContent = lastMsg.getReasoningContent();

                // IMPORTANT: We can ONLY add guidance when it's safe to do so:
                // 1. When last message is a user message (start of new turn)
                // 2. When last message is an assistant WITHOUT tool_calls (final response)
                // NEVER add user message after assistant with tool_calls - this breaks the tool chain
                boolean lastMsgHasPendingToolCalls = "assistant".equals(lastMsg.getRole()) && lastMsg.hasToolCalls();
                boolean safeToAddGuidance = !lastMsgHasPendingToolCalls &&
                    ("user".equals(lastMsg.getRole()) || "assistant".equals(lastMsg.getRole()));

                if (!explorationBudget.isGuidanceAdded() &&
                    safeToAddGuidance &&
                    explorationBudget.shouldTransitionToExecution(reasoningContent)) {
                    String budgetWarning = explorationBudget.getBudgetWarning();
                    if (budgetWarning != null) {
                        logger.info("Exploration budget constraint triggered, adding guidance message");

                        // If last message is an assistant without tool_calls, append guidance to its content
                        // This preserves the conversation flow without breaking any tool chains
                        if ("assistant".equals(lastMsg.getRole()) && !lastMsg.hasToolCalls()) {
                            String currentContent = lastMsg.getContent() != null ? lastMsg.getContent() : "";
                            String guidanceDetail =
                                "\n\n=======================================\n" +
                                "EXPLORATION PHASE ENDED\n" +
                                "=======================================\n\n" +
                                "You have exhausted the exploration budget. The following files have been explored, do NOT re-read:\n\n" +
                                "Start executing the task now. Do NOT call read_file / list_files / search_files again.\n\n" +
                                "Available tools:\n" +
                                "  - write_file: write/overwrite file\n" +
                                "  - replace_in_file: replace text in file\n" +
                                "  - compile_sources: compile source\n" +
                                "  - run_command: execute command\n\n" +
                                "Based on what you have already read, start modifying the code directly.";
                            lastMsg.setContent(currentContent + "\n\n" + budgetWarning + guidanceDetail);
                        } else {
                            String guidanceDetail =
                                "\n\n=======================================\n" +
                                "EXPLORATION PHASE ENDED\n" +
                                "=======================================\n\n" +
                                "You have exhausted the exploration budget. The following files have been explored, do NOT re-read:\n\n" +
                                "Start executing the task now. Do NOT call read_file / list_files / search_files again.\n\n" +
                                "Available tools:\n" +
                                "  - write_file: write/overwrite file\n" +
                                "  - replace_in_file: replace text in file\n" +
                                "  - compile_sources: compile source\n" +
                                "  - run_command: execute command\n\n" +
                                "Based on what you have already read, start modifying the code directly.";
                            ChatMessage guidanceMsg = new ChatMessage("user",
                                budgetWarning + guidanceDetail);
                            messages.add(guidanceMsg);
                            lastMsg = guidanceMsg;
                        }

                        explorationBudget.setGuidanceAdded(true);
                        logger.info("Guidance message added successfully. Message count: {}. Continuing execution...", messages.size());
                    }
                }

                // Record exploration step
                explorationBudget.recordStep();

                // Loop detection — find the last assistant message (may have tool_calls)
                // instead of using lastMsg which may be a tool result message.
                ChatMessage loopCheckMsg = null;
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if ("assistant".equals(messages.get(i).getRole())) {
                        loopCheckMsg = messages.get(i);
                        break;
                    }
                }
                if (loopCheckMsg != null && loopDetector.detectLoop(loopCheckMsg)) {
                    String result = loopDetector.buildLoopExceededMessage(tokenUsage[0], tokenUsage[1],
                            tokenUsage[2], sessionTracker);
                    saveIncrementalSummary(context, messages, userInput, runStartTime);
                    pruneHistoryIfNeeded(messages);
                    logTokenUsage(tokenUsage);
                    sessionTracker.flushAuditLog();
                    return result;
                }

                // Stuck detection — consecutive tool failures without making progress
                if (loopDetector.isStuck() && safeToAddGuidance) {
                    String stuckMsg = loopDetector.buildStuckMessage();
                    logger.warn("Agent stuck after {} consecutive errors, injecting ask-user message",
                            loopDetector.getConsecutiveErrorCount());
                    if ("assistant".equals(lastMsg.getRole()) && !lastMsg.hasToolCalls()) {
                        String currentContent = lastMsg.getContent() != null ? lastMsg.getContent() : "";
                        lastMsg.setContent(currentContent + "\n\n" + stuckMsg);
                    } else {
                        messages.add(new ChatMessage("user", stuckMsg));
                    }
                    loopDetector.resetErrorCount();
                    continue;
                }

                // Save checkpoint periodically
                if (checkpointManager != null && step > 0 && step % 5 == 0) {
                    // Set enhanced checkpoint data from SessionTracker
                    checkpointManager.setCheckpointData(
                        context.getIncrementalSummary(),  // llmSummary - incremental summary across runs
                        null,  // compressedContext - for context compression
                        sessionTracker.buildFileChangeSummary(),
                        sessionTracker.getToolResultHashes(),
                        messages.size(),
                        (int) tokenUsage[2]  // totalTokens
                    );
                    Map<String, Object> agentState = buildAgentState(context);
                    checkpointManager.saveCheckpoint(userInput, messages, step, agentState);
                }

                sessionTracker.setTaskContext(getCurrentTaskId(context), step, context.getProjectPath());

                // Check for final response
                // Skip guidance messages when checking for final response
                ChatMessage msgToCheck = messages.getLast();
                if ("user".equals(msgToCheck.getRole()) && msgToCheck.getContent() != null
                    && msgToCheck.getContent().contains("[BUDGET")) {
                    // This is a budget guidance message, not a final response
                    // Continue to next iteration to let LLM respond
                    logger.debug("Skipping final response check for budget guidance message");
                } else if ("assistant".equals(msgToCheck.getRole())
                        && (msgToCheck.getContent() == null || msgToCheck.getContent().isEmpty())
                        && !msgToCheck.hasToolCalls()) {
                    // Empty assistant response (e.g., thinking-only from Kimi in SSE mode).
                    // Skip final response check and continue to next step naturally.
                    logger.debug("Skipping final response check for empty assistant message (thinking-only)");
                } else if (context.getAgentLoop().isFinalResponse(messages)) {
                    ChatMessage finalMsg = messages.getLast();
                    String result = finalMsg.getContent();
                    if (tokenUsage[2] > 0) {
                        result += "\n\n{{token_usage_summary:"
                            + I18n.formatTokenCount(tokenUsage[0]) + ":"
                            + I18n.formatTokenCount(tokenUsage[1]) + ":"
                            + I18n.formatTokenCount(tokenUsage[2]) + "}}";
                    }
                    if (sessionTracker.hasChanges()) {
                        result += "\n\n" + sessionTracker.buildSummary();
                    }
                    saveIncrementalSummary(context, messages, userInput, runStartTime);
                    pruneHistoryIfNeeded(messages);
                    logTokenUsage(tokenUsage);
                    sessionTracker.flushAuditLog();
                    return result;
                }

            } catch (ToolRegistry.UnauthorizedAccessException e) {
                throw e;
            } catch (ToolConfirmationException e) {
                // Save current messages for retry
                throw e;
            } catch (Exception e) {
                logger.error("Error in step {}", step, e);
                String errMsg = e.getMessage();

                // Check if this is a 400/2013 error due to broken tool chain
                if (errMsg != null && isToolChainError(errMsg)) {
                    logger.warn("API tool chain error detected, attempting automatic recovery...");

                    // Clean up incomplete tool chains - prefer removeLastIncompletePair
                    // which specifically handles the last incomplete assistant+tool pair
                    int originalSize = messages.size();
                    messages = contextManager.removeLastIncompletePair(messages);
                    if (messages.size() == originalSize) {
                        // If no change, try full cleanup
                        messages = contextManager.cleanupIncompleteToolChains(messages);
                    }
                    logger.info("Cleaned up tool chain, message count: {} -> {}", originalSize, messages.size());

                    // If we still have messages left after cleanup, retry this step
                    if (messages.size() > 1 && step < currentMaxSteps - 1) {
                        logger.info("Retrying step {} after cleanup...", step + 1);
                        // Don't increment step, retry the same step
                        step--;
                        continue;
                    }
                }

                // Check if this is a rate limit error (429)
                if (errMsg != null && (errMsg.contains("rate limit exceeded") || errMsg.contains("usage limit exceeded") || errMsg.contains("429"))) {
                    // For rate limit errors, wait and retry
                    if (step < currentMaxSteps - 1) {
                        logger.warn("Rate limit exceeded, waiting before retry...");
                        try {
                            Thread.sleep(5000); // Wait 5 seconds
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        step--; // Retry same step
                        continue;
                    }
                }

                // Check if this is a circuit breaker error
                if (errMsg != null && errMsg.contains("Circuit breaker") && errMsg.contains("is open")) {
                    // Circuit breaker is open - wait for recovery
                    // CircuitBreaker default reset timeout is 30 seconds
                    logger.warn("Circuit breaker open, waiting for recovery...");
                    if (step < currentMaxSteps - 1) {
                        try {
                            System.out.println(I18n.get("api_circuit_waiting"));
                            Thread.sleep(35000); // Wait 35 seconds for circuit breaker recovery (30s timeout + 5s buffer)
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        step--; // Retry same step
                        continue;
                    }
                }

                pruneHistoryIfNeeded(messages);
                logTokenUsage(tokenUsage);
                String errorMsg;
                if (errMsg != null && errMsg.contains("Circuit breaker") && errMsg.contains("is open")) {
                    errorMsg = "{{api_circuit_open}}";
                } else if (errMsg != null && (errMsg.contains("rate limit") || errMsg.contains("usage limit"))) {
                    String resetInfo = "";
                    if (errMsg.contains("resets at")) {
                        int idx = errMsg.indexOf("resets at");
                        String after = errMsg.substring(idx);
                        resetInfo = "\n" + I18n.get("api_reset_time", after.substring("resets at".length()).trim());
                    }
                    errorMsg = "{{api_rate_limit}}" + resetInfo;
                } else if (errMsg != null && (errMsg.startsWith("API调用失败") || errMsg.startsWith("API call failed"))) {
                    String actualError = errMsg.substring(Math.max("API调用失败".length(), "API call failed".length())).trim();
                    if (actualError.startsWith(": ")) {
                        actualError = actualError.substring(2);
                    }
                    errorMsg = I18n.get("api_error", actualError);
                } else if (errMsg != null && errMsg.contains("400") && isToolChainError(errMsg)) {
                    errorMsg = "{{api_tool_chain_error}}";
                } else if (errMsg != null && errMsg.contains("Empty response from model after streaming")) {
                    errorMsg = I18n.get("error",
                        "模型未返回有效结果（无文本回复、无工具调用）。\n" +
                        "请重试。如果问题持续，请简化问题描述或切换模型。\n" +
                        "Empty response from model.");
                } else if (errMsg != null && errMsg.contains("403")) {
                    // 403 — permission denied or quota exceeded
                    if (errMsg.contains("permission_error") || errMsg.contains("usage limit")) {
                        errorMsg = "{{api_quota_exceeded}}";
                    } else {
                        errorMsg = "{{api_auth_error}}";
                    }
                } else if (errMsg != null && errMsg.contains("401")) {
                    errorMsg = "{{api_auth_error}}";
                } else if (errMsg != null && (errMsg.contains("Connection refused") || errMsg.contains("connect timed out")
                    || errMsg.contains("Connection reset") || errMsg.contains("Name or service not known")
                    || errMsg.contains("Network is unreachable") || errMsg.contains("timeout"))) {
                    errorMsg = "{{api_network_error}}";
                } else {
                    errorMsg = I18n.get("error", errMsg);
                }
                if (tokenUsage[2] > 0) {
                    errorMsg += "\n\n{{token_usage_summary:"
                        + I18n.formatTokenCount(tokenUsage[0]) + ":"
                        + I18n.formatTokenCount(tokenUsage[1]) + ":"
                        + I18n.formatTokenCount(tokenUsage[2]) + "}}";
                }
                if (sessionTracker.hasChanges()) {
                    errorMsg += "\n\n" + sessionTracker.buildSummary();
                }
                saveIncrementalSummary(context, messages, userInput, runStartTime);
                sessionTracker.flushAuditLog();
                return errorMsg;
            }
        }

        // Max steps exceeded
        pruneHistoryIfNeeded(messages);
        String maxStepsMsg = "Task exceeded maximum steps (" + currentMaxSteps + "). Please try a more specific request.";
        if (tokenUsage[2] > 0) {
            maxStepsMsg += "\n\n{{token_usage_summary:"
                + I18n.formatTokenCount(tokenUsage[0]) + ":"
                + I18n.formatTokenCount(tokenUsage[1]) + ":"
                + I18n.formatTokenCount(tokenUsage[2]) + "}}";
        }
        if (sessionTracker.hasChanges()) {
            maxStepsMsg += "\n\n" + sessionTracker.buildSummary();
        }
        saveIncrementalSummary(context, messages, userInput, runStartTime);
        logTokenUsage(tokenUsage);
        sessionTracker.flushAuditLog();
        return maxStepsMsg;
    }

    /**
     * Execute pending tools in resume mode.
     * Phase 1: Execute all read-only tools in parallel.
     * Phase 2: Execute write tools serially, collect all results in original order.
     */
    private List<ChatMessage> executePendingTools(LoopContext context, List<ChatMessage> messages,
                                                   Map<String, Tool> allTools, String endpoint) throws Exception {
        ChatMessage lastMsg = messages.getLast();
        if (!("assistant".equals(lastMsg.getRole()) && lastMsg.hasToolCalls())) {
            return messages;
        }

        List<ToolCall> allResumeCalls = lastMsg.getToolCalls();
        logger.info("Resume mode: executing {} pending tool(s) directly", allResumeCalls.size());

        // Fallback retry callback that does NOT call the LLM API
        // (in resume mode, the LLM was already called once; avoid redundant API calls)
        ToolExecutor.RetryCallback fallbackCallback = (failedTc, error, tools, msgs, ep) -> {
            logger.warn("Resume mode tool {} failed (will retry): {}", failedTc.getName(), error);
            // Add a tool result message so executeWithRetry doesn't pick up wrong tool call
            ChatMessage errorMsg = new ChatMessage("tool", "ERROR: " + error, failedTc.getId());
            errorMsg.setToolCallName(failedTc.getName());
            errorMsg.setToolCallIndex(failedTc.getIndex());
            msgs.add(errorMsg);
            return msgs;
        };

        // Phase 1: Execute all read-only tools in parallel
        Map<String, String> readResults = new ConcurrentHashMap<>();
        Map<String, Exception> readErrors = new ConcurrentHashMap<>();
        List<ToolCall> readTools = new ArrayList<>();
        for (ToolCall tc : allResumeCalls) {
            if (context.getToolExecutor().isReadOnlyTool(tc.getName())) {
                readTools.add(tc);
            }
        }
        if (!readTools.isEmpty()) {
            logger.info("Resume mode: executing {} read-only tool(s) in parallel", readTools.size());
            final List<ChatMessage> capturedMessages = messages;
            final String capturedEndpoint = endpoint;
            final Map<String, Tool> capturedTools = allTools;
            final ToolExecutor.RetryCallback capturedCallback = fallbackCallback;
            ExecutorService parallelExecutor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (final ToolCall currentTc : readTools) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            String result = context.getToolExecutor().executeWithRetry(currentTc, capturedTools,
                                capturedMessages, capturedEndpoint, capturedCallback);
                            readResults.put(currentTc.getId(), result);
                        } catch (Exception e) {
                            readErrors.put(currentTc.getId(), e);
                            logger.warn("Resume read-only tool {} failed: {}", currentTc.getName(), e.getMessage());
                        }
                    }, parallelExecutor));
                }
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(120, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    logger.error("Resume mode parallel execution timed out after 120s");
                    for (ToolCall tc : readTools) {
                        if (!readResults.containsKey(tc.getId()) && !readErrors.containsKey(tc.getId())) {
                            readErrors.put(tc.getId(), new RuntimeException("Parallel execution timed out"));
                        }
                    }
                    for (CompletableFuture<Void> f : futures) f.cancel(true);
                }
            } finally {
                parallelExecutor.shutdown();
            }
        }

        // Phase 2: Collect all results in original order (read from map, write execute serially)
        for (ToolCall tc : allResumeCalls) {
            String result;
            if (context.getToolExecutor().isReadOnlyTool(tc.getName())) {
                String readResult = readResults.get(tc.getId());
                if (readResult != null) {
                    result = readResult;
                } else {
                    Exception error = readErrors.get(tc.getId());
                    result = "ERROR: " + (error != null ? error.getMessage() : "Unknown parallel execution error");
                }
            } else {
                try {
                    result = context.getToolExecutor().executeWithRetry(tc, allTools, messages, endpoint, fallbackCallback);
                } catch (Exception e) {
                    logger.error("Resume write tool {} failed: {}", tc.getName(), e.getMessage());
                    result = "ERROR: " + e.getMessage();
                }
            }
            ChatMessage toolMsg = new ChatMessage("tool", result, tc.getId());
            toolMsg.setToolCallName(tc.getName());
            toolMsg.setToolCallIndex(tc.getIndex());
            messages.add(toolMsg);
        }

        return messages;
    }

    /**
     * Track tools used in resume mode for selective tool sending.
     */
    private void trackResumeTools(LoopContext context, List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("tool".equals(m.getRole())) {
                String name = m.getToolCallName();
                if (name != null) {
                    context.getToolsUsedInCurrentRun().add(name);
                }
            } else if ("assistant".equals(m.getRole()) && m.hasToolCalls()) {
                break;
            }
        }
    }

    /**
     * Build a filtered tool map for step 1+ selective tool sending.
     * Only includes core always-send tools and tools that have been used so far in this run.
     * After step 3, exploration-only tools (get_source_tree, list_files, etc.) are
     * removed from the always-send set to save tokens — they're still available if
     * the LLM already used them this run.
     */
    static Map<String, Tool> buildFilteredTools(Map<String, Tool> allTools, Set<String> usedTools,
                                                  Set<String> alwaysSendTools, Set<String> explorationTools, int step) {
        Map<String, Tool> filtered = new TreeMap<>();
        for (String name : alwaysSendTools) {
            // Skip exploration-only tools after step 3 — by then the LLM has
            // already explored the project structure.
            if (step > 3 && explorationTools.contains(name)) {
                continue;
            }
            if (allTools.containsKey(name)) {
                filtered.put(name, allTools.get(name));
            }
        }
        for (String name : usedTools) {
            if (!filtered.containsKey(name) && allTools.containsKey(name)) {
                filtered.put(name, allTools.get(name));
            }
        }
        return filtered;
    }

    /**
     * Prune toolsUsedInCurrentRun with a sliding window of 3 steps.
     * Tools not used in the last 3 steps are removed, keeping the
     * tool definitions compact for step 3+ selective sending.
     */
    private void pruneToolsByStep(LoopContext context, int currentStep) {
        int windowStart = Math.max(0, currentStep - 3);
        Set<String> recentTools = new HashSet<>();
        for (int s = currentStep; s >= windowStart; s--) {
            Set<String> stepToolsForStep = context.getToolsByStep().get(s);
            if (stepToolsForStep != null) {
                recentTools.addAll(stepToolsForStep);
            }
        }
        // Remove tools from toolsUsedInCurrentRun that aren't in the recent window
        // Always keep tools from ALWAYS_SEND_TOOLS (they're handled separately)
        Set<String> toRemove = new HashSet<>();
        for (String tool : context.getToolsUsedInCurrentRun()) {
            if (!recentTools.contains(tool)) {
                toRemove.add(tool);
            }
        }
        context.getToolsUsedInCurrentRun().removeAll(toRemove);
        if (!toRemove.isEmpty()) {
            logger.debug("Pruned {} tools from sliding window (step {}): {}", toRemove.size(), currentStep, toRemove);
        }
    }

    /**
     * Scan the most recent tool results and record them for stuck detection.
     * Scans backwards from the last message, stopping at the first assistant
     * message with tool_calls (which generated the tool results).
     */
    static void checkToolResults(List<ChatMessage> messages, LoopDetector detector) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("tool".equals(m.getRole())) {
                detector.recordToolResult(m.getToolCallName(), m.getContent());
            } else if ("assistant".equals(m.getRole()) && m.hasToolCalls()) {
                break; // Stop at the assistant message that generated these tools
            }
        }
    }

    /**
     * Check if the error message indicates a broken tool chain.
     */
    static boolean isToolChainError(String errorMsg) {
        if (errorMsg == null) return false;
        String lower = errorMsg.toLowerCase();
        return lower.contains("tool_call_id") ||
               lower.contains("tool_calls must be followed") ||
               lower.contains("tool call result does not follow tool call") ||
               lower.contains("did not have response messages") ||
               lower.contains("must be a response to a preceding message with 'tool_calls'") ||
               (lower.contains("missing tool") && lower.contains("response"));
    }

    private void logTokenUsage(long[] tokenUsage) {
        if (tokenUsage[2] > 0) {
            logger.info("[TOKEN USAGE] prompt_tokens={}, completion_tokens={}, total_tokens={}",
                tokenUsage[0], tokenUsage[1], tokenUsage[2]);
        }
    }

    /**
     * Prune history if it exceeds size limits.
     * Removes oldest messages when history grows too large.
     * Preserves index 0 if it's a system message to maintain context.
     */
    static void pruneHistoryIfNeeded(List<ChatMessage> messages) {
        if (messages.size() >= 100) { // MAX_HISTORY_MESSAGES
            int toRemove = Math.max(1, messages.size() / 5);
            int startIndex = 0;
            // Preserve index 0 if it's a system message (for context)
            if (!messages.isEmpty() && "system".equals(messages.getFirst().getRole())) {
                startIndex = 1;
                toRemove = Math.min(toRemove, messages.size() - 1);
            }
            if (toRemove > 0 && startIndex < messages.size()) {
                messages.subList(startIndex, startIndex + toRemove).clear();
                logger.debug("Pruned {} messages from history, remaining: {}", toRemove, messages.size());
            }
        }
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
     * Build and save incremental summary after a run() completes.
     * Called at each normal exit point of runLoop().
     */
    private void saveIncrementalSummary(LoopContext context, List<ChatMessage> messages,
                                         String userInput, long startTime) {
        ContextManager contextManager = context.getContextManager();
        if (contextManager == null) return;
        try {
            long durationMs = System.currentTimeMillis() - startTime;
            long[] tokenUsage = context.getTokenUsage();
            String runSummary = contextManager.buildRunSummary(userInput, messages, durationMs,
                tokenUsage[0], tokenUsage[1]);
            context.setIncrementalSummary(contextManager.mergeSummaries(
                context.getIncrementalSummary(), runSummary, 2000));

            CheckpointManager checkpointManager = context.getCheckpointManager();
            if (checkpointManager != null) {
                checkpointManager.setCheckpointData(
                    context.getIncrementalSummary(),
                    null,
                    context.getSessionTracker() != null ? context.getSessionTracker().buildFileChangeSummary() : null,
                    context.getSessionTracker() != null ? context.getSessionTracker().getToolResultHashes() : null,
                    messages.size(),
                    (int) tokenUsage[2]
                );
                // Persist to DB so it's available on resume
                Map<String, Object> agentState = buildAgentState(context);
                checkpointManager.saveCheckpoint(userInput, messages, Integer.MAX_VALUE, agentState);
            }
        } catch (Exception e) {
            logger.warn("Failed to save incremental summary", e);
        }
    }

    /**
     * Build agent state map for checkpoint saving.
     */
    private Map<String, Object> buildAgentState(LoopContext context) {
        Map<String, Object> state = new HashMap<>();
        state.put("autoApproveWrite", context.getPermissionChecker().isAutoApproveWrite());
        state.put("explorationUsed", context.getExplorationBudget().getUsedCount());
        state.put("guidanceAdded", context.getExplorationBudget().isGuidanceAdded());
        state.put("promptTokens", context.getTokenUsage()[0]);
        state.put("completionTokens", context.getTokenUsage()[1]);
        state.put("totalTokens", context.getTokenUsage()[2]);
        return state;
    }

    /**
     * Get current task ID from the context.
     */
    private String getCurrentTaskId(LoopContext context) {
        CheckpointManager cm = context.getCheckpointManager();
        return cm != null ? cm.getCurrentTaskId() : null;
    }
}
