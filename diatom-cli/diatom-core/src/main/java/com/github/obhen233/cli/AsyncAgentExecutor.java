package com.github.obhen233.cli;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.agent.ToolConfirmationException;
import com.github.obhen233.core.tool.ToolRegistry.UnauthorizedAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Asynchronous agent execution engine.
 * Submits tasks to a blocking queue; a worker thread processes them sequentially.
 * Supports cancellation (clear queue + interrupt agent), status notifications,
 * and confirmation callbacks for interactive prompts.
 */
public class AsyncAgentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AsyncAgentExecutor.class);

    private final ReActAgent agent;
    private final BlockingQueue<Task> taskQueue = new LinkedBlockingQueue<>();
    private final AtomicReference<TerminalIO.Status> status = new AtomicReference<>(TerminalIO.Status.IDLE);
    private final Thread workerThread;

    private Consumer<String> outputConsumer;
    private Consumer<TerminalIO.Status> statusListener;
    private ConfirmationHandler confirmationHandler;

    private volatile boolean running = true;
    private volatile boolean cancelled = false;

    /** Callback for interactive confirmation prompts during agent execution. */
    @FunctionalInterface
    public interface ConfirmationHandler {
        /**
         * Called when the agent needs user confirmation.
         * The handler should block until the user responds.
         *
         * @param exception the confirmation exception with context
         * @return the user's response ("y", "n", "a", "s", "t", "c")
         */
        String handleConfirmation(ToolConfirmationException exception);
    }

    /** Callback for authorization prompts during agent execution. */
    @FunctionalInterface
    public interface AuthorizationHandler {
        /**
         * Called when the agent needs path authorization.
         *
         * @param exception the unauthorized access exception with context
         * @return the user's response ("y", "a", "n")
         */
        String handleAuthorization(UnauthorizedAccessException exception);
    }

    private AuthorizationHandler authorizationHandler;

    private static class Task {
        final String message;
        final Consumer<String> resultCallback;

        Task(String message, Consumer<String> resultCallback) {
            this.message = message;
            this.resultCallback = resultCallback;
        }
    }

    public AsyncAgentExecutor(ReActAgent agent) {
        this.agent = agent;
        this.workerThread = new Thread(this::workerLoop, "agent-executor");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    /**
     * Submit a message for agent execution. If already running, the message is queued.
     */
    public void submit(String message) {
        submit(message, null);
    }

    /**
     * Submit a message with a result callback.
     */
    public void submit(String message, Consumer<String> resultCallback) {
        if (!running) {
            logger.warn("Executor is shut down, cannot submit: {}", message);
            return;
        }
        taskQueue.offer(new Task(message, resultCallback));
    }

    /**
     * Cancel the current execution and clear the queue.
     */
    public void cancel() {
        cancelled = true;
        taskQueue.clear();
        agent.requestInterrupt();
        status.set(TerminalIO.Status.INTERRUPTED);
        if (statusListener != null) {
            statusListener.accept(TerminalIO.Status.INTERRUPTED);
        }
    }

    /**
     * Shutdown the executor and the worker thread.
     */
    public void shutdown() {
        running = false;
        cancelled = true;
        taskQueue.clear();
        workerThread.interrupt();
    }

    /**
     * Wait for the executor to become idle.
     */
    public void waitForIdle() throws InterruptedException {
        while (status.get() == TerminalIO.Status.RUNNING) {
            Thread.sleep(100);
        }
    }

    // --- Getters ---

    public TerminalIO.Status getStatus() {
        return status.get();
    }

    public boolean isBusy() {
        return status.get() == TerminalIO.Status.RUNNING;
    }

    public boolean isIdle() {
        return status.get() == TerminalIO.Status.IDLE;
    }

    // --- Setters ---

    public void setOutputConsumer(Consumer<String> consumer) {
        this.outputConsumer = consumer;
    }

    public void setStatusListener(Consumer<TerminalIO.Status> listener) {
        this.statusListener = listener;
    }

    public void setConfirmationHandler(ConfirmationHandler handler) {
        this.confirmationHandler = handler;
    }

    public void setAuthorizationHandler(AuthorizationHandler handler) {
        this.authorizationHandler = handler;
    }

    // --- Worker loop ---

    private void workerLoop() {
        while (running) {
            try {
                Task task = taskQueue.take(); // blocks until a task is available
                if (!running) break;

                cancelled = false;
                status.set(TerminalIO.Status.RUNNING);
                if (statusListener != null) {
                    statusListener.accept(TerminalIO.Status.RUNNING);
                }

                executeTask(task);

                // Only go back to IDLE if no new task has been queued in the meantime
                if (!cancelled && running) {
                    status.set(TerminalIO.Status.IDLE);
                    if (statusListener != null) {
                        statusListener.accept(TerminalIO.Status.IDLE);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        status.set(TerminalIO.Status.IDLE);
        if (statusListener != null) {
            statusListener.accept(TerminalIO.Status.IDLE);
        }
    }

    private void executeTask(Task task) {
        agent.resetInterrupt();
        try {
            String response = agent.run(task.message);
            if (outputConsumer != null && !cancelled) {
                outputConsumer.accept(response);
            }
            if (task.resultCallback != null) {
                task.resultCallback.accept(response);
            }
        } catch (ToolConfirmationException e) {
            if (confirmationHandler != null && !cancelled) {
                String userResponse = confirmationHandler.handleConfirmation(e);
                if ("c".equalsIgnoreCase(userResponse.trim())) {
                    // Cancelled — return to IDLE
                    logger.info("User cancelled confirmation");
                } else {
                    // Re-submit with the user's choice — the handler should have
                    // already set the agent's history for resume mode.
                    // We re-run with the same message.
                    executeTask(task);
                }
            }
        } catch (UnauthorizedAccessException e) {
            if (authorizationHandler != null && !cancelled) {
                String userResponse = authorizationHandler.handleAuthorization(e);
                if (!"n".equalsIgnoreCase(userResponse.trim())) {
                    // Re-run with authorization granted
                    executeTask(task);
                }
            }
        } catch (Exception e) {
            String errMsg = e.getMessage();
            if (errMsg != null && outputConsumer != null) {
                outputConsumer.accept("Error: " + errMsg);
            }
            logger.error("Agent execution error", e);
        }
    }
}
