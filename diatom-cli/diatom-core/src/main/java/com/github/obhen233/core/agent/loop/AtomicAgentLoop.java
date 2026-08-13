package com.github.obhen233.core.agent.loop;

import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.core.agent.tool.ToolExecutor;
import com.github.obhen233.core.agent.context.ToolResultSummarizer;
import com.github.obhen233.util.ProgressSpinner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.obhen233.util.JsonUtils;

/**
 * Atomic mode agent loop (for Claude/MiniMax models).
 * Executes ALL tool calls from a single LLM response before returning results.
 */
public class AtomicAgentLoop implements AgentLoop {
    private static final Logger logger = LoggerFactory.getLogger(AtomicAgentLoop.class);
    private static final int MAX_API_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final AiHttpClient httpClient;
    private final ModelAdapter adapter;
    private final ToolExecutor toolExecutor;
    private final PermissionChecker permissionChecker;
    private final ToolResultSummarizer summarizer;
    private final long[] tokenUsage; // [0]=promptTokens, [1]=completionTokens, [2]=totalTokens
    private AiHttpClient.StreamConsumer streamingConsumer;
    private java.util.function.Consumer<String> statusCallback;

    public AtomicAgentLoop(AiHttpClient httpClient, ModelAdapter adapter,
                           ToolExecutor toolExecutor, PermissionChecker permissionChecker,
                           long[] tokenUsage, ToolResultSummarizer summarizer) {
        this.httpClient = httpClient;
        this.adapter = adapter;
        this.toolExecutor = toolExecutor;
        this.permissionChecker = permissionChecker;
        this.tokenUsage = tokenUsage;
        this.summarizer = summarizer != null ? summarizer : new ToolResultSummarizer();
    }

