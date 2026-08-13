package com.github.obhen233.quarkus.runtime.terminal;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * IDE Terminal WebSocket 端点（{@code /ide/terminal}），镜像 starter {@code TerminalWebSocketHandler}。
 *
 * <p>Phase 4 v1：连接建立/关闭登记到 {@link TerminalSessionManager}，文本消息走
 * {@link TerminalSessionManager#onInput} 基础转发（回显 + 中断）。完整 PTY 级联
 * （WsTerminalIO + AsyncAgentExecutor）后续接入。</p>
 */
@WebSocket(path = "/ide/terminal")
@ApplicationScoped
public class TerminalWebSocket {

    private final TerminalSessionManager sessionManager;

    @Inject
    WebSocketConnection connection;

    public TerminalWebSocket(TerminalSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @OnOpen
    public String onOpen() {
        sessionManager.register(connection.id(), uri(), remoteAddress());
        return "Diatom Terminal Ready.\n> ";
    }

    @OnTextMessage
    public String onTextMessage(String message) {
        return sessionManager.onInput(connection.id(), message);
    }

    @OnClose
    public void onClose() {
        sessionManager.closeSession(connection.id());
    }

    private String uri() {
        try {
            io.quarkus.websockets.next.HandshakeRequest h = connection.handshakeRequest();
            StringBuilder sb = new StringBuilder(h.scheme()).append("://").append(h.host())
                    .append(h.path());
            if (h.query() != null && !h.query().isEmpty()) {
                sb.append('?').append(h.query());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String remoteAddress() {
        try {
            return connection.handshakeRequest().remoteAddress();
        } catch (Exception e) {
            return "";
        }
    }
}
