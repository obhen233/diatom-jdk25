package com.github.obhen233.adapter.claude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of a claude CLI subprocess.
 *
 * <p>Encapsulates process creation, stdin/stdout/stderr handling,
 * timeout management, and graceful/cancel shutdown.</p>
 */
public class ClaudeProcessManager {

    private static final Logger LOG = Logger.getLogger(ClaudeProcessManager.class.getName());

    private Process process;
    private Thread stdoutReader;
    private Thread stderrReader;
    private volatile boolean cancelled;

    /**
     * Start a claude CLI process.
     *
     * @param workingDir the working directory for the process
     * @param command    full command and arguments list
     * @throws IOException if process creation fails
     */
    public void start(String workingDir, List<String> command) throws IOException {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("Process is already running");
        }

        cancelled = false;

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new java.io.File(workingDir));
        pb.redirectErrorStream(false);

        // Unset CLAUDECODE to prevent nested session detection
        // when the adapter runs inside a Claude Code session itself.
        java.util.Map<String, String> env = pb.environment();
        env.remove("CLAUDECODE");
        // Also clear session ID to avoid cross-session contamination
        env.remove("CLAUDE_SESSION_ID");
        env.remove("CLAUDE_WORKFLOW_ID");

        process = pb.start();
    }

    /**
     * Write content to the process's stdin and close it.
     *
     * @param content the content to write
     * @throws IOException if writing fails
     */
    public void writeStdin(String content) throws IOException {
        if (process == null) {
            throw new IllegalStateException("Process not started");
        }

        OutputStream os = process.getOutputStream();
        Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        writer.write(content);
        writer.flush();
        writer.close();
    }

    /**
     * Read stdout line by line, feeding each line to the consumer.
     *
     * <p>This method blocks until the stream ends or the process is cancelled.
     * Runs the reading on a separate daemon thread internally.</p>
     *
     * @param consumer callback for each line of stdout; may be null
     * @return the complete stdout content as a string
     * @throws IOException if reading fails
     */
    public String readStdout(final LineConsumer consumer) throws IOException {
        if (process == null) {
            throw new IllegalStateException("Process not started");
        }

        final InputStream inputStream = process.getInputStream();
        final StringBuilder sb = new StringBuilder(4096);

        stdoutReader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                        if (consumer != null) {
                            consumer.onLine(line);
                        }
                    }
                } catch (IOException e) {
                    if (!cancelled) {
                        LOG.log(Level.WARNING, "Error reading stdout", e);
                    }
                }
            }
        }, "claude-stdout-reader");
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        return sb.toString();
    }

    /**
     * Read stderr asynchronously (for logging purposes).
     *
     * <p>Stderr is read on a daemon thread and logged at FINE level.</p>
     *
     * @throws IOException if reading fails
     */
    public void readStderr() throws IOException {
        if (process == null) {
            throw new IllegalStateException("Process not started");
        }

        final InputStream errorStream = process.getErrorStream();

        stderrReader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(errorStream, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LOG.fine("[claude stderr] " + line);
                    }
                } catch (IOException e) {
                    if (!cancelled) {
                        LOG.log(Level.WARNING, "Error reading stderr", e);
                    }
                }
            }
        }, "claude-stderr-reader");
        stderrReader.setDaemon(true);
        stderrReader.start();
    }

    /**
     * Wait for the process to complete.
     *
     * @param timeout timeout value (0 means wait forever)
     * @param unit    time unit
     * @return the exit code of the process
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public int waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        if (process == null) {
            throw new IllegalStateException("Process not started");
        }

        if (timeout > 0) {
            boolean completed = process.waitFor(timeout, unit);
            if (!completed) {
                process.destroyForcibly();
            }
        } else {
            process.waitFor();
        }

        return process.exitValue();
    }

    /**
     * Cancel the currently running process.
     *
     * <p>First attempts graceful destruction, then forcibly destroys
     * after a short grace period.</p>
     */
    public void cancel() {
        cancelled = true;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                boolean stopped = process.waitFor(3, TimeUnit.SECONDS);
                if (!stopped) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /**
     * Check whether the process is currently running.
     *
     * @return true if the process is alive
     */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * Wait for the stdout reader thread to finish.
     */
    public void joinStdoutReader() {
        if (stdoutReader != null && stdoutReader.isAlive()) {
            try {
                stdoutReader.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Simple consumer interface for processing stdout lines.
     */
    public interface LineConsumer {
        void onLine(String line);
    }
}
