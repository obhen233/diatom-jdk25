package com.github.obhen233.core.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;

import static com.github.obhen233.core.pipeline.PipelineConstants.*;

/**
 * PipelineRunner that executes local shell commands.
 * Supports the "run_command" action type.
 */
public class LocalRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(LocalRunner.class);

    @Override
    public String getActionType() {
        return ACTION_RUN_COMMAND;
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String command = step.getCommand();
        if (command == null || command.trim().isEmpty()) {
            if (step.getCommands() != null && !step.getCommands().isEmpty()) {
                command = CommandJoiner.join(step.getCommands());
            } else {
                callback.onError("Step '" + step.getName() + "' has no command");
                return false;
            }
        }

        callback.onOutput("$ " + command + "\n");
        logger.info("Pipeline step '{}' executing: {}", step.getName(), command);

        String projectDir = variables.get(VAR_PROJECT_DIR);
        File cwd = projectDir != null ? new File(projectDir) : new File(".");

        int exitCode = CommandExecutor.execute(command, cwd, TIMEOUT_DEFAULT, callback);

        boolean success = exitCode == 0;
        if (success) {
            callback.onOutput("\n" + CHECK + " Step '" + step.getName() + "' completed\n");
        } else {
            callback.onOutput("\n" + CROSS + " Step '" + step.getName() + "' failed (exit code: " + exitCode + ")\n");
        }

        return success;
    }
}
