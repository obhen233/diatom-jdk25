package com.github.obhen233.spi;

/**
 * Callback interface for receiving deployment execution events.
 * Used by {@link DeployProvider} to stream output and report completion.
 *
 * Implementations typically forward these events to a WebSocket session,
 * terminal UI, or other real-time output channel.
 */
public interface DeployCallback {

    /**
     * Called when the deployment produces output text.
     *
     * @param text the output text (may be multiple lines)
     */
    void onOutput(String text);

    /**
     * Called when an individual pipeline step completes.
     *
     * @param stepName the name of the step
     * @param success  whether the step succeeded
     */
    void onStepComplete(String stepName, boolean success);

    /**
     * Called when the entire deployment pipeline completes.
     *
     * @param success whether the entire pipeline succeeded
     */
    void onPipelineComplete(boolean success);

    /**
     * Called when an error occurs during deployment.
     *
     * @param message the error message
     */
    void onError(String message);

    /**
     * Called when a deployment step reports byte-level progress (e.g., SCP file upload).
     * Default no-op keeps existing implementations backward-compatible.
     *
     * @param stepName  the name of the step currently reporting progress
     * @param current   bytes transferred so far
     * @param total     total bytes to transfer
     * @param speedBps  current transfer speed in bytes per second
     */
    default void onProgress(String stepName, long current, long total, long speedBps) {
        // no-op for backward compatibility
    }
}
