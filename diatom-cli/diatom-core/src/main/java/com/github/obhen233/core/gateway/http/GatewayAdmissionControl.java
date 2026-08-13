package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.security.SecurityHeadersInjector;
import com.github.obhen233.core.gateway.security.SecurityProviderLoader;
import com.github.obhen233.core.gateway.sync.FileDiffResult;
import com.github.obhen233.core.gateway.collaboration.PipelineOrchestrator;
import com.github.obhen233.core.gateway.sync.ProjectSyncService;
import com.github.obhen233.spi.ConcurrencyControlProvider;
import org.slf4j.LoggerFactory;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Admission control and helper methods for GatewayHttpServer.
 * NO endpoint routes - just helper methods called by other handlers.
 */
class GatewayAdmissionControl {

    private final GatewayHttpServer server;

    GatewayAdmissionControl(GatewayHttpServer server) {
        this.server = server;
    }

    // ========== 并发控制 ==========

    /**
     * 尝试获取并发处理许可，获取失败则向客户端返回 429。
     * <p>
     * 决策链路：先咨询 SPI（如果有），再尝试信号量获取。
     * </p>
     *
     * @return true 如果成功获取许可，false 如果被拒绝
     */
    boolean tryAcquirePermit(HttpExchange exchange) throws IOException {
        Semaphore requestSemaphore = server.getRequestSemaphore();
        WorkerRegistry registry = server.getRegistry();
        ConcurrencyControlProvider concurrencyControl = server.getConcurrencyControl();

        // 1. 计算当前活跃数和 Worker 平均负载
        int currentActive = requestSemaphore.getQueueLength()
                + (GatewayHttpServer.DEFAULT_MAX_CONCURRENT_REQUESTS - requestSemaphore.availablePermits());
        double avgWorkerLoad = computeAvgWorkerLoad();

        // 2. SPI 自定义准入决策
        String clientIp = exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getHostString() : "unknown";
        int maxConcurrent = requestSemaphore.availablePermits()
                + (GatewayHttpServer.DEFAULT_MAX_CONCURRENT_REQUESTS - requestSemaphore.availablePermits());

        if (concurrencyControl != null
                && !concurrencyControl.acceptRequest(clientIp, currentActive, maxConcurrent, avgWorkerLoad)) {
            String json = "{\"error\":\"Request rejected by concurrency policy\",\"active\":"
                    + currentActive + ",\"workerLoad\":" + String.format("%.2f", avgWorkerLoad) + "}";
            sendJson(exchange, 429, json);
            LoggerFactory.getLogger(GatewayAdmissionControl.class).warn("Admission control (SPI): rejected request from {}, active={}, workerLoad={}",
                    clientIp, currentActive, String.format("%.2f", avgWorkerLoad));
            return false;
        }

        // 3. 信号量获取
        if (!requestSemaphore.tryAcquire()) {
            String json = "{\"error\":\"Too many concurrent requests\",\"maxConcurrent\":"
                    + maxConcurrent + ",\"active\":" + currentActive + "}";
            sendJson(exchange, 429, json);
            LoggerFactory.getLogger(GatewayAdmissionControl.class).warn("Admission control: rejected request (active={}, max={})",
                    currentActive, maxConcurrent);
            return false;
        }
        return true;
    }

    /**
     * 释放并发处理许可，并更新 Router 的活跃请求计数
     */
    void releasePermit(String workerId) {
        server.getRequestSemaphore().release();
        if (workerId != null && server.getCapabilityRouter() != null) {
            server.getCapabilityRouter().decrementActive(workerId);
        }
    }

    /**
     * 计算当前可用 Worker 的平均负载
     */
    private double computeAvgWorkerLoad() {
        List<WorkerInfo> workers = server.getRegistry().availableWorkers();
        if (workers == null || workers.isEmpty()) return 0.0;
        double sum = 0;
        for (WorkerInfo w : workers) {
            sum += w.getMetrics().getCurrentLoad();
        }
        return sum / workers.size();
    }

