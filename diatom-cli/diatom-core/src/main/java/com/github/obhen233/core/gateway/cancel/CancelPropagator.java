package com.github.obhen233.core.gateway.cancel;

import com.github.obhen233.core.gateway.http.dto.CancelRequestPayload;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 取消传播 — 从 Gateway 到 Worker 的取消链路
 */
public class CancelPropagator {
    private static final Logger logger = LoggerFactory.getLogger(CancelPropagator.class);

    private final TaskManager taskManager;
    private final WorkerRegistry registry;

    public CancelPropagator(TaskManager taskManager, WorkerRegistry registry) {
        this.taskManager = taskManager;
        this.registry = registry;
    }

    /**
     * 传播取消到指定 Worker
     */
    public void propagateCancel(String taskId) {
        com.github.obhen233.core.gateway.task.TaskState state = taskManager.getTask(taskId);
        if (state == null) return;

        taskManager.cancelTask(taskId);

        String workerId = state.getWorkerId();
        if (workerId == null) return;

        // 主动通知 Worker
        WorkerInfo worker = registry.getWorker(workerId);
        if (worker != null) {
            notifyWorkerCancel(worker, taskId);
        }
    }

    /**
     * 主动 POST /worker/{id}/cancel
     */
    private void notifyWorkerCancel(WorkerInfo worker, String taskId) {
        try {
            URL url = new URL(worker.getBaseUrl() + "/worker/v1/cancel");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            CancelRequestPayload payload = new CancelRequestPayload();
            payload.taskId = taskId;
            String body = JsonUtils.toJson(payload);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            logger.info("Cancel notification sent to worker {} for task {}, response={}",
                    worker.getWorkerId(), taskId, code);
        } catch (Exception e) {
            logger.warn("Failed to send cancel to worker {}: {}", worker.getWorkerId(), e.getMessage());
        }
    }
}
