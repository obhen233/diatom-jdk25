package com.github.obhen233.core.agent;

import com.github.obhen233.core.database.TaskDao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task Manager for async execution and background task management.
 * Allows running tasks in the background and monitoring their status.
 */
public class TaskManager {
    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);

    private final ExecutorService executor;
    private final Map<String, Task> tasks;
    private final AtomicInteger taskCounter;

    private TaskDao taskDao;
    private Long defaultProjectId;

    public TaskManager() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.tasks = new ConcurrentHashMap<>();
        this.taskCounter = new AtomicInteger(0);
    }

    /**
     * Inject the TaskDao for persisting task state.
     */
    public void setTaskDao(TaskDao taskDao) {
        this.taskDao = taskDao;
    }

    /**
     * Set the default project ID for tasks submitted without an explicit projectId.
     */
    public void setDefaultProjectId(Long defaultProjectId) {
        this.defaultProjectId = defaultProjectId;
    }

    /**
     * Submit a task to be executed asynchronously.
     *
     * @param userInput      the user input for the task
     * @param agent          the ReActAgent to execute the task
     * @param workspacePath  the workspace path for this task
     * @param explicitTaskId explicit task ID (preferred); null to generate a sequential ID
     * @return the task ID
     */
    public String submit(String userInput, ReActAgent agent, String workspacePath, String explicitTaskId) {
        return submit(userInput, agent, workspacePath, explicitTaskId, null);
    }

    /**
     * Submit a task to be executed asynchronously with project association.
     *
     * @param userInput      the user input for the task
     * @param agent          the ReActAgent to execute the task
     * @param workspacePath  the workspace path for this task
     * @param explicitTaskId explicit task ID (preferred); null to generate a sequential ID
     * @param projectId      the project ID to associate with this task (null to use default)
     * @return the task ID
     */
    public String submit(String userInput, ReActAgent agent, String workspacePath, String explicitTaskId, Long projectId) {
        String taskId = explicitTaskId != null ? explicitTaskId : "task_" + taskCounter.incrementAndGet();

        Task task = new Task();
        task.id = taskId;
        task.userInput = userInput;
        task.status = TaskStatus.PENDING;
        task.startTime = System.currentTimeMillis();
        task.workspacePath = workspacePath;
        task.projectId = projectId != null ? projectId : defaultProjectId;

        insertTaskToDb(task);

        Future<String> future = executor.submit(() -> {
            task.status = TaskStatus.RUNNING;
            updateTaskStatusInDb(taskId, "RUNNING");
            logger.info("Task {} started: {}", taskId, truncateInput(userInput));
            try {
                String result = agent.run(userInput);
                task.status = TaskStatus.COMPLETED;
                task.result = result;
                task.endTime = System.currentTimeMillis();
                updateTaskStatusInDb(taskId, "COMPLETED");
                logger.info("Task {} completed in {}ms", taskId, task.getDurationMs());
                return result;
            } catch (Exception e) {
                task.status = TaskStatus.FAILED;
                task.error = e.getMessage();
                task.endTime = System.currentTimeMillis();
                updateTaskStatusInDb(taskId, "FAILED");
                logger.error("Task {} failed: {}", taskId, e.getMessage());
                throw new RuntimeException(e);
            }
        });

        task.future = future;
        tasks.put(taskId, task);

        logger.info("Task {} submitted: {}", taskId, truncateInput(userInput));
        return taskId;
    }

    /**
     * Submit a task without waiting for completion (fire-and-forget).
     */
    public String submitBackground(String userInput, ReActAgent agent, String workspacePath, String explicitTaskId,
                                   TaskCompletionCallback callback) {
        return submitBackground(userInput, agent, workspacePath, explicitTaskId, callback, null);
    }

    /**
     * Submit a task without waiting for completion, with project association.
     */
    public String submitBackground(String userInput, ReActAgent agent, String workspacePath, String explicitTaskId,
                                   TaskCompletionCallback callback, Long projectId) {
        String taskId = submit(userInput, agent, workspacePath, explicitTaskId, projectId);

        executor.submit(() -> {
            try {
                Task task = tasks.get(taskId);
                if (task != null && task.future != null) {
                    String result = task.future.get();
                    if (callback != null) {
                        callback.onComplete(taskId, result);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (callback != null) {
                    callback.onError(taskId, e);
                }
            } catch (ExecutionException e) {
                if (callback != null) {
                    callback.onError(taskId, e.getCause());
                }
            }
        });

        return taskId;
    }

    /**
     * Persist or update a task step. Safe to call from the agent loop.
     */
    public void updateTaskStep(String taskId, int stepNumber, String description, String status) {
        if (taskDao == null) return;
        try {
            TaskDao.TaskStep step = new TaskDao.TaskStep();
            step.taskId = taskId;
            step.stepNumber = stepNumber;
            step.description = description != null ? description : "Step " + stepNumber;
            step.status = status;
            step.createdAt = System.currentTimeMillis();
            taskDao.insertTaskStep(step);
            taskDao.updateTaskStep(taskId, stepNumber);
        } catch (Exception e) {
            logger.warn("Failed to update task step for {}", taskId, e);
        }
    }

    /**
     * Update the latest snapshot reference for a task.
     */
    public void updateTaskSnapshot(String taskId, int snapshotId) {
        if (taskDao == null) return;
        try {
            taskDao.updateTaskSnapshot(taskId, snapshotId);
        } catch (Exception e) {
            logger.warn("Failed to update task snapshot for {}", taskId, e);
        }
    }

    /**
     * Update the context checkpoint reference for a task.
     */
    public void updateTaskCheckpoint(String taskId, Integer checkpointId) {
        if (taskDao == null) return;
        try {
            taskDao.updateTaskCheckpoint(taskId, checkpointId);
        } catch (Exception e) {
            logger.warn("Failed to update task checkpoint for {}", taskId, e);
        }
    }

    /**
     * Get a task by ID
     */
    public Task getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * Get all tasks (from memory; database list available via taskDao.findAllTasks())
     */
    public List<Task> listTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * Get tasks filtered by status
     */
    public List<Task> listTasks(TaskStatus status) {
        List<Task> filtered = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (task.status == status) {
                filtered.add(task);
            }
        }
        return filtered;
    }

    /**
     * Cancel a running task
     * @return true if the task was found and cancellation was requested
     */
    public boolean cancel(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) {
            return false;
        }

        if (task.future != null && !task.future.isDone()) {
            boolean cancelled = task.future.cancel(true);
            if (cancelled) {
                task.status = TaskStatus.CANCELLED;
                task.endTime = System.currentTimeMillis();
                updateTaskStatusInDb(taskId, "CANCELLED");
                logger.info("Task {} cancelled", taskId);
            }
            return cancelled;
        }

        return false;
    }

    /**
     * Wait for a task to complete
     * @param taskId The task ID
     * @param timeoutMs Maximum time to wait
     * @return The task result, or null if timeout or error
     */
    public String waitForTask(String taskId, long timeoutMs) {
        Task task = tasks.get(taskId);
        if (task == null || task.future == null) {
            return null;
        }

        try {
            return task.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.warn("Task {} timed out after {}ms", taskId, timeoutMs);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    /**
     * Get the count of running tasks
     */
    public int getRunningCount() {
        return listTasks(TaskStatus.RUNNING).size();
    }

    /**
     * Get the count of pending tasks
     */
    public int getPendingCount() {
        return listTasks(TaskStatus.PENDING).size();
    }

    /**
     * Get the count of completed tasks
     */
    public int getCompletedCount() {
        return listTasks(TaskStatus.COMPLETED).size();
    }

    /**
     * Get the count of failed tasks
     */
    public int getFailedCount() {
        return listTasks(TaskStatus.FAILED).size();
    }

    /**
     * Clean up completed/failed/cancelled tasks older than the specified age
     */
    public void cleanupOldTasks(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, Task> entry : tasks.entrySet()) {
            Task task = entry.getValue();
            if (task.endTime > 0 && task.endTime < cutoff) {
                if (task.status == TaskStatus.COMPLETED ||
                    task.status == TaskStatus.FAILED ||
                    task.status == TaskStatus.CANCELLED) {
                    toRemove.add(entry.getKey());
                }
            }
        }

        for (String taskId : toRemove) {
            tasks.remove(taskId);
        }

        if (!toRemove.isEmpty()) {
            logger.info("Cleaned up {} old tasks", toRemove.size());
        }
    }

    /**
     * Shutdown the task manager gracefully
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void insertTaskToDb(Task task) {
        if (taskDao == null) return;
        try {
            TaskDao.TaskInfo info = new TaskDao.TaskInfo();
            info.id = task.id;
            info.status = toDbStatus(task.status);
            info.originalRequest = task.userInput;
            info.currentStep = 0;
            info.totalSteps = 0;
            info.workspacePath = task.workspacePath;
            info.projectId = task.projectId;
            info.contextCheckpointId = null;
            info.latestSnapshotId = null;
            info.createdAt = task.startTime;
            info.updatedAt = task.startTime;
            taskDao.insertTask(info);
        } catch (Exception e) {
            logger.warn("Failed to insert task into database", e);
        }
    }

    private void updateTaskStatusInDb(String taskId, String status) {
        if (taskDao == null) return;
        try {
            taskDao.updateTaskStatus(taskId, status);
        } catch (Exception e) {
            logger.warn("Failed to update task status in database", e);
        }
    }

    private String toDbStatus(TaskStatus status) {
        return status != null ? status.name() : "PENDING";
    }

    private String truncateInput(String input) {
        if (input == null) return "";
        if (input.length() <= 50) return input;
        return input.substring(0, 50) + "...";
    }

    /**
     * Task status enum
     */
    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Task data class
     */
    public static class Task {
        public String id;
        public String userInput;
        public Future<String> future;
        public TaskStatus status;
        public String result;
        public String error;
        public long startTime;
        public long endTime;
        public String workspacePath;
        public Long projectId;

        public long getDurationMs() {
            if (endTime > 0) {
                return endTime - startTime;
            }
            return System.currentTimeMillis() - startTime;
        }

        public String getDurationFormatted() {
            long ms = getDurationMs();
            if (ms < 1000) {
                return ms + "ms";
            } else if (ms < 60000) {
                return (ms / 1000) + "s";
            } else {
                return (ms / 60000) + "m";
            }
        }
    }

    /**
     * Callback interface for async task completion
     */
    public interface TaskCompletionCallback {
        void onComplete(String taskId, String result);
        void onError(String taskId, Throwable error);
    }
}
