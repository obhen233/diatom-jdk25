package com.github.obhen233.compiler.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 消息发送工具。
 * 集中封装 {@link WebSocketSession} 上的 JSON 消息发送（stdout/exit/error/scp_progress/通用 JSON），
 * 供 {@link TerminalWebSocketHandler} 与 {@link AiChatWebSocketHandler} 复用。
 */
public final class WebSocketMessenger {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessenger.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private WebSocketMessenger() {
    }

    /**
     * 解析客户端 JSON 消息为 Map。解析失败时返回 null。
     */
    public static Map<String, Object> parseMessage(String payload) {
        try {
            return JSON.readValue(payload, Map.class);
        } catch (IOException e) {
            return null;
        }
    }

    public static void sendStdout(WebSocketSession session, String data) {
        sendWsJson(session, createMessage("stdout", "data", data));
    }

    public static void sendExit(WebSocketSession session, int code, String cwd) {
        try {
            Map<String, Object> msg = new HashMap<>(4);
            msg.put("type", "exit");
            msg.put("code", code);
            msg.put("cwd", cwd);
            String json = JSON.writeValueAsString(msg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to send exit message: {}", e.getMessage());
        }
    }

    public static void sendError(WebSocketSession session, String message) {
        sendWsJson(session, createMessage("error", "message", message));
    }

    public static void sendScpProgress(WebSocketSession session, String stepName,
                                       long current, long total, long speedBps) {
        Map<String, Object> msg = createMessage("scp_progress", "stepName", stepName);
        msg.put("current", current);
        msg.put("total", total);
        msg.put("speedBps", speedBps);
        sendWsJson(session, msg);
    }

    public static void sendWsJson(WebSocketSession session, Map<String, Object> data) {
        try {
            String json = JSON.writeValueAsString(data);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to send WebSocket message: {}", e.getMessage());
        }
    }

    public static Map<String, Object> createMessage(String type, String dataKey, String dataValue) {
        Map<String, Object> msg = new HashMap<>(3);
        msg.put("type", type);
        msg.put(dataKey, dataValue);
        return msg;
    }
}
