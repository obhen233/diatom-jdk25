package com.github.obhen233.core.pipeline;

/**
 * Callback interface for pipeline execution events.
 * Used to stream output and notify of step/pipeline completion.
 */
public interface PipelineCallback {

    /**
     * Called when there is output data from a step execution.
     */
    void onOutput(String text);

    /**
     * Called when an individual step completes.
     *
     * @param stepName the name of the step
     * @param success  true if the step succeeded
     */
    void onStepComplete(String stepName, boolean success);

    /**
     * Called when the entire pipeline completes.
     *
     * @param success true if all steps succeeded
     */
    void onPipelineComplete(boolean success);

    /**
     * Called when an error occurs during pipeline execution.
     */
    void onError(String message);

    /**
     * Called when a step reports byte-level progress (e.g., SCP file upload).
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