    public void setStreamingConsumer(AiHttpClient.StreamConsumer streamingConsumer) {
        this.streamingConsumer = streamingConsumer;
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
     */
    private List<ChatMessage> executeStepWithRetry(List<ChatMessage> messages, Map<String, Tool> allTools,
                                                    String endpoint, int retryCount) throws Exception {
        // When streamingConsumer is set, the API should return SSE format (stream=true)
        // Otherwise, use non-streaming response (stream=false)
        // This is compatible with both standalone JAR mode (no streamingConsumer) and embedded mode (with streamingConsumer)
        boolean shouldStream = streamingConsumer != null;
        String requestJson = adapter.buildRequest(messages, new ArrayList<>(allTools.values()), shouldStream, true);
        logger.debug("ATOMIC MODE REQUEST ({} messages, stream={})", messages.size(), shouldStream);
        logger.debug("Request: {}", requestJson);

        String responseJson;
        boolean usedStreaming = false;
        List<ToolCall> streamToolCalls = null;

        try {
            // Use streaming if consumer is set
            if (streamingConsumer != null) {
                final StringBuilder accumulatedResponse = new StringBuilder();
                final IOException[] streamError = {null};
                final String[] lastToolCallData = {null};
                // Accumulate Anthropic SSE tool_use events (separate events per tool)
                final List<String> anthropicToolCallEvents = new ArrayList<>();
                // Track in-progress Anthropic tool calls for input_json_delta accumulation
                final java.util.Map<Integer, String> pendingToolCallStarts = new java.util.HashMap<>();
                final java.util.Map<Integer, StringBuilder> pendingToolCallInputs = new java.util.HashMap<>();

                // Create a wrapper consumer that accumulates the response
                // and stops the spinner on the first streaming data (so spinner
                // output doesn't conflict with streamed content on the same line).
                final java.util.concurrent.atomic.AtomicBoolean spinnerStopped =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
                ProgressSpinner.ProgressSession streamSession = ProgressSpinner.start("Requesting LLM...");
                AiHttpClient.StreamConsumer wrapperConsumer = new AiHttpClient.StreamConsumer() {
                    private void stopSpinnerOnFirstData() {
                        if (spinnerStopped.compareAndSet(false, true)) {
                            streamSession.stopSilent(); // clear spinner line, no newline
                        }
                    }
                    @Override
                    public void onToken(String token) {
                        stopSpinnerOnFirstData();
                        streamingConsumer.onToken(token);
                    }
                    @Override
                    public void onComplete(String fullResponse) {
                        accumulatedResponse.append(fullResponse);
                        streamingConsumer.onComplete(fullResponse);
                    }
                    @Override
                    public void onData(String data) {
                        stopSpinnerOnFirstData();
                        streamingConsumer.onData(data);
                        // Track SSE data that contains tool calls
                        if (data != null && !data.isEmpty()) {
                            lastToolCallData[0] = extractToolCallSseData(data, lastToolCallData[0]);
                            // Accumulate Anthropic tool_use events with input_json_delta merging
                            accumulateAnthropicToolCallEvent(data, anthropicToolCallEvents,
                                pendingToolCallStarts, pendingToolCallInputs);
                        }
                    }
                    @Override
                    public void onError(Throwable e) {
                        stopSpinnerOnFirstData();
                        if (e instanceof IOException) {
                            streamError[0] = (IOException) e;
                        } else {
                            streamError[0] = new IOException(e);
                        }
                    }
                    @Override
                    public void onUsage(long promptTokens, long completionTokens, long totalTokens) {
                        tokenUsage[0] += promptTokens;
                        tokenUsage[1] += completionTokens;
                        tokenUsage[2] += totalTokens;
                        logger.info("[TOKEN USAGE] prompt={}, completion={}, total={}, accumulated total={}",
                            promptTokens, completionTokens, totalTokens, tokenUsage[2]);
                    }
                };
                try {
                    httpClient.postStreamSync(endpoint, requestJson, wrapperConsumer);
                } finally {
                    streamSession.stopSilent(); // don't print "LLM responded" to terminal
                }

                if (streamError[0] != null) {
                    throw streamError[0];
                }

                responseJson = accumulatedResponse.toString();

                // Handle pending (unfinished) Anthropic tool calls that never received
                // content_block_stop (e.g., Kimi K2.7 sends tool_use start + thinking_delta,
                // then message_stop before content_block_stop for the tool_use block).
                if (!pendingToolCallStarts.isEmpty()) {
                    // Only process pending tool calls if the stream produced text content.
                    // When Kimi returns thinking-only content, it may emit tool_use start
                    // events without corresponding stop events — these are artifacts, not
                    // real invocations. Processing them leads to infinite re-execution of
                    // stale tool calls.
                    if (responseJson == null || responseJson.isEmpty()) {
                        logger.warn("Stream ended with {} pending tool call(s) but no text output" +
                                " — discarding them (thinking-only response)",
                            pendingToolCallStarts.size());
                    } else {
                        logger.warn("Stream ended with {} pending tool call(s) — processing them",
                            pendingToolCallStarts.size());
                        for (java.util.Map.Entry<Integer, String> entry : pendingToolCallStarts.entrySet()) {
                            int idx = entry.getKey();
                            String startEvent = entry.getValue();
                            StringBuilder accumulatedInput = pendingToolCallInputs.get(idx);
                            if (accumulatedInput != null && accumulatedInput.length() > 0) {
                                String merged = startEvent.replace("\"input\":{}", "\"input\":" + accumulatedInput.toString());
                                anthropicToolCallEvents.add(merged);
                            } else {
                                // Even with empty args, this is a valid tool call — add as-is
                                anthropicToolCallEvents.add(startEvent);
                            }
                        }
                    }
                    pendingToolCallStarts.clear();
                    pendingToolCallInputs.clear();
                }

                // Parse tool calls from tracked SSE data
                // Try OpenAI format first (single event), then Anthropic format (accumulated events)
                if (lastToolCallData[0] != null) {
                    streamToolCalls = parseToolCallsFromSseData(lastToolCallData[0]);
                }
                if ((streamToolCalls == null || streamToolCalls.isEmpty()) && !anthropicToolCallEvents.isEmpty()) {
                    streamToolCalls = parseAnthropicToolCalls(anthropicToolCallEvents);
                    logger.debug("Parsed {} Anthropic tool call(s) from stream (including pending)",
                        streamToolCalls != null ? streamToolCalls.size() : 0);
                }

                // Detect truly empty response: no text AND no tool calls.
                // This check must come AFTER tool call parsing so pending tool calls
                // (Kimi K2.7 tool_use without content_block_stop) are counted.
                boolean hasText = responseJson != null && !responseJson.isEmpty();
                boolean hasTools = streamToolCalls != null && !streamToolCalls.isEmpty();
                if (!hasText && !hasTools) {
                    // Thinking-only: model is reasoning but produced no text/tool_use.
                    // In SSE mode, the thinking content is already streamed to the user.
                    // Return an empty assistant message so the main loop continues to
                    // the next step naturally — the model may produce output on retry.
                    logger.warn("Empty response from model after streaming: " +
                        "model produced no text and no valid tool calls");
                    if (shouldStream) {
                        ChatMessage emptyMsg = new ChatMessage();
                        emptyMsg.setRole("assistant");
                        emptyMsg.setContent("");
                        messages.add(emptyMsg);
                        return messages;
                    }
                    throw new IOException("Empty response from model after streaming. " +
                        "The model produced no text and no valid tool calls.");
                }
            } else {
                ProgressSpinner.ProgressSession nonStreamSession = ProgressSpinner.start("Requesting LLM...");
                try {
                    if (statusCallback != null) statusCallback.accept("generating");
                    responseJson = httpClient.post(endpoint, requestJson);
                } finally {
                    if (statusCallback != null) statusCallback.accept(null);
                    nonStreamSession.stopSilent();
                }
            }
        } catch (java.io.IOException e) {
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
                // Retry on connection-level errors (no HTTP response received).
                // After RetryInterceptor's connection eviction, a fresh connection often recovers.
                if (!errorMsg.startsWith("Unexpected response code:")) {
                    logger.warn("API connection error (attempt {}/{}): {}, retrying agent step...",
                        retryCount + 1, MAX_API_RETRIES, errorMsg);

                    // Empty response: inject a hint to guide the model to produce actual output
                    if (errorMsg.contains("Empty response from model after streaming")) {
                        ChatMessage hint = new ChatMessage("user",
                            "【系统提示】上一步模型只产生了思考内容，没有实际回复。\n" +
                            "请直接输出文本回复或调用工具来完成任务。不要只思考不输出。\n" +
                            "[System] The previous response contained only thinking without any actual output. " +
                            "Please produce a concrete response with text or tool calls.");
                        messages.add(hint);
                        logger.info("Added retry hint message to guide model to produce actual output");
                    }

                    if (retryCount > 0) {
                        Thread.sleep(RETRY_DELAY_MS * (1 << retryCount));
                    }
                    return executeStepWithRetry(messages, allTools, endpoint, retryCount + 1);
                }
            }
            throw e;
        }

        logger.debug("Response: {}", responseJson);

        logger.debug("Response JSON length: {}", responseJson != null ? responseJson.length() : 0);

        ChatMessage assistantMsg;

        // === Streaming path: accumulated text + optional tool calls from SSE ===
        if (shouldStream) {
            assistantMsg = new ChatMessage();
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(responseJson);
            if (streamToolCalls != null && !streamToolCalls.isEmpty()) {
                assistantMsg.setToolCalls(streamToolCalls);
                logger.debug("Streaming mode: extracted {} tool call(s) from stream", streamToolCalls.size());
            }
            messages.add(assistantMsg);

            if (!assistantMsg.hasToolCalls()) {
                // Pure text response, no tool calls — return directly
                logger.debug("Streaming mode: text-only response (length={})",
                    responseJson != null ? responseJson.length() : 0);
                return messages;
            }
            // Has tool calls — fall through to shared execution below
        } else {
            // === Non-streaming path: parse full JSON response ===
            try {
                ChatResponse response = adapter.parseResponse(responseJson);
                if (response == null) {
                    logger.error("Empty response from model. responseJson='{}'",
                        responseJson != null && responseJson.length() > 200
                            ? responseJson.substring(0, 200) + "..."
                            : responseJson);
                    throw new RuntimeException("Empty response from model. The API may have returned an error or unexpected format.");
                }
                assistantMsg = response.getMessage();

                // Track token usage (streaming mode may not provide usage info)
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

                messages.add(assistantMsg);
                logger.debug("Assistant message: role={}, content={}, hasToolCalls={}",
                    assistantMsg.getRole(), assistantMsg.getContent(), assistantMsg.hasToolCalls());
            } catch (com.github.obhen233.core.adapter.OpenAIAdapter.ApiException e) {
                String errorMsg = e.getMessage();
                if (errorMsg != null) {
                    if (errorMsg.contains("[2013]") && errorMsg.contains("tool_call")) {
                        logger.error("API session state corrupted: tool call ID no longer valid.");
                        throw new RuntimeException("API会话状态损坏: tool_call_id无效。请尝试简化任务或重启会话。\n" +
                            "API session state corrupted: tool_call_id invalid. Try simplifying the task or restarting the session.\n" +
                            "Error: " + errorMsg);
                    }
                    if (errorMsg.contains("400") || errorMsg.toLowerCase().contains("tool_call")) {
                        logger.warn("API tool chain error: {}", errorMsg);
                    }
                }
                throw new RuntimeException("API调用失败: " + errorMsg);
            }
        }

        // === Shared tool execution (streaming with tool calls + non-streaming) ===
        if (assistantMsg.hasToolCalls()) {
            String aiClassification = permissionChecker.parseAiClassification(assistantMsg.getReasoningContent());
            logger.debug("AI classification: {}", aiClassification);

            List<ToolCall> allToolCalls = assistantMsg.getToolCalls();

            // Phase 1: Pre-check all permissions (budget + confirmation) before any execution
            List<ToolCall> executableTools = new ArrayList<>();
            for (ToolCall tc : allToolCalls) {
                logger.info("Pre-checking tool: {} with args: {}", tc.getName(), tc.getArguments());

                // Check exploration budget BEFORE executing the tool
                String budgetError = permissionChecker.checkExplorationBudget(tc.getName(), tc.getArguments());
                if (budgetError != null) {
                    logger.warn("Exploration budget blocked tool: {}", tc.getName());
                    ChatMessage toolMsg = new ChatMessage("tool", budgetError, tc.getId());
                    toolMsg.setToolCallName(tc.getName());
                    toolMsg.setToolCallIndex(tc.getIndex());
                    messages.add(toolMsg);
                    continue;
                }

                // Check if tool needs confirmation
                String action = permissionChecker.needsConfirmation(tc.getName(), tc.getArguments(), aiClassification);
                if (action != null) {
                    Tool tool = allTools.get(tc.getName());
                    String readableName = tool != null ? tool.getReadableName() : tc.getName();
                    throw new com.github.obhen233.core.agent.ToolConfirmationException(
                        tc.getName(), readableName, action, tc.getArguments(), action, messages, tc.getId());
                }

                executableTools.add(tc);
            }

            // Phase 2: Separate read-only and write tools
            List<ToolCall> readTools = new ArrayList<>();
            for (ToolCall tc : executableTools) {
                if (toolExecutor.isReadOnlyTool(tc.getName())) {
                    readTools.add(tc);
                }
            }

            // Phase 3: Execute all read-only tools in parallel
            Map<String, String> readResults = new ConcurrentHashMap<>();
            Map<String, Exception> readErrors = new ConcurrentHashMap<>();
            if (!readTools.isEmpty()) {
                logger.info("Executing {} read-only tool(s) in parallel", readTools.size());
                ExecutorService parallelExecutor = Executors.newVirtualThreadPerTaskExecutor();
                try {
                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    for (ToolCall tc : readTools) {
                        futures.add(CompletableFuture.runAsync(() -> {
                            try {
                                String result = toolExecutor.executeWithRetry(tc, allTools, messages, endpoint,
                                    (failedTc, error, tools, msgs, ep) ->
                                        requestAlternativeImplementation(failedTc, error, tools, msgs, ep));
                                readResults.put(tc.getId(), result);
                            } catch (Exception e) {
                                readErrors.put(tc.getId(), e);
                                logger.warn("Read-only tool {} failed in parallel: {}", tc.getName(), e.getMessage());
                            }
                        }, parallelExecutor));
                    }
                    // Wait for all parallel tools to complete (with timeout)
                    try {
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(120, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        logger.error("Parallel read-only tool execution timed out after 120s");
                        for (ToolCall tc : readTools) {
                            if (!readResults.containsKey(tc.getId()) && !readErrors.containsKey(tc.getId())) {
                                readErrors.put(tc.getId(), new RuntimeException("Parallel execution timed out after 120s"));
                                logger.warn("Read-only tool {} timed out in parallel execution", tc.getName());
                            }
                        }
                        // Cancel remaining incomplete futures
                        for (CompletableFuture<Void> f : futures) {
                            f.cancel(true);
                        }
                    }
                } finally {
                    parallelExecutor.shutdown();
                }
            }

            // Phase 4: Execute write tools serially, collect all results in original order
            int tcIndex = 0;
            for (ToolCall tc : executableTools) {
                tcIndex++;
                logger.info("Executing tool #{}: {} with args: {}", tcIndex, tc.getName(), tc.getArguments());

                String result;
                if (toolExecutor.isReadOnlyTool(tc.getName())) {
                    // Get result from parallel execution
                    String readResult = readResults.get(tc.getId());
                    if (readResult != null) {
                        result = readResult;
                    } else {
                        Exception error = readErrors.get(tc.getId());
                        result = "ERROR: " + (error != null ? error.getMessage() : "Unknown parallel execution error");
                    }
                } else {
                    // Execute write tools serially
                    try {
                        result = toolExecutor.executeWithRetry(tc, allTools, messages, endpoint,
                            (failedTc, error, tools, msgs, ep) ->
                                requestAlternativeImplementation(failedTc, error, tools, msgs, ep));
                    } catch (Exception e) {
                        logger.error("Write tool #{} failed: {}", tcIndex, e.getMessage());
                        throw e;
                    }
                }

                // Check for permission errors
                String errorPath = permissionChecker.extractPathFromArgs(tc.getArguments());
                if (permissionChecker.isPermissionError(result)) {
                    throw new com.github.obhen233.core.tool.ToolRegistry.UnauthorizedAccessException(
                        "Access denied for tool: " + tc.getName() + ", result: " + result,
                        errorPath,
                        tc.getName()
                    );
                }

                logger.info("Tool #{} result length: {} chars", tcIndex, result != null ? result.length() : 0);
                // Summarize large tool results to save tokens
                result = summarizer.processResult(tc.getName(), tc.getArguments(), result);
                ChatMessage toolMsg = new ChatMessage("tool", result, tc.getId());
                toolMsg.setToolCallName(tc.getName());
                toolMsg.setToolCallIndex(tc.getIndex());
                messages.add(toolMsg);
            }
        }

        return messages;
    }

    /**
     * Check if an SSE data line contains tool calls.
     * Supports both OpenAI format (choices[].delta/message.tool_calls) and
     * Anthropic format (message_delta with stop_reason: tool_use).
     */
    private String extractToolCallSseData(String data, String previousData) {
        try {
            ObjectMapper mapper = JsonUtils.getMapper();
            JsonNode root = mapper.readTree(data);
            if (!root.has("choices")) {
                // Check Anthropic format
                if (root.has("type")) {
                    String type = root.get("type").asText();
                    // content_block_start with tool_use indicates Anthropic tool call
                    if ("content_block_start".equals(type) && root.has("content_block")) {
                        JsonNode cb = root.get("content_block");
                        if (cb.has("type") && "tool_use".equals(cb.get("type").asText())) {
                            return data;
                        }
                    }
                    // message_delta with stop_reason: tool_use signals end of tool calls
                    if ("message_delta".equals(type) && root.has("delta")) {
                        JsonNode delta = root.get("delta");
                        if (delta.has("stop_reason") && "tool_use".equals(delta.get("stop_reason").asText())) {
                            return data;
                        }
                    }
                }
                return previousData;
            }
            JsonNode choice = root.get("choices").get(0);

            // Check finish_reason: "tool_calls"
            if (choice.has("finish_reason") && "tool_calls".equals(choice.get("finish_reason").asText())) {
                return data;
            }
            // Check delta.tool_calls
            if (choice.has("delta") && choice.get("delta").has("tool_calls")) {
                return data;
            }
            // Check message.tool_calls (final event)
            if (choice.has("message") && choice.get("message").has("tool_calls")) {
                return data;
            }
        } catch (Exception e) {
            // Not valid JSON for tool call detection
        }
        return previousData;
    }

    /**
     * Accumulate Anthropic SSE tool_use events with input_json_delta merging.
     * The initial content_block_start for tool_use has input:{}, but the actual
     * arguments arrive via subsequent input_json_delta events. This method:
     * 1. Captures content_block_start (tool_use) → stores as pending with index
     * 2. Accumulates input_json_delta fragments → appends to pending input by index
     * 3. On content_block_stop → merges accumulated input into start event, adds to list
     */
    private void accumulateAnthropicToolCallEvent(String data,
                                                   List<String> toolCallEvents,
                                                   Map<Integer, String> pendingStarts,
                                                   Map<Integer, StringBuilder> pendingInputs) {
        try {
            ObjectMapper mapper = JsonUtils.getMapper();
            JsonNode root = mapper.readTree(data);
            if (!root.has("type")) return;
            String type = root.get("type").asText();
            int index = root.has("index") ? root.get("index").asInt() : 0;

            if ("content_block_start".equals(type) && root.has("content_block")) {
                JsonNode cb = root.get("content_block");
                if (cb.has("type") && "tool_use".equals(cb.get("type").asText())) {
                    pendingStarts.put(index, data);
                    pendingInputs.put(index, new StringBuilder());
                }
            } else if ("content_block_delta".equals(type) && root.has("delta")) {
                JsonNode delta = root.get("delta");
                if (delta.has("type") && "input_json_delta".equals(delta.get("type").asText())) {
                    StringBuilder sb = pendingInputs.get(index);
                    if (sb != null) {
                        sb.append(delta.get("partial_json").asText());
                    }
                }
            } else if ("content_block_stop".equals(type)) {
                String startEvent = pendingStarts.get(index);
                if (startEvent != null) {
                    StringBuilder accumulatedInput = pendingInputs.get(index);
                    if (accumulatedInput != null && accumulatedInput.length() > 0) {
                        // Merge accumulated input JSON into the start event
                        String merged = startEvent.replace("\"input\":{}", "\"input\":" + accumulatedInput.toString());
                        toolCallEvents.add(merged);
                    } else {
                        toolCallEvents.add(startEvent);
                    }
                    pendingStarts.remove(index);
                    pendingInputs.remove(index);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to accumulate Anthropic tool call event: {}", e.getMessage());
        }
    }

    /**
     * Parse ToolCall objects from accumulated Anthropic SSE content_block_start events.
     * Each event contains one complete tool_use block.
     */
    private List<ToolCall> parseAnthropicToolCalls(List<String> toolCallEvents) {
        List<ToolCall> toolCalls = new ArrayList<>();
        try {
            ObjectMapper mapper = JsonUtils.getMapper();
            for (String eventData : toolCallEvents) {
                JsonNode root = mapper.readTree(eventData);
                JsonNode cb = root.get("content_block");
                ToolCall tc = new ToolCall();
                tc.setId(cb.has("id") ? cb.get("id").asText() : "");
                tc.setName(cb.has("name") ? cb.get("name").asText() : "");
                tc.setIndex(toolCalls.size());
                JsonNode input = cb.get("input");
                tc.setArguments(input != null ? input.toString() : "{}");
                toolCalls.add(tc);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse Anthropic tool calls from SSE: {}", e.getMessage());
        }
        return toolCalls;
    }

    /**
     * Parse ToolCall objects from SSE data that contains tool_calls (OpenAI format).
     */
    private List<ToolCall> parseToolCallsFromSseData(String jsonData) {
        List<ToolCall> toolCalls = new ArrayList<>();
        try {
            ObjectMapper mapper = JsonUtils.getMapper();
            JsonNode root = mapper.readTree(jsonData);
            if (!root.has("choices")) return toolCalls;

            JsonNode choice = root.get("choices").get(0);

            // Try message.tool_calls first (final event with full message, preferred)
            JsonNode toolCallsNode = null;
            if (choice.has("message") && choice.get("message").has("tool_calls")) {
                toolCallsNode = choice.get("message").get("tool_calls");
            } else if (choice.has("delta") && choice.get("delta").has("tool_calls")) {
                toolCallsNode = choice.get("delta").get("tool_calls");
            }

            if (toolCallsNode == null) return toolCalls;

            for (JsonNode tc : toolCallsNode) {
                ToolCall call = new ToolCall();
                call.setId(tc.has("id") ? tc.get("id").asText() : "");
                if (tc.has("index")) {
                    call.setIndex(tc.get("index").asInt());
                }
                if (tc.has("function")) {
                    JsonNode func = tc.get("function");
                    call.setName(func.has("name") ? func.get("name").asText() : "");
                    call.setArguments(func.has("arguments") ? func.get("arguments").asText() : "");
                }
                toolCalls.add(call);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse tool calls from SSE data: {}", e.getMessage());
        }
        return toolCalls;
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
               lower.contains("without `tool_result` blocks") ||
               lower.contains("tool_use` ids were found without") ||
               (lower.contains("missing tool") && lower.contains("response"));
    }

    /**
     * Extract missing tool_call_ids from error message
     */
    private List<String> extractMissingToolCallIds(String errorMsg) {
        List<String> ids = new ArrayList<>();
        if (errorMsg == null) return ids;
        
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
        // Work on a copy to avoid corrupting the original messages list on API failure
        // Synchronize to ensure thread safety when called from parallel tool execution
        List<ChatMessage> modifiedMessages;
        synchronized (messages) {
            modifiedMessages = new ArrayList<>(messages);

            // If the last message is an assistant with tool_calls but no corresponding tool result,
            // add a tool result first to maintain valid message sequence
            if (!modifiedMessages.isEmpty()) {
                ChatMessage lastMsg = modifiedMessages.get(modifiedMessages.size() - 1);
                if ("assistant".equals(lastMsg.getRole()) && lastMsg.hasToolCalls()) {
                    ChatMessage toolMsg = new ChatMessage("tool", "ERROR: " + error, failedTc.getId());
                    toolMsg.setToolCallName(failedTc.getName());
                    toolMsg.setToolCallIndex(failedTc.getIndex());
                    modifiedMessages.add(toolMsg);
                }
            }
        }

        // 2a: For read-only tools, don't call LLM, just return with error result
        if (toolExecutor.isReadOnlyTool(failedTc.getName())) {
            synchronized (messages) {
                messages.addAll(modifiedMessages.subList(messages.size(), modifiedMessages.size()));
            }
            return messages;
        }

        // 2b: Use simplified retry prompt without redundant plain-text tool list
        // (tools are already sent via API tools parameter)
        String retryPrompt = "Command failed: " + failedTc.getName() + " with error: " + error + "\n" +
                             "Please provide an alternative implementation using different tools or approach.";

        modifiedMessages.add(new ChatMessage("user", retryPrompt));

        String requestJson = adapter.buildRequest(modifiedMessages, new ArrayList<>(allTools.values()), false, true);
        if (statusCallback != null) statusCallback.accept("generating");
        String responseJson;
        try {
            responseJson = httpClient.post(endpoint, requestJson);
        } finally {
            if (statusCallback != null) statusCallback.accept(null);
        }

        ChatResponse response = adapter.parseResponse(responseJson);
        ChatMessage assistantMsg = response.getMessage();
        synchronized (messages) {
            messages.add(assistantMsg);
        }

        return messages;
    }
}