    /**
     * 检查是否存在不同类型的 Worker（高成本 + 低成本），满足流水线条件。
     */
    boolean hasDiverseWorkers() {
        List<WorkerInfo> workers = server.getRegistry().availableWorkers();
        if (workers.size() < 2) return false;
        boolean hasHighCost = false;
        boolean hasLowCost = false;
        for (WorkerInfo w : workers) {
            if (w.getCostPer1kTokens() >= 0.01) {
                hasHighCost = true;
            } else {
                hasLowCost = true;
            }
        }
        return hasHighCost && hasLowCost;
    }

    /**
     * Authenticate incoming request via X-Diatom-Auth header.
     * Returns false and sends 401 if authentication fails.
     */
    boolean authenticateRequest(HttpExchange exchange) throws IOException {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                headers.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        SecurityHeadersInjector injector = new SecurityHeadersInjector(
                SecurityProviderLoader.getAuthProvider(),
                SecurityProviderLoader.getEncryptionProvider());
        if (!injector.authenticateRequest(headers)) {
            sendError(exchange, 401, "Unauthorized");
            return false;
        }
        return true;
    }

    /**
     * 将 PipelineResult 的 stages 转为 JSON 数组字符串。
     */
    String buildPipelineStagesJson(PipelineOrchestrator.PipelineResult result) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (PipelineOrchestrator.StageResult stage : result.getStages()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"name\":\"").append(safe(stage.getStageName()))
              .append("\",\"worker\":\"").append(safe(stage.getWorkerId()))
              .append("\",\"status\":\"");
            if (stage.isSkipped()) {
                sb.append("skipped");
            } else if (stage.isSuccess()) {
                sb.append("ok");
            } else {
                sb.append("failed");
            }
            sb.append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 判断是否可以用 zip 推送。
     * 四个条件全部通过才返回 true。
     *
     * @param size             项目大小估算
     * @param worker           目标 Worker
     * @param estimatedZipBytes 预估 zip 大小（0 表示未知，跳过内存检查）
     */
    boolean canPushZip(ProjectSyncService.ProjectSize size, WorkerInfo worker, long estimatedZipBytes) {
        // ① 文件数限制
        if (size.fileCount > GatewayHttpServer.PROJECT_PUSH_MAX_FILES) return false;

        // ② 网络带宽限制
        long bandwidthBps = estimateBandwidthBps(worker);
        long estimatedTransferMs = (size.totalBytes * 8L * 1000L) / bandwidthBps;
        if (estimatedTransferMs >= GatewayHttpServer.MAX_TRANSFER_TIME_MS) return false;

        // ③ Gateway 内存保护（只有在已知 zip 大小时检查）
        if (estimatedZipBytes > 0) {
            long availableMem = getAvailableMemory();
            if (availableMem < estimatedZipBytes * GatewayHttpServer.MEMORY_BUFFER_FACTOR) return false;
        }

        // ④ Gateway CPU 保护
        if (getSystemCpuLoad() > GatewayHttpServer.MAX_CPU_LOAD) return false;

        return true;
    }

