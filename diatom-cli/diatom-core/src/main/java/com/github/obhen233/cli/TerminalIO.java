package com.github.obhen233.cli;

import java.util.function.Consumer;

/**
 * Terminal I/O abstraction for both CLI (JLine) and IDE (WebSocket) modes.
 * All methods are designed to be thread-safe.
 */
public interface TerminalIO {

    /** Terminal execution status */
    enum Status {
        IDLE,
        RUNNING,
        INTERRUPTED
    }

    /**
     * Start the terminal I/O loop (e.g., enter raw mode, start input thread).
     */
    void start();

    /**
     * Stop the terminal I/O and release resources.
     */
    void stop();

    /**
     * Write text to the terminal output without a trailing newline.
     */
    void write(String text);

    /**
     * Write text to the terminal output with a trailing newline.
     */
    void writeLine(String text);

    /**
     * Register a handler for incoming user input lines.
     */
    void setLineHandler(Consumer<String> handler);

    /**
     * Register a handler for interrupt signals (ESC/Ctrl+C).
     */
    void setInterruptHandler(Runnable handler);

    /**
     * Register a handler for status changes (IDLE, RUNNING, INTERRUPTED).
     */
    void setStatusHandler(Consumer<Status> handler);
}
