package com.github.obhen233.core.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.tool.Tool;

import java.util.ArrayList;
import java.util.List;

import com.github.obhen233.util.JsonUtils;

/**
 * Anthropic Messages API adapter.
 * Converts internal ChatMessage format to/from Anthropic's API format.
 *
 * Key differences from OpenAI:
 * - System message is a top-level "system" field, not in messages array
 * - Tool results use "tool_result" content blocks
 * - Tool calls use "tool_use" content blocks
 * - Auth uses "x-api-key" header instead of "Authorization: Bearer"
 * - Response has "content" array instead of choices[0].message
 */
public class AnthropicAdapter implements ModelAdapter {
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private volatile String model;
    private final ObjectMapper mapper = JsonUtils.getMapper();
    private final int maxTokens;

    public AnthropicAdapter(String model) {
        this(model, 8192);
    }

    public AnthropicAdapter(String model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public ModelType getModelType() {
        return ModelType.CLAUDE;
    }

    @Override
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * Dynamically adjust max_tokens based on task complexity and model type.
     * Kimi K2.7 has forced extended thinking that consumes tokens before output;
     * increase budget so thinking + output both fit.
     */
    private int getAdjustedMaxTokens(List<ChatMessage> messages, List<Tool> tools) {
        int base = this.maxTokens;
        if (base <= 0) base = 8192;

        // Kimi K2.7 forces extended thinking — thinking consumes tokens before any output.
        // Increase budget so thinking has room AND output can follow.
        boolean isKimi = model != null && model.toLowerCase().contains("kimi");
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

        int messageCount = messages != null ? messages.size() : 0;
        // Kimi with extended thinking: double the budget to leave room for output
        if (isKimi) {
            int kimiBudget = Math.max(base * 2, 16384);
            if (messageCount > 10) {
                kimiBudget = Math.max(kimiBudget, 32768);
            }
            return kimiBudget;
        }
        if (messageCount > 20 && hasWriteTools) {
            return Math.max(base * 2, 16384);
        }
        if (messageCount > 30) {
            return Math.max(base * 2, 16384);
        }
        return base;
    }

    @Override
    public String buildRequest(List<ChatMessage> messages, List<Tool> tools, boolean stream) {
        return buildRequest(messages, tools, stream, false);
    }

    @Override
    public String buildRequest(List<ChatMessage> messages, List<Tool> tools, boolean stream, boolean includeToolResults) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", getAdjustedMaxTokens(messages, tools));

        if (stream) {
            body.put("stream", true);
        }

        // Extract system message (Anthropic puts it at top level)
        String systemContent = null;
        List<ChatMessage> nonSystemMessages = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                systemContent = msg.getContent();
            } else {
                nonSystemMessages.add(msg);
            }
        }

        if (systemContent != null && !systemContent.isEmpty()) {
            body.put("system", systemContent);
        }

        // Build messages array
        body.set("messages", messagesToAnthropicJson(nonSystemMessages));

        // Build tools array
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", toolsToAnthropicJson(tools));
        }

        // Add thinking mode parameters for DeepSeek models using Anthropic format
        if (model.toLowerCase().contains("deepseek")) {
            // For Anthropic format, use output_config with effort
            ObjectNode outputConfig = mapper.createObjectNode();
            outputConfig.put("effort", "high");
            body.set("output_config", outputConfig);
        }
        // Note: Kimi K2.7 has forced extended thinking that cannot be disabled via API.
        // The `thinking` parameter is silently ignored and does not affect behavior.
        // Handling is done at the streaming layer (AiHttpClient) and retry layer.

        return body.toString();
    }

    @Override
    public ChatResponse parseResponse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            JsonNode root = mapper.readTree(json);

            // Check for error
            if (root.has("error")) {
                JsonNode error = root.get("error");
                String errorMsg = error.has("message") ? error.get("message").asText() : "Unknown error";
                String errorType = error.has("type") ? error.get("type").asText() : "api_error";
                throw new OpenAIAdapter.ApiException("Anthropic API error [" + errorType + "]: " + errorMsg);
            }

            // Parse Anthropic response into our internal ChatResponse format
            ChatResponse response = new ChatResponse();

            // Parse usage
            if (root.has("usage")) {
                JsonNode usageNode = root.get("usage");
                ChatResponse.Usage usage = new ChatResponse.Usage();
                usage.setPromptTokens(usageNode.has("input_tokens") ? usageNode.get("input_tokens").asInt() : 0);
                usage.setCompletionTokens(usageNode.has("output_tokens") ? usageNode.get("output_tokens").asInt() : 0);
                usage.setTotalTokens(usage.getPromptTokens() + usage.getCompletionTokens());
                response.setUsage(usage);
            }

            // Parse content blocks into ChatMessage
            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setRole("assistant");

            StringBuilder textContent = new StringBuilder();
            List<ToolCall> toolCalls = new ArrayList<>();
            String reasoningContent = null;

            if (root.has("content") && root.get("content").isArray()) {
                for (JsonNode block : root.get("content")) {
                    String type = block.has("type") ? block.get("type").asText() : "";

                    if ("text".equals(type)) {
                        if (textContent.length() > 0) textContent.append("\n");
                        textContent.append(block.get("text").asText());
                    } else if ("tool_use".equals(type)) {
                        ToolCall tc = new ToolCall();
                        tc.setId(block.get("id").asText());
                        tc.setName(block.get("name").asText());
                        // Anthropic sends input as object, we need it as string
                        JsonNode input = block.get("input");
                        tc.setArguments(input != null ? input.toString() : "{}");
                        tc.setIndex(toolCalls.size());
                        toolCalls.add(tc);
                    } else if ("thinking".equals(type)) {
                        // DeepSeek thinking mode: extract thinking content from content block
                        if (block.has("thinking") && !block.get("thinking").isNull()) {
                            reasoningContent = block.get("thinking").asText();
                        }
                    }
                }
            }

            // Check for reasoning_content in DeepSeek Anthropic responses
            // Also check if reasoningContent was extracted from type: "thinking" blocks
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                assistantMsg.setReasoningContent(reasoningContent);
            } else if (root.has("reasoning_content") && !root.get("reasoning_content").isNull()) {
                reasoningContent = root.get("reasoning_content").asText();
                assistantMsg.setReasoningContent(reasoningContent);
            }

            assistantMsg.setContent(textContent.length() > 0 ? textContent.toString() : null);
            if (!toolCalls.isEmpty()) {
                assistantMsg.setToolCalls(toolCalls);
            }

            // Wrap in choices format for compatibility
            List<ChatResponse.Choice> choices = new ArrayList<>();
            ChatResponse.Choice choice = new ChatResponse.Choice();
            choice.setMessage(assistantMsg);
            choices.add(choice);
            response.setChoices(choices);

            return response;
        } catch (OpenAIAdapter.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Anthropic response: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ChatMessage> parseMessages(String json) {
        List<ChatMessage> messages = new ArrayList<>();
        ChatResponse response = parseResponse(json);
        if (response != null && response.getMessage() != null) {
            messages.add(response.getMessage());
        }
        return messages;
    }

    /**
     * Merge consecutive assistant messages that only contain tool_use blocks (no text content).
     * DeepSeek sometimes generates multiple assistant messages with tool_use in sequence,
     * but Anthropic API requires each tool_use to be immediately followed by tool_result.
     * This method merges such consecutive tool_use-only messages into a single assistant message.
     */
    private List<ChatMessage> mergeConsecutiveAssistantToolUses(List<ChatMessage> messages) {
        List<ChatMessage> merged = new ArrayList<>();
        List<ToolCall> pendingToolCalls = new ArrayList<>();
        String pendingReasoningContent = null;

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);

            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls() && msg.getContent() == null) {
                // This assistant message only has tool_use, no text - merge tool calls
                pendingToolCalls.addAll(msg.getToolCalls());
                if (pendingReasoningContent == null && msg.getReasoningContent() != null) {
                    pendingReasoningContent = msg.getReasoningContent();
                }
            } else {
                // Non-assistant or has text content - flush pending tool calls first
                if (!pendingToolCalls.isEmpty()) {
                    ChatMessage mergedAssistant = new ChatMessage("assistant", null);
                    mergedAssistant.setToolCalls(pendingToolCalls);
                    mergedAssistant.setReasoningContent(pendingReasoningContent);
                    merged.add(mergedAssistant);
                    pendingToolCalls.clear();
                    pendingReasoningContent = null;
                }
                merged.add(msg);
            }
        }

        // Flush any remaining pending tool calls
        if (!pendingToolCalls.isEmpty()) {
            ChatMessage mergedAssistant = new ChatMessage("assistant", null);
            mergedAssistant.setToolCalls(pendingToolCalls);
            mergedAssistant.setReasoningContent(pendingReasoningContent);
            merged.add(mergedAssistant);
        }

        return merged;
    }

    /**
     * Convert internal messages to Anthropic format.
     * Anthropic requires alternating user/assistant messages.
     * Tool results are sent as user messages with tool_result content blocks.
     *
     * DeepSeek special handling: merges consecutive assistant messages with tool_use
     * to avoid "tool_use without tool_result" errors.
     */
    private ArrayNode messagesToAnthropicJson(List<ChatMessage> messages) {
        ArrayNode arr = mapper.createArrayNode();

        // For DeepSeek: merge consecutive assistant messages with tool_use
        List<ChatMessage> processedMessages = isDeepSeekModel()
            ? mergeConsecutiveAssistantToolUses(messages)
            : messages;

        for (int i = 0; i < processedMessages.size(); i++) {
            ChatMessage msg = messages.get(i);
            String role = msg.getRole();

            if ("user".equals(role)) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("role", "user");
                // Simple text content
                ArrayNode content = mapper.createArrayNode();
                ObjectNode textBlock = mapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", msg.getContent() != null ? msg.getContent() : "");
                content.add(textBlock);
                obj.set("content", content);
                arr.add(obj);

            } else if ("assistant".equals(role)) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("role", "assistant");
                ArrayNode content = mapper.createArrayNode();

                // Text content
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    ObjectNode textBlock = mapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", msg.getContent());
                    content.add(textBlock);
                }

                // Tool use blocks
                if (msg.hasToolCalls()) {
                    for (ToolCall tc : msg.getToolCalls()) {
                        ObjectNode toolUseBlock = mapper.createObjectNode();
                        toolUseBlock.put("type", "tool_use");
                        toolUseBlock.put("id", tc.getId());
                        toolUseBlock.put("name", tc.getName());
                        try {
                            toolUseBlock.set("input", mapper.readTree(tc.getArguments()));
                        } catch (Exception e) {
                            toolUseBlock.set("input", mapper.createObjectNode());
                        }
                        content.add(toolUseBlock);
                    }
                }

                // DeepSeek thinking mode: add thinking block to content array
                if (isDeepSeekModel() && msg.getReasoningContent() != null && !msg.getReasoningContent().isEmpty()) {
                    ObjectNode thinkingBlock = mapper.createObjectNode();
                    thinkingBlock.put("type", "thinking");
                    thinkingBlock.put("thinking", msg.getReasoningContent());
                    content.add(thinkingBlock);
                }

                obj.set("content", content);

                arr.add(obj);

            } else if ("tool".equals(role)) {
                // Anthropic: tool results are sent as user messages with tool_result content blocks
                // Collect consecutive tool messages into one user message
                ArrayNode content = mapper.createArrayNode();
                int j = i;
                while (j < messages.size() && "tool".equals(messages.get(j).getRole())) {
                    ChatMessage toolMsg = messages.get(j);
                    ObjectNode toolResultBlock = mapper.createObjectNode();
                    toolResultBlock.put("type", "tool_result");
                    toolResultBlock.put("tool_use_id", toolMsg.getToolCallId());
                    toolResultBlock.put("content", toolMsg.getContent() != null ? toolMsg.getContent() : "");
                    content.add(toolResultBlock);
                    j++;
                }
                i = j - 1; // Skip processed tool messages

                ObjectNode obj = mapper.createObjectNode();
                obj.put("role", "user");
                obj.set("content", content);
                arr.add(obj);
            }
        }

        return arr;
    }

    /**
     * Convert tools to Anthropic format.
     * Anthropic uses "input_schema" instead of "parameters".
     */
    private ArrayNode toolsToAnthropicJson(List<Tool> tools) {
        ArrayNode arr = mapper.createArrayNode();
        for (Tool tool : tools) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("name", tool.getName());
            obj.put("description", tool.getDescription());
            try {
                JsonNode schema = mapper.readTree(tool.getParametersSchema());
                // DeepSeek Anthropic endpoint requires "type": "object" in input_schema
                if (schema instanceof ObjectNode && isDeepSeekModel()) {
                    ObjectNode schemaObj = (ObjectNode) schema;
                    JsonNode typeNode = schemaObj.get("type");
                    if (typeNode == null || !"object".equals(typeNode.asText())) {
                        schemaObj.put("type", "object");
                    }
                }
                obj.set("input_schema", schema != null ? schema : createObjectSchema());
            } catch (Exception e) {
                obj.set("input_schema", createObjectSchema());
            }
            arr.add(obj);
        }
        return arr;
    }

    /**
     * Check if the current model is a DeepSeek model.
     * DeepSeek's Anthropic endpoint enforces stricter schema validation.
     */
    private boolean isDeepSeekModel() {
        return model != null && model.toLowerCase().contains("deepseek");
    }

    /**
     * Create a minimal object schema with type: "object".
     */
    private ObjectNode createObjectSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        return schema;
    }

    /**
     * Get the Anthropic API version header value.
     */
    public static String getApiVersion() {
        return ANTHROPIC_VERSION;
    }
}
