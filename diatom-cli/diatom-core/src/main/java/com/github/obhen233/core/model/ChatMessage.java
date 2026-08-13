package com.github.obhen233.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessage {
    private String role;
    private String content;
    private String toolCallId;
    private String toolCallName;
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;
    @JsonProperty("reasoning_content")
    private String reasoningContent;
    private Integer toolCallIndex;

    public ChatMessage() {}

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public ChatMessage(String role, String content, String toolCallId) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public String getToolCallName() { return toolCallName; }
    public void setToolCallName(String toolCallName) { this.toolCallName = toolCallName; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

    public String getReasoningContent() { return reasoningContent; }
    public void setReasoningContent(String reasoningContent) { this.reasoningContent = reasoningContent; }

    public Integer getToolCallIndex() { return toolCallIndex; }
    public void setToolCallIndex(Integer toolCallIndex) { this.toolCallIndex = toolCallIndex; }

    public boolean hasToolCalls() {
        // Only return true for assistant messages with actual tool calls,
        // NOT for tool result messages (which have toolCallId set but no toolCalls)
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static List<ChatMessage> of(String role, String content) {
        List<ChatMessage> list = new ArrayList<>();
        list.add(new ChatMessage(role, content));
        return list;
    }
}