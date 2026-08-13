package com.github.obhen233.core.agent;

import com.github.obhen233.core.model.ChatMessage;

import java.util.List;

public class ToolConfirmationException extends RuntimeException {
    private final String toolName;
    private final String readableName;
    private final String operationDescription;
    private final String arguments;
    private final String action;
    private final List<ChatMessage> messages;
    private final String toolCallId;

    public ToolConfirmationException(String toolName, String arguments, String action) {
        super("需要确认: " + action);
        this.toolName = toolName;
        this.readableName = null;
        this.operationDescription = action;
        this.arguments = arguments;
        this.action = action;
        this.messages = null;
        this.toolCallId = null;
    }

    public ToolConfirmationException(String toolName, String readableName, String operationDescription, String arguments, String action) {
        super("需要确认: " + action);
        this.toolName = toolName;
        this.readableName = readableName;
        this.operationDescription = operationDescription;
        this.arguments = arguments;
        this.action = action;
        this.messages = null;
        this.toolCallId = null;
    }

    public ToolConfirmationException(String toolName, String readableName, String operationDescription, String arguments, String action, List<ChatMessage> messages) {
        this(toolName, readableName, operationDescription, arguments, action, messages, null);
    }

    public ToolConfirmationException(String toolName, String readableName, String operationDescription, String arguments, String action, List<ChatMessage> messages, String toolCallId) {
        super("需要确认: " + action);
        this.toolName = toolName;
        this.readableName = readableName;
        this.operationDescription = operationDescription;
        this.arguments = arguments;
        this.action = action;
        this.messages = messages;
        this.toolCallId = toolCallId;
    }

    public String getToolName() { return toolName; }
    public String getReadableName() { return readableName; }
    public String getOperationDescription() { return operationDescription; }
    public String getArguments() { return arguments; }
    public String getAction() { return action; }
    public List<ChatMessage> getMessages() { return messages; }
    public String getToolCallId() { return toolCallId; }
}
