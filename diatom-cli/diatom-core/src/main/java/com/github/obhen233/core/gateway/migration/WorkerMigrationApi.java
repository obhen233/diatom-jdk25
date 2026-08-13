package com.github.obhen233.core.gateway.migration;

import com.github.obhen233.core.gateway.checkpoint.CheckpointService;
import com.github.obhen233.core.gateway.task.TaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Worker 迁移 API
 * 从 Gateway 加载 checkpoint 恢复任务执行
 */
public class WorkerMigrationApi {
    private static final Logger logger = LoggerFactory.getLogger(WorkerMigrationApi.class);

    private final CheckpointService checkpointService;
    private final TaskManager taskManager;

    public WorkerMigrationApi(CheckpointService checkpointService, TaskManager taskManager) {
        this.checkpointService = checkpointService;
        this.taskManager = taskManager;
    }

    /**
     * 从 Gateway 加载 checkpoint
     */
    public String loadCheckpoint(String gatewayUrl, String taskId, int checkpointStep) {
        try {
            URL url = new URL(gatewayUrl + "/gateway/v1/checkpoint?taskId=" + taskId + "&step=" + checkpointStep);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 200) {
                byte[] buf = new byte[8192];
                int n = conn.getInputStream().read(buf);
                conn.disconnect();
                if (n > 0) {
                    return new String(buf, 0, n, StandardCharsets.UTF_8);
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            logger.warn("Failed to load checkpoint from gateway: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 构造续接 prompt
     */
    public String buildContinuationPrompt(String originalRequest, String llmSummary, String fileChangeSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("[原始用户请求]\n");
        sb.append(originalRequest != null ? originalRequest : "").append("\n\n");
        sb.append("[以下是由上一个 Worker 已完成的工作]\n");
        sb.append(llmSummary != null ? llmSummary : "").append("\n\n");
        sb.append("[已完成文件变更]\n");
        sb.append(fileChangeSummary != null ? fileChangeSummary : "").append("\n\n");
        sb.append("[请从中断处继续，不要重复已完成的工作]");
        return sb.toString();
    }
}
