package com.github.obhen233.starter.terminal;

import com.github.obhen233.cli.AsyncAgentExecutor;
import com.github.obhen233.cli.TerminalIO;
import com.github.obhen233.core.agent.ReActAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for terminal sessions.
 * Each connection creates a WsTerminalIO and AsyncAgentExecutor pair.
 */
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TerminalWebSocketHandler.class);

    private final ReActAgent agentTemplate;
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(ReActAgent agentTemplate) {
        this.agentTemplate = agentTemplate;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        logger.info("WebSocket terminal session established: {}", wsSession.getId());

        // Create WsTerminalIO for this session
        WsTerminalIO wsTerminalIO = new WsTerminalIO(wsSession);

        // Create AsyncAgentExecutor for this session (uses the template agent)
        // Note: In production, you may want a dedicated agent per session
        AsyncAgentExecutor executor = new AsyncAgentExecutor(agentTemplate);

        // Wire output from executor back to WebSocket
        executor.setOutputConsumer(response -> {
            wsTerminalIO.write("\n" + response + "\n");
        });

        // Wire input from WebSocket to executor
        wsTerminalIO.setLineHandler(input -> {
            if (input != null && !input.trim().isEmpty()) {
                executor.submit(input.trim());
            }
        });

        // Wire interrupt from WebSocket to executor
        wsTerminalIO.setInterruptHandler(() -> {
            executor.cancel();
            wsTerminalIO.write("\n[Interrupted]\n");
        });

        // Wire status changes back to client
        executor.setStatusListener(status -> {
            wsTerminalIO.updateStatus(status);
            String statusMsg = status == TerminalIO.Status.RUNNING
                ? "\n[Status: RUNNING]\n" : "\n[Status: IDLE]\n";
            wsTerminalIO.write(statusMsg);
        });

        wsTerminalIO.start();
        wsTerminalIO.write("Diatom Terminal Ready.\n> ");

        // Store session
        sessions.put(wsSession.getId(), new TerminalSession(wsSession, wsTerminalIO, executor));
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
        TerminalSession terminalSession = sessions.get(wsSession.getId());
        if (terminalSession != null) {
            terminalSession.wsTerminalIO.onMessage(message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        logger.info("WebSocket terminal session closed: {} with status {}", wsSession.getId(), status);
        TerminalSession terminalSession = sessions.remove(wsSession.getId());
        if (terminalSession != null) {
            terminalSession.executor.shutdown();
            terminalSession.wsTerminalIO.stop();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception) {
        logger.error("WebSocket transport error for session {}: {}", wsSession.getId(), exception.getMessage());
        TerminalSession terminalSession = sessions.get(wsSession.getId());
        if (terminalSession != null) {
            terminalSession.wsTerminalIO.write("\n[Transport error: " + exception.getMessage() + "]\n");
        }
    }

    public Map<String, TerminalSession> getSessions() {
        return sessions;
    }

    public TerminalSession getSession(String id) {
        return sessions.get(id);
    }

    /**
     * Holds the components for a single terminal session.
     */
    public static class TerminalSession {
        public final WebSocketSession wsSession;
        public final WsTerminalIO wsTerminalIO;
        public final AsyncAgentExecutor executor;

        TerminalSession(WebSocketSession wsSession, WsTerminalIO wsTerminalIO, AsyncAgentExecutor executor) {
            this.wsSession = wsSession;
            this.wsTerminalIO = wsTerminalIO;
            this.executor = executor;
        }
    }
}
