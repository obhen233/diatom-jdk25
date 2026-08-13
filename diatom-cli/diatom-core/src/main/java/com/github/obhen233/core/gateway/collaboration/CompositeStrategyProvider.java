package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.spi.IsolationContext;
import com.github.obhen233.spi.ResourceContentionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * 组合策略：同时支持沙箱隔离和锁隔离。
 *
 * 策略选择逻辑：
 * 1. 分析所有子任务的资源声明
 * 2. 无资源重叠的子任务 → 沙箱策略（并行无竞争）
 * 3. 有资源重叠但操作不同的子任务 → 沙箱策略（各自修改副本，合并时 three-way merge）
 * 4. 有资源重叠且需要即时一致性的场景 → 锁策略（串行访问共享资源）
 *
 * 此为默认 SPI 实现，当用户未注册自定义 ResourceContentionProvider 时使用。
 */
public class CompositeStrategyProvider implements ResourceContentionProvider {
    private static final Logger logger = LoggerFactory.getLogger(CompositeStrategyProvider.class);

    private final SandboxWorkspaceManager sandboxManager;
    private final ResourceLockManager lockManager;
    private final SandboxStrategy sandboxStrategy;
    private final LockStrategy lockStrategy;

    public CompositeStrategyProvider(SandboxWorkspaceManager sandboxManager,
                                      ResourceLockManager lockManager) {
        this.sandboxManager = sandboxManager;
        this.lockManager = lockManager;
        this.sandboxStrategy = new SandboxStrategy(sandboxManager);
        this.lockStrategy = new LockStrategy(lockManager);
    }

    @Override
    public IsolationContext prepareEnvironment(String taskId,
                                                List<WorkerCoordinator.SubTask> subTasks,
                                                List<WorkerInfo> assignedWorkers,
                                                Path projectRoot) {
        IsolationContext context = new IsolationContext("composite");

        // 分析子任务之间的资源重叠
        Map<String, Set<String>> subTaskResources = analyzeResources(subTasks);
        boolean hasContention = hasResourceContention(subTaskResources);

        if (!hasContention) {
            // 无竞争：所有子任务使用沙箱策略
            logger.info("No resource contention detected, using sandbox strategy for all sub-tasks");
            IsolationContext sandboxCtx = sandboxStrategy.prepareEnvironment(
                    taskId, subTasks, assignedWorkers, projectRoot);
            context.setAttribute("delegateStrategy", "sandbox");
            context.setAttribute("delegateContext", sandboxCtx);
            // 复制 sandbox paths
            for (WorkerCoordinator.SubTask st : subTasks) {
                Path sp = sandboxCtx.getSandboxPath(st.getSubTaskId());
                if (sp != null) context.addSandboxPath(st.getSubTaskId(), sp);
            }
        } else {
            // 有竞争：使用锁策略（串行化对资源的访问）
            logger.info("Resource contention detected, using lock strategy for all sub-tasks");
            IsolationContext lockCtx = lockStrategy.prepareEnvironment(
                    taskId, subTasks, assignedWorkers, projectRoot);
            context.setAttribute("delegateStrategy", "lock");
            context.setAttribute("delegateContext", lockCtx);
            // 复制 lock tokens
            for (Map.Entry<String, IsolationContext.LockToken> entry : lockCtx.getHeldLocks().entrySet()) {
                context.addLock(entry.getKey(), entry.getValue());
            }
        }

        return context;
    }

    @Override
    public Map<String, List<FileDiff>> collectChanges(String taskId,
                                                        List<WorkerCoordinator.SubTask> subTasks,
                                                        IsolationContext context) {
        String delegateStrategy = context.getAttribute("delegateStrategy");
        IsolationContext delegateCtx = context.getAttribute("delegateContext");

        if ("sandbox".equals(delegateStrategy) && delegateCtx != null) {
            return sandboxStrategy.collectChanges(taskId, subTasks, delegateCtx);
        }
        // 锁策略不产生 diff
        return Collections.emptyMap();
    }

