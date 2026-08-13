package com.github.obhen233.core.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.util.ApiUrlUtils;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Responses API adapter (POST {@code /v1/responses}).
 *
 * <p>Converts between the internal {@link ChatMessage} model and the Responses
 * API wire format. Compared to chat completions, the Responses format differs in:
 * <ul>
 *   <li>system content goes into a top-level {@code instructions} field</li>
 *   <li>messages are an {@code input} items array with
 *       {@code function_call} / {@code function_call_output} items</li>
 *   <li>tools are flat objects (name/description/parameters at top level,
 *       no nested {@code function})</li>
 *   <li>max tokens uses {@code max_output_tokens}</li>
 * </ul>
 *
 * <p>This adapter reports {@link ModelType#OPENAI} so the {@code IterativeAgentLoop}
 * (non-streaming) is used. If the API rejects the model with
 * {@code model_not_supported} / 404, the adapter transparently degrades to the
 * OpenAI chat completions format via an internal {@link OpenAIAdapter}.</p>
 */
public class ResponsesAdapter implements ModelAdapter, FallbackCapable {
    private static final Logger logger = LoggerFactory.getLogger(ResponsesAdapter.class);
    private final ObjectMapper mapper = JsonUtils.getMapper();
    private final OpenAIAdapter openaiFallback;
    private final int maxTokens;
    private volatile String model;
    private volatile boolean fallbackActive = false;

    public ResponsesAdapter(String model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.openaiFallback = new OpenAIAdapter(model, maxTokens);
    }

    @Override
    public ModelType getModelType() {
        return ModelType.OPENAI;
    }

    @Override
    public void setModel(String model) {
        logger.info("Updating Responses adapter model: {} -> {}", this.model, model);
        this.model = model;
        this.openaiFallback.setModel(model);
    }

    @Override
    public String buildRequest(List<ChatMessage> messages, List<Tool> tools, boolean stream) {
        return buildRequest(messages, tools, stream, false);
    }

    @Override
    public String buildRequest(List<ChatMessage> messages, List<Tool> tools, boolean stream, boolean includeToolResults) {
        if (fallbackActive) {
            return openaiFallback.buildRequest(messages, tools, stream, includeToolResults);
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);

        // System messages are merged into the top-level instructions field.
        StringBuilder instructions = new StringBuilder();
        List<ChatMessage> inputMessages = new ArrayList<>();
        if (messages != null) {
            for (ChatMessage msg : messages) {
                if ("system".equals(msg.getRole())) {
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        if (instructions.length() > 0) instructions.append("\n");
                        instructions.append(msg.getContent());
                    }
                } else {
                    inputMessages.add(msg);
                }
            }
        }
        if (instructions.length() > 0) {
            body.put("instructions", instructions.toString());
        }

        body.set("input", messagesToResponsesJson(inputMessages));
        body.put("max_output_tokens", getAdjustedMaxTokens(messages, tools));
        body.put("stream", stream);

        if (tools != null && !tools.isEmpty()) {
            body.set("tools", toolsToResponsesJson(tools));
        }

        return body.toString();
    }

    @Override
    public ChatResponse parseResponse(String json) {
        if (fallbackActive) {
            return openaiFallback.parseResponse(json);
        }
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("{")) {
            throw new IllegalStateException("Non-JSON response: " + trimmed.substring(0, Math.min(100, trimmed.length())));
        }

        try {
            JsonNode root = mapper.readTree(json);

            // OpenAI-style error object
            if (root.has("error") && !root.get("error").isNull()) {
                JsonNode error = root.get("error");
                String errorMsg = error.has("message") ? error.get("message").asText() : "Unknown error";
                throw new OpenAIAdapter.ApiException("API error: " + errorMsg);
            }

            ChatResponse response = new ChatResponse();

            // Usage: input_tokens / output_tokens / total_tokens
            if (root.has("usage") && !root.get("usage").isNull()) {
                JsonNode usageNode = root.get("usage");
                ChatResponse.Usage usage = new ChatResponse.Usage();
                int promptTokens = usageNode.has("input_tokens") ? usageNode.get("input_tokens").asInt() : 0;
                int completionTokens = usageNode.has("output_tokens") ? usageNode.get("output_tokens").asInt() : 0;
                usage.setPromptTokens(promptTokens);
                usage.setCompletionTokens(completionTokens);
                usage.setTotalTokens(usageNode.has("total_tokens")
                        ? usageNode.get("total_tokens").asInt()
                        : promptTokens + completionTokens);
                response.setUsage(usage);
            }

            // Parse output[] items into a single assistant message.
            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setRole("assistant");
            StringBuilder textContent = new StringBuilder();
            List<ToolCall> toolCalls = new ArrayList<>();
            String reasoningContent = null;
            int toolCallIndex = 0;

            if (root.has("output") && root.get("output").isArray()) {
                for (JsonNode item : root.get("output")) {
                    String type = item.has("type") ? item.get("type").asText() : "";
                    if ("message".equals(type)) {
                        JsonNode content = item.get("content");
                        if (content != null && content.isArray()) {
                            for (JsonNode block : content) {
                                String blockType = block.has("type") ? block.get("type").asText() : "";
                                if ("output_text".equals(blockType) && block.has("text")) {
                                    if (textContent.length() > 0) textContent.append("\n");
                                    textContent.append(block.get("text").asText());
                                }
                            }
                        }
                    } else if ("function_call".equals(type)) {
                        ToolCall tc = new ToolCall();
                        String id = item.has("call_id") ? item.get("call_id").asText()
                                : (item.has("id") ? item.get("id").asText() : null);
                        tc.setId(id);
                        tc.setName(item.has("name") ? item.get("name").asText() : "");
                        tc.setArguments(item.has("arguments") ? item.get("arguments").asText() : "{}");
                        tc.setIndex(toolCallIndex++);
                        toolCalls.add(tc);
                    } else if ("reasoning".equals(type) && item.has("summary") && item.get("summary").isArray()) {
                        // reasoning.summary[].text — collect into reasoningContent
                        StringBuilder reasoningBuilder = new StringBuilder();
                        for (JsonNode summary : item.get("summary")) {
                            if (summary.has("text")) {
                                if (reasoningBuilder.length() > 0) reasoningBuilder.append("\n");
                                reasoningBuilder.append(summary.get("text").asText());
                            }
                        }
                        if (reasoningBuilder.length() > 0) {
                            reasoningContent = reasoningBuilder.toString();
                        }
                    }
                }
            }

            assistantMsg.setContent(textContent.length() > 0 ? textContent.toString() : null);
            if (!toolCalls.isEmpty()) {
                assistantMsg.setToolCalls(toolCalls);
            }
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                assistantMsg.setReasoningContent(reasoningContent);
            }

            // Map Responses status to a chat-completions-style finish_reason.
            String status = root.has("status") ? root.get("status").asText() : "";
            String finishReason;
            if ("incomplete".equals(status) && root.has("incomplete_details") && root.get("incomplete_details").has("reason")
                    && "max_output_tokens".equals(root.get("incomplete_details").get("reason").asText())) {
                finishReason = "length";
            } else if (!toolCalls.isEmpty()) {
                finishReason = "tool_calls";
            } else {
                finishReason = "stop";
            }

            List<ChatResponse.Choice> choices = new ArrayList<>();
            ChatResponse.Choice choice = new ChatResponse.Choice();
            choice.setMessage(assistantMsg);
            choice.setFinishReason(finishReason);
            choices.add(choice);
            response.setChoices(choices);

            return response;
        } catch (OpenAIAdapter.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Responses API response: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ChatMessage> parseMessages(String json) {
        ChatResponse response = parseResponse(json);
        List<ChatMessage> messages = new ArrayList<>();
        if (response != null && response.getMessage() != null) {
            messages.add(response.getMessage());
        }
        return messages;
    }

    // ==================== Fallback ====================

    @Override
    public boolean tryActivateFallback(IOException e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        if (lower.contains("model_not_supported")
                || lower.contains("not supported for the responses endpoint")
                || lower.contains("unexpected response code: 404")) {
            if (!fallbackActive) {
                logger.warn("Responses API rejected model '{}', falling back to chat completions: {}", model, msg);
                fallbackActive = true;
            }
            return true;
        }
        return false;
    }

    public boolean isFallbackActive() {
        return fallbackActive;
    }

    @Override
    public String effectiveEndpoint(String requestedEndpoint) {
        if (fallbackActive) {
            return ApiUrlUtils.responsesToChatUrl(requestedEndpoint);
        }
        return requestedEndpoint;
    }

    // ==================== Wire format conversion ====================

    /**
     * Convert internal messages to Responses API input items.
     * <ul>
     *   <li>user → {@code {"role":"user","content":"..."}}</li>
     *   <li>assistant → {@code {"role":"assistant","content":[output_text, function_call...]}}</li>
     *   <li>tool → {@code {"type":"function_call_output","call_id":...,"output":"..."}}</li>
     * </ul>
     */
    private ArrayNode messagesToResponsesJson(List<ChatMessage> messages) {
        ArrayNode arr = mapper.createArrayNode();
        for (ChatMessage msg : messages) {
            String role = msg.getRole();
            if ("user".equals(role)) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("role", "user");
                obj.put("content", msg.getContent() != null ? msg.getContent() : "");
                arr.add(obj);
            } else if ("assistant".equals(role)) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("role", "assistant");
                ArrayNode content = mapper.createArrayNode();
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    ObjectNode textItem = mapper.createObjectNode();
                    textItem.put("type", "output_text");
                    textItem.put("text", msg.getContent());
                    content.add(textItem);
                }
                // Do NOT echo reasoning content back (conservative — avoids format validation issues).
                if (msg.hasToolCalls()) {
                    for (ToolCall tc : msg.getToolCalls()) {
                        ObjectNode fcItem = mapper.createObjectNode();
                        fcItem.put("type", "function_call");
                        fcItem.put("id", tc.getId());
                        fcItem.put("call_id", tc.getId());
                        fcItem.put("name", tc.getName());
                        fcItem.put("arguments", tc.getArguments() != null ? tc.getArguments() : "{}");
                        content.add(fcItem);
                    }
                }
                obj.set("content", content);
                arr.add(obj);
            } else if ("tool".equals(role)) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("type", "function_call_output");
                obj.put("call_id", msg.getToolCallId() != null ? msg.getToolCallId() : "");
                obj.put("output", msg.getContent() != null ? msg.getContent() : "");
                arr.add(obj);
            }
        }
        return arr;
    }

    /**
     * Convert tools to the Responses API format: flat objects with top-level
     * name/description/parameters (no nested {@code function}), sorted by name.
     */
    private ArrayNode toolsToResponsesJson(List<Tool> tools) {
        List<Tool> sortedTools = new ArrayList<>(tools);
        sortedTools.sort((a, b) -> a.getName().compareTo(b.getName()));

        ArrayNode arr = mapper.createArrayNode();
        for (Tool tool : sortedTools) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("type", "function");
            obj.put("name", tool.getName());
            obj.put("description", tool.getDescription());
            try {
                String schema = tool.getParametersSchema();
                if (schema == null || schema.trim().isEmpty() || "{}".equals(schema.trim())) {
                    obj.set("parameters", createEmptySchema());
                } else {
                    JsonNode parsed = mapper.readTree(schema);
                    if (parsed.isObject() && !parsed.has("type")) {
                        ((ObjectNode) parsed).put("type", "object");
                    }
                    obj.set("parameters", parsed);
                }
            } catch (Exception e) {
                obj.set("parameters", createEmptySchema());
            }
            arr.add(obj);
        }
        return arr;
    }

    private ObjectNode createEmptySchema() {
        ObjectNode emptySchema = mapper.createObjectNode();
        emptySchema.put("type", "object");
        emptySchema.set("properties", mapper.createObjectNode());
        return emptySchema;
    }

    /**
     * Dynamically adjust max_output_tokens based on task complexity
     * (mirrors {@link OpenAIAdapter#getAdjustedMaxTokens}).
     */
    private int getAdjustedMaxTokens(List<ChatMessage> messages, List<Tool> tools) {
        int base = this.maxTokens;
        if (base <= 0) base = 8192;

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
        if (messageCount > 20 && hasWriteTools) {
            return Math.max(base * 2, 16384);
        }
        if (messageCount > 30) {
            return Math.max(base * 2, 16384);
        }
        return base;
    }
}
