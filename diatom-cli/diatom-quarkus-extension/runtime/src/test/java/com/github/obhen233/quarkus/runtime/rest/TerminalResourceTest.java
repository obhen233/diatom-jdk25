package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.quarkus.runtime.terminal.TerminalSessionManager;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link TerminalResource} 测试（纯 JUnit：直接实例化 {@link TerminalSessionManager} + {@link TerminalResource}）。
 *
 * <p>Phase 4 v1：验证 info / sessions / sessions{id} 会话管理 REST 与输入基础转发（回显 + 中断）。</p>
 */
public class TerminalResourceTest {

    private final TerminalSessionManager sessionManager = new TerminalSessionManager();
    private final TerminalResource resource = new TerminalResource(sessionManager);

    @Test
    public void infoReportsIdleWhenNoSessions() {
        Map<String, Object> info = resource.getInfo();
        assertEquals(0, info.get("activeSessions"));
        assertEquals("idle", info.get("status"));
        assertEquals("/ide/terminal", info.get("terminalPath"));
        assertEquals("WebSocket", info.get("protocol"));
    }

    @Test
    public void infoReportsActiveWithRegisteredSession() {
        sessionManager.register("s1", "ws://localhost/ide/terminal", "/127.0.0.1:5555");
        Map<String, Object> info = resource.getInfo();
        assertEquals(1, info.get("activeSessions"));
        assertEquals("active", info.get("status"));
        assertTrue(((java.util.Set<?>) info.get("sessionIds")).contains("s1"));
    }

    @Test
    public void listSessionsShowsRegisteredSession() {
        sessionManager.register("s1", "ws://localhost/ide/terminal", "/127.0.0.1:5555");
        List<Map<String, Object>> sessions = resource.listSessions();
        assertEquals(1, sessions.size());
        assertEquals("s1", sessions.get(0).get("sessionId"));
        assertEquals(Boolean.TRUE, sessions.get(0).get("open"));
        assertEquals("IDLE", sessions.get(0).get("status"));
    }

    @Test
    public void getSessionReturnsDetailsForExisting() {
        sessionManager.register("s1", "ws://localhost/ide/terminal", "/127.0.0.1:5555");
        Map<String, Object> s = resource.getSession("s1");
        assertEquals("s1", s.get("sessionId"));
        assertEquals(Boolean.TRUE, s.get("open"));
        assertEquals("IDLE", s.get("status"));
        assertEquals("ws://localhost/ide/terminal", s.get("uri"));
    }

    @Test
    public void getSessionReturnsErrorForMissing() {
        Map<String, Object> s = resource.getSession("nope");
        assertEquals("Session not found", s.get("error"));
        assertEquals("nope", s.get("sessionId"));
    }

    @Test
    public void closeSessionRemovesSession() {
        sessionManager.register("s1", "ws://localhost/ide/terminal", "/127.0.0.1:5555");
        Map<String, Object> result = resource.closeSession("s1");
        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("s1", result.get("sessionId"));
        assertEquals(0, resource.getInfo().get("activeSessions"));
        // 二次关闭 → not found
        assertEquals("Session not found", resource.closeSession("s1").get("error"));
    }

    @Test
    public void onInputEchoesAndHandlesInterrupt() {
        sessionManager.register("s1", "ws://localhost/ide/terminal", "/127.0.0.1:5555");
        assertEquals("\nhello\n> ", sessionManager.onInput("s1", "hello"));
        assertEquals("\n[Interrupted]\n> ", sessionManager.onInput("s1", "/interrupt"));
        assertEquals("> ", sessionManager.onInput("s1", "   "));
    }
}