    @Override
    public void cleanup(String taskId, List<WorkerCoordinator.SubTask> subTasks, IsolationContext context) {
        String delegateStrategy = context.getAttribute("delegateStrategy");
        IsolationContext delegateCtx = context.getAttribute("delegateContext");

        if ("sandbox".equals(delegateStrategy) && delegateCtx != null) {
            sandboxStrategy.cleanup(taskId, subTasks, delegateCtx);
        } else if ("lock".equals(delegateStrategy) && delegateCtx != null) {
            lockStrategy.cleanup(taskId, subTasks, delegateCtx);
        }
    }

    @Override
    public String resolveWorkerWorkspacePath(WorkerCoordinator.SubTask subTask, IsolationContext context) {
        String delegateStrategy = context.getAttribute("delegateStrategy");
        IsolationContext delegateCtx = context.getAttribute("delegateContext");

        if ("sandbox".equals(delegateStrategy) && delegateCtx != null) {
            return sandboxStrategy.resolveWorkerWorkspacePath(subTask, delegateCtx);
        }
        // 锁策略返回 null（不切换 workspace）
        return null;
    }

    /**
     * 分析子任务的资源声明。
     * 当前使用子任务描述中的文件名/路径作为资源标识。
     * 后续可以由 LLM 拆分时标注更精确的资源。
     */
    private Map<String, Set<String>> analyzeResources(List<WorkerCoordinator.SubTask> subTasks) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (WorkerCoordinator.SubTask subTask : subTasks) {
            Set<String> resources = extractResourcesFromDescription(subTask.getDescription());
            result.put(subTask.getSubTaskId(), resources);
        }
        return result;
    }

    /**
     * 从子任务描述中提取资源路径。
     * 简单的启发式提取：寻找描述中的文件路径模式。
     */
    private Set<String> extractResourcesFromDescription(String description) {
        Set<String> resources = new HashSet<>();
        if (description == null) return resources;

        // 查找描述中的文件路径模式（如 src/main/java/X.java）
        String[] words = description.split("\\s+");
        for (String word : words) {
            word = word.trim();
            // 匹配包含斜杠或反斜杠的路径
            if ((word.contains("/") || word.contains("\\"))
                    && (word.endsWith(".java") || word.endsWith(".xml")
                    || word.endsWith(".properties") || word.endsWith(".json")
                    || word.endsWith(".yaml") || word.endsWith(".yml")
                    || word.endsWith(".ts") || word.endsWith(".js")
                    || word.endsWith(".py") || word.endsWith(".md"))) {
                // 清理标点符号
                word = word.replaceAll("[,\\.;:!?()\\[\\]{}]$", "");
                resources.add(word);
            }
        }
        return resources;
    }

    /**
     * 检测子任务之间是否存在资源重叠。
     */
    private boolean hasResourceContention(Map<String, Set<String>> subTaskResources) {
        List<Set<String>> resourceSets = new ArrayList<>(subTaskResources.values());

        // 如果没有子任务声明了资源，则认定为无竞争
        boolean anyHasResources = resourceSets.stream().anyMatch(s -> !s.isEmpty());
        if (!anyHasResources) return false;

        // 检查是否有交集
        for (int i = 0; i < resourceSets.size(); i++) {
            for (int j = i + 1; j < resourceSets.size(); j++) {
                Set<String> intersection = new HashSet<>(resourceSets.get(i));
                intersection.retainAll(resourceSets.get(j));
                if (!intersection.isEmpty()) {
                    logger.debug("Resource contention detected between sub-tasks: {}", intersection);
                    return true;
                }
            }
        }
        return false;
    }

    public SandboxStrategy getSandboxStrategy() {
        return sandboxStrategy;
    }

    public LockStrategy getLockStrategy() {
        return lockStrategy;
    }
}
