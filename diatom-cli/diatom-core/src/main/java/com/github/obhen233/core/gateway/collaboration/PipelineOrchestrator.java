package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.security.SecurityHeadersInjector;
import com.github.obhen233.core.gateway.security.SecurityProviderLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流水线编排引擎。
 * <p>
 * 按顺序执行 Pipeline 中的每个阶段，每个阶段选择一个最合适的 Worker
 * （先按 Predicate 过滤，再按当前负载升序排序），
 * 将上一阶段的输出作为下一阶段的上下文传入。
 */
public class PipelineOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final WorkerRegistry registry;

    public PipelineOrchestrator(WorkerRegistry registry) {
        this.registry = registry;
    }

    /**
     * 执行流水线。
     *
     * @param pipeline 流水线定义
     * @param userInput 用户原始输入
     * @return 流水线执行结果
     */
    public PipelineResult execute(Pipeline pipeline, String userInput) {
        logger.info("Executing pipeline '{}' with {} stage(s)", pipeline.getName(), pipeline.getStages().size());

        List<StageResult> stageResults = new ArrayList<>();
        Map<String, String> accumulatedResults = new LinkedHashMap<>();
        String previousResult = null;
        boolean overallSuccess = true;

        for (PipelineStage stage : pipeline.getStages()) {
            PipelineStage.PipelineContext context =
                    new PipelineStage.PipelineContext(userInput, previousResult, accumulatedResults);

            // 1. Filter workers by matcher predicate
            List<WorkerInfo> matched = registry.availableWorkers().stream()
                    .filter(stage.getMatcher())
                    .collect(Collectors.toList());

            if (matched.isEmpty()) {
                logger.warn("No available worker matching stage '{}' — skipping", stage.getName());
                stageResults.add(new StageResult(stage.getName(), null, false, null,
                        "No available worker matching predicate", true));
                continue;
            }

            // 2. Select worker with lowest current load (built-in load balancing)
            WorkerInfo selected = matched.stream()
                    .min(Comparator.comparingDouble(w -> w.getMetrics().getCurrentLoad()))
                    .orElse(matched.get(0));

            // 3. Build prompt for this stage
            String prompt = stage.getContextBuilder().apply(context);

            // 4. Send to worker
            String rawResponse;
            try {
                rawResponse = postToWorker(selected, prompt);
            } catch (Exception e) {
                logger.error("Pipeline stage '{}' failed on worker '{}': {}",
                        stage.getName(), selected.getWorkerId(), e.getMessage());
                stageResults.add(new StageResult(stage.getName(), selected.getWorkerId(),
                        false, null, e.getMessage(), false));
                overallSuccess = false;
                // Continue to next stage even if this one failed
                previousResult = null;
                continue;
            }

            // 5. Extract result
            String extracted = stage.getResultExtractor().apply(rawResponse);
            accumulatedResults.put(stage.getName(), extracted);
            previousResult = extracted;

            stageResults.add(new StageResult(stage.getName(), selected.getWorkerId(),
                    true, extracted, null, false));

            logger.info("Pipeline stage '{}' completed on worker '{}' ({} chars)",
                    stage.getName(), selected.getWorkerId(),
                    extracted != null ? extracted.length() : 0);
        }

        return new PipelineResult(pipeline.getName(), overallSuccess, stageResults);
    }

    /**
     * HTTP POST 请求到 Worker 的 /worker/v1/chat 端点。
     */
    private String postToWorker(WorkerInfo worker, String message) throws Exception {
        URL url = new URL(worker.getBaseUrl() + "/worker/v1/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(600000);

        SecurityHeadersInjector injector = new SecurityHeadersInjector(
                SecurityProviderLoader.getAuthProvider(),
                SecurityProviderLoader.getEncryptionProvider());
        injector.injectIntoConnection(conn, worker.getWorkerId());

        String requestBody = "{\"taskId\":\"\",\"message\":\""
                + escapeJson(message) + "\"}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        String responseBody = readConnectionBody(conn, code);
        conn.disconnect();

        if (code != 200) {
            throw new RuntimeException("Worker returned HTTP " + code + ": "
                    + truncate(responseBody, 500));
        }

        return responseBody;
    }

    private static String readConnectionBody(HttpURLConnection conn, int code) throws java.io.IOException {
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = is.read(buf, 0, buf.length)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 流水线执行结果。
     */
    public static class PipelineResult {
        private final String pipelineName;
        private final boolean success;
        private final List<StageResult> stages;

        public PipelineResult(String pipelineName, boolean success, List<StageResult> stages) {
            this.pipelineName = pipelineName;
            this.success = success;
            this.stages = stages;
        }

        public String getPipelineName() {
            return pipelineName;
        }

        public boolean isSuccess() {
            return success;
        }

        public List<StageResult> getStages() {
            return stages;
        }

        /**
         * 获取所有已完成（非跳过）阶段的响应文本。
         */
        public String getAllResponses() {
            StringBuilder sb = new StringBuilder();
            for (StageResult stage : stages) {
                if (stage.getResponse() != null && !stage.isSkipped()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append("=== ").append(stage.getStageName()).append(" ===\n");
                    sb.append(stage.getResponse());
                }
            }
            return sb.toString();
        }

        /**
         * 获取最后阶段的响应文本（适用于分析→执行的最终结果）。
         */
        public String getLastResponse() {
            for (int i = stages.size() - 1; i >= 0; i--) {
                if (!stages.get(i).isSkipped() && stages.get(i).getResponse() != null) {
                    return stages.get(i).getResponse();
                }
            }
            return null;
        }

        /**
         * 格式化为可读文本输出。
         */
        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("  [Pipeline] ").append(pipelineName).append("\n");
            for (int i = 0; i < stages.size(); i++) {
                StageResult s = stages.get(i);
                String status;
                if (s.isSkipped()) {
                    status = "SKIPPED";
                } else if (s.isSuccess()) {
                    status = "OK";
                } else {
                    status = "FAILED";
                }
                sb.append("  Stage ").append(i + 1).append(" [").append(s.getStageName())
                        .append("] ").append(status);
                if (s.getWorkerId() != null) {
                    sb.append(" (").append(s.getWorkerId()).append(")");
                }
                sb.append("\n");
                if (s.getError() != null) {
                    sb.append("    Error: ").append(s.getError()).append("\n");
                }
                if (s.getResponse() != null && !s.isSkipped()) {
                    // Print first 200 chars as preview
                    String preview = s.getResponse().length() > 200
                            ? s.getResponse().substring(0, 200) + "..."
                            : s.getResponse();
                    sb.append("    ").append(preview.replace("\n", "\n    ")).append("\n");
                }
            }
            return sb.toString();
        }
    }

    /**
     * 单个阶段的执行结果。
     */
    public static class StageResult {
        private final String stageName;
        private final String workerId;
        private final boolean success;
        private final String response;
        private final String error;
        private final boolean skipped;

        public StageResult(String stageName, String workerId, boolean success,
                           String response, String error, boolean skipped) {
            this.stageName = stageName;
            this.workerId = workerId;
            this.success = success;
            this.response = response;
            this.error = error;
            this.skipped = skipped;
        }

        public String getStageName() { return stageName; }
        public String getWorkerId() { return workerId; }
        public boolean isSuccess() { return success; }
        public String getResponse() { return response; }
        public String getError() { return error; }
        public boolean isSkipped() { return skipped; }
    }
}
