package com.github.obhen233.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight progress spinner for terminal operations.
 *
 * Displays an animated spinner with a status message on the current terminal line.
 * Designed for long-running operations (LLM requests, Maven builds, tool execution).
 *
 * Usage:
 *   ProgressSession session = ProgressSpinner.start("Building project...");
 *   try {
 *       // ... long operation ...
 *   } finally {
 *       session.stop("Build complete");
 *   }
 *
 * Terminal writer must be set once during application startup:
 *   ProgressSpinner.setWriter(terminalWriter);
 */
public class ProgressSpinner {

    private static final Logger logger = LoggerFactory.getLogger(ProgressSpinner.class);
    private static final String[] SPINNER_CHARS = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    // Max spinner line length for padding stop() output to fully overwrite the line
    private static final int SPINNER_LINE_PAD = 80;
    private static volatile PrintWriter writer;

    private ProgressSpinner() {}

    /**
     * Set the terminal PrintWriter for spinner output.
     * Called once during application initialization from TerminalUI.
     */
    public static void setWriter(PrintWriter w) {
        writer = w;
    }

    /**
     * Start a new progress session with the given status message.
     * Returns a ProgressSession that must be stopped via {@link ProgressSession#stop(String)}.
     */
    public static ProgressSession start(String message) {
        if (writer == null) {
            logger.debug("ProgressSpinner: writer not set, skipping spinner");
            return new ProgressSession(null, message, null);
        }
        ProgressSession session = new ProgressSession(writer, message, SPINNER_CHARS);
        session.start();
        return session;
    }

    /**
     * A single progress session with a spinner thread.
     */
    public static class ProgressSession {
        private final PrintWriter writer;
        private final String message;
        private final String[] spinnerChars;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private Thread spinnerThread;
        private long startTime;

        ProgressSession(PrintWriter writer, String message, String[] spinnerChars) {
            this.writer = writer;
            this.message = message;
            this.spinnerChars = spinnerChars;
        }

        /**
         * Start the spinner in a daemon thread.
         */
        void start() {
            if (writer == null) return;
            running.set(true);
            startTime = System.currentTimeMillis();
            spinnerThread = new Thread(() -> {
                int i = 0;
                while (running.get()) {
                    String spinner = (spinnerChars != null) ? spinnerChars[i % spinnerChars.length] : ".";
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    // Overwrite current line with spinner + message + elapsed time.
                    // Pad to SPINNER_LINE_PAD so that shorter messages fully clear previous output.
                    String line = spinner + " " + message + " (" + elapsed + "s)";
                    writer.print("\r" + padTo(line, SPINNER_LINE_PAD));
                    writer.flush();
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    i++;
                }
            }, "progress-spinner");
            spinnerThread.setDaemon(true);
            spinnerThread.start();
        }

        /**
         * Stop the spinner and optionally print a completion message.
         * Clears the spinner line from the terminal.
         *
         * @param doneMessage completion message to print (e.g. "Build complete"), or null to just clear
         */
        public void stop(String doneMessage) {
            if (writer == null) return;
            running.set(false);
            if (spinnerThread != null) {
                try {
                    spinnerThread.interrupt();
                    spinnerThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Clear the spinner line — pad to SPINNER_LINE_PAD to ensure the entire
            // previous spinner line is overwritten (otherwise shorter messages leave
            // visible fragments like "14s)" after the checkmark line).
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (doneMessage != null && !doneMessage.isEmpty()) {
                writer.print("\r" + padTo("\u2714 " + doneMessage + " (" + elapsed + "s)", SPINNER_LINE_PAD));
            } else {
                writer.print("\r" + String.format("%" + SPINNER_LINE_PAD + "s", ""));
            }
            writer.println();
            writer.flush();
        }

        /** Pad string with trailing spaces to the given width (Java 8 compatible). */
        private String padTo(String s, int width) {
            if (s.length() >= width) return s;
            StringBuilder sb = new StringBuilder(width);
            sb.append(s);
            for (int i = s.length(); i < width; i++) {
                sb.append(' ');
            }
            return sb.toString();
        }

        /**
         * Stop the spinner silently — clear the line without printing any completion message.
         * Used when the spinner was stopped early (e.g. first stream data arrived) and the
         * completion message would interfere with streaming output.
         */
        public void stopSilent() {
            if (writer == null) return;
            running.set(false);
            if (spinnerThread != null) {
                try {
                    spinnerThread.interrupt();
                    spinnerThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Clear the line without printing a completion message
            writer.print("\r" + String.format("%" + SPINNER_LINE_PAD + "s", ""));
            writer.print("\r");
            writer.flush();
        }
    }
}
