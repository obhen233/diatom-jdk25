package com.github.obhen233.core.agent.loop;

import com.github.obhen233.core.adapter.FallbackCapable;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.core.agent.tool.ToolExecutor;
import com.github.obhen233.core.agent.context.ToolResultSummarizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iterative mode agent loop (for OpenAI-style models).
 * Executes tool calls one at a time and immediately sends results back to LLM.
 */
public class IterativeAgentLoop implements AgentLoop {
    private static final Logger logger = LoggerFactory.getLogger(IterativeAgentLoop.class);
    private static final int MAX_API_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final AiHttpClient httpClient;
    private final ModelAdapter adapter;
    private final ToolExecutor toolExecutor;
    private final PermissionChecker permissionChecker;
    private final ToolResultSummarizer summarizer;
    private java.util.function.Consumer<String> statusCallback;

    // Token usage tracking (mutable reference from outer agent)
    private final long[] tokenUsage; // [0]=promptTokens, [1]=completionTokens, [2]=totalTokens

    public IterativeAgentLoop(AiHttpClient httpClient, ModelAdapter adapter,
                              ToolExecutor toolExecutor, PermissionChecker permissionChecker,
                              long[] tokenUsage, ToolResultSummarizer summarizer) {
        this.httpClient = httpClient;
        this.adapter = adapter;
        this.toolExecutor = toolExecutor;
        this.permissionChecker = permissionChecker;
        this.tokenUsage = tokenUsage;
        this.summarizer = summarizer != null ? summarizer : new ToolResultSummarizer();
    }

    public void setStatusCallback(java.util.function.Consumer<String> callback) {
        this.statusCallback = callback;
    }

    @Override
    public List<ChatMessage> executeStep(List<ChatMessage> messages, Map<String, Tool> allTools, String endpoint) throws Exception {
        return executeStepWithRetry(messages, allTools, endpoint, 0);
    }

