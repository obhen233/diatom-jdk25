package com.github.obhen233.cli.provider;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.agent.TaskManager;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

/**
 * Log command provider.
 */
public class LogCommandProvider implements CoreCommandProvider {

    private ReActAgent agent;
    private TaskManager taskManager;

    public void setTaskManager(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public void init(ReActAgent agent) {
        this.agent = agent;
    }

    @Override
    public String getCommandName() {
        return "log";
    }

    @Override
    public String getDescription() {
        return "{{cli.log.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.log.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        String lower = args.toLowerCase().trim();

        if (lower.startsWith("--task ")) {
            String taskId = lower.substring(7).trim();
            return handleLogTask(taskId);
        }

        if (lower.isEmpty() || "log".equals(lower)) {
            return handleLogCurrent();
        }

        return "ERROR {{log.error.unknown:" + args + "}}";
    }

    private String handleLogCurrent() {
        if (taskManager == null) {
            return "ERROR {{log.error.task_manager_not_available}}";
        }

        String currentTaskId = agent != null ? agent.getCurrentTaskId() : null;
        if (currentTaskId == null) {
            return "INFO {{log.error.no_current_task}}";
        }

        return "INFO {{log.show.not_implemented:" + currentTaskId + "}}";
    }

    private String handleLogTask(String taskId) {
        if (taskManager == null) {
            return "ERROR {{log.error.task_manager_not_available}}";
        }

        return "INFO {{log.show.not_implemented:" + taskId + "}}";
    }
}