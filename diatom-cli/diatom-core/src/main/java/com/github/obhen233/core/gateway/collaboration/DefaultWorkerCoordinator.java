package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.http.dto.CollaborationSummaryResponse;
import com.github.obhen233.core.gateway.http.dto.FileConflictResponse;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.routing.ScoreCalculator;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认多 Worker 并行协同实现
 *
 * 将复杂任务按语义拆分为多个独立子任务，分配给不同 Worker 并行执行，
 * 最后合并各子任务结果为完整输出。
 *
 * P6 实现，提供基础的分解/分配/合并逻辑
 */
public class DefaultWorkerCoordinator implements WorkerCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(DefaultWorkerCoordinator.class);

    private static final int MAX_SUB_TASKS = 5;
    private static final Map<String, List<String>> TASK_TYPE_SPLITS = new HashMap<>();
    static {
        TASK_TYPE_SPLITS.put("bug_fix", Arrays.asList("root_cause_analysis", "fix_implementation", "testing"));
        TASK_TYPE_SPLITS.put("code_review", Arrays.asList("security_audit", "performance_analysis", "style_check", "logic_review"));
        TASK_TYPE_SPLITS.put("feature_development", Arrays.asList("requirement_analysis", "design", "implementation", "testing"));
        TASK_TYPE_SPLITS.put("data_analysis", Arrays.asList("data_collection", "cleaning", "analysis", "visualization", "reporting"));
        TASK_TYPE_SPLITS.put("documentation", Arrays.asList("outline", "drafting", "review", "finalization"));
    }

    /**
     * 将原始任务分解为多个独立子任务
     * 三种策略按优先级：
     * 1. requirement 有 capabilities → 每个 capability 一个子任务（最多 5 个）
     * 2. taskType 已知 → 按功能领域拆分
     * 3. 回退 → 单子任务包含全部请求
     */
    @Override
    public List<SubTask> decompose(String originalTask, TaskRequirement requirement) {
        List<SubTask> subTasks = new ArrayList<>();
        if (originalTask == null || originalTask.trim().isEmpty()) {
            return subTasks;
        }

        String parentTaskId = "task-" + System.currentTimeMillis();

        // Strategy 1: Capability-based decomposition
        if (requirement != null && requirement.getRequiredCapabilities() != null
                && !requirement.getRequiredCapabilities().isEmpty()) {
            int count = 0;
            for (String capability : requirement.getRequiredCapabilities()) {
                if (count >= MAX_SUB_TASKS) break;
                String subTaskId = "sub-" + UUID.randomUUID().toString().substring(0, 8);
                subTasks.add(new SubTask(subTaskId,
                        "[" + capability + "] " + originalTask,
                        parentTaskId, count));
                count++;
            }
            logger.debug("Decomposed by capabilities into {} sub-tasks", subTasks.size());
            return subTasks;
        }

        // Strategy 2: Task-type based decomposition
        if (requirement != null && requirement.getTaskType() != null) {
            List<String> splits = TASK_TYPE_SPLITS.get(requirement.getTaskType());
            if (splits != null) {
                int order = 0;
                for (String area : splits) {
                    if (order >= MAX_SUB_TASKS) break;
                    String subTaskId = "sub-" + UUID.randomUUID().toString().substring(0, 8);
                    subTasks.add(new SubTask(subTaskId,
                            "[" + area + "] " + originalTask,
                            parentTaskId, order));
                    order++;
                }
                logger.debug("Decomposed by task type '{}' into {} sub-tasks",
                        requirement.getTaskType(), subTasks.size());
                return subTasks;
            }
        }

        // Strategy 3: Fallback — single sub-task with full request
        String subTaskId = "sub-" + UUID.randomUUID().toString().substring(0, 8);
        subTasks.add(new SubTask(subTaskId, originalTask, parentTaskId, 0));
        logger.debug("Fallback: single sub-task (no decomposition strategy matched)");
        return subTasks;
    }

    /**
     * 为每个子任务选择合适的 Worker
     * 基于能力评分：为每个子任务构建 TaskRequirement，使用 ScoreCalculator 评分，
     * 选择最匹配的 Worker，同时考虑 maxConcurrency 限制
     */
    @Override
    public List<WorkerInfo> assignWorkers(List<SubTask> subTasks, List<WorkerInfo> availableWorkers) {
        List<WorkerInfo> assigned = new ArrayList<>();
        if (subTasks.isEmpty() || availableWorkers.isEmpty()) return assigned;

        ScoreCalculator calculator = new ScoreCalculator();
        Map<String, Integer> workerLoad = new HashMap<>();

        for (WorkerInfo w : availableWorkers) {
            workerLoad.put(w.getWorkerId(), w.getMetrics().getActiveTasks());
        }

        for (SubTask subTask : subTasks) {
            // Build per-sub-task TaskRequirement from description
            TaskRequirement subReq = buildSubRequirement(subTask.getDescription());

            // Score all workers, sort descending
            List<WorkerInfo> scored = availableWorkers.stream()
                    .sorted(Comparator.comparingDouble(w -> -calculator.calculate(w, subReq)))
                    .collect(Collectors.toList());

            // Pick best worker respecting maxConcurrency
            WorkerInfo best = null;
            for (WorkerInfo w : scored) {
                int currentLoad = workerLoad.getOrDefault(w.getWorkerId(), 0);
                if (currentLoad < w.getMaxConcurrency()) {
                    best = w;
                    workerLoad.put(w.getWorkerId(), currentLoad + 1);
                    break;
                }
            }

            // Fallback to best-score worker if all at capacity
            if (best == null && !scored.isEmpty()) {
                best = scored.get(0);
                logger.warn("All workers at capacity, assigning sub-task {} to best-score worker {}",
                        subTask.getSubTaskId(), best.getWorkerId());
            }

            assigned.add(best);
        }

        logger.debug("Assigned {} sub-tasks to workers", subTasks.size());
        return assigned;
    }

    /**
     * 合并子任务结果为结构化 JSON 输出
     * 按 order 排序，输出 collaboration_summary + results 数组
     */
    @Override
    public String mergeResults(List<SubTaskResult> results) {
        if (results == null || results.isEmpty()) return "{}";

        CollaborationSummaryResponse resp = buildCollaborationResponse(results);
        logger.info("Merged {} sub-task results: {} success, {} failed",
                results.size(), resp.collaborationSummary.success, resp.collaborationSummary.failed);
        return JsonUtils.toJson(resp);
    }

    /**
     * Build a CollaborationSummaryResponse from sub-task results.
     */
    private static CollaborationSummaryResponse buildCollaborationResponse(List<SubTaskResult> results) {
        if (results == null || results.isEmpty()) return new CollaborationSummaryResponse();

        // Sort by order field
        results.sort(Comparator.comparingInt(SubTaskResult::getOrder));

        CollaborationSummaryResponse resp = new CollaborationSummaryResponse();
        CollaborationSummaryResponse.CollaborationSummary summary = new CollaborationSummaryResponse.CollaborationSummary();
        summary.total = results.size();

        java.util.List<CollaborationSummaryResponse.SubTaskResult> resultList = new java.util.ArrayList<>();

        for (SubTaskResult result : results) {
            CollaborationSummaryResponse.SubTaskResult r = new CollaborationSummaryResponse.SubTaskResult();
            r.subTaskId = result.getSubTaskId();
            r.success = result.isSuccess();
            r.summary = result.getSummary();
            r.detail = truncate(result.getDetail(), 10000);
            resultList.add(r);

            if (result.isSuccess()) {
                summary.success++;
            } else {
                summary.failed++;
            }
        }

        resp.collaborationSummary = summary;
        resp.results = resultList;
        return resp;
    }

    /**
     * 合并子任务结果，附带文件变更 diff。
     *
     * 当沙箱策略产生文件 diff 时，检测冲突并决定合并方式：
     * - 无冲突：直接应用所有 diff 到项目文件
     * - 有冲突：在结果中包含冲突标记，由 LLM 层裁决
     */
    @Override
    public String mergeResults(List<SubTaskResult> results, Map<String, List<FileDiff>> diffs) {
        // 无 diff → 使用原始文本合并
        if (diffs == null || diffs.isEmpty() || allDiffsEmpty(diffs)) {
            return mergeResults(results);
        }

        // 检测文件冲突
        Map<String, List<FileDiff>> fileConflicts = detectFileConflicts(diffs);

        if (fileConflicts.isEmpty()) {
            // 无冲突：直接应用所有 diff
            directApplyChanges(diffs);
            logger.info("Applied {} file changes directly (no conflicts)", countTotalDiffs(diffs));
            return mergeResults(results);
        }

        // 有冲突：在合并结果中包含冲突信息
        logger.warn("Detected {} file conflicts, including in merge result", fileConflicts.size());
        return mergeWithConflicts(results, diffs, fileConflicts);
    }

    /**
     * 检测文件冲突：同一文件被多个子任务修改。
     */
    static Map<String, List<FileDiff>> detectFileConflicts(Map<String, List<FileDiff>> diffs) {
        Map<String, List<FileDiff>> byFile = new LinkedHashMap<>();
        for (List<FileDiff> fileDiffs : diffs.values()) {
            for (FileDiff diff : fileDiffs) {
                byFile.computeIfAbsent(diff.getRelativePath(), k -> new ArrayList<>()).add(diff);
            }
        }

        Map<String, List<FileDiff>> conflicts = new LinkedHashMap<>();
        for (Map.Entry<String, List<FileDiff>> entry : byFile.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.put(entry.getKey(), entry.getValue());
            }
        }
        return conflicts;
    }

    /**
     * 直接应用变更到项目文件（无冲突场景）。
     */
    static void directApplyChanges(Map<String, List<FileDiff>> diffs) {
        String workspaceDir = System.getProperty("diatom.workspace.dir", ".");
        Path projectRoot = Paths.get(workspaceDir);

        for (List<FileDiff> fileDiffs : diffs.values()) {
            for (FileDiff diff : fileDiffs) {
                if (diff == null) continue;
                applySingleDiff(diff, projectRoot);
            }
        }
    }

    private static void applySingleDiff(FileDiff diff, Path projectRoot) {
        Path targetFile = projectRoot.resolve(diff.getRelativePath().replace('/', java.io.File.separatorChar));

        try {
            switch (diff.getChangeType()) {
                case CREATED:
                case MODIFIED:
                    if (diff.getNewContent() != null) {
                        Files.createDirectories(targetFile.getParent());
                        Files.write(targetFile, diff.getNewContent().getBytes(StandardCharsets.UTF_8));
                    }
                    break;
                case DELETED:
                    Files.deleteIfExists(targetFile);
                    break;
            }
        } catch (IOException e) {
            // Log and continue — don't fail the whole merge for one file
            java.util.logging.Logger.getLogger(DefaultWorkerCoordinator.class.getName())
                    .warning("Failed to apply diff to " + diff.getRelativePath() + ": " + e.getMessage());
        }
    }

    /**
     * 有冲突时生成包含冲突信息的合并结果。
     */
    private String mergeWithConflicts(List<SubTaskResult> results,
                                       Map<String, List<FileDiff>> diffs,
                                       Map<String, List<FileDiff>> conflicts) {
        CollaborationSummaryResponse resp = buildCollaborationResponse(results);

        // Build file conflict list
        java.util.List<FileConflictResponse> conflictList = new java.util.ArrayList<>();
        for (Map.Entry<String, List<FileDiff>> entry : conflicts.entrySet()) {
            FileConflictResponse fcr = new FileConflictResponse();
            fcr.file = entry.getKey();
            java.util.List<FileConflictResponse.ConflictModification> mods = new java.util.ArrayList<>();
            for (FileDiff diff : entry.getValue()) {
                FileConflictResponse.ConflictModification mod = new FileConflictResponse.ConflictModification();
                mod.subTaskId = diff.getSubTaskId();
                mod.changeType = diff.getChangeType().name();
                if (diff.getChangeType() == FileDiff.ChangeType.MODIFIED) {
                    mod.unifiedDiff = truncate(diff.getUnifiedDiff(), 2000);
                    mod.hasOldContent = diff.getOldContent() != null;
                    mod.hasNewContent = diff.getNewContent() != null;
                }
                mods.add(mod);
            }
            fcr.modifications = mods;
            conflictList.add(fcr);
        }

        resp.fileConflicts = conflictList;
        resp.conflictResolutionNeeded = true;
        return JsonUtils.toJson(resp);
    }

    private static boolean allDiffsEmpty(Map<String, List<FileDiff>> diffs) {
        for (List<FileDiff> list : diffs.values()) {
            if (list != null && !list.isEmpty()) return false;
        }
        return true;
    }

    private static int countTotalDiffs(Map<String, List<FileDiff>> diffs) {
        int count = 0;
        for (List<FileDiff> list : diffs.values()) {
            if (list != null) count += list.size();
        }
        return count;
    }

    private static TaskRequirement buildSubRequirement(String description) {
        TaskRequirement req = new TaskRequirement();
        if (description != null && description.startsWith("[") && description.contains("]")) {
            int end = description.indexOf(']');
            String capability = description.substring(1, end).trim();
            List<String> caps = new ArrayList<>();
            caps.add(capability);
            req.setRequiredCapabilities(caps);
        }
        return req;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
