package com.github.obhen233.starter.gateway.remote;

/**
 * Unified chat service abstraction for local (ReActAgent) and remote (Gateway) modes.
 *
 * IDE components depend on this interface and are agnostic to whether
 * the AI agent runs in-process or via a remote Gateway connection.
 */
public interface ChatService {

    /**
     * Non-streaming chat. Blocks until the full response is available.
     *
     * @param message   the user's prompt
     * @param sessionId conversation session identifier
     * @param taskId    optional task identifier (may be null/empty)
     * @return the full AI response text
     */
    String chat(String message, String sessionId, String taskId);

    /**
     * Streaming chat. Tokens are delivered incrementally via the handler.
     *
     * @param message   the user's prompt
     * @param sessionId conversation session identifier
     * @param taskId    optional task identifier (may be null/empty)
     * @param handler   callback for streaming events
     */
    void chatStream(String message, String sessionId, String taskId, StreamHandler handler);

    /**
     * Callback interface for receiving streaming chat events.
     */
    interface StreamHandler {
        /** Called for each content token received from the AI. */
        void onToken(String content);

        /** Called when the full response is complete. */
        void onComplete(String fullResponse);

        /** Called when an error occurs during streaming. */
        void onError(String error);
    }
}
