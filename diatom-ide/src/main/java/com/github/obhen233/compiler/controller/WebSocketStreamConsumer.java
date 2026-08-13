package com.github.obhen233.compiler.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.http.AiHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridges diatom-core's StreamConsumer to WebSocket events.
 *
 * Forwards LLM tokens and tool outputs as WebSocket JSON messages to the frontend terminal.
 */
public class WebSocketStreamConsumer implements AiHttpClient.StreamConsumer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketStreamConsumer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebSocketSession session;
    private final String sessionId;
    private final StringBuilder tokenBuffer = new StringBuilder();

    public WebSocketStreamConsumer(WebSocketSession session, String sessionId) {
        this.session = session;
        this.sessionId = sessionId;
    }

    @Override
    public void onToken(String token) {
        tokenBuffer.append(token);
        sendWsMessage("think", token);
    }

    @Override
    public void onComplete(String fullResponse) {
        logger.debug("Stream complete, total length: {}", fullResponse.length());
    }

    @Override
    public void onData(String data) {
        logger.trace("Stream data: {}", data.length());
    }

    @Override
    public void onError(Throwable e) {
        logger.error("Stream error", e);
        sendWsMessage("error", e.getMessage());
    }

    /**
     * Send a WebSocket JSON message with event name and text.
     */
    public void sendWsMessage(String type, String text) {
        try {
            Map<String, String> data = new HashMap<>(4);
            data.put("type", type);
            data.put("text", text);
            if (sessionId != null) {
                data.put("sessionId", sessionId);
            }
            String json = JSON.writeValueAsString(data);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException ex) {
            logger.warn("WebSocket send failed ({}): {}", type, ex.getMessage());
        }
    }

    /**
     * Send a WebSocket JSON message with a pre-built map.
     */
    public void sendWsMessage(String type, Map<String, Object> data) {
        try {
            Map<String, Object> message = new HashMap<>(data);
            message.put("type", type);
            String json = JSON.writeValueAsString(message);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException ex) {
            logger.warn("WebSocket send failed ({}): {}", type, ex.getMessage());
        }
    }

    /**
     * Get the accumulated token text so far.
     */
    public String getAccumulatedText() {
        return tokenBuffer.toString();
    }
}
