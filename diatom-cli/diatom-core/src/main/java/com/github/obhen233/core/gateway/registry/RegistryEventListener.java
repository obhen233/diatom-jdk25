package com.github.obhen233.core.gateway.registry;

import com.github.obhen233.core.gateway.profile.CapabilityAnalyzer;
import com.github.obhen233.core.gateway.profile.CapabilityProfile;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 注册事件监听器
 * 新 Worker 注册时触发路由规则重计算
 */
public class RegistryEventListener {
    private static final Logger logger = LoggerFactory.getLogger(RegistryEventListener.class);

    private final CapabilityRouter router;
    private final CapabilityAnalyzer analyzer;
    private final WorkerRegistry registry;
    private final TaskManager taskManager;

    public RegistryEventListener(CapabilityRouter router, CapabilityAnalyzer analyzer, WorkerRegistry registry) {
        this(router, analyzer, registry, null);
    }

    public RegistryEventListener(CapabilityRouter router, CapabilityAnalyzer analyzer, WorkerRegistry registry, TaskManager taskManager) {
        this.router = router;
        this.analyzer = analyzer;
        this.registry = registry;
        this.taskManager = taskManager;
    }

    /**
     * 绑定到注册中心事件
     */
    public void bind() {
        registry.subscribe(this::onRegistryEvent);
        logger.info("Registry event listener bound");
    }

    private void onRegistryEvent(RegistryEvent event) {
        switch (event.getType()) {
            case REGISTERED:
                onWorkerRegistered(event);
                break;
            case DEREGISTERED:
                onWorkerDeregistered(event);
                break;
            case HEARTBEAT_TIMEOUT:
                onHeartbeatTimeout(event);
                break;
            case STATUS_CHANGED:
                onStatusChanged(event);
                break;
        }
    }

    /**
     * 新 Worker 注册时：
     * 1. 读取 Worker 的 capability profile
     * 2. Gateway Agent 分析 profile，提取增强的能力特征
     * 3. 更新 Worker 的 capabilities
     * 4. 重新计算路由权重
     */
    private void onWorkerRegistered(RegistryEvent event) {
        WorkerInfo worker = event.getWorker();
        logger.info("New worker registered: {} (model={})", worker.getWorkerId(), worker.getModel());

        // 1. 读取 capability profile
        CapabilityProfile profile = loadCapabilityProfile(worker);
        if (profile == null) {
            logger.debug("No capability profile found for worker {}, using default", worker.getWorkerId());
            return;
        }

        // 2. 分析 profile，提取增强的能力特征
        CapabilityProfile analyzed = analyzeProfile(profile);
        if (analyzed == null) return;

        // 3. 更新 Worker 的 capabilities
        if (analyzed.getInferredCapabilities() != null) {
            worker.mergeCapabilities(analyzed.getInferredCapabilities());
        }

        // 4. 传播 CapabilityProfile 中的 routing-relevant 字段到 WorkerInfo
        if (analyzed.getBoundaries() != null && !analyzed.getBoundaries().isEmpty()) {
            worker.setBoundaries(analyzed.getBoundaries());
        }
        if (analyzed.getMaxTokens() > 0) {
            worker.setMaxTokens(analyzed.getMaxTokens());
        }
        if (analyzed.getApiProvider() != null) {
            worker.setApiProvider(analyzed.getApiProvider());
        }
        // 如果 capability 中包含 tool-use，标记 supportsToolCalls
        if (analyzed.getInferredCapabilities() != null &&
            analyzed.getInferredCapabilities().keySet().stream().anyMatch(k -> k.toLowerCase().contains("tool"))) {
            worker.setSupportsToolCalls(true);
        }
        if (analyzed.isSupportsToolCalls()) {
            worker.setSupportsToolCalls(true);
        }

        // 5. 记录日志
        logger.info("Worker {} capabilities updated: strengths={}, suitable={}",
                worker.getWorkerId(),
                analyzed.getStrengths().size(),
                analyzed.getSuitableTaskTypes().size());

        // 5. 路由重平衡：将 PENDING 任务重新评估分配
        rebalancePendingTasks();
    }

