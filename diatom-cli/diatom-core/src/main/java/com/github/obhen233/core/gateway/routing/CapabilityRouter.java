package com.github.obhen233.core.gateway.routing;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 基于能力的路由分配器。
 * <p>
 * 特性：
 * <ul>
 *   <li>LLM 优先路由：由 LLM 直接建议 Worker</li>
 *   <li>背压感知评分：将 Worker 心跳报告的 currentLoad 作为评分惩罚因子</li>
 *   <li>请求排队+自动重试：所有 Worker 满载时排队，有空位时自动重试</li>
 * </ul>
 * </p>
 *
 * <h3>评分公式</h3>
 * effectiveScore = capabilityScore × max(0.5, 1.0 - currentLoad × 0.5)
 * <br>
 * 即：100% 负载时惩罚 50 分，50% 负载时惩罚 25 分，0% 负载无惩罚。
 */
public class CapabilityRouter {
    private static final Logger logger = LoggerFactory.getLogger(CapabilityRouter.class);

    private final WorkerRegistry registry;
    private final ScoreCalculator scoreCalculator;

    /** 每个 Worker 当前的活跃请求数（Gateway 侧跟踪） */
    private final ConcurrentHashMap<String, AtomicInteger> activeRequests = new ConcurrentHashMap<>();

    /** 默认 maxConcurrency（WorkerInfo 未设置时使用） */
    private static final int DEFAULT_MAX_CONCURRENCY = 5;

    /** 背压评分惩罚系数：currentLoad × LOAD_PENALTY 为最大减分比例 */
    private static final double LOAD_PENALTY = 0.5;

    /** 排队请求队列 */
    private final Queue<QueuedRequest> pendingQueue = new ConcurrentLinkedQueue<>();

    /** 重试调度器 */
    private final ScheduledExecutorService retryScheduler;

    /** 排队请求最长等待时间（毫秒） */
    private static final long QUEUE_TIMEOUT_MS = 300_000; // 5 分钟

    /** 重试间隔（毫秒） */
    private static final long RETRY_INTERVAL_MS = 2_000; // 2 秒

