package com.github.obhen233.compiler.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.http.AiHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridges diatom-core's StreamConsumer to Spring SSE events.
 *
 * Forwards LLM tokens and tool outputs as SSE events to the frontend terminal.
 */
public class IdeStreamConsumer implements AiHttpClient.StreamConsumer {

    private static final Logger logger = LoggerFactory.getLogger(IdeStreamConsumer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SseEmitter emitter;
    private final StringBuilder tokenBuffer = new StringBuilder();

    public IdeStreamConsumer(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onToken(String token) {
        tokenBuffer.append(token);
        sendEvent("think", token);
    }

    @Override
    public void onComplete(String fullResponse) {
        logger.debug("Stream complete, total length: {}", fullResponse.length());
    }

    @Override
    public void onData(String data) {
        // Raw SSE data from the LLM API - not forwarded to frontend
        logger.trace("Stream data: {}", data.length());
    }

    @Override
    public void onError(Throwable e) {
        logger.error("Stream error", e);
        sendEvent("error", e.getMessage());
    }

    /**
     * Send a named SSE event with JSON data.
     */
    public void sendEvent(String name, String text) {
        try {
            Map<String, String> data = new HashMap<>(2);
            data.put("text", text);
            emitter.send(SseEmitter.event()
                    .name(name)
                    .data(JSON.writeValueAsString(data)));
        } catch (Exception ex) {
            logger.warn("SSE send failed ({}): {}", name, ex.getMessage());
        }
    }

    /**
     * Send a named SSE event with a pre-built map.
     */
    public void sendEvent(String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(name)
                    .data(JSON.writeValueAsString(data)));
        } catch (Exception ex) {
            logger.warn("SSE send failed ({}): {}", name, ex.getMessage());
        }
    }

    /**
     * Get the accumulated token text so far.
     */
    public String getAccumulatedText() {
        return tokenBuffer.toString();
    }
}
