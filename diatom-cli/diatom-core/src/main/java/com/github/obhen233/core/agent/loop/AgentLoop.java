package com.github.obhen233.core.agent.loop;

import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * Interface for agent execution loop.
 * Two implementations: AtomicAgentLoop (Claude/MiniMax) and IterativeAgentLoop (OpenAI).
 */
public interface AgentLoop {

    /**
     * Execute one step of the agent loop.
     *
     * @param messages current conversation messages (will be modified in place)
     * @param allTools available tools
     * @param endpoint API endpoint
     * @return updated messages list after executing this step
     * @throws Exception on errors
     */
    List<ChatMessage> executeStep(List<ChatMessage> messages, Map<String, Tool> allTools, String endpoint) throws Exception;

    /**
     * Check if the last message indicates a final response (no more tool calls needed)
     */
    default boolean isFinalResponse(List<ChatMessage> messages) {
        if (messages.isEmpty()) return false;
        ChatMessage lastMsg = messages.get(messages.size() - 1);
        // Tool results need to be sent back to LLM
        if ("tool".equals(lastMsg.getRole())) {
            return false;
        }
        // Assistant message with no tool calls = final response
        return !lastMsg.hasToolCalls();
    }
}