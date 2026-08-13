package com.github.obhen233.core.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class OpenAIAdapter implements ModelAdapter {
    private static final Logger logger = LoggerFactory.getLogger(OpenAIAdapter.class);
    private volatile String model;
    private final ObjectMapper mapper = JsonUtils.getMapper();
    private volatile ModelType modelType;
    private final int maxTokens;

    public OpenAIAdapter(String model) {
        this(model, 8192);
    }

    public OpenAIAdapter(String model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.modelType = detectModelType(model);
    }

    private ModelType detectModelType(String model) {
        String lower = model.toLowerCase();
        if (lower.contains("minimax") || lower.contains("m2")) {
            return ModelType.MINIMAX;
        }
        if (lower.contains("claude")) {
            return ModelType.CLAUDE;
        }
        return ModelType.OPENAI;
    }

    @Override
    public ModelType getModelType() {
        return modelType;
    }

    @Override
    public void setModel(String model) {
        logger.info("Updating OpenAI adapter model: {} -> {}", this.model, model);
        this.model = model;
        this.modelType = detectModelType(model);
    }

    @Override
    public String buildRequest(List<ChatMessage> messages, List<Tool> tools, boolean stream) {
        return buildRequest(messages, tools, stream, false);
    }

    public String buildRequest(List<ChatMessage> messages, List<Tool> tools, boolean stream, boolean includeToolResults) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messagesToJson(messages, includeToolResults));

        // Dynamically adjust max_tokens based on task complexity
        int adjustedMaxTokens = getAdjustedMaxTokens(messages, tools);
        body.put("max_tokens", adjustedMaxTokens);
        body.put("stream", stream);

        if (tools != null && !tools.isEmpty()) {
            body.set("tools", toolsToJson(tools));
            // Only add tool_choice for non-MiniMax models AND only on first step (saves tokens)
            // First step = few messages (system + user input), subsequent steps have more messages
            if (!model.startsWith("MiniMax") && messages.size() <= 3) {
                body.put("tool_choice", "auto");
            }
        }

        // Add reasoning_effort for DeepSeek models (thinking mode is enabled by default)
        if (model.toLowerCase().contains("deepseek")) {
            // Set reasoning effort based on task complexity
            // High effort for complex tasks, medium for simple tasks (saves tokens & time)
            ObjectNode thinkingNode = mapper.createObjectNode();
            thinkingNode.put("type", "enabled");
            body.set("thinking", thinkingNode);
            String effort = determineReasoningEffort(messages);
            body.put("reasoning_effort", effort);
        }

        return body.toString();
    }
    
    /**
     * Determine reasoning effort based on task complexity
     * Uses token count and code symbol density for fast classification
     * - high: complex tasks (debugging, refactoring, code-heavy)
     * - medium: simple tasks (file operations, short queries)
     */
    private String determineReasoningEffort(List<ChatMessage> messages) {
        // Find the first user message (the task)
        String userTask = null;
        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                userTask = msg.getContent();
                break;
            }
        }
        
        if (userTask == null || userTask.isEmpty()) {
            return "medium";
        }
        
        // Fast filter: Low token count → simple task
        com.github.obhen233.util.TokenCounter tokenCounter = new com.github.obhen233.util.TokenCounter();
        int tokenCount = tokenCounter.count(userTask);
        if (tokenCount < 30) {
            logger.debug("Simple task ({} tokens), using medium reasoning effort", tokenCount);
            return "medium";
        }
        
        // High code symbol density → code/debug task → complex
        String symbols = "{}()[]<>;=|&!@#$%^*/\\";
        long symbolCount = userTask.chars().filter(c -> symbols.indexOf(c) >= 0).count();
        double symbolDensity = (double) symbolCount / userTask.length();
        if (symbolDensity > 0.1) {
            logger.debug("Code task (symbol density: {:.2f}), using high reasoning effort", symbolDensity);
            return "high";
        }
        
        // Default rule: High token count → complex task
        if (tokenCount > 150) {
            logger.debug("Complex task ({} tokens), using high reasoning effort", tokenCount);
            return "high";
        }
        
        return "medium";
    }

    /**
     * Dynamically adjust max_tokens based on task complexity.
     *
     * Rules:
     * - Base: configurable value (default 8192)
     * - Many conversation turns + write tools present → double the limit
     *   (model is deep in code generation, needs room for multi-file writes)
     */
    private int getAdjustedMaxTokens(List<ChatMessage> messages, List<Tool> tools) {
        int base = this.maxTokens;
        if (base <= 0) base = 8192;

        // Check if this is a code generation step (many messages + write tools available)
        boolean hasWriteTools = false;
        if (tools != null) {
            for (Tool tool : tools) {
                String name = tool.getName();
                if ("write_file".equals(name) || "write_source_file".equals(name)
                        || "edit_file".equals(name) || "replace_in_file".equals(name)) {
                    hasWriteTools = true;
                    break;
                }
            }
        }

        // Deep into a task (>20 messages) with write tools → likely generating code
        int messageCount = messages != null ? messages.size() : 0;
        if (messageCount > 20 && hasWriteTools) {
            int dynamicLimit = Math.max(base * 2, 16384);
            logger.debug("Deep task ({} messages) with write tools, increasing max_tokens from {} to {}",
                messageCount, base, dynamicLimit);
            return dynamicLimit;
        }

        // Many messages even without write tools → may still need more output
        if (messageCount > 30) {
            int dynamicLimit = Math.max(base * 2, 16384);
            logger.debug("Deep task ({} messages), increasing max_tokens from {} to {}",
                messageCount, base, dynamicLimit);
            return dynamicLimit;
        }

        return base;
    }

    @Override
    public ChatResponse parseResponse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("{")) {
            throw new IllegalStateException("Non-JSON response: " + trimmed.substring(0, Math.min(100, trimmed.length())));
        }

        // Check for error responses (e.g., MiniMax API error format)
        try {
            JsonNode root = mapper.readTree(json);
            // Check base_resp for errors (MiniMax API format)
            if (root.has("base_resp")) {
                JsonNode baseResp = root.get("base_resp");
                if (baseResp.has("status_code")) {
                    int statusCode = baseResp.get("status_code").asInt();
                    if (statusCode != 0) {
                        String statusMsg = baseResp.has("status_msg") ? baseResp.get("status_msg").asText() : "Unknown error";
                        throw new ApiException("API error [" + statusCode + "]: " + statusMsg);
                    }
                }
            }
            // Check error field (OpenAI API format)
            if (root.has("error")) {
                JsonNode error = root.get("error");
                String errorMsg = error.has("message") ? error.get("message").asText() : "Unknown error";
                throw new ApiException("API error: " + errorMsg);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // Continue parsing if it's not an error response
        }

        ChatResponse response = JsonUtils.fromJson(json, ChatResponse.class);

        // Validate tool call arguments in the response
        // When the API response hits max_tokens (finish_reason: "length"),
        // tool call arguments can be truncated and contain invalid JSON.
        // These invalid arguments would cause subsequent API requests to fail.
        if (response != null && response.getMessage() != null && response.getMessage().hasToolCalls()) {
            boolean fixed = false;
            for (ToolCall tc : response.getMessage().getToolCalls()) {
                String args = tc.getArguments();
                if (args != null && !args.isEmpty() && !isValidJsonString(args.trim())) {
                    logger.warn("Invalid JSON arguments received for tool {} (args length: {}), replacing with error placeholder. Response may have been truncated (max_tokens limit).", tc.getName(), args.length());
                    tc.setArguments("{\"__error__\": \"invalid_arguments_from_api\"}");
                    fixed = true;
                }
            }
            if (fixed) {
                logger.info("Fixed {} tool call(s) with invalid arguments",
                    response.getMessage().getToolCalls().size());
            }
        }

        // Debug: log reasoning_content if present
        if (response != null && response.getMessage() != null && response.getMessage().getReasoningContent() != null) {
            logger.debug("Parsed reasoning_content from response: {}", response.getMessage().getReasoningContent().substring(0, Math.min(50, response.getMessage().getReasoningContent().length())));
        }
        return response;
    }

    public static class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }
    }

    @Override
    public List<ChatMessage> parseMessages(String json) {
        List<ChatMessage> messages = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode choices = root.get("choices");

            if (choices != null && choices.isArray()) {
                for (JsonNode choice : choices) {
                    JsonNode message = choice.get("message");
                    if (message != null) {
                        ChatMessage chatMessage = new ChatMessage();
                        chatMessage.setRole(message.get("role").asText());
                        chatMessage.setContent(message.has("content") && !message.get("content").isNull()
                                ? message.get("content").asText() : "");

                        // Parse reasoning_content (MiniMax extended field)
                        if (message.has("reasoning_content") && !message.get("reasoning_content").isNull()) {
                            chatMessage.setReasoningContent(message.get("reasoning_content").asText());
                        }

                        if (message.has("tool_calls")) {
                            List<ToolCall> toolCalls = new ArrayList<>();
                            JsonNode toolCallsArr = message.get("tool_calls");
                            for (JsonNode tc : toolCallsArr) {
                                ToolCall toolCall = new ToolCall();
                                // MiniMax uses "call_id", OpenAI uses "id" - handle both
                                if (tc.has("call_id")) {
                                    toolCall.setId(tc.get("call_id").asText());
                                } else if (tc.has("id")) {
                                    toolCall.setId(tc.get("id").asText());
                                }
                                JsonNode fn = tc.get("function");
                                toolCall.setName(fn.get("name").asText());

                                // Validate arguments is valid JSON before storing
                                String argsStr = fn.get("arguments").asText();
                                if (!isValidJsonString(argsStr)) {
                                    logger.warn("Invalid JSON arguments received for tool {}: {}", toolCall.getName(), argsStr);
                                    // Store a placeholder that will cause error when executed
                                    toolCall.setArguments("{\"__error__\": \"invalid_arguments_from_api\"}");
                                } else {
                                    toolCall.setArguments(argsStr);
                                }

                                if (tc.has("index") && !tc.get("index").isNull()) {
                                    toolCall.setIndex(tc.get("index").asInt());
                                }
                                toolCalls.add(toolCall);
                            }
                            chatMessage.setToolCalls(toolCalls);
                        }
                        messages.add(chatMessage);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse messages", e);
        }
        return messages;
    }

    private ArrayNode messagesToJson(List<ChatMessage> messages, boolean includeToolResults) {
        ArrayNode arr = mapper.createArrayNode();
        
        // For DeepSeek thinking mode, ALL assistant messages must include reasoning_content
        // Check if this is a DeepSeek model
        boolean isDeepSeek = model.toLowerCase().contains("deepseek");
        
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            ObjectNode obj = mapper.createObjectNode();
            obj.put("role", msg.getRole());

            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                // Tool result message (standard OpenAI format: role + tool_call_id + content)
                obj.put("tool_call_id", msg.getToolCallId());
                obj.put("content", msg.getContent() != null ? msg.getContent() : "");
            } else if ("assistant".equals(msg.getRole())) {
                // Assistant message: when has tool_calls, content should be null (per OpenAI spec)
                if (msg.hasToolCalls()) {
                    // Content must be null when there are tool_calls
                    obj.putNull("content");
                    ArrayNode toolCallsArr = mapper.createArrayNode();
                    for (ToolCall tc : msg.getToolCalls()) {
                        ObjectNode tcObj = mapper.createObjectNode();
                        tcObj.put("id", tc.getId());
                        tcObj.put("type", "function");
                        ObjectNode fnObj = mapper.createObjectNode();
                        fnObj.put("name", tc.getName());
                        fnObj.put("arguments", tc.getArguments() != null ? tc.getArguments() : "{}");
                        tcObj.set("function", fnObj);
                        toolCallsArr.add(tcObj);
                    }
                    obj.set("tool_calls", toolCallsArr);
                } else {
                    obj.put("content", msg.getContent() != null ? msg.getContent() : "");
                }
                // DeepSeek requires ALL reasoning_content to be passed back to API
                // For other models, only include for the last assistant message to save tokens
                if (msg.getReasoningContent() != null && !msg.getReasoningContent().isEmpty()) {
                    if (isDeepSeek) {
                        // For DeepSeek: always include reasoning_content
                        obj.put("reasoning_content", msg.getReasoningContent());
                    } else {
                        // For other models: only include for last assistant message
                        // Find last assistant index
                        int lastAssistantIdx = -1;
                        for (int j = messages.size() - 1; j >= 0; j--) {
                            if ("assistant".equals(messages.get(j).getRole())) {
                                lastAssistantIdx = j;
                                break;
                            }
                        }
                        if (i == lastAssistantIdx) {
                            logger.debug("Including reasoning_content in request for last assistant message: {}", 
                                msg.getReasoningContent().substring(0, Math.min(50, msg.getReasoningContent().length())));
                            obj.put("reasoning_content", msg.getReasoningContent());
                        }
                    }
                }
            } else {
                // System or user message
                obj.put("content", msg.getContent() != null ? msg.getContent() : "");
            }
            arr.add(obj);
        }
        return arr;
    }

    /**
     * Find the index of a tool call by matching its ID with the ID in assistant messages.
     * This is used as a fallback when toolCallIndex is not set on the tool result message.
     */
    private Integer findToolCallIndexById(List<ChatMessage> messages, String toolCallId) {
        if (messages == null || toolCallId == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
                for (int j = 0; j < msg.getToolCalls().size(); j++) {
                    if (toolCallId.equals(msg.getToolCalls().get(j).getId())) {
                        return j;
                    }
                }
            }
        }
        return null;
    }

    private ArrayNode toolsToJson(List<Tool> tools) {
        // Sort tools by name for stable ordering (required for DeepSeek context cache)
        List<Tool> sortedTools = new ArrayList<>(tools);
        sortedTools.sort((a, b) -> a.getName().compareTo(b.getName()));
        
        ArrayNode arr = mapper.createArrayNode();
        for (Tool tool : sortedTools) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("type", "function");
            ObjectNode function = mapper.createObjectNode();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            try {
                String schema = tool.getParametersSchema();
                if (schema == null || schema.trim().isEmpty() || "{}".equals(schema.trim())) {
                    // Empty schema → valid "no parameters" schema
                    ObjectNode emptySchema = mapper.createObjectNode();
                    emptySchema.put("type", "object");
                    emptySchema.set("properties", mapper.createObjectNode());
                    function.set("parameters", emptySchema);
                } else {
                    JsonNode parsed = mapper.readTree(schema);
                    // Ensure "type": "object" is present
                    if (!parsed.has("type")) {
                        ((ObjectNode) parsed).put("type", "object");
                    }
                    function.set("parameters", parsed);
                }
            } catch (Exception e) {
                ObjectNode emptySchema = mapper.createObjectNode();
                emptySchema.put("type", "object");
                emptySchema.set("properties", mapper.createObjectNode());
                function.set("parameters", emptySchema);
            }
            obj.set("function", function);
            arr.add(obj);
        }
        return arr;
    }

    /**
     * Validate if a string is valid JSON object.
     * Used to detect truncated JSON from API responses.
     */
    private boolean isValidJsonString(String str) {
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
}