package com.github.obhen233.starter.worker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker 侧并发与负载状态。
 *
 * <p>提供三件事：
 * <ul>
 *   <li><b>准入控制</b>：{@link #tryAcquire()} 在达到 {@code maxConcurrency} 时返回
 *       false，调用方应返回 503/429 让 Gateway 转排队或换 worker。</li>
 *   <li><b>负载上报</b>：{@link #getActiveTasks()} / {@link #getCurrentLoad()}
 *       供心跳上报，Gateway 侧 {@code CapabilityRouter} 据此分流。</li>
 *   <li><b>串行锁</b>：{@code WorkerRestController} 与 IDE 本地 AI 通道统一在共享
 *       {@code ReActAgent} 实例上 synchronized（Agent 非线程安全）。{@link #getAgentLock()}
 *       仅保留作向后兼容。</li>
 * </ul>
 */
public class WorkerLoadState {

    private final int maxConcurrency;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicLong rejected = new AtomicLong(0);
    /** 串行化本地 Agent 执行的锁（Agent 非线程安全） */
    private final Object agentLock = new Object();

    public WorkerLoadState(int maxConcurrency) {
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    /**
     * 尝试占用一个并发槽位。
     *
     * @return true 表示获得槽位（调用方处理完必须 {@link #release()}）；
     *         false 表示已满，调用方应返回 503/429。
     */
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

    /** 释放一个并发槽位（与 {@link #tryAcquire()} 配对）。 */
    public void release() {
        activeTasks.decrementAndGet();
    }

    /** 当前活跃任务数。 */
    public int getActiveTasks() {
        return activeTasks.get();
    }

    /** 最大并发数（配置值，至少为 1）。 */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /** 因满载被拒绝的请求总数（监控用）。 */
    public long getRejectedCount() {
        return rejected.get();
    }

    /** 当前负载 0.0~1.0 = activeTasks / maxConcurrency。 */
    public double getCurrentLoad() {
        return Math.min(1.0, (double) activeTasks.get() / maxConcurrency);
    }

    /**
     * 串行化本地 Agent 执行的锁（向后兼容保留）。
     *
     * <p>当前 Worker 任务执行与 IDE 本地 AI 通道统一在共享 {@code ReActAgent} 实例上
     * synchronized，两者共享同一监视器。此方法不再被 starter 使用，仅供自定义集成方沿用。</p>
     */
    public Object getAgentLock() {
        return agentLock;
    }
}
