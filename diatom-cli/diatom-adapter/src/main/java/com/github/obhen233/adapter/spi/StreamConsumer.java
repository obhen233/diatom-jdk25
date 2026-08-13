package com.github.obhen233.adapter.spi;

/**
 * Callback interface for consuming streaming token output from an agent.
 *
 * <p>AgentAdapter implementations that support SSE streaming should
 * override {@link AgentAdapter#executeStream(AgentRequest, StreamConsumer)}
 * and emit tokens via this consumer.</p>
 */
@FunctionalInterface
public interface StreamConsumer {
    /** Called for each token/chunk of the streaming response. */
    void onToken(String token);

    /** Called when streaming completes successfully. */
    default void onComplete() {}

    /** Called when an error occurs during streaming. */
    default void onError(String error) {}
}