    /**
     * Execute step with retry logic for 400 errors.
     * When a 400 error occurs due to broken tool chain, clean up and retry.
     */
    private List<ChatMessage> executeStepWithRetry(List<ChatMessage> messages, Map<String, Tool> allTools,
                                                    String endpoint, int retryCount) throws Exception {
        String requestJson = adapter.buildRequest(messages, new ArrayList<>(allTools.values()), false, true);
        logger.debug("Request: {}", requestJson);

        String responseJson = null;
        try {
            if (statusCallback != null) statusCallback.accept("generating");
            String callEndpoint = endpoint;
            if (adapter instanceof FallbackCapable) {
                callEndpoint = ((FallbackCapable) adapter).effectiveEndpoint(endpoint);
            }
            responseJson = httpClient.post(callEndpoint, requestJson);
        } catch (java.io.IOException e) {
            // Runtime fallback: a FallbackCapable adapter (e.g. Responses API) can
            // degrade to chat completions and resend the same request immediately.
            boolean fallbackHandled = false;
            if (adapter instanceof FallbackCapable) {
                FallbackCapable fc = (FallbackCapable) adapter;
                if (fc.tryActivateFallback(e)) {
                    requestJson = adapter.buildRequest(messages, new ArrayList<>(allTools.values()), false, true);
                    responseJson = httpClient.post(fc.effectiveEndpoint(endpoint), requestJson);
                    fallbackHandled = true;
                }
            }
            if (!fallbackHandled) {
                String errorMsg = e.getMessage();
                if (errorMsg != null && retryCount < MAX_API_RETRIES) {
                    if (isToolChainError(errorMsg)) {
                        logger.warn("API 400 error due to broken tool chain (attempt {}/{}), attempting recovery...",
                            retryCount + 1, MAX_API_RETRIES);
                        if (retryCount > 0) {
                            Thread.sleep(RETRY_DELAY_MS * (1 << retryCount));
                        }
                        throw e;
                    }
                    // Retry on connection-level errors (no HTTP response received)
                    if (!errorMsg.startsWith("Unexpected response code:")) {
                        logger.warn("API connection error (attempt {}/{}): {}, retrying agent step...",
                            retryCount + 1, MAX_API_RETRIES, errorMsg);
                        if (retryCount > 0) {
                            Thread.sleep(RETRY_DELAY_MS * (1 << retryCount));
                        }
                        return executeStepWithRetry(messages, allTools, endpoint, retryCount + 1);
                    }
                }
                throw e;
            }
        } finally {
            if (statusCallback != null) statusCallback.accept(null);
        }

        logger.debug("Response: {}", responseJson);

        try {
            ChatResponse response = adapter.parseResponse(responseJson);
            ChatMessage assistantMsg = response.getMessage();

            // Track token usage
            if (response.getUsage() != null) {
                tokenUsage[0] += response.getUsage().getPromptTokens();
                tokenUsage[1] += response.getUsage().getCompletionTokens();
                tokenUsage[2] += response.getUsage().getTotalTokens();
                logger.info("[TOKEN USAGE] prompt={}, completion={}, total={}, accumulated total={}",
                    response.getUsage().getPromptTokens(), response.getUsage().getCompletionTokens(),
                    response.getUsage().getTotalTokens(), tokenUsage[2]);
            }

            if (assistantMsg == null) {
                throw new RuntimeException("No response from model");
            }
            
            // Handle finish_reason=length - response was truncated
            if (response.isLengthLimited()) {
                logger.warn("Response truncated due to length limit (finish_reason=length)");
                // Append guidance to the assistant message content instead of adding new user message
                String lengthGuidance = "\n\n[LENGTH LIMIT REACHED] 响应超长，请分多次执行任务。\n" +
                    "[LENGTH LIMIT REACHED] Response too long, please split into multiple steps.\n" +
                    "建议：先完成当前部分，然后在下一轮继续剩余工作。\n" +
                    "Suggestion: Complete current part first, then continue remaining work in next turn.";
                if (assistantMsg.getContent() != null) {
                    assistantMsg.setContent(assistantMsg.getContent() + lengthGuidance);
                } else {
                    assistantMsg.setContent(lengthGuidance);
                }
            }

            // Debug: log reasoning_content if present
            if (assistantMsg.getReasoningContent() != null) {
                logger.debug("Assistant reasoning_content: {}", assistantMsg.getReasoningContent().substring(0, Math.min(100, assistantMsg.getReasoningContent().length())));
            }

            messages.add(assistantMsg);
            logger.debug("Assistant message: role={}, content={}, hasToolCalls={}",
                assistantMsg.getRole(), assistantMsg.getContent(), assistantMsg.hasToolCalls());

            if (assistantMsg.hasToolCalls()) {
                // Get AI's classification from reasoning_content
                String aiClassification = permissionChecker.parseAiClassification(assistantMsg.getReasoningContent());
                logger.debug("AI classification: {}", aiClassification);

                // Execute ALL tool calls (MiniMax requires all tool_calls to have results)
                for (ToolCall tc : assistantMsg.getToolCalls()) {
                    logger.info("Executing tool: {} with args: {}", tc.getName(), tc.getArguments());

                    // Check exploration budget BEFORE executing the tool
                    String budgetError = permissionChecker.checkExplorationBudget(tc.getName(), tc.getArguments());
                    if (budgetError != null) {
                        // Budget exceeded - return error message directly to model
                        logger.warn("Exploration budget blocked tool: {}", tc.getName());
                        ChatMessage toolMsg = new ChatMessage("tool", budgetError, tc.getId());
                        toolMsg.setToolCallName(tc.getName());
                        toolMsg.setToolCallIndex(tc.getIndex());
                        messages.add(toolMsg);
                        continue;  // Skip to next tool call
                    }
                    
                    // Check if this is a budget exemption tool (get_source_tree)
                    // These tools are always allowed and don't count against budget
                    if ("get_source_tree".equals(tc.getName())) {
                        logger.debug("get_source_tree is budget-exempt, always allowing");
                    }

                    // Check if tool needs confirmation
                    String action = permissionChecker.needsConfirmation(tc.getName(), tc.getArguments(), aiClassification);
                    if (action != null) {
                        // Get readableName and operationDescription from tool metadata
                        Tool tool = allTools.get(tc.getName());
                        String readableName = tool != null ? tool.getReadableName() : tc.getName();
                        throw new com.github.obhen233.core.agent.ToolConfirmationException(
                            tc.getName(), readableName, action, tc.getArguments(), action, messages, tc.getId());
                    }

                    // Execute with retry logic
                    String result = toolExecutor.executeWithRetry(tc, allTools, messages, endpoint,
                        (failedTc, error, tools, msgs, ep) -> requestAlternativeImplementation(failedTc, error, tools, msgs, ep));

                    // Check for permission errors
                    String errorPath = permissionChecker.extractPathFromArgs(tc.getArguments());
                    if (permissionChecker.isPermissionError(result)) {
                        throw new com.github.obhen233.core.tool.ToolRegistry.UnauthorizedAccessException(
                            "Access denied for tool: " + tc.getName() + ", result: " + result,
                            errorPath,
                            tc.getName()
                        );
                    }

                    logger.debug("Tool result: {}", result);
                    // Summarize large tool results to save tokens
                    result = summarizer.processResult(tc.getName(), tc.getArguments(), result);
                    ChatMessage toolMsg = new ChatMessage("tool", result, tc.getId());
                    toolMsg.setToolCallName(tc.getName());
                    toolMsg.setToolCallIndex(tc.getIndex());
                    messages.add(toolMsg);
                }
            }

            return messages;
        } catch (com.github.obhen233.core.adapter.OpenAIAdapter.ApiException e) {
            throw new RuntimeException("API调用失败: " + e.getMessage());
        }
    }

