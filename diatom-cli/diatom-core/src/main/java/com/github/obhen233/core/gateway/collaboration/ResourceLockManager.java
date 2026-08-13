package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.spi.IsolationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 分布式资源锁管理器。
 *
 * 管理文件级别的读写锁，支持：
 * - WRITE 锁：排他，仅一个持有者
 * - READ 锁：共享，多个 worker 可同时持有
 * - 租约自动过期
 * - 死锁检测（wait-for graph + DFS）
 * - 锁升级（READ → WRITE）
 * - 排队等待
 */
public class ResourceLockManager {
    private static final Logger logger = LoggerFactory.getLogger(ResourceLockManager.class);

    private final ConcurrentHashMap<String, LockInfo> activeLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkedBlockingQueue<LockInfo.LockRequest>> waitQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> resourceMutexes = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final long defaultLeaseMs;
    private final long maxWaitMs;
    private final long deadlockCheckIntervalMs;
    private final long leaseCheckIntervalMs;

    public ResourceLockManager() {
        this(30000L, 120000L, 5000L, 2000L);
    }

    public ResourceLockManager(long defaultLeaseMs, long maxWaitMs,
                                long deadlockCheckIntervalMs, long leaseCheckIntervalMs) {
        this.defaultLeaseMs = defaultLeaseMs;
        this.maxWaitMs = maxWaitMs;
        this.deadlockCheckIntervalMs = deadlockCheckIntervalMs;
        this.leaseCheckIntervalMs = leaseCheckIntervalMs;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "lock-manager");
            t.setDaemon(true);
            return t;
        });
        scheduleLeaseChecker();
        scheduleDeadlockDetector();
    }

    /**
     * 尝试获取锁。
     *
     * @param resourceId 锁资源 ID（如文件路径）
     * @param workerId   请求锁的 worker ID
     * @param mode       锁模式（READ/WRITE）
     * @param leaseMs    租约时长（毫秒），<=0 使用默认值
     * @param waitMs     最大等待时间（毫秒），0 表示非阻塞
     * @return LockToken 成功；null 失败（超时或被拒绝）
     */
    /**
     * Acquire a distributed lock (overload accepting IsolationContext.LockMode).
     */
    public IsolationContext.LockToken acquire(String resourceId, String workerId,
                                               IsolationContext.LockMode mode, long leaseMs, long waitMs) {
        LockInfo.LockMode internalMode = (mode == IsolationContext.LockMode.READ)
                ? LockInfo.LockMode.READ : LockInfo.LockMode.WRITE;
        return acquire(resourceId, workerId, internalMode, leaseMs, waitMs);
    }

    /**
     * Acquire a distributed lock.
     * @param resourceId 资源标识
     * @param workerId   请求 Worker
     * @param mode       锁模式（READ 共享 / WRITE 独占）
     * @param leaseMs    租约时长（毫秒），<=0 使用默认值
     * @param waitMs     最大等待时间（毫秒），0 表示非阻塞
     * @return LockToken 成功；null 失败（超时或被拒绝）
     */
    public IsolationContext.LockToken acquire(String resourceId, String workerId,
                                               LockInfo.LockMode mode, long leaseMs, long waitMs) {
        if (leaseMs <= 0) leaseMs = defaultLeaseMs;
        if (waitMs <= 0) waitMs = 0;

        ReentrantLock mutex = resourceMutexes.computeIfAbsent(resourceId, k -> new ReentrantLock());

        try {
            if (mutex.tryLock(waitMs > 0 ? waitMs : 0, TimeUnit.MILLISECONDS)) {
                try {
                    return tryAcquire(resourceId, workerId, mode, leaseMs);
                } finally {
                    mutex.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 非阻塞或超时，尝试排队
        if (waitMs > 0) {
            return enqueueAndWait(resourceId, workerId, mode, leaseMs, waitMs);
        }

        return null;
    }

    private IsolationContext.LockToken tryAcquire(String resourceId, String workerId,
                                                    LockInfo.LockMode mode, long leaseMs) {
        LockInfo existing = activeLocks.get(resourceId);

        if (existing == null) {
            // 无竞争，直接授予
            return grantLock(resourceId, workerId, mode, leaseMs);
        }

        // READ 锁兼容：已有 READ 锁且请求也是 READ
        if (existing.getMode() == LockInfo.LockMode.READ && mode == LockInfo.LockMode.READ) {
            existing.getSharedReadHolders().add(workerId);
            logger.debug("Shared READ lock granted for {} to {}", resourceId, workerId);
            return new IsolationContext.LockToken(
                    UUID.randomUUID().toString(), resourceId, workerId, "READ",
                    System.currentTimeMillis() + Math.max(existing.getRemainingLeaseMs(), leaseMs));
        }

        // 锁升级：同一 worker 从 READ 升级到 WRITE
        if (existing.getMode() == LockInfo.LockMode.READ && mode == LockInfo.LockMode.WRITE
                && existing.getSharedReadHolders().size() == 1
                && existing.getSharedReadHolders().contains(workerId)) {
            // 唯一持有者，可直接升级
            activeLocks.put(resourceId, new LockInfo(resourceId, workerId,
                    UUID.randomUUID().toString(), LockInfo.LockMode.WRITE,
                    System.currentTimeMillis(), leaseMs));
            logger.debug("Lock upgraded from READ to WRITE for {} by {}", resourceId, workerId);
            return new IsolationContext.LockToken(
                    activeLocks.get(resourceId).getToken(), resourceId, workerId, "WRITE",
                    System.currentTimeMillis() + leaseMs);
        }

        // 冲突
        logger.debug("Lock conflict on {}: existing={} by {}, requested={} by {}",
                resourceId, existing.getMode(), existing.getHolderWorkerId(), mode, workerId);
        return null;
    }

    private IsolationContext.LockToken grantLock(String resourceId, String workerId,
                                                   LockInfo.LockMode mode, long leaseMs) {
        String token = UUID.randomUUID().toString();
        LockInfo lockInfo = new LockInfo(resourceId, workerId, token, mode,
                System.currentTimeMillis(), leaseMs);
        activeLocks.put(resourceId, lockInfo);
        logger.debug("Lock granted: {} on {} to {} (token={}, lease={}ms)",
                mode, resourceId, workerId, token, leaseMs);
        return new IsolationContext.LockToken(token, resourceId, workerId,
                mode.name(), System.currentTimeMillis() + leaseMs);
    }

    private IsolationContext.LockToken enqueueAndWait(String resourceId, String workerId,
                                                        LockInfo.LockMode mode, long leaseMs, long waitMs) {
        LinkedBlockingQueue<LockInfo.LockRequest> queue = waitQueues.computeIfAbsent(
                resourceId, k -> new LinkedBlockingQueue<>());

        LockInfo.LockRequest request = new LockInfo.LockRequest(resourceId, workerId, mode, leaseMs);
        queue.offer(request);

        long deadline = System.currentTimeMillis() + waitMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                ReentrantLock mutex = resourceMutexes.get(resourceId);
                if (mutex != null && mutex.tryLock(100, TimeUnit.MILLISECONDS)) {
                    try {
                        // 检查是否轮到自己了
                        LockInfo.LockRequest head = queue.peek();
                        if (head != null && head.getWorkerId().equals(workerId)
                                && head.getMode() == mode) {
                            queue.poll(); // 出队
                            IsolationContext.LockToken result = tryAcquire(resourceId, workerId, mode, leaseMs);
                            if (result != null) {
                                return result;
                            }
                            // 仍然冲突，重新入队
                            break;
                        }
                    } finally {
                        mutex.unlock();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 超时，移除请求
        queue.remove(request);
        logger.debug("Lock wait timeout for {} on {} by {}", mode, resourceId, workerId);
        return null;
    }

    /**
     * 释放锁。
     *
     * @param resourceId 资源 ID
     * @param token      锁令牌
     * @param workerId   worker ID
     * @return 是否成功释放
     */
    public boolean release(String resourceId, String token, String workerId) {
        ReentrantLock mutex = resourceMutexes.get(resourceId);
        if (mutex == null) return false;

        mutex.lock();
        try {
            LockInfo lockInfo = activeLocks.get(resourceId);
            if (lockInfo == null) {
                logger.debug("No active lock on {} to release", resourceId);
                return false;
            }

            // READ 锁：移除该 worker 的共享持有
            if (lockInfo.getMode() == LockInfo.LockMode.READ) {
                lockInfo.getSharedReadHolders().remove(workerId);
                if (!lockInfo.getSharedReadHolders().isEmpty()) {
                    logger.debug("READ lock on {} still held by: {}", resourceId, lockInfo.getSharedReadHolders());
                    wakeWaitQueue(resourceId);
                    return true;
                }
            }

            // 验证 token 和 workerId
            if (!lockInfo.getToken().equals(token)) {
                logger.warn("Token mismatch for lock on {}: expected {}, got {}",
                        resourceId, lockInfo.getToken(), token);
                return false;
            }
            if (!lockInfo.getHolderWorkerId().equals(workerId)) {
                logger.warn("Worker mismatch for lock on {}: expected {}, got {}",
                        resourceId, lockInfo.getHolderWorkerId(), workerId);
                return false;
            }

            activeLocks.remove(resourceId);
            logger.debug("Lock released: {} on {} by {}", lockInfo.getMode(), resourceId, workerId);

            // 唤醒等待队列
            wakeWaitQueue(resourceId);

            // 清理空映射避免内存泄漏
            cleanupEmptyMaps(resourceId);
            return true;
        } finally {
            mutex.unlock();
        }
    }

    /**
     * 续租。
     */
    public boolean renewLease(String resourceId, String token, long additionalMs) {
        ReentrantLock mutex = resourceMutexes.get(resourceId);
        if (mutex == null) return false;

        mutex.lock();
        try {
            LockInfo lockInfo = activeLocks.get(resourceId);
            if (lockInfo == null || !lockInfo.getToken().equals(token)) {
                return false;
            }
            // 创建一个新的 LockInfo 延长租约
            activeLocks.put(resourceId, new LockInfo(resourceId, lockInfo.getHolderWorkerId(),
                    lockInfo.getToken(), lockInfo.getMode(),
                    System.currentTimeMillis() - (lockInfo.getLeaseDurationMs() - lockInfo.getRemainingLeaseMs()),
                    lockInfo.getRemainingLeaseMs() + additionalMs));
            logger.debug("Lease renewed for {} on {} (+{}ms)", lockInfo.getMode(), resourceId, additionalMs);
            return true;
        } finally {
            mutex.unlock();
        }
    }

    /**
     * 获取当前所有活跃锁的状态。
     */
    public Map<String, Object> getLockStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> locksList = new ArrayList<>();
        for (Map.Entry<String, LockInfo> entry : activeLocks.entrySet()) {
            Map<String, Object> lockInfo = new LinkedHashMap<>();
            lockInfo.put("resourceId", entry.getKey());
            lockInfo.put("holder", entry.getValue().getHolderWorkerId());
            lockInfo.put("mode", entry.getValue().getMode().name());
            lockInfo.put("acquiredAt", entry.getValue().getAcquiredAt());
            lockInfo.put("remainingLeaseMs", entry.getValue().getRemainingLeaseMs());
            lockInfo.put("sharedReadHolders", new ArrayList<>(entry.getValue().getSharedReadHolders()));
            locksList.add(lockInfo);
        }
        result.put("locks", locksList);

        Map<String, Object> queuesInfo = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedBlockingQueue<LockInfo.LockRequest>> entry : waitQueues.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                queuesInfo.put(entry.getKey(), entry.getValue().size());
            }
        }
        result.put("queues", queuesInfo);
        return result;
    }

    /**
     * 释放特定 worker 持有的所有锁（用于 worker 离线清理）。
     */
    public List<String> releaseWorkerLocks(String workerId) {
        List<String> released = new ArrayList<>();
        for (Map.Entry<String, LockInfo> entry : activeLocks.entrySet()) {
            String resourceId = entry.getKey();
            LockInfo lockInfo = entry.getValue();
            if (lockInfo.getHolderWorkerId().equals(workerId)
                    || lockInfo.getSharedReadHolders().contains(workerId)) {
                if (release(resourceId, lockInfo.getToken(), workerId)) {
                    released.add(resourceId);
                }
            }
        }
        return released;
    }

    /**
     * Get lock info for a specific resource.
     */
    public IsolationContext.LockInfo getLockInfo(String resourceId) {
        LockInfo info = activeLocks.get(resourceId);
        if (info == null) return null;
        return new IsolationContext.LockInfo(
                info.getResourceId(),
                info.getHolderWorkerId(),
                info.getMode().name(),
                info.getExpiresAt());
    }

    /**
     * Get all active locks.
     */
    public List<IsolationContext.LockInfo> getAllLocks() {
        List<IsolationContext.LockInfo> result = new ArrayList<>();
        for (LockInfo info : activeLocks.values()) {
            result.add(new IsolationContext.LockInfo(
                    info.getResourceId(),
                    info.getHolderWorkerId(),
                    info.getMode().name(),
                    info.getExpiresAt()));
        }
        return result;
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    // ===== 内部方法 =====

    private void scheduleLeaseChecker() {
        scheduler.scheduleAtFixedRate(() -> {
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, LockInfo> entry : activeLocks.entrySet()) {
                if (entry.getValue().isExpired()) {
                    expired.add(entry.getKey());
                }
            }
            for (String resourceId : expired) {
                LockInfo lockInfo = activeLocks.get(resourceId);
                if (lockInfo != null && lockInfo.isExpired()) {
                    activeLocks.remove(resourceId);
                    logger.warn("Lock lease expired and released: {} on {} by {}",
                            lockInfo.getMode(), resourceId, lockInfo.getHolderWorkerId());
                    wakeWaitQueue(resourceId);
                    cleanupEmptyMaps(resourceId);
                }
            }
        }, leaseCheckIntervalMs, leaseCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleDeadlockDetector() {
        scheduler.scheduleAtFixedRate(this::detectDeadlocks,
                deadlockCheckIntervalMs, deadlockCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 死锁检测：构建 wait-for graph 并检测环。
     * 检测到死锁时，驱逐 workerId 字典序最小的节点。
     */
    void detectDeadlocks() {
        // 构建 wait-for graph
        // workerA → workerB 表示 workerA 在等 workerB 释放锁
        Map<String, Set<String>> waitForGraph = new HashMap<>();

        for (Map.Entry<String, LinkedBlockingQueue<LockInfo.LockRequest>> entry : waitQueues.entrySet()) {
            String resourceId = entry.getKey();
            LockInfo holder = activeLocks.get(resourceId);
            if (holder == null) continue;

            String holderWorkerId = holder.getHolderWorkerId();
            for (LockInfo.LockRequest request : entry.getValue()) {
                waitForGraph.computeIfAbsent(request.getWorkerId(), k -> new HashSet<>())
                        .add(holderWorkerId);
            }
        }

        // DFS 环检测
        for (String worker : waitForGraph.keySet()) {
            Set<String> visited = new HashSet<>();
            List<String> path = new ArrayList<>();
            if (dfsDetectCycle(worker, waitForGraph, visited, path)) {
                // 检测到环，驱逐路径中 workerId 字典序最小的
                String victim = Collections.min(path);
                logger.warn("Deadlock detected! Evicting worker: {}", victim);
                // 释放受害者持有的所有锁
                releaseWorkerLocks(victim);
            }
        }
    }

    private boolean dfsDetectCycle(String node, Map<String, Set<String>> graph,
                                    Set<String> visited, List<String> path) {
        if (path.contains(node)) {
            // 环已形成，记录路径
            path.add(node);
            return true;
        }
        if (visited.contains(node)) return false;

        visited.add(node);
        path.add(node);

        Set<String> neighbors = graph.get(node);
        if (neighbors != null) {
            for (String neighbor : neighbors) {
                if (dfsDetectCycle(neighbor, graph, visited, path)) {
                    return true;
                }
            }
        }

        path.remove(path.size() - 1);
        return false;
    }

    private void wakeWaitQueue(String resourceId) {
        LinkedBlockingQueue<LockInfo.LockRequest> queue = waitQueues.get(resourceId);
        if (queue == null || queue.isEmpty()) return;

        // 尝试为队列头部的请求授予锁
        ReentrantLock mutex = resourceMutexes.get(resourceId);
        if (mutex != null && mutex.tryLock()) {
            try {
                LockInfo.LockRequest head = queue.peek();
                if (head != null) {
                    IsolationContext.LockToken token = tryAcquire(
                            resourceId, head.getWorkerId(), head.getMode(), head.getLeaseMs());
                    if (token != null) {
                        queue.poll();
                        logger.debug("Woken waiting request: {} on {} by {}",
                                head.getMode(), resourceId, head.getWorkerId());
                    }
                }
            } finally {
                mutex.unlock();
            }
        }
    }

    /**
     * 清理已释放资源的空映射条目，防止无界增长。
     */
    private void cleanupEmptyMaps(String resourceId) {
        if (activeLocks.containsKey(resourceId)) return;
        LinkedBlockingQueue<LockInfo.LockRequest> queue = waitQueues.get(resourceId);
        if (queue == null || queue.isEmpty()) {
            waitQueues.remove(resourceId);
            resourceMutexes.remove(resourceId);
        }
    }
}
