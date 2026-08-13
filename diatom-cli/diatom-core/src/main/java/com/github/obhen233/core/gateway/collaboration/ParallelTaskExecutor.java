package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.checkpoint.CheckpointReport;
import com.github.obhen233.core.gateway.checkpoint.CheckpointService;
import com.github.obhen233.core.gateway.http.dto.SubTaskPayload;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.security.SecurityHeadersInjector;
import com.github.obhen233.core.gateway.security.SecurityProviderLoader;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 并行任务执行器
 * 将子任务分配到多个 Worker 并行执行，收集结果
 *
 * P6 预留接口，当前提供线程池基础实现
 */
public class ParallelTaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ParallelTaskExecutor.class);

    private final ExecutorService executor;
    private CheckpointService checkpointService;

    public ParallelTaskExecutor() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.checkpointService = null;
    }

    public ParallelTaskExecutor(CheckpointService checkpointService, int maxThreads) {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.checkpointService = checkpointService;
    }

    /**
     * 设置 CheckpointService（延迟注入，解决依赖顺序问题）
     */
    public void setCheckpointService(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    /**
     * 并行执行子任务列表（无工作空间）
     */
    public List<WorkerCoordinator.SubTaskResult> executeParallel(
            List<WorkerCoordinator.SubTask> subTasks,
            List<WorkerInfo> workers,
            long timeout, TimeUnit unit) {
        return executeParallel(subTasks, workers, timeout, unit, (String) null);
    }

    /**
     * 并行执行子任务列表（含工作空间）
     */
    public List<WorkerCoordinator.SubTaskResult> executeParallel(
            List<WorkerCoordinator.SubTask> subTasks,
            List<WorkerInfo> workers,
            long timeout, TimeUnit unit,
            String workspacePath) {
        // Delegate to per-worker paths overload with same path for all
        List<String> wsPaths;
        if (workspacePath != null) {
            wsPaths = java.util.Collections.nCopies(Math.min(subTasks.size(), workers.size()), workspacePath);
        } else {
            wsPaths = null;
        }
        return executeParallel(subTasks, workers, timeout, unit, (java.util.List<String>) wsPaths);
    }

    /**
     * 并行执行子任务列表（每个 worker 可指定不同的工作空间路径）
     */
    public List<WorkerCoordinator.SubTaskResult> executeParallel(
            List<WorkerCoordinator.SubTask> subTasks,
            List<WorkerInfo> workers,
            long timeout, TimeUnit unit,
            List<String> workspacePaths) {
        return executeParallel(subTasks, workers, timeout, unit, workspacePaths, null);
    }

    /**
     * 并行执行子任务列表（每个 worker 可指定不同的工作空间路径和文件清单）
     *
     * @param fileManifests 每个子任务对应的文件清单 JSON 字符串（可为 null）
     */
    public List<WorkerCoordinator.SubTaskResult> executeParallel(
            List<WorkerCoordinator.SubTask> subTasks,
            List<WorkerInfo> workers,
            long timeout, TimeUnit unit,
            List<String> workspacePaths,
            List<String> fileManifests) {

        List<Future<WorkerCoordinator.SubTaskResult>> futures = new ArrayList<>();
        for (int i = 0; i < subTasks.size() && i < workers.size(); i++) {
            final int idx = i;
            String wsPath = workspacePaths != null && idx < workspacePaths.size()
                    ? workspacePaths.get(idx) : null;
            String manifest = fileManifests != null && idx < fileManifests.size()
                    ? fileManifests.get(idx) : null;
            futures.add(executor.submit(() ->
                    executeOnWorker(subTasks.get(idx), workers.get(idx), wsPath, manifest)));
        }

        List<WorkerCoordinator.SubTaskResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Future<WorkerCoordinator.SubTaskResult> future = futures.get(i);
            WorkerCoordinator.SubTask subTask = subTasks.get(i);
            try {
                WorkerCoordinator.SubTaskResult result = future.get(timeout, unit);
                results.add(result);
                // Record checkpoint after each sub-task completes
                recordCheckpoint(subTask, result);
            } catch (Exception e) {
                logger.warn("Parallel sub-task {} execution failed: {}", subTask.getSubTaskId(), e.getMessage());
                results.add(new WorkerCoordinator.SubTaskResult(
                        subTask.getSubTaskId(), false, "Execution failed", e.getMessage(), subTask.getOrder()));
                recordCheckpoint(subTask, new WorkerCoordinator.SubTaskResult(
                        subTask.getSubTaskId(), false, "Execution failed", e.getMessage()));
            }
        }
        return results;
    }

    private WorkerCoordinator.SubTaskResult executeOnWorker(
            WorkerCoordinator.SubTask subTask, WorkerInfo worker, String workspacePath) {
        return executeOnWorker(subTask, worker, workspacePath, null);
    }

    private WorkerCoordinator.SubTaskResult executeOnWorker(
            WorkerCoordinator.SubTask subTask, WorkerInfo worker,
            String workspacePath, String fileManifestJson) {
        logger.info("Executing sub-task {} on worker {} ({}:{})",
                subTask.getSubTaskId(), worker.getWorkerId(), worker.getHost(), worker.getPort());
        try {
            String workerUrl = "http://" + worker.getHost() + ":" + worker.getPort() + "/worker/v1/chat";
            URL url = new URL(workerUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(300000);

            // Inject security headers
            SecurityHeadersInjector injector = new SecurityHeadersInjector(
                    SecurityProviderLoader.getAuthProvider(),
                    SecurityProviderLoader.getEncryptionProvider());
            injector.injectIntoConnection(conn, worker.getWorkerId());

            // Build request body with workspace path and file manifest
            SubTaskPayload payload = new SubTaskPayload();
            payload.subTaskId = subTask.getSubTaskId();
            payload.parentTaskId = subTask.getParentTaskId();
            payload.description = subTask.getDescription();
            payload.order = subTask.getOrder();
            payload.workspacePath = workspacePath;
            if (fileManifestJson != null && !fileManifestJson.isEmpty()) {
                payload.fileManifest = fileManifestJson;
            }
            String body = JsonUtils.toJson(payload);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            String responseBody;
            try (InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream() : conn.getErrorStream()) {
                java.util.Scanner scanner = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
                responseBody = scanner.hasNext() ? scanner.next() : "";
            }
            conn.disconnect();

            if (responseCode >= 200 && responseCode < 300) {
                // Extract response and summary fields from worker JSON response
                String workerResponse = extractJsonValue(responseBody, "response");
                String workerSummary = extractJsonValue(responseBody, "summary");
                String detailText = workerResponse != null ? workerResponse : responseBody;
                String summaryText = workerSummary != null ? workerSummary : "Executed on " + worker.getWorkerId();

                logger.info("Sub-task {} completed on worker {} (response={})",
                        subTask.getSubTaskId(), worker.getWorkerId(), responseCode);
                return new WorkerCoordinator.SubTaskResult(
                        subTask.getSubTaskId(), true, summaryText, detailText, subTask.getOrder());
            } else {
                logger.warn("Sub-task {} failed on worker {} (response={}): {}",
                        subTask.getSubTaskId(), worker.getWorkerId(), responseCode, responseBody);
                return new WorkerCoordinator.SubTaskResult(
                        subTask.getSubTaskId(), false,
                        "Failed on " + worker.getWorkerId(),
                        "HTTP " + responseCode + ": " + responseBody,
                        subTask.getOrder());
            }
        } catch (Exception e) {
            logger.warn("Sub-task {} execution on worker {} failed: {}",
                    subTask.getSubTaskId(), worker.getWorkerId(), e.getMessage());
            return new WorkerCoordinator.SubTaskResult(
                    subTask.getSubTaskId(), false,
                    "Execution failed on " + worker.getWorkerId(),
                    "Error: " + e.getMessage(),
                    subTask.getOrder());
        }
    }

    /**
     * 记录子任务 checkpoint
     */
    private void recordCheckpoint(WorkerCoordinator.SubTask subTask, WorkerCoordinator.SubTaskResult result) {
        if (checkpointService == null) return;
        try {
            CheckpointReport report = new CheckpointReport();
            report.setTaskId(subTask.getParentTaskId());
            report.setStepCount(subTask.getOrder());
            report.setTokenUsage(0);
            report.setMessageCount(0);
            report.setLlmSummary("sub-task " + subTask.getSubTaskId()
                    + ": " + (result.isSuccess() ? "success" : "failed")
                    + " - " + result.getSummary());
            report.setFileChangeSummary("");
            report.setProgress((int) (((double) (subTask.getOrder() + 1) / 10) * 100));
            checkpointService.receiveCheckpoint(report);
        } catch (Exception e) {
            logger.warn("Failed to record checkpoint for sub-task {}: {}",
                    subTask.getSubTaskId(), e.getMessage());
        }
    }

    private static String extractJsonValue(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) {
            // Try non-string value
            search = "\"" + key + "\":";
            start = json.indexOf(search);
            if (start < 0) return null;
            start += search.length();
            while (start < json.length() && json.charAt(start) == ' ') start++;
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            if (end < 0) return json.substring(start).trim();
            return json.substring(start, end).trim();
        }
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
