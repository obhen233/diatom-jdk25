package com.github.obhen233.spi;

import com.github.obhen233.core.gateway.collaboration.FileDiff;
import com.github.obhen233.core.gateway.collaboration.WorkerCoordinator;
import com.github.obhen233.core.gateway.registry.WorkerInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SPI 接口：自定义协作任务的资源争抢策略。
 *
 * 控制并行子任务之间的资源隔离方式：沙箱隔离、锁隔离、或自定义策略。
 * 默认实现 CompositeStrategyProvider 同时支持沙箱+锁两种策略。
 *
 * 注册方式：
 * diatom-custom/src/main/resources/META-INF/services/
 *   com.github.obhen233.spi.ResourceContentionProvider
 * 内容为实现类的全限定名
 *
 * SPI 加载方式：SpiLoader.getFirst(ResourceContentionProvider.class, defaultImpl)
 */
public interface ResourceContentionProvider {

    /**
     * 为所有子任务准备隔离执行环境。
     * 在 executeParallel() 之前调用。
     *
     * @return IsolationContext — 需要在 cleanup 时释放的资源句柄
     */
    IsolationContext prepareEnvironment(
            String taskId,
            List<WorkerCoordinator.SubTask> subTasks,
            List<WorkerInfo> assignedWorkers,
            Path projectRoot
    );

    /**
     * 执行完成后收集所有子任务的变更 diff。
     * 仅沙箱策略返回有意义的 diff，锁策略返回空列表。
     */
    Map<String, List<FileDiff>> collectChanges(
            String taskId,
            List<WorkerCoordinator.SubTask> subTasks,
            IsolationContext context
    );

    /**
     * 清理隔离环境（删除沙箱/释放锁）。
     * 在 mergeResults() 之后调用。
     */
    void cleanup(String taskId, List<WorkerCoordinator.SubTask> subTasks, IsolationContext context);

    /**
     * 获取该策略下 worker 应该使用的 workspace 路径。
     * 沙箱策略：返回沙箱目录路径
     * 锁策略：返回原始项目路径
     */
    default String resolveWorkerWorkspacePath(WorkerCoordinator.SubTask subTask, IsolationContext context) {
        Path sandboxPath = context.getSandboxPath(subTask.getSubTaskId());
        return sandboxPath != null ? sandboxPath.toString() : null;
    }
}
