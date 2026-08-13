package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 流水线定义，包含按顺序执行的多个阶段。
 * <p>
 * 使用 Builder 模式构建：
 * <pre>{@code
 * Pipeline pipeline = Pipeline.builder("analysis-execution")
 *     .stage("analysis",
 *         w -> w.getCostPer1kTokens() >= 0.01,
 *         ctx -> ctx.getUserInput(),
 *         PipelineStage::extractResponse)
 *     .stage("execution",
 *         w -> w.getCostPer1kTokens() < 0.01,
 *         ctx -> "Previous analysis:\n" + ctx.getPreviousResult(),
 *         PipelineStage::extractResponse)
 *     .build();
 * }</pre>
 */
public class Pipeline {

    private final String name;
    private final List<PipelineStage> stages;

    public Pipeline(String name, List<PipelineStage> stages) {
        this.name = name;
        this.stages = Collections.unmodifiableList(new ArrayList<>(stages));
    }

    public String getName() {
        return name;
    }

    public List<PipelineStage> getStages() {
        return stages;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Pipeline Builder
     */
    public static class Builder {
        private final String name;
        private final List<PipelineStage> stages = new ArrayList<>();

        public Builder(String name) {
            this.name = name;
        }

        /**
         * 添加一个阶段。
         *
         * @param name            阶段名称
         * @param matcher         Worker 过滤谓词
         * @param contextBuilder  上下文转 prompt 函数
         * @param resultExtractor 原始响应转结果函数
         */
        public Builder stage(String name,
                              Predicate<WorkerInfo> matcher,
                              Function<PipelineStage.PipelineContext, String> contextBuilder,
                              Function<String, String> resultExtractor) {
            stages.add(new PipelineStage(name, matcher, contextBuilder, resultExtractor));
            return this;
        }

        public Pipeline build() {
            return new Pipeline(name, stages);
        }
    }
}