    public CapabilityRouter(WorkerRegistry registry) {
        this.registry = registry;
        this.scoreCalculator = new ScoreCalculator();
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "router-retry");
            t.setDaemon(true);
            return t;
        });
        this.retryScheduler.scheduleAtFixedRate(this::retryQueued,
                RETRY_INTERVAL_MS, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ========== 活跃请求跟踪 ==========

    /**
     * 返回指定 Worker 当前的活跃请求数
     */
    public int getActiveRequests(String workerId) {
        AtomicInteger counter = activeRequests.get(workerId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 增加指定 Worker 的活跃请求计数
     */
    public int incrementActive(String workerId) {
        return activeRequests.computeIfAbsent(workerId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * 减少指定 Worker 的活跃请求计数。
     * 每次减少后自动触发排队重试，因为可能有 Worker 已经空闲。
     */
    public int decrementActive(String workerId) {
        AtomicInteger counter = activeRequests.get(workerId);
        if (counter != null) {
            int val = counter.decrementAndGet();
            if (val <= 0) {
                activeRequests.remove(workerId);
            }
            // 有 Worker 空闲了，尝试重试排队请求
            if (!pendingQueue.isEmpty()) {
                retryScheduler.submit(this::retryQueued);
            }
            return val;
        }
        return 0;
    }

    /**
     * 检查指定 Worker 是否还有容量
     */
    public boolean hasCapacity(WorkerInfo worker) {
        int maxConcurrency = worker.getMaxConcurrency() > 0
                ? worker.getMaxConcurrency() : DEFAULT_MAX_CONCURRENCY;
        return getActiveRequests(worker.getWorkerId()) < maxConcurrency;
    }

    // ========== 核心路由 ==========

    /**
     * 计算考虑背压的有效评分。
     * <p>
     * effectiveScore = capabilityScore × max(0.5, 1.0 - currentLoad × LOAD_PENALTY)
     * </p>
     */
    private double effectiveScore(double capabilityScore, WorkerInfo worker) {
        double load = worker.getMetrics().getCurrentLoad();
        // load 应该在 0~1 之间，防止异常值
        load = Math.max(0, Math.min(1, load));
        double factor = Math.max(0.5, 1.0 - load * LOAD_PENALTY);
        return capabilityScore * factor;
    }

    /**
     * 基于能力评分路由 + 背压感知 + group 亲和度。
     * <p>
     * 策略（两阶段）：
     * Phase 1 — Group affinity: 按 group 分组，每组内评分排序，
     *           取各组最高分 worker，优先选同 group worker。
     *           没有 groupHint 时逐个 group 尝试。
     * Phase 2 — Cross-group fallback: 所有 Worker 统一按评分排序，
     *           从高分到低分选有容量的 Worker。
     * </p>
     */
    public WorkerInfo route(TaskRequirement requirement) {
        return route(requirement, Collections.<String>emptySet());
    }

    /**
     * 带排除集合的路由：跳过已确认过载/无响应的 Worker（例如刚返回 503 的）。
     */
    public WorkerInfo route(TaskRequirement requirement, Set<String> excludedWorkerIds) {
        List<WorkerInfo> available = registry.availableWorkers();
        if (available.isEmpty()) return null;
        if (excludedWorkerIds != null && !excludedWorkerIds.isEmpty()) {
            available = available.stream()
                    .filter(w -> !excludedWorkerIds.contains(w.getWorkerId()))
                    .collect(Collectors.toList());
            if (available.isEmpty()) {
                logger.warn("All workers excluded by retry set, no route target");
                return null;
            }
        }

        // Phase 1: Group affinity routing
        // Group workers by their group field
        Map<String, List<WorkerInfo>> grouped = available.stream()
                .filter(w -> w.getGroup() != null && !w.getGroup().isEmpty())
                .collect(Collectors.groupingBy(WorkerInfo::getGroup));
        // Ungrouped workers (no group set)
        List<WorkerInfo> ungrouped = available.stream()
                .filter(w -> w.getGroup() == null || w.getGroup().isEmpty())
                .collect(Collectors.toList());

        // Try each group independently, pick best worker per group
        List<ScoredWorker> groupWinners = new ArrayList<>();
        for (Map.Entry<String, List<WorkerInfo>> entry : grouped.entrySet()) {
            List<WorkerInfo> groupWorkers = entry.getValue();
            ScoredWorker best = groupWorkers.stream()
                    .map(w -> new ScoredWorker(w, scoreCalculator.calculate(w, requirement)))
                    .max(Comparator.comparingDouble(ScoredWorker::getScore))
                    .orElse(null);
            if (best != null) {
                groupWinners.add(best);
            }
        }
        // Also consider ungrouped workers
        for (WorkerInfo w : ungrouped) {
            groupWinners.add(new ScoredWorker(w, scoreCalculator.calculate(w, requirement)));
        }

        // Sort group winners by score descending, pick first with capacity
        groupWinners.sort(Comparator.comparingDouble(ScoredWorker::getScore).reversed());
        for (ScoredWorker sw : groupWinners) {
            if (hasCapacity(sw.worker)) {
                logger.debug("Group-affinity routed: {} (group={}, score={}, load={})",
                        sw.worker.getWorkerId(),
                        sw.worker.getGroup() != null ? sw.worker.getGroup() : "(none)",
                        String.format("%.2f", sw.score),
                        String.format("%.2f", sw.worker.getMetrics().getCurrentLoad()));
                return sw.worker;
            }
        }

        // Phase 2: Cross-group fallback — all workers by score desc
        // (only if Phase 1 couldn't find any worker with capacity)
        List<ScoredWorker> allScored = available.stream()
                .map(w -> new ScoredWorker(w,
                        effectiveScore(scoreCalculator.calculate(w, requirement), w)))
                .sorted(Comparator.comparingDouble(ScoredWorker::getScore).reversed())
                .collect(Collectors.toList());

        for (ScoredWorker sw : allScored) {
            if (hasCapacity(sw.worker)) {
                logger.debug("Cross-group routed: {} (effScore={}, load={}, active={}/{})",
                        sw.worker.getWorkerId(),
                        String.format("%.2f", sw.score),
                        String.format("%.2f", sw.worker.getMetrics().getCurrentLoad()),
                        getActiveRequests(sw.worker.getWorkerId()),
                        sw.worker.getMaxConcurrency() > 0 ? sw.worker.getMaxConcurrency() : DEFAULT_MAX_CONCURRENCY);
                return sw.worker;
            }
        }

        // 3. 全部满载：仍选最高分 Worker
        WorkerInfo best = allScored.get(0).worker;
        logger.warn("All workers at capacity, routing to best-match: {} (effScore={}, load={}, active={}/{})",
                best.getWorkerId(), String.format("%.2f", allScored.get(0).score),
                String.format("%.2f", best.getMetrics().getCurrentLoad()),
                getActiveRequests(best.getWorkerId()),
                best.getMaxConcurrency() > 0 ? best.getMaxConcurrency() : DEFAULT_MAX_CONCURRENCY);
        return best;
    }

    /**
     * LLM-first routing + 背压感知。
     */
    public WorkerInfo routeWithLLMSuggestion(TaskRequirement requirement) {
        return routeWithLLMSuggestion(requirement, Collections.<String>emptySet());
    }

    /**
     * LLM-first routing + 背压感知，带排除集合（跳过已 503/无响应的 Worker）。
     */
    public WorkerInfo routeWithLLMSuggestion(TaskRequirement requirement, Set<String> excludedWorkerIds) {
        String suggestedId = requirement.getSuggestedWorkerId();
        if (suggestedId != null && !suggestedId.isEmpty() && !"null".equals(suggestedId)) {
            WorkerInfo suggested = registry.getWorker(suggestedId);
            boolean excluded = excludedWorkerIds != null && excludedWorkerIds.contains(suggestedId);
            if (suggested != null && suggested.isAvailable() && !excluded) {
                if (hasCapacity(suggested)) {
                    logger.info("LLM suggested worker: {} (reasoning: {}), using direct route",
                            suggestedId, requirement.getReasoning());
                    return suggested;
                } else {
                    logger.warn("LLM suggested worker {} but at capacity (active={}/{}), falling back",
                            suggestedId, getActiveRequests(suggestedId),
                            suggested.getMaxConcurrency() > 0 ? suggested.getMaxConcurrency() : DEFAULT_MAX_CONCURRENCY);
                }
            } else {
                logger.warn("LLM suggested worker {} but not available{}, falling back to score-based routing",
                        suggestedId, excluded ? " (excluded by retry)" : "");
            }
        }
        return route(requirement, excludedWorkerIds);
    }

    // ========== 请求排队 + 自动重试 ==========

    /**
     * 排队请求条目
     */
    public static class QueuedRequest {
        private final TaskRequirement requirement;
        private final Consumer<WorkerInfo> onRouted;
        private final long enqueuedAt;

        QueuedRequest(TaskRequirement requirement, Consumer<WorkerInfo> onRouted) {
            this.requirement = requirement;
            this.onRouted = onRouted;
            this.enqueuedAt = System.currentTimeMillis();
        }

        public TaskRequirement getRequirement() { return requirement; }
        public long getEnqueuedAt() { return enqueuedAt; }
        public long getAgeMs() { return System.currentTimeMillis() - enqueuedAt; }
    }

    /**
     * 尝试路由，如果所有 Worker 都满载则排队等待。
     *
     * @param requirement 任务需求
     * @param onRouted    路由成功后回调（参数为选中的 Worker）
     * @return true 表示立即路由成功，false 表示已排队
     */
    public boolean routeOrQueue(TaskRequirement requirement, Consumer<WorkerInfo> onRouted) {
        // 先尝试立即路由
        WorkerInfo worker = route(requirement);
        if (worker != null && hasCapacity(worker)) {
            onRouted.accept(worker);
            return true;
        }

        // 全部满载，排队
        pendingQueue.offer(new QueuedRequest(requirement, onRouted));
        logger.info("Request queued (queueSize={}, taskType={})",
                pendingQueue.size(), requirement.getTaskType());
        return false;
    }

    /**
     * 当前排队请求数
     */
    public int getQueueSize() {
        return pendingQueue.size();
    }

    /**
     * 重试排队请求：遍历队列，尝试路由，成功则出队并回调。
     */
    private void retryQueued() {
        if (pendingQueue.isEmpty()) return;

        int batchSize = pendingQueue.size();
        int routed = 0;
        int expired = 0;

        for (int i = 0; i < batchSize; i++) {
            QueuedRequest qr = pendingQueue.peek();
            if (qr == null) break;

            // 检查是否超时
            if (qr.getAgeMs() > QUEUE_TIMEOUT_MS) {
                pendingQueue.poll();
                expired++;
                logger.warn("Queued request expired after {}ms (taskType={})",
                        qr.getAgeMs(), qr.requirement.getTaskType());
                continue;
            }

            // 尝试路由
            WorkerInfo worker = route(qr.requirement);
            if (worker != null && hasCapacity(worker)) {
                pendingQueue.poll();
                try {
                    qr.onRouted.accept(worker);
                } catch (Exception e) {
                    logger.error("Queued request callback failed", e);
                }
                routed++;
            } else {
                // 还是没有容量，跳出（队列保持 FIFO，继续等）
                break;
            }
        }

        if (routed > 0 || expired > 0) {
            logger.info("Retry: {} routed, {} expired, {} remaining",
                    routed, expired, pendingQueue.size());
        }
    }

    // ========== 排序工具 ==========

    /**
     * 分数相近时的二次排序：负载优先 -> 延迟 -> 成功率 -> 成本
     */
    private List<ScoredWorker> secondarySort(List<ScoredWorker> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble((ScoredWorker sw) -> sw.worker.getMetrics().getCurrentLoad())
                        .thenComparingDouble(sw -> sw.worker.getMetrics().getAvgLatencyMs())
                        .thenComparingDouble(sw -> -sw.worker.getMetrics().getSuccessRate())
                        .thenComparingDouble(sw -> sw.worker.getCostPer1kTokens()))
                .collect(Collectors.toList());
    }

    /**
     * 关闭重试调度器
     */
    public void shutdown() {
        retryScheduler.shutdown();
        try {
            retryScheduler.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            retryScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        int remaining = pendingQueue.size();
        if (remaining > 0) {
            logger.warn("Router shutdown with {} queued requests remaining", remaining);
            pendingQueue.clear();
        }
    }

    private static class ScoredWorker {
        final WorkerInfo worker;
        final double score;

        ScoredWorker(WorkerInfo worker, double score) {
            this.worker = worker;
            this.score = score;
        }

        double getScore() { return score; }
    }
}
