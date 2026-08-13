package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.routing.TaskRequirement;

import java.util.List;
import java.util.Map;

/**
 * 多 Worker 并行协同 SPI 接口
 * 将复杂任务分解为多个子任务，分配给不同 Worker 并行执行
 *
 * P6 预留接口，当前提供默认的串行实现
 */
public interface WorkerCoordinator {

    /**
     * 将任务分解为子任务需求列表
     */
    List<SubTask> decompose(String originalTask, TaskRequirement requirement);

    /**
     * 为每个子任务选择合适的 Worker
     */
    List<WorkerInfo> assignWorkers(List<SubTask> subTasks, List<WorkerInfo> availableWorkers);

    /**
     * 合并子任务结果
     */
    String mergeResults(List<SubTaskResult> results);

    /**
     * 合并子任务结果，附带文件 diff 信息。
     * 默认实现忽略 diff，调用原有的 mergeResults。
     */
    default String mergeResults(List<SubTaskResult> results, Map<String, List<FileDiff>> diffs) {
        return mergeResults(results);
    }

    /**
     * 子任务定义
     */
    class SubTask {
        private final String subTaskId;
        private final String description;
        private final String parentTaskId;
        private final int order;

        public SubTask(String subTaskId, String description, String parentTaskId, int order) {
            this.subTaskId = subTaskId;
            this.description = description;
            this.parentTaskId = parentTaskId;
            this.order = order;
        }

        public String getSubTaskId() { return subTaskId; }
        public String getDescription() { return description; }
        public String getParentTaskId() { return parentTaskId; }
        public int getOrder() { return order; }
    }

    /**
     * 子任务结果
     */
    class SubTaskResult {
        private final String subTaskId;
        private final boolean success;
        private final String summary;
        private final String detail;
        private final int order;

        public SubTaskResult(String subTaskId, boolean success, String summary, String detail) {
            this(subTaskId, success, summary, detail, 0);
        }

        public SubTaskResult(String subTaskId, boolean success, String summary, String detail, int order) {
            this.subTaskId = subTaskId;
            this.success = success;
            this.summary = summary;
            this.detail = detail;
            this.order = order;
        }

        public String getSubTaskId() { return subTaskId; }
        public boolean isSuccess() { return success; }
        public String getSummary() { return summary; }
        public String getDetail() { return detail; }
        public int getOrder() { return order; }
    }
}
