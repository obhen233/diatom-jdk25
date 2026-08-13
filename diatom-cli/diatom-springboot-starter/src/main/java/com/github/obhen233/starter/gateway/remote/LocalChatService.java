package com.github.obhen233.starter.gateway.remote;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.http.AiHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local implementation of {@link ChatService} that wraps a {@link ReActAgent}
 * running in-process. Used when {@code diatom.gateway.remote-enable=false} (default).
 */
public class LocalChatService implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(LocalChatService.class);

    private final ReActAgent agent;

    public LocalChatService(ReActAgent agent) {
        this.agent = agent;
    }

    /**
     * Returns the underlying {@link ReActAgent} for direct access when needed
     * (e.g., setting streaming consumers, workspace dir, etc.).
     */
    public ReActAgent getAgent() {
        return agent;
    }

    @Override
    public String chat(String message, String sessionId, String taskId) {
        log.debug("LocalChatService.chat() sessionId={}", sessionId);
        return agent.run(message);
    }

    @Override
    public void chatStream(String message, String sessionId, String taskId,
                           StreamHandler handler) {
        log.debug("LocalChatService.chatStream() sessionId={}", sessionId);

        agent.setStreamingConsumer(new AiHttpClient.StreamConsumer() {
            @Override
            public void onToken(String token) {
                handler.onToken(token);
            }

            @Override
            public void onComplete(String fullResponse) {
                handler.onComplete(fullResponse);
            }

            @Override
            public void onError(Throwable e) {
                handler.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });

        try {
            String result = agent.run(message);
            // If onComplete was not called by the stream consumer, call it now
            // (e.g., when streaming is not actually enabled by the agent loop)
            handler.onComplete(result);
        } catch (Exception e) {
            log.error("LocalChatService.chatStream() failed", e);
            handler.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
