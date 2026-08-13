package com.github.obhen233.core.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;

import static com.github.obhen233.core.pipeline.PipelineConstants.*;

/**
 * PipelineRunner for Kubernetes operations via kubectl CLI.
 * Supports the "k8s" action type.
 */
public class K8sRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(K8sRunner.class);

    @Override
    public String getActionType() {
        return ACTION_K8S;
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String command = step.getCommand();
        if (command == null || command.trim().isEmpty()) {
            if (step.getCommands() != null && !step.getCommands().isEmpty()) {
                command = CommandJoiner.join(step.getCommands());
            } else {
                callback.onError("K8s step '" + step.getName() + "' has no command");
                return false;
            }
        }

        command = command.trim();
        callback.onOutput("$ kubectl " + command + "\n");
        logger.info("K8s step '{}': kubectl {}", step.getName(), command);

        String projectDir = variables.get(VAR_PROJECT_DIR);
        File workDir = projectDir != null ? new File(projectDir) : new File(".");

        // Build kubectl command with global flags
        StringBuilder kubectlCmd = new StringBuilder("kubectl");
        String kubeconfig = variables.get(VAR_KUBECONFIG_PATH);
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            kubectlCmd.append(" --kubeconfig=").append(kubeconfig);
        }
        String namespace = variables.get(VAR_K8S_NAMESPACE);
        if (namespace != null && !namespace.isEmpty()) {
            kubectlCmd.append(" -n ").append(namespace);
        }
        String context = variables.get(VAR_K8S_CONTEXT);
        if (context != null && !context.isEmpty()) {
            kubectlCmd.append(" --context=").append(context);
        }
        kubectlCmd.append(" ").append(command);

        int exitCode = CommandExecutor.execute(kubectlCmd.toString(), workDir, TIMEOUT_K8S, callback);

        boolean success = exitCode == 0;
        if (success) {
            callback.onOutput("\n" + CHECK + " K8s step '" + step.getName() + "' completed\n");
        } else {
            callback.onOutput("\n" + CROSS + " K8s step '" + step.getName() + "' failed (exit: " + exitCode + ")\n");
        }
        return success;
    }
}
