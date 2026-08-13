package com.github.obhen233.core.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;

import static com.github.obhen233.core.pipeline.PipelineConstants.*;

/**
 * PipelineRunner for SVN (Subversion) operations via CLI.
 * Supports the "svn" action type.
 */
public class SvnRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SvnRunner.class);

    @Override
    public String getActionType() {
        return ACTION_SVN;
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String command = step.getCommand();
        if (command == null || command.trim().isEmpty()) {
            if (step.getCommands() != null && !step.getCommands().isEmpty()) {
                command = CommandJoiner.join(step.getCommands());
            } else {
                callback.onError("SVN step '" + step.getName() + "' has no command");
                return false;
            }
        }

        String projectDir = variables.get(VAR_PROJECT_DIR);
        File workDir = projectDir != null ? new File(projectDir) : new File(".");

        // Build auth parameters
        String svnUser = variables.get(VAR_SVN_USER);
        String svnPassword = variables.get(VAR_SVN_PASSWORD);
        StringBuilder authBuilder = new StringBuilder();
        if (svnUser != null && !svnUser.isEmpty()) {
            authBuilder.append(" --username ").append(svnUser);
        }
        if (svnPassword != null && !svnPassword.isEmpty()) {
            authBuilder.append(" --password ").append(svnPassword);
        }
        authBuilder.append(" --non-interactive");

        String trustCert = variables.getOrDefault(VAR_SVN_TRUST_CERT, "false");
        if ("true".equalsIgnoreCase(trustCert)) {
            authBuilder.append(" --trust-server-cert-failures=unknown-ca,cn-mismatch,expired,not-yet-valid,other");
            authBuilder.append(" --trust-server-cert");
        }

        callback.onOutput("$ svn " + command + "\n");
        logger.info("SVN step '{}': svn {}", step.getName(), command);

        String svnFullCmd = "svn" + authBuilder + " " + command;
        int exitCode = CommandExecutor.execute(svnFullCmd, workDir, TIMEOUT_DEFAULT, callback);

        boolean success = exitCode == 0;
        if (success) {
            callback.onOutput("\n" + CHECK + " SVN step '" + step.getName() + "' completed\n");
        } else {
            callback.onOutput("\n" + CROSS + " SVN step '" + step.getName() + "' failed (exit: " + exitCode + ")\n");
        }
        return success;
    }
}
