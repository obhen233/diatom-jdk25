package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.core.gateway.sync.FileDiffResult;
import com.github.obhen233.core.gateway.sync.ProjectSyncService;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.spi.IsolationContext;
import com.github.obhen233.spi.ResourceContentionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 复合任务管理器
 * 支持将复杂任务分解为并行子任务，在多个 Worker 上协同执行
 *
 * P6 预留接口，继承 TaskManager 并添加协同能力
 */
public class CompositeTaskManager extends TaskManager {
    private static final Logger logger = LoggerFactory.getLogger(CompositeTaskManager.class);

    private final WorkerCoordinator coordinator;
    private final ParallelTaskExecutor parallelExecutor;
    private final CapabilityRouter router;
    private final WorkerRegistry registry;
    private final WorkspaceManager workspaceManager;
    private final WorkspaceRouter workspaceRouter;
    private final ResourceContentionProvider contentionProvider;
    private final ProjectSyncService projectSyncService;

    /**
     * 可复用的 RemoteSandboxStrategy 实例（跨轮协作保持 lastPushedSnapshots）。
     * 懒加载，首次检测到远程 Worker 时创建。
     */
    private volatile RemoteSandboxStrategy remoteSandboxStrategy;

    public CompositeTaskManager(WorkerCoordinator coordinator,
                                 ParallelTaskExecutor parallelExecutor,
                                 CapabilityRouter router,
                                 WorkerRegistry registry) {
        this(coordinator, parallelExecutor, router, registry, new WorkspaceManager(), null, null, null);
    }

    public CompositeTaskManager(WorkerCoordinator coordinator,
                                 ParallelTaskExecutor parallelExecutor,
                                 CapabilityRouter router,
                                 WorkerRegistry registry,
                                 WorkspaceManager workspaceManager) {
        this(coordinator, parallelExecutor, router, registry, workspaceManager, null, null, null);
    }

    public CompositeTaskManager(WorkerCoordinator coordinator,
                                 ParallelTaskExecutor parallelExecutor,
                                 CapabilityRouter router,
                                 WorkerRegistry registry,
                                 WorkspaceManager workspaceManager,
                                 WorkspaceRouter workspaceRouter,
                                 ResourceContentionProvider contentionProvider) {
        this(coordinator, parallelExecutor, router, registry, workspaceManager, workspaceRouter, contentionProvider, null);
    }

    public CompositeTaskManager(WorkerCoordinator coordinator,
                                 ParallelTaskExecutor parallelExecutor,
                                 CapabilityRouter router,
                                 WorkerRegistry registry,
                                 WorkspaceManager workspaceManager,
                                 WorkspaceRouter workspaceRouter,
                                 ResourceContentionProvider contentionProvider,
                                 ProjectSyncService projectSyncService) {
        this.coordinator = coordinator;
        this.parallelExecutor = parallelExecutor;
        this.router = router;
        this.registry = registry;
        this.workspaceManager = workspaceManager;
        this.workspaceRouter = workspaceRouter != null
                ? workspaceRouter : new WorkspaceRouter(workspaceManager);
        this.contentionProvider = contentionProvider;
        this.projectSyncService = projectSyncService;
    }

    public CompositeTaskManager(WorkerCoordinator coordinator,
                                 ParallelTaskExecutor parallelExecutor,
                                 CapabilityRouter router,
                                 WorkerRegistry registry,
                                 WorkspaceManager workspaceManager,
                                 WorkspaceRouter workspaceRouter) {
        this(coordinator, parallelExecutor, router, registry, workspaceManager, workspaceRouter, null, null);
    }

    /**
     * 提交并行协同任务（使用默认 projectRoot）
     */
    public String submitCollaborativeTask(String sessionId, String request,
                                           TaskRequirement requirement) {
        Path projectRoot = Paths.get(System.getProperty("diatom.workspace.dir", "."));
        return submitCollaborativeTask(sessionId, request, requirement, projectRoot);
    }

