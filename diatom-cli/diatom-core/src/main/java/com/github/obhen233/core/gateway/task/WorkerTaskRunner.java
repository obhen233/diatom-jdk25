package com.github.obhen233.core.gateway.task;

import com.github.obhen233.core.gateway.http.dto.CancelRequestPayload;
import com.github.obhen233.core.gateway.http.dto.ShutdownNoticePayload;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker 任务执行器
 * 管理单个任务的执行生命周期，支持优雅关闭时的中断和 checkpoint 保存
 */
public class WorkerTaskRunner {
    private static final Logger logger = LoggerFactory.getLogger(WorkerTaskRunner.class);

    private final String taskId;
    private final String gatewayUrl;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private volatile Thread runningThread;
    private volatile int currentStep;
    private volatile int tokenUsage;

    public WorkerTaskRunner(String taskId, String gatewayUrl) {
        this.taskId = taskId;
        this.gatewayUrl = gatewayUrl;
    }

    public String getTaskId() { return taskId; }

    public void setRunningThread(Thread thread) {
        this.runningThread = thread;
    }

    public void updateProgress(int step, int tokens) {
        this.currentStep = step;
        this.tokenUsage = tokens;
    }

    /**
     * 请求停止当前任务
     */
    public void requestStop() {
        stopped.set(true);
        if (runningThread != null) {
            runningThread.interrupt();
        }
    }

    public boolean isStopped() {
        return stopped.get();
    }

    /**
     * 等待任务完成（带超时）
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        try {
            return completionLatch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 标记任务完成
     */
    public void markCompleted() {
        completionLatch.countDown();
    }

    /**
     * 保存最终 checkpoint 并通知 Gateway
     */
    public void saveFinalCheckpoint(String llmSummary, String fileChangeSummary) {
        try {
            URL url = new URL(gatewayUrl + "/gateway/v1/checkpoint");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            ShutdownNoticePayload payload = new ShutdownNoticePayload();
            payload.taskId = taskId;
            payload.stepCount = currentStep;
            payload.tokenUsage = tokenUsage;
            payload.agentState = "SHUTTING_DOWN";
            payload.llmSummary = llmSummary;
            payload.fileChangeSummary = fileChangeSummary;
            String body = JsonUtils.toJson(payload);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            logger.info("Final checkpoint saved for task {}, response={}", taskId, code);
        } catch (Exception e) {
            logger.warn("Failed to save final checkpoint for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 通知 Gateway 任务已取消
     */
    public void notifyGatewayCancelled() {
        notifyGatewayStatus("CANCELLED");
    }

    /**
     * 通知 Gateway 任务已暂停
     */
    public void notifyGatewaySuspended() {
        notifyGatewayStatus("SUSPENDED");
    }

    private void notifyGatewayStatus(String status) {
        try {
            URL url = new URL(gatewayUrl + "/gateway/v1/tasks/" + taskId + "/cancel");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            Map<String, String> statusMap = new HashMap<>();
            statusMap.put("taskId", taskId);
            statusMap.put("status", status);
            String body = JsonUtils.toJson(statusMap);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            logger.warn("Failed to notify Gateway for task {}: {}", taskId, e.getMessage());
        }
    }

}
