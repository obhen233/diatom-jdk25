package com.github.obhen233.core.adapter;

import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import com.github.obhen233.core.tool.Tool;

import java.util.List;

public interface ModelAdapter {
    String buildRequest(List<ChatMessage> messages, List<Tool> tools, boolean stream);
    String buildRequest(List<ChatMessage> messages, List<Tool> tools, boolean stream, boolean includeToolResults);
    ChatResponse parseResponse(String json);
    List<ChatMessage> parseMessages(String json);
    /** Update the model name at runtime (e.g., when user changes AI settings). */
    void setModel(String model);

    enum ModelType {
        OPENAI,      // OpenAI, DeepSeek, etc. - supports iterative tool calls
        MINIMAX,     // MiniMax - requires atomic tool calls
        CLAUDE       // Claude - requires atomic tool calls
    }

    ModelType getModelType();
}
