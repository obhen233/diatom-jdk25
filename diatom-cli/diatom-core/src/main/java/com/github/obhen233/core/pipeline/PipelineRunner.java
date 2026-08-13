package com.github.obhen233.core.pipeline;

import java.util.Map;

/**
 * Interface for executing a single pipeline step.
 * Each runner handles a specific action type (e.g., "run_command", "ssh_command").
 */
public interface PipelineRunner {

    /**
     * Returns the action type this runner supports.
     */
    String getActionType();

    /**
     * Execute a pipeline step.
     *
     * @param step     the pipeline step definition
     * @param variables resolved variable map ({{VAR}} already replaced)
     * @param callback callback for output and completion notification
     * @return true if the step completed successfully
     * @throws Exception if execution fails
     */
    boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception;
}
