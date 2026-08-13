package com.github.obhen233.core.gateway.migration;

import com.github.obhen233.core.gateway.checkpoint.CheckpointService;
import com.github.obhen233.core.gateway.http.dto.MigrationRequestPayload;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 任务迁移编排
 * Token 耗尽 / Worker 故障时，将任务迁移到另一个 Worker
 */
public class TaskMigrator {
    private static final Logger logger = LoggerFactory.getLogger(TaskMigrator.class);

    private final TaskManager taskManager;
    private final WorkerRegistry registry;
    private final CapabilityRouter router;
    private final CheckpointService checkpointService;

    public TaskMigrator(TaskManager taskManager, WorkerRegistry registry,
                        CapabilityRouter router, CheckpointService checkpointService) {
        this.taskManager = taskManager;
        this.registry = registry;
        this.router = router;
        this.checkpointService = checkpointService;
    }

    /**
     * Token 耗尽时触发迁移
     */
    public boolean migrateOnTokenExhausted(String taskId, String llmSummary, String fileChangeSummary, int checkpointStep) {
        taskManager.markTokenExhausted(taskId, llmSummary, fileChangeSummary, checkpointStep);

        // Find new worker
        TaskState state = taskManager.getTask(taskId);
        if (state == null) return false;

        TaskRequirement req = new TaskRequirement();
        req.setTaskType("migration");
        req.setRequiredCapabilities(java.util.Collections.singletonList("通用开发"));

        WorkerInfo newWorker = router.route(req);
        if (newWorker == null) {
            logger.warn("No available worker for migration of task: {}", taskId);
            return false;
        }

        // Reassign
        taskManager.reAssignTask(taskId, newWorker.getWorkerId());
        taskManager.startTask(taskId);

        // Notify new worker
        notifyWorkerMigration(newWorker, state);
        return true;
    }

    private void notifyWorkerMigration(WorkerInfo worker, TaskState state) {
        try {
            URL url = new URL(worker.getBaseUrl() + "/worker/v1/migrate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            String workspacePath = state.getAttribute("workspacePath");
            MigrationRequestPayload payload = new MigrationRequestPayload();
            payload.taskId = state.getTaskId();
            payload.originalRequest = state.getOriginalRequest();
            payload.checkpointStep = state.getCheckpointStep();
            payload.workspacePath = workspacePath;
            String body = JsonUtils.toJson(payload);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            logger.info("Migration notification sent to worker {} for task {}, response={}",
                    worker.getWorkerId(), state.getTaskId(), code);
        } catch (Exception e) {
            logger.warn("Failed to notify worker {} for migration: {}", worker.getWorkerId(), e.getMessage());
        }
    }

}
