package com.github.obhen233.starter.terminal;

import com.github.obhen233.cli.TerminalIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * WebSocket implementation of TerminalIO.
 * Text frames carry user input; "__INTERRUPT__" signals cancellation.
 * Output is written back through the WebSocket session.
 */
public class WsTerminalIO implements TerminalIO {

    private static final Logger logger = LoggerFactory.getLogger(WsTerminalIO.class);
    private static final String INTERRUPT_SIGNAL = "__INTERRUPT__";

    private final WebSocketSession session;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.IDLE);

    private Consumer<String> lineHandler;
    private Runnable interruptHandler;
    private Consumer<Status> statusHandler;

    public WsTerminalIO(WebSocketSession session) {
        this.session = session;
    }

    @Override
    public void start() {
        status.set(Status.IDLE);
    }

    @Override
    public void stop() {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException e) {
            logger.warn("Error closing WebSocket session", e);
        }
    }

    @Override
    public void write(String text) {
        sendText(text);
    }

    @Override
    public void writeLine(String text) {
        sendText(text + "\n");
    }

    @Override
    public void setLineHandler(Consumer<String> handler) {
        this.lineHandler = handler;
    }

    @Override
    public void setInterruptHandler(Runnable handler) {
        this.interruptHandler = handler;
    }

    @Override
    public void setStatusHandler(Consumer<Status> handler) {
        this.statusHandler = handler;
    }

    /**
     * Handle an incoming text message from the WebSocket client.
     * Special signal "__INTERRUPT__" triggers the interrupt handler.
     * All other text is forwarded as user input.
     */
    public void onMessage(String message) {
        if (INTERRUPT_SIGNAL.equals(message)) {
            if (interruptHandler != null) {
                interruptHandler.run();
            }
            return;
        }
        if (lineHandler != null) {
            lineHandler.accept(message);
        }
    }

    /**
     * Update the current status and notify listeners.
     */
    public void updateStatus(Status newStatus) {
        Status old = status.getAndSet(newStatus);
        if (old != newStatus && statusHandler != null) {
            statusHandler.accept(newStatus);
        }
    }

    public Status getCurrentStatus() {
        return status.get();
    }

    public WebSocketSession getSession() {
        return session;
    }

    private void sendText(String text) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(text));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to send WebSocket message", e);
        }
    }
}
