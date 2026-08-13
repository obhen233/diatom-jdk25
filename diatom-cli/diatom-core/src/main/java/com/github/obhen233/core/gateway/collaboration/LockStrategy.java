package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.spi.IsolationContext;
import com.github.obhen233.spi.ResourceContentionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * 锁隔离策略。
 *
 * 不为子任务创建隔离目录，而是通过分布式锁来串行化对共享资源的访问。
 * Worker 直接在原始项目文件上操作，但通过锁机制防止并发写入冲突。
 */
public class LockStrategy implements ResourceContentionProvider {
    private static final Logger logger = LoggerFactory.getLogger(LockStrategy.class);

    private final ResourceLockManager lockManager;
    private final long defaultLeaseMs;
    private final long defaultWaitMs;

    public LockStrategy(ResourceLockManager lockManager) {
        this(lockManager, 60000L, 120000L);
    }

    public LockStrategy(ResourceLockManager lockManager, long defaultLeaseMs, long defaultWaitMs) {
        this.lockManager = lockManager;
        this.defaultLeaseMs = defaultLeaseMs;
        this.defaultWaitMs = defaultWaitMs;
    }

    @Override
    public IsolationContext prepareEnvironment(String taskId,
                                                List<WorkerCoordinator.SubTask> subTasks,
                                                List<WorkerInfo> assignedWorkers,
                                                Path projectRoot) {
        IsolationContext context = new IsolationContext("lock");

        for (int i = 0; i < subTasks.size() && i < assignedWorkers.size(); i++) {
            WorkerCoordinator.SubTask subTask = subTasks.get(i);
            WorkerInfo worker = assignedWorkers.get(i);

            // 对于锁策略，每个子任务需要声明其资源（后续可以由 LLM 拆分时标注）
            // 这里使用 subTaskId 作为资源标识，获取 WRITE 锁
            String resourceId = "task:" + subTask.getSubTaskId();

            IsolationContext.LockToken token = lockManager.acquire(
                    resourceId, worker.getWorkerId(),
                    LockInfo.LockMode.WRITE, defaultLeaseMs, defaultWaitMs);

            if (token != null) {
                context.addLock(resourceId, token);
                logger.debug("Acquired WRITE lock for sub-task {} on worker {}",
                        subTask.getSubTaskId(), worker.getWorkerId());
            } else {
                logger.warn("Failed to acquire lock for sub-task {} on worker {} (timeout)",
                        subTask.getSubTaskId(), worker.getWorkerId());
            }
        }

        return context;
    }

    @Override
    public Map<String, List<FileDiff>> collectChanges(String taskId,
                                                        List<WorkerCoordinator.SubTask> subTasks,
                                                        IsolationContext context) {
        // 锁策略不产生 diff（worker 直接在原始项目上操作）
        return Collections.emptyMap();
    }

    @Override
    public void cleanup(String taskId, List<WorkerCoordinator.SubTask> subTasks, IsolationContext context) {
        for (Map.Entry<String, IsolationContext.LockToken> entry : context.getHeldLocks().entrySet()) {
            String resourceId = entry.getKey();
            IsolationContext.LockToken token = entry.getValue();
            lockManager.release(resourceId, token.getToken(), token.getWorkerId());
            logger.debug("Released lock for {} by {}", resourceId, token.getWorkerId());
        }
    }

    @Override
    public String resolveWorkerWorkspacePath(WorkerCoordinator.SubTask subTask, IsolationContext context) {
        // 锁策略不改变 workspace 路径，直接在原始项目路径上操作
        return null;
    }

    public ResourceLockManager getLockManager() {
        return lockManager;
    }
}