    /**
     * 路由重平衡：遍历所有 PENDING 任务，重新评估分配策略
     * 新 Worker 注册后调用，将等待中的任务重新路由
     */
    private void rebalancePendingTasks() {
        if (taskManager == null) return;
        List<TaskState> pendingTasks = taskManager.getTasksByStatus(TaskStatus.PENDING);
        if (pendingTasks.isEmpty()) return;

        logger.info("Rebalancing {} pending tasks after new worker registration", pendingTasks.size());
        for (TaskState task : pendingTasks) {
            // 获取可用 Worker 列表
            List<WorkerInfo> available = registry.availableWorkers();
            if (available.isEmpty()) {
                logger.debug("No available workers for pending task {}", task.getTaskId());
                continue;
            }

            // 根据 Worker 负载选择最优分配
            WorkerInfo bestWorker = selectBestWorker(available, task);
            if (bestWorker != null) {
                taskManager.assignTask(task.getTaskId(), bestWorker.getWorkerId());
                logger.info("Rebalanced: task {} reassigned to worker {}",
                        task.getTaskId(), bestWorker.getWorkerId());
            }
        }
    }

    /**
     * 根据负载和 capacity 选择最优 Worker
     */
    private WorkerInfo selectBestWorker(List<WorkerInfo> workers, TaskState task) {
        WorkerInfo best = null;
        int minLoad = Integer.MAX_VALUE;
        for (WorkerInfo w : workers) {
            if (!w.isAvailable()) continue;
            int load = (w.getMetrics() != null) ? w.getMetrics().getActiveTasks() : 0;
            if (load < minLoad) {
                minLoad = load;
                best = w;
            }
        }
        return best;
    }

    private void onWorkerDeregistered(RegistryEvent event) {
        logger.info("Worker deregistered: {}", event.getWorkerId());
    }

    private void onHeartbeatTimeout(RegistryEvent event) {
        logger.warn("Worker heartbeat timeout: {}", event.getWorkerId());
    }

    private void onStatusChanged(RegistryEvent event) {
        logger.debug("Worker status changed: {} -> {}", event.getWorkerId(), event.getWorker().getStatus());
    }

    private CapabilityProfile loadCapabilityProfile(WorkerInfo worker) {
        try {
            String installHome = System.getProperty("diatom.install.home",
                    System.getProperty("user.home") + "/.diatom");
            Path profilePath = Paths.get(installHome, "workers", worker.getWorkerId(), "capability.md");
            if (Files.exists(profilePath)) {
                String content = new String(Files.readAllBytes(profilePath), StandardCharsets.UTF_8);
                CapabilityProfile profile = analyzer.parseMarkdown(content, worker.getWorkerId());
                profile.setModel(worker.getModel());
                return profile;
            }
        } catch (Exception e) {
            logger.warn("Failed to load capability profile for {}: {}", worker.getWorkerId(), e.getMessage());
        }
        return null;
    }

    private CapabilityProfile analyzeProfile(CapabilityProfile profile) {
        if (profile == null) return null;

        // 从 strengths 中推断能力分数
        java.util.Map<String, Double> inferred = new java.util.HashMap<>();
        for (String strength : profile.getStrengths()) {
            inferred.put(strength, 0.85);
        }
        for (String type : profile.getSuitableTaskTypes()) {
            inferred.put(typeToCapability(type), 0.90);
        }

        profile.setInferredCapabilities(inferred);

        // 生成摘要
        StringBuilder summary = new StringBuilder("Worker擅长: ");
        java.util.List<String> topSkills = new java.util.ArrayList<>(profile.getStrengths());
        if (topSkills.size() > 3) topSkills = topSkills.subList(0, 3);
        summary.append(String.join(", ", topSkills));
        if (!profile.getUnsuitableTaskTypes().isEmpty()) {
            summary.append("; 不适合: ").append(String.join(", ", profile.getUnsuitableTaskTypes()));
        }
        profile.setSummary(summary.toString());

        return profile;
    }

    private String typeToCapability(String taskType) {
        switch (taskType) {
            case "refactoring": return "代码重构";
            case "bug_fix": return "Bug修复";
            case "testing": return "测试";
            case "architecture": return "架构设计";
            case "code_review": return "代码审查";
            case "documentation": return "文档撰写";
            default: return "通用开发";
        }
    }
}
