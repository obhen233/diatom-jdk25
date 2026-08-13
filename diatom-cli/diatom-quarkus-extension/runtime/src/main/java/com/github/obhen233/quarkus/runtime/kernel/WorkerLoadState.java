package com.github.obhen233.quarkus.runtime.kernel;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker 负载状态（移植自 Spring Boot starter 的 {@code WorkerLoadState}）。
 *
 * <p>无锁 CAS 准入控制：{@link #tryAcquire()} 成功返回 true（调用方必须 {@link #release()}），
 * 满负载时返回 false 并累计拒绝计数。当前负载 {@code active / max} 供心跳上报，
 * 供 Gateway 端 {@code CapabilityRouter} 评分。
 */
public class WorkerLoadState {

    private final int maxConcurrency;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicLong rejected = new AtomicLong(0);
    private final Object agentLock = new Object();

    public WorkerLoadState(int maxConcurrency) {
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    /** CAS 准入：有槽位返回 true，否则计数拒绝并返回 false。 */
    public boolean tryAcquire() {
        int current = activeTasks.get();
        while (current < maxConcurrency) {
            if (activeTasks.compareAndSet(current, current + 1)) {
                return true;
            }
            current = activeTasks.get();
        }
        rejected.incrementAndGet();
        return false;
    }

    public void release() {
        activeTasks.decrementAndGet();
    }

    public int getActiveTasks() {
        return activeTasks.get();
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public long getRejectedCount() {
        return rejected.get();
    }

    public double getCurrentLoad() {
        return Math.min(1.0, (double) activeTasks.get() / maxConcurrency);
    }

    public Object getAgentLock() {
        return agentLock;
    }
}
