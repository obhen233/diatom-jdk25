package com.github.obhen233.starter.adapter;

import com.github.obhen233.starter.adapter.AdapterDriverPlugin.AdapterRequest;
import com.github.obhen233.starter.adapter.AdapterDriverPlugin.AdapterResponse;
import com.github.obhen233.starter.gateway.remote.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * ChatService implementation for ADAPTER mode.
 *
 * <p>Injects a unified {@link ChatService} into the IDE for adapter mode.
 * The local capability is a proxy: it builds an {@link AdapterRequest} and
 * forwards it through the {@link AdapterDriverPlugin} SPI to an external AI
 * agent (Claude Code, Cursor, custom agents). The LLM is executed by the
 * external agent — the adapter only relays the request and returns the reply.</p>
 */
public class AdapterChatService implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(AdapterChatService.class);

    private final AdapterDriverPlugin driver;

    public AdapterChatService(AdapterDriverPlugin driver) {
        this.driver = driver;
    }

    @Override
    public String chat(String message, String sessionId, String taskId) {
        AdapterRequest request = new AdapterRequest();
        request.setAgentId(driver.getDriverType());
        request.setMessage(message);
        Map<String, String> metadata = new HashMap<>();
        if (sessionId != null) metadata.put("sessionId", sessionId);
        if (taskId != null) metadata.put("taskId", taskId);
        request.setMetadata(metadata);

        log.info("Adapter chat forwarded to driver: type={}, taskId={}", driver.getDriverType(), taskId);
        AdapterResponse response = driver.handleRequest(request);
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getError() != null
                    ? response.getError() : "Adapter driver failed");
        }
        return response.getContent();
    }

    @Override
    public void chatStream(String message, String sessionId, String taskId,
                           StreamHandler handler) {
        try {
            String result = chat(message, sessionId, taskId);
            // 底层驱动为同步调用，返回完整结果。分块经 onToken 下发，
            // 让前端获得渐进式输出体验（最终 onComplete 仍回传全文）。
            emitChunked(result, handler);
        } catch (Exception e) {
            log.error("AdapterChatService.chatStream() failed", e);
            handler.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /**
     * 将完整结果按固定块大小经 {@link StreamHandler#onToken} 逐段下发，最后回传全文。
     */
    private static void emitChunked(String result, StreamHandler handler) {
        if (result == null || result.isEmpty()) {
            handler.onComplete(result);
            return;
        }
        int chunkSize = 100;
        for (int i = 0; i < result.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, result.length());
            handler.onToken(result.substring(i, end));
        }
        handler.onComplete(result);
    }
}