    /**
     * 提交并行协同任务（指定 projectRoot）
     * 将任务分解后分配到多个 Worker 执行，最后合并结果
     */
    public String submitCollaborativeTask(String sessionId, String request,
                                           TaskRequirement requirement, Path projectRoot) {
        String parentTaskId = createTask(sessionId, request);
        logger.info("Collaborative task created: {}, decomposing...", parentTaskId);

        // 0. 分解任务
        List<WorkerCoordinator.SubTask> subTasks = coordinator.decompose(request, requirement);
        logger.info("Task decomposed into {} sub-tasks", subTasks.size());

        if (subTasks.isEmpty()) {
            completeTask(parentTaskId);
            return "{\"collaboration_summary\":{\"total\":0,\"success\":0,\"failed\":0},\"results\":[]}";
        }

        // 1. 分配 Worker
        List<WorkerInfo> available = registry.availableWorkers();
        List<WorkerInfo> assigned = coordinator.assignWorkers(subTasks, available);

        // 检测远程 Worker，需要启用 RemoteSandboxStrategy
        ResourceContentionProvider effectiveProvider = contentionProvider;
        boolean hasRemoteWorker = false;
        if (projectSyncService != null) {
            for (WorkerInfo worker : assigned) {
                if (!RemoteSandboxStrategy.isLocalHost(worker.getHost())) {
                    hasRemoteWorker = true;
                    break;
                }
            }
            if (hasRemoteWorker) {
                // 复用已有的 RemoteSandboxStrategy 实例，保持 lastPushedSnapshots 跨轮积累
                if (remoteSandboxStrategy == null) {
                    Path sandboxBaseDir = projectRoot.resolve(".diatom").resolve("sandbox");
                    remoteSandboxStrategy = new RemoteSandboxStrategy(projectSyncService, sandboxBaseDir);
                    logger.info("Created reusable RemoteSandboxStrategy (persistent across collaboration rounds)");
                }
                effectiveProvider = remoteSandboxStrategy;
            }
        }

        // 2. 准备隔离环境（SPI 策略：沙箱/锁/组合 或远程沙箱）
        IsolationContext isolationContext = null;
        List<String> workspacePaths;
        List<String> fileManifests = null;

        if (effectiveProvider != null) {
            isolationContext = effectiveProvider.prepareEnvironment(
                    parentTaskId, subTasks, assigned, projectRoot);
            // 使用策略提供的 workspace 路径
            workspacePaths = new ArrayList<>();
            for (WorkerCoordinator.SubTask subTask : subTasks) {
                String wsPath = effectiveProvider.resolveWorkerWorkspacePath(subTask, isolationContext);
                workspacePaths.add(wsPath);
            }

            // 沙箱策略需要同时传递文件清单（Worker 本地无项目文件时可从 Gateway 拉取）
            String strategyType = isolationContext.getStrategyType();
            if ("sandbox".equals(strategyType) || "composite".equals(strategyType)
                    || "remote-sandbox".equals(strategyType)) {
                String manifestJson = WorkerWorkspaceResolver.buildFileManifestJson(projectRoot);
                fileManifests = new ArrayList<>();
                for (int i = 0; i < subTasks.size(); i++) {
                    fileManifests.add(manifestJson);
                }
            }
        } else {
            // 无策略时，按 worker group 解析工作空间路径（同 group 共享，不同 group 隔离）
            workspacePaths = resolveWorkspacePaths(assigned);
        }

        // 3. 并行执行（每个 worker 拿到各自的 workspace 路径和文件清单）
        List<WorkerCoordinator.SubTaskResult> results = parallelExecutor.executeParallel(
                subTasks, assigned, 10, java.util.concurrent.TimeUnit.MINUTES,
                workspacePaths, fileManifests);

        // 3b. 从 Worker 响应中提取 fileDiffs 并应用到 Gateway 本地项目
        //     确保多轮协作时 Gateway 项目文件始终与 Worker 输出保持一致
        if (projectSyncService != null && hasRemoteWorker) {
            applyFileDiffsFromResults(results, projectRoot);
            // 同步更新 RemoteSandboxStrategy 中的快照，
            // 确保下一轮增量推送基于最新状态计算
            if (remoteSandboxStrategy != null) {
                for (WorkerInfo worker : assigned) {
                    remoteSandboxStrategy.updateSnapshot(worker.getWorkerId(), projectRoot);
                }
            }
        }

        // 4. 收集变更 diff 并合并结果
        Map<String, List<FileDiff>> diffs = null;
        if (effectiveProvider != null && isolationContext != null) {
            diffs = effectiveProvider.collectChanges(parentTaskId, subTasks, isolationContext);
        }
        String merged = coordinator.mergeResults(results, diffs);

        // 5. 完成主任务
        TaskState parent = getTask(parentTaskId);
        if (parent != null) {
            parent.addAttribute("collaborationResults", results.size() + " sub-tasks");
        }
        completeTask(parentTaskId);

        // 6. 清理隔离环境
        if (effectiveProvider != null && isolationContext != null) {
            try {
                effectiveProvider.cleanup(parentTaskId, subTasks, isolationContext);
            } catch (Exception e) {
                logger.warn("Failed to cleanup isolation context: {}", e.getMessage());
            }
        }

        // 7. 释放 workgroup 工作空间
        releaseGroupWorkspaces(assigned);

        logger.info("Collaborative task {} completed with {} sub-task results",
                parentTaskId, results.size());
        return merged;
    }

