package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.spi.IsolationContext;
import com.github.obhen233.spi.ResourceContentionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 沙箱隔离策略。
 *
 * 为每个子任务创建独立的沙箱目录（项目文件的符号链接或副本），
 * 使各 Worker 在隔离环境中修改文件副本。
 * 执行完成后通过 diff 收集变更。
 */
public class SandboxStrategy implements ResourceContentionProvider {
    private static final Logger logger = LoggerFactory.getLogger(SandboxStrategy.class);

    private final SandboxWorkspaceManager sandboxManager;

    public SandboxStrategy(SandboxWorkspaceManager sandboxManager) {
        this.sandboxManager = sandboxManager;
    }

    @Override
    public IsolationContext prepareEnvironment(String taskId,
                                                List<WorkerCoordinator.SubTask> subTasks,
                                                List<WorkerInfo> assignedWorkers,
                                                Path projectRoot) {
        IsolationContext context = new IsolationContext("sandbox");

        for (int i = 0; i < subTasks.size(); i++) {
            WorkerCoordinator.SubTask subTask = subTasks.get(i);
            try {
                Path sandboxPath = sandboxManager.createSandbox(projectRoot, taskId, subTask.getSubTaskId());
                context.addSandboxPath(subTask.getSubTaskId(), sandboxPath);
                logger.debug("Created sandbox for sub-task {} at {}", subTask.getSubTaskId(), sandboxPath);
            } catch (IOException e) {
                logger.error("Failed to create sandbox for sub-task {}: {}", subTask.getSubTaskId(), e.getMessage());
            }
        }

        return context;
    }

    @Override
    public Map<String, List<FileDiff>> collectChanges(String taskId,
                                                        List<WorkerCoordinator.SubTask> subTasks,
                                                        IsolationContext context) {
        Map<String, List<FileDiff>> result = new LinkedHashMap<>();
        Path projectRoot = Paths.get(System.getProperty("diatom.workspace.dir", "."));

        for (WorkerCoordinator.SubTask subTask : subTasks) {
            Path sandboxPath = context.getSandboxPath(subTask.getSubTaskId());
            if (sandboxPath == null || !sandboxPath.toFile().exists()) {
                result.put(subTask.getSubTaskId(), Collections.emptyList());
                continue;
            }

            try {
                List<FileDiff> diffs = sandboxManager.collectChanges(sandboxPath, projectRoot, subTask.getSubTaskId());
                // 过滤掉 null（未变更的文件）
                List<FileDiff> nonNullDiffs = new ArrayList<>();
                for (FileDiff d : diffs) {
                    if (d != null) nonNullDiffs.add(d);
                }
                result.put(subTask.getSubTaskId(), nonNullDiffs);
                logger.debug("Collected {} changes for sub-task {}", nonNullDiffs.size(), subTask.getSubTaskId());
            } catch (IOException e) {
                logger.warn("Failed to collect changes for sub-task {}: {}", subTask.getSubTaskId(), e.getMessage());
                result.put(subTask.getSubTaskId(), Collections.emptyList());
            }
        }

        return result;
    }

    @Override
    public void cleanup(String taskId, List<WorkerCoordinator.SubTask> subTasks, IsolationContext context) {
        for (WorkerCoordinator.SubTask subTask : subTasks) {
            Path sandboxPath = context.getSandboxPath(subTask.getSubTaskId());
            if (sandboxPath != null) {
                sandboxManager.releaseSandbox(sandboxPath);
            }
        }
    }

    @Override
    public String resolveWorkerWorkspacePath(WorkerCoordinator.SubTask subTask, IsolationContext context) {
        Path sandboxPath = context.getSandboxPath(subTask.getSubTaskId());
        return sandboxPath != null ? sandboxPath.toString() : null;
    }
}