    /**
     * 将项目 zip 推送到 Worker。
     * HTTP POST binary body + headers
     */
    void pushProjectToWorker(WorkerInfo worker, String workspacePath, String taskId, byte[] projectZip) {
        String targetUrl = worker.getBaseUrl() + "/worker/v1/project/push";
        try {
            URL url = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("X-Workspace-Path", workspacePath);
            conn.setRequestProperty("X-Task-Id", taskId);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            // Inject security headers
            SecurityHeadersInjector pushInjector = new SecurityHeadersInjector(
                    SecurityProviderLoader.getAuthProvider(),
                    SecurityProviderLoader.getEncryptionProvider());
            pushInjector.injectIntoConnection(conn, worker.getWorkerId());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(projectZip);
            }

            int code = conn.getResponseCode();
            String responseBody;
            try (InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                responseBody = baos.toString("UTF-8");
            }
            conn.disconnect();

            if (code == 200) {
                LoggerFactory.getLogger(GatewayAdmissionControl.class).info("Project push to worker {} succeeded ({} bytes zip)", worker.getWorkerId(), projectZip.length);
                // Extract fileDiffs if present in response
                applyFileDiffsFromResponse(responseBody);
            } else {
                LoggerFactory.getLogger(GatewayAdmissionControl.class).warn("Project push to worker {} returned HTTP {}: {}", worker.getWorkerId(), code, truncate(responseBody, 200));
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayAdmissionControl.class).warn("Failed to push project to worker {}: {}", worker.getWorkerId(), e.getMessage());
        }
    }

    /**
     * 获取 Gateway 自身的基础 URL，用于 Worker 回调。
     */
    String getGatewayBaseUrl(WorkerInfo worker) {
        String host = worker.getHost();
        String gwHost = System.getProperty("gateway.host", "127.0.0.1");
        String gwPort = System.getProperty("gateway.port", String.valueOf(server.getPort()));
        return "http://" + gwHost + ":" + gwPort;
    }

    /**
     * 从 Worker response body 中提取并应用 fileDiffs。
     */
    void applyFileDiffsFromResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return;
        String diffsSection = extractFullJsonValue(responseBody, "fileDiffs");
        if (diffsSection == null || "null".equals(diffsSection) || "[]".equals(diffsSection)) return;
        try {
            List<FileDiffResult> diffs = parseFileDiffs(diffsSection);
            if (!diffs.isEmpty()) {
                server.getProjectSyncService().applyDiffs(diffs, Paths.get(resolveWorkspaceDir()));
                LoggerFactory.getLogger(GatewayAdmissionControl.class).info("Applied {} file diffs from worker response", diffs.size());
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayAdmissionControl.class).warn("Failed to apply file diffs from worker response: {}", e.getMessage());
        }
    }

    /**
     * 根据 Worker 延迟估算带宽。
     * 简单模型：延迟越低 → 带宽越高。
     * 延迟 1ms → ~100MB/s，延迟 100ms → ~1MB/s
     */
    private long estimateBandwidthBps(WorkerInfo worker) {
        double latencyMs = worker.getMetrics().getAvgLatencyMs();
        if (latencyMs <= 0) latencyMs = 1;
        return (long) Math.max(1_000_000, 100_000_000L / latencyMs);
    }

    /**
     * 获取 Gateway JVM 当前可用内存（字节）
     */
    private long getAvailableMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.freeMemory() + (rt.maxMemory() - rt.totalMemory());
    }

    /**
     * 获取 Gateway 当前 CPU 负载（0~1）
     */
    private double getSystemCpuLoad() {
        try {
            return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Proxy a chat request to the worker via HTTP POST and return the raw response JSON.
     */
    String proxyToWorker(WorkerInfo worker, String taskId, String sessionId, String message) {
        try {
            URL url = new URL(worker.getBaseUrl() + "/worker/v1/chat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(600000); // 10 min for long-running tasks

            // Inject security headers
            SecurityHeadersInjector injector = new SecurityHeadersInjector(
                    SecurityProviderLoader.getAuthProvider(),
                    SecurityProviderLoader.getEncryptionProvider());
            injector.injectIntoConnection(conn, worker.getWorkerId());

            String requestBody = "{\"taskId\":\"" + safe(taskId)
                    + "\",\"sessionId\":\"" + safe(sessionId)
                    + "\",\"message\":\"" + escapeJson(message) + "\"}";
            byte[] proxyBytes = requestBody.getBytes(StandardCharsets.UTF_8);
            java.util.Map<String, String> proxyEncHeaders = new java.util.HashMap<>();
            proxyBytes = SecurityHeadersInjector.encryptBody(proxyBytes, worker.getWorkerId(), proxyEncHeaders);
            for (java.util.Map.Entry<String, String> e : proxyEncHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(proxyBytes);
            }

            int code = conn.getResponseCode();
            String responseBody = readConnectionBody(conn, code);
            conn.disconnect();

            if (code != 200) {
                throw new IOException("Worker returned HTTP " + code + ": " + truncate(responseBody, 500));
            }

            return responseBody;
        } catch (IOException e) {
            throw new RuntimeException("Failed to proxy request to worker " + worker.getWorkerId() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 解析 JSON fileDiffs 数组为 FileDiffResult 列表。
     * 格式: [{"relativePath":"...","changeType":"MODIFIED","newContent":"...","oldContent":"..."},...]
     */
    List<FileDiffResult> parseFileDiffs(String jsonArray) {
        List<FileDiffResult> diffs = new ArrayList<>();
        if (jsonArray == null || jsonArray.isEmpty()) return diffs;
        String content = jsonArray.trim();
        if (!content.startsWith("[") || !content.endsWith("]")) return diffs;
        content = content.substring(1, content.length() - 1).trim();
        if (content.isEmpty()) return diffs;

        // Split object by object boundaries
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String objJson = content.substring(objStart, i + 1);
                    FileDiffResult diff = parseSingleDiff(objJson);
                    if (diff != null) diffs.add(diff);
                    objStart = -1;
                }
            }
        }
        return diffs;
    }

    private FileDiffResult parseSingleDiff(String objJson) {
        if (objJson == null || objJson.isEmpty()) return null;
        String relativePath = extractJsonValue(objJson, "relativePath");
        String changeType = extractJsonValue(objJson, "changeType");
        String newContent = extractJsonValue(objJson, "newContent");
        String oldContent = extractJsonValue(objJson, "oldContent");
        if (relativePath == null || changeType == null) return null;
        return new FileDiffResult(relativePath, changeType, newContent, oldContent);
    }

    /**
     * 读取最大并发请求数，优先级：SPI 自定义 > 系统属性 > 默认值 20。
     */
    int parseMaxConcurrentRequests() {
        // 1. SPI 优先级最高
        ConcurrencyControlProvider concurrencyControl = server.getConcurrencyControl();
        if (concurrencyControl != null) {
            int spiVal = concurrencyControl.getMaxConcurrentRequests();
            if (spiVal > 0) return spiVal;
        }
        // 2. 系统属性
        String val = System.getProperty("gateway.max.concurrent.requests");
        if (val != null) {
            try {
                int parsed = Integer.parseInt(val);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException e) {
                LoggerFactory.getLogger(GatewayAdmissionControl.class).warn("Invalid gateway.max.concurrent.requests: {}", val);
            }
        }
        return GatewayHttpServer.DEFAULT_MAX_CONCURRENT_REQUESTS;
    }

    int parseQueueConcurrency() {
        // Priority 1: System property
        String val = System.getProperty("gateway.queue.concurrency");
        if (val != null) {
            try {
                int parsed = Integer.parseInt(val);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException e) {
                LoggerFactory.getLogger(GatewayAdmissionControl.class).warn("Invalid gateway.queue.concurrency system prop: {}", val);
            }
        }
        // Priority 2: Environment variable
        String envVal = System.getenv("DIATOM_GATEWAY_QUEUE_CONCURRENCY");
        if (envVal != null) {
            try {
                int parsed = Integer.parseInt(envVal.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException e) {
                LoggerFactory.getLogger(GatewayAdmissionControl.class).warn("Invalid DIATOM_GATEWAY_QUEUE_CONCURRENCY env: {}", envVal);
            }
        }
        // Priority 3: Config property (from configManager)
        ConfigManager configManager = server.getConfigManager();
        if (configManager != null) {
            String cfgVal = configManager.get("gateway.queue.concurrency");
            if (cfgVal != null) {
                try {
                    int parsed = Integer.parseInt(cfgVal.trim());
                    if (parsed > 0) return parsed;
                } catch (NumberFormatException e) {
                    LoggerFactory.getLogger(GatewayAdmissionControl.class).warn("Invalid gateway.queue.concurrency config: {}", cfgVal);
                }
            }
        }
        // Default: available processors
        int defaultVal = Math.max(1, Runtime.getRuntime().availableProcessors());
        LoggerFactory.getLogger(GatewayAdmissionControl.class).info("Queue concurrency defaulting to {} (availableProcessors)", defaultVal);
        return defaultVal;
    }
}
