package com.github.obhen233.compiler.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WebSocketMessenger} 单元测试 —— 覆盖消息构造与 JSON 解析，
 * 以及 WebSocket 会话打开/关闭两种状态下的发送行为。
 */
class WebSocketMessengerTest {

    @Test
    void createMessage_buildsTypeAndData() {
        Map<String, Object> msg = WebSocketMessenger.createMessage("stdout", "data", "hello");
        assertEquals("stdout", msg.get("type"));
        assertEquals("hello", msg.get("data"));
    }

    @Test
    void parseMessage_returnsMapForValidJson() {
        Map<String, Object> msg = WebSocketMessenger.parseMessage("{\"type\":\"exec\",\"command\":\"ls\"}");
        assertNotNull(msg);
        assertEquals("exec", msg.get("type"));
        assertEquals("ls", msg.get("command"));
    }

    @Test
    void parseMessage_returnsNullForInvalidJson() {
        assertNull(WebSocketMessenger.parseMessage("not json"));
    }

    @Test
    void sendStdout_sendsTextMessageWhenOpen() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        WebSocketMessenger.sendStdout(session, "hello");
        verify(session).sendMessage(argThat(m ->
                ((TextMessage) m).getPayload().contains("\"hello\"")));
    }

    @Test
    void sendStdout_doesNothingWhenClosed() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);
        WebSocketMessenger.sendStdout(session, "hello");
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendExit_includesCodeAndCwd() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        WebSocketMessenger.sendExit(session, 0, "/workspace/proj");
        verify(session).sendMessage(argThat(m -> {
            String payload = ((TextMessage) m).getPayload();
            return payload.contains("\"code\":0") && payload.contains("/workspace/proj");
        }));
    }
}
