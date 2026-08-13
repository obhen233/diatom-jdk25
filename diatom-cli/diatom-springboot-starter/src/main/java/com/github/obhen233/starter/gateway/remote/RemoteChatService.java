package com.github.obhen233.starter.gateway.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote implementation of {@link ChatService} that delegates to a
 * remote Diatom Gateway via {@link GatewayChatClient}.
 * <p>
 * Used when {@code diatom.gateway.remote-enable=true}.
 */
public class RemoteChatService implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(RemoteChatService.class);

    private final GatewayChatClient client;

    public RemoteChatService(GatewayChatClient client) {
        this.client = client;
    }

    @Override
    public String chat(String message, String sessionId, String taskId) {
        log.debug("RemoteChatService.chat() sessionId={}", sessionId);
        GatewayChatClient.ChatResponse response = client.chat(message, sessionId, taskId);
        return response.getResponse() != null ? response.getResponse() : "";
    }

    @Override
    public void chatStream(String message, String sessionId, String taskId,
                           StreamHandler handler) {
        log.debug("RemoteChatService.chatStream() sessionId={}", sessionId);

        final StringBuilder accumulated = new StringBuilder();
        client.chatStream(message, sessionId, taskId, new GatewayChatClient.SseEventHandler() {
            @Override
            public void onRouted(String taskId, String worker) {
                log.debug("Stream routed to worker={} taskId={}", worker, taskId);
            }

            @Override
            public void onToken(String content) {
                if (content != null) {
                    accumulated.append(content);
                }
                handler.onToken(content);
            }

            @Override
            public void onComplete(String taskId, String worker, Object fileDiffs) {
                // 回传累积的完整内容，避免 onComplete 丢失远端仅经 complete 事件返回的结果。
                handler.onComplete(accumulated.toString());
            }

            @Override
            public void onError(String error) {
                handler.onError(error);
            }
        });
    }
}
