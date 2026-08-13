package com.github.obhen233.core.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.obhen233.core.pipeline.PipelineConstants.*;

/**
 * PipelineRunner for Docker operations via Docker CLI.
 * Supports the "docker" action type.
 */
public class DockerRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DockerRunner.class);

    @Override
    public String getActionType() {
        return ACTION_DOCKER;
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String command = step.getCommand();
        if (command == null || command.trim().isEmpty()) {
            if (step.getCommands() != null && !step.getCommands().isEmpty()) {
                command = CommandJoiner.join(step.getCommands());
            } else {
                callback.onError("Docker step '" + step.getName() + "' has no command");
                return false;
            }
        }

        command = command.trim();
        callback.onOutput("$ docker " + command + "\n");
        logger.info("Docker step '{}': docker {}", step.getName(), command);

        String projectDir = variables.get(VAR_PROJECT_DIR);
        File workDir = projectDir != null ? new File(projectDir) : new File(".");

        // Auto-login if DOCKER_USER and DOCKER_PASSWORD are provided
        String dockerUser = variables.get(VAR_DOCKER_USER);
        String dockerPassword = variables.get(VAR_DOCKER_PASSWORD);
        String dockerRegistry = variables.get(VAR_DOCKER_REGISTRY);

        if (dockerUser != null && dockerPassword != null && !dockerUser.isEmpty() && !dockerPassword.isEmpty()
                && !command.startsWith("login")) {
            String loginCmd = "docker login";
            if (dockerRegistry != null && !dockerRegistry.isEmpty()) {
                loginCmd += " " + dockerRegistry;
            }
            loginCmd += " -u " + dockerUser + " -p " + dockerPassword;

            CommandExecutor.execute(loginCmd, workDir, 1, callback);
            // Login result is best-effort; continue regardless
        }

        // Execute docker command
        int exitCode = CommandExecutor.execute("docker " + command, workDir, TIMEOUT_DOCKER, callback);

        boolean success = exitCode == 0;
        if (success) {
            callback.onOutput("\n" + CHECK + " Docker step '" + step.getName() + "' completed\n");
        } else {
            callback.onOutput("\n" + CROSS + " Docker step '" + step.getName() + "' failed (exit: " + exitCode + ")\n");
        }
        return success;
    }
}
