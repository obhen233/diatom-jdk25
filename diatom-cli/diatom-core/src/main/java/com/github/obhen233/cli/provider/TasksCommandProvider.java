package com.github.obhen233.cli.provider;

import com.github.obhen233.cli.TerminalUI;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.agent.TaskManager;
import com.github.obhen233.core.database.ChangeLogDao;
import com.github.obhen233.core.database.TaskDao;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Tasks command provider.
 */
public class TasksCommandProvider implements CoreCommandProvider,
        TerminalUI.AgentAware, TerminalUI.TaskDaoAware, TerminalUI.TaskManagerAware, TerminalUI.ChangeLogAware {

    private ReActAgent agent;
    private TaskManager taskManager;
    private TaskDao taskDao;
    private ChangeLogDao changeLogDao;

    public void setTaskManager(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public void init(ReActAgent agent) {
        this.agent = agent;
    }

    @Override
    public void initTaskDao(TaskDao taskDao) {
        this.taskDao = taskDao;
    }

    @Override
    public void initTaskManager(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public void initChangeLogDao(ChangeLogDao changeLogDao) {
        this.changeLogDao = changeLogDao;
    }

    @Override
    public String getCommandName() {
        return "tasks";
    }

    @Override
    public String getDescription() {
        return "{{cli.tasks.description}}";
    }

    @Override
    public String getHelp() {
        return "tasks | task <id> | task <id> resume | task <id> cancel | task <id> log";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            return handleTasksList();
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) {
            return handleTaskShow(parts[0]);
        }
        if (parts.length == 2) {
            if ("resume".equalsIgnoreCase(parts[1])) {
                return handleTaskResume(parts[0]);
            }
            if ("cancel".equalsIgnoreCase(parts[1])) {
                return handleTaskCancel(parts[0]);
            }
            if ("log".equalsIgnoreCase(parts[1])) {
                return handleTaskLog(parts[0]);
            }
        }

        return "ERROR {{tasks.error.unknown:" + args + "}}";
    }

    private String handleTasksList() {
        if (taskDao == null) {
            return "ERROR {{tasks.error.not_available}}";
        }

        List<TaskDao.TaskInfo> tasks = taskDao.findAllTasks();
        if (tasks.isEmpty()) {
            return "INFO {{cli_tasks_empty}}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("INFO Tasks:\n");
        sb.append(String.format("%-42s %-10s %-9s %-19s %s%n", "ID", "STATUS", "STEP", "UPDATED", "REQUEST"));
        sb.append(String.format("%-42s %-10s %-9s %-19s %s%n", "--", "------", "----", "-------", "-------"));
        for (TaskDao.TaskInfo task : tasks) {
            sb.append(String.format("%-42s %-10s %-9s %-19s %s%n",
                task.id,
                task.status,
                task.currentStep + "/" + task.totalSteps,
                formatTime(task.updatedAt),
                truncate(task.originalRequest, 60)));
        }
        return sb.toString();
    }

    private String handleTaskShow(String id) {
        if (taskDao == null) {
            return "ERROR {{tasks.error.not_available}}";
        }
        if (id == null || id.isEmpty()) {
            return "ERROR {{tasks.error.id_required}}";
        }

        TaskDao.TaskInfo task = taskDao.findTaskById(id);
        if (task == null) {
            return "ERROR {{cli_task_not_found:" + id + "}}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("INFO Task ").append(task.id).append("\n");
        sb.append("Status: ").append(task.status).append("\n");
        sb.append("Request: ").append(task.originalRequest != null ? task.originalRequest : "").append("\n");
        sb.append("Workspace: ").append(task.workspacePath != null ? task.workspacePath : "").append("\n");
        sb.append("Step: ").append(task.currentStep).append("/").append(task.totalSteps).append("\n");
        sb.append("Latest snapshot: ").append(task.latestSnapshotId != null ? task.latestSnapshotId : "(none)").append("\n");
        sb.append("Context checkpoint: ").append(task.contextCheckpointId != null ? task.contextCheckpointId : "(none)").append("\n");
        sb.append("Created: ").append(formatTime(task.createdAt)).append("\n");
        sb.append("Updated: ").append(formatTime(task.updatedAt)).append("\n");

        List<TaskDao.TaskStep> steps = taskDao.findStepsByTaskId(id);
        if (!steps.isEmpty()) {
            sb.append("\nSteps:\n");
            for (TaskDao.TaskStep step : steps) {
                sb.append(String.format("  #%d [%s] %s%n", step.stepNumber,
                    step.status != null ? step.status : "UNKNOWN",
                    step.description != null ? step.description : ""));
            }
        }
        return sb.toString();
    }

    private String handleTaskResume(String id) {
        if (id == null || id.isEmpty()) {
            return "ERROR {{tasks.error.id_required}}";
        }
        if (agent == null) {
            return "ERROR {{tasks.error.agent_not_initialized}}";
        }
        if (!agent.resumeFromCheckpoint(id)) {
            return "ERROR {{checkpoint_resume_not_found}}";
        }

        TaskDao.TaskInfo task = taskDao != null ? taskDao.findTaskById(id) : null;
        String input = task != null ? task.originalRequest : null;
        if (input == null || input.trim().isEmpty()) {
            return "SUCCESS Resumed task " + id + ". Run the next command to continue.";
        }

        if (taskManager != null) {
            taskManager.submitBackground(input, agent, task.workspacePath, id, null);
            return "SUCCESS Resumed task " + id + " in background.";
        }

        String result = agent.run(input);
        return "SUCCESS Resumed task " + id + ".\n" + result;
    }

    private String handleTaskCancel(String id) {
        if (id == null || id.isEmpty()) {
            return "ERROR {{tasks.error.id_required}}";
        }

        boolean cancelled = taskManager != null && taskManager.cancel(id);
        if (taskDao != null) {
            taskDao.updateTaskStatus(id, "CANCELLED");
        }
        return cancelled
            ? "SUCCESS {{cli_task_cancel}}: " + id
            : "INFO Task " + id + " marked CANCELLED.";
    }

    private String handleTaskLog(String id) {
        if (id == null || id.isEmpty()) {
            return "ERROR {{tasks.error.id_required}}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("INFO Log for task ").append(id).append("\n");

        if (changeLogDao != null) {
            List<ChangeLogDao.ChangeLog> logs = changeLogDao.findByTaskId(id);
            if (!logs.isEmpty()) {
                for (ChangeLogDao.ChangeLog log : logs) {
                    sb.append(String.format("[%s] step=%s %s %s %s%n",
                        formatTime(log.createdAt),
                        log.stepNumber != null ? log.stepNumber : "-",
                        log.status != null ? log.status : "",
                        log.operation != null ? log.operation : "",
                        log.filePath != null ? log.filePath : ""));
                    if (log.summary != null && !log.summary.isEmpty()) {
                        sb.append("  ").append(log.summary).append("\n");
                    }
                }
                return sb.toString();
            }
        }

        if (taskDao != null) {
            List<TaskDao.TaskStep> steps = taskDao.findStepsByTaskId(id);
            if (!steps.isEmpty()) {
                for (TaskDao.TaskStep step : steps) {
                    sb.append(String.format("[%s] #%d [%s] %s%n",
                        formatTime(step.createdAt), step.stepNumber, step.status, step.description));
                }
                return sb.toString();
            }
        }

        sb.append("No logs found.");
        return sb.toString();
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max - 3) + "...";
    }

    private String formatTime(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
    }
}