    /**
     * 为每个分配的 worker 解析工作空间路径
     * 有 group 的 worker → 解析到 group 级别工作空间（同组共享）
     * 无 group 的 worker → null（无工作空间）
     */
    private List<String> resolveWorkspacePaths(List<WorkerInfo> assigned) {
        List<String> paths = new ArrayList<>();
        for (WorkerInfo worker : assigned) {
            String wsPath = workspaceRouter.resolveWorkspace(worker);
            paths.add(wsPath);
        }
        return paths;
    }

    /**
     * 释放所有分配的 worker 所属 group 的工作空间
     */
    private void releaseGroupWorkspaces(List<WorkerInfo> assigned) {
        for (WorkerInfo worker : assigned) {
            String group = worker.getGroup();
            if (group != null && !group.isEmpty()) {
                workspaceRouter.releaseGroup(group);
            }
        }
    }

    /**
     * 从子任务执行结果中提取 fileDiffs 并应用到 Gateway 本地项目目录。
     * 解决多轮协作时 Gateway 项目文件与 Worker 输出不一致的问题。
     * <p>
     * Worker 返回的 chat response JSON 中包含 fileDiffs 数组，
     * 格式: [{"relativePath":"...","changeType":"MODIFIED","newContent":"..."}]
     */
    private void applyFileDiffsFromResults(List<WorkerCoordinator.SubTaskResult> results, Path projectRoot) {
        for (WorkerCoordinator.SubTaskResult result : results) {
            if (!result.isSuccess()) continue;

            String detail = result.getDetail();
            if (detail == null || detail.isEmpty()) continue;

            // 从 JSON response 中提取 "fileDiffs":[{...}] 部分
            String diffsSection = extractFullJsonValue(detail, "fileDiffs");
            if (diffsSection == null || "null".equals(diffsSection) || "[]".equals(diffsSection)) continue;

            try {
                List<FileDiffResult> diffs = parseFileDiffs(diffsSection);
                if (!diffs.isEmpty()) {
                    projectSyncService.applyDiffs(diffs, projectRoot);
                    logger.info("Applied {} file diffs from sub-task {} to project {}",
                            diffs.size(), result.getSubTaskId(), projectRoot);
                }
            } catch (Exception e) {
                logger.warn("Failed to apply file diffs from sub-task {}: {}",
                        result.getSubTaskId(), e.getMessage());
            }
        }
    }

    /**
     * 从 JSON 字符串中提取指定 key 的完整值（支持嵌套对象/数组）。
     */
    private static String extractFullJsonValue(String json, String key) {
        if (json == null || key == null) return null;
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;

        char first = json.charAt(start);
        if (first == '[') {
            // 提取完整数组
            int depth = 0;
            int end = start;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) { end = i + 1; break; }
                }
            }
            return json.substring(start, end);
        } else if (first == '{') {
            int depth = 0;
            int end = start;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end = i + 1; break; }
                }
            }
            return json.substring(start, end);
        } else if (first == '"') {
            int end = json.indexOf("\"", start + 1);
            return end > start ? json.substring(start + 1, end) : null;
        } else {
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            if (end < 0) end = json.length();
            return json.substring(start, end).trim();
        }
    }

    /**
     * 解析 JSON fileDiffs 数组为 FileDiffResult 列表。
     * 格式: [{"relativePath":"...","changeType":"MODIFIED","newContent":"...","oldContent":"..."}]
     */
    private static List<FileDiffResult> parseFileDiffs(String jsonArray) {
        List<FileDiffResult> diffs = new ArrayList<>();
        if (jsonArray == null || jsonArray.isEmpty()) return diffs;
        String content = jsonArray.trim();
        if (!content.startsWith("[") || !content.endsWith("]")) return diffs;
        content = content.substring(1, content.length() - 1).trim();
        if (content.isEmpty()) return diffs;

        // Split by object boundaries
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

    private static FileDiffResult parseSingleDiff(String objJson) {
        if (objJson == null || objJson.isEmpty()) return null;
        String relativePath = extractJsonValue(objJson, "relativePath");
        String changeType = extractJsonValue(objJson, "changeType");
        String newContent = extractJsonValue(objJson, "newContent");
        String oldContent = extractJsonValue(objJson, "oldContent");
        if (relativePath == null || changeType == null) return null;
        return new FileDiffResult(relativePath, changeType, newContent, oldContent);
    }

    /**
     * 从 JSON 字符串中提取指定 key 的简单字符串值（非嵌套）。
     */
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
        parallelExecutor.shutdown();
        workspaceRouter.releaseAll();
    }
}