    /**
     * Check if the error message indicates a broken tool chain
     */
    private boolean isToolChainError(String errorMsg) {
        if (errorMsg == null) return false;
        String lower = errorMsg.toLowerCase();
        return lower.contains("tool_call_id") ||
               lower.contains("tool_calls must be followed") ||
               lower.contains("tool call result does not follow tool call") ||
               lower.contains("did not have response messages") ||
               lower.contains("must be a response to a preceding message with 'tool_calls'") ||
               (lower.contains("missing tool") && lower.contains("response"));
    }

    /**
     * Extract missing tool_call_ids from error message
     */
    private List<String> extractMissingToolCallIds(String errorMsg) {
        List<String> ids = new ArrayList<>();
        if (errorMsg == null) return ids;
        
        // Pattern to match tool_call_ids in error messages
        // Example: "tool_call_ids did not have response messages: call_xxx, call_yyy"
        Pattern pattern = Pattern.compile("call_[a-zA-Z0-9_]+");
        Matcher matcher = pattern.matcher(errorMsg);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }

    private List<ChatMessage> requestAlternativeImplementation(ToolCall failedTc, String error,
                                                                Map<String, Tool> allTools,
                                                                List<ChatMessage> messages, String endpoint) throws Exception {
        if (!messages.isEmpty()) {
            ChatMessage lastMsg = messages.get(messages.size() - 1);
            if ("assistant".equals(lastMsg.getRole()) && lastMsg.hasToolCalls()) {
                ChatMessage toolMsg = new ChatMessage("tool", "ERROR: " + error, failedTc.getId());
                toolMsg.setToolCallName(failedTc.getName());
                toolMsg.setToolCallIndex(failedTc.getIndex());
                messages.add(toolMsg);
            }
        }

        // 2a: For read-only tools, don't call LLM, just return with error result
        if (toolExecutor.isReadOnlyTool(failedTc.getName())) {
            return messages;
        }

        // 2b: Use simplified retry prompt without redundant plain-text tool list
        // (tools are already sent via API tools parameter)
        String retryPrompt = "Command failed: " + failedTc.getName() + " with error: " + error + "\n" +
                             "Please provide an alternative implementation using different tools or approach.";

        messages.add(new ChatMessage("user", retryPrompt));

        String requestJson = adapter.buildRequest(messages, new ArrayList<>(allTools.values()), false, true);
        String responseJson;
        if (statusCallback != null) statusCallback.accept("generating");
        try {
            String callEndpoint = endpoint;
            if (adapter instanceof FallbackCapable) {
                callEndpoint = ((FallbackCapable) adapter).effectiveEndpoint(endpoint);
            }
            responseJson = httpClient.post(callEndpoint, requestJson);
        } finally {
            if (statusCallback != null) statusCallback.accept(null);
        }
        ChatResponse response = adapter.parseResponse(responseJson);
        ChatMessage assistantMsg = response.getMessage();
        messages.add(assistantMsg);

        return messages;
    }
}