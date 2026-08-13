package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 流水线阶段定义。
 * <p>
 * 每个阶段包含 Worker 过滤条件、上下文构建器和结果提取器，
 * 由 {@link PipelineOrchestrator} 顺序执行。
 */
public class PipelineStage {

    private final String name;
    private final Predicate<WorkerInfo> matcher;
    private final Function<PipelineContext, String> contextBuilder;
    private final Function<String, String> resultExtractor;

    public PipelineStage(String name,
                          Predicate<WorkerInfo> matcher,
                          Function<PipelineContext, String> contextBuilder,
                          Function<String, String> resultExtractor) {
        this.name = name;
        this.matcher = matcher;
        this.contextBuilder = contextBuilder;
        this.resultExtractor = resultExtractor;
    }

    public String getName() {
        return name;
    }

    public Predicate<WorkerInfo> getMatcher() {
        return matcher;
    }

    public Function<PipelineContext, String> getContextBuilder() {
        return contextBuilder;
    }

    public Function<String, String> getResultExtractor() {
        return resultExtractor;
    }

    /**
     * 默认结果提取器：从 Worker 返回的 JSON 中提取 "response" 字段。
     */
    public static String extractResponse(String rawResponse) {
        if (rawResponse == null) return null;
        String search = "\"response\":\"";
        int start = rawResponse.indexOf(search);
        if (start < 0) {
            search = "\"response\": \"";
            start = rawResponse.indexOf(search);
        }
        if (start < 0) return rawResponse;
        start += search.length();
        int end = rawResponse.indexOf("\"", start);
        if (end < 0) return rawResponse;
        return rawResponse.substring(start, end);
    }

    /**
     * 流水线上下文，在阶段间传递。
     */
    public static class PipelineContext {
        private final String userInput;
        private final String previousResult;
        private final Map<String, String> stageResults;

        public PipelineContext(String userInput) {
            this.userInput = userInput;
            this.previousResult = null;
            this.stageResults = new LinkedHashMap<>();
        }

        public PipelineContext(String userInput, String previousResult, Map<String, String> stageResults) {
            this.userInput = userInput;
            this.previousResult = previousResult;
            this.stageResults = stageResults;
        }

        public String getUserInput() {
            return userInput;
        }

        public String getPreviousResult() {
            return previousResult;
        }

        public Map<String, String> getStageResults() {
            return stageResults;
        }
    }
}
