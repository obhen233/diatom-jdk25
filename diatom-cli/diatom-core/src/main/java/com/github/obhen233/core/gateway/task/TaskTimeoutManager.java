package com.github.obhen233.core.gateway.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务超时管理器
 * 检测长时间无进展的任务，触发 TIMEOUT_SOON 警告或自动 SUSPENDED
 */
public class TaskTimeoutManager {
    private static final Logger logger = LoggerFactory.getLogger(TaskTimeoutManager.class);

    private static final long DEFAULT_TASK_TIMEOUT_MS = 600_000;       // 10分钟
    private static final long DEFAULT_TIMEOUT_WARN_MS = 480_000;       // 8分钟 (TIMEOUT_SOON)
    private static final long SCAN_INTERVAL_MS = 30_000;               // 30秒扫描一次

    private final TaskManager taskManager;
    private final ScheduledExecutorService scheduler;
    private final long taskTimeoutMs;
    private final long timeoutWarnMs;
    private volatile boolean running = true;

    public TaskTimeoutManager(TaskManager taskManager) {
        this(taskManager, DEFAULT_TASK_TIMEOUT_MS, DEFAULT_TIMEOUT_WARN_MS);
    }

    public TaskTimeoutManager(TaskManager taskManager, long taskTimeoutMs, long timeoutWarnMs) {
        this.taskManager = taskManager;
        this.taskTimeoutMs = taskTimeoutMs;
        this.timeoutWarnMs = timeoutWarnMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-timeout-scanner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动超时扫描
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::scanForTimeouts,
                SCAN_INTERVAL_MS, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("Task timeout manager started (timeout={}ms, warn={}ms)",
                taskTimeoutMs, timeoutWarnMs);
    }

    /**
     * 停止扫描
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    private void scanForTimeouts() {
        if (!running) return;

        for (TaskState task : taskManager.getActiveTasks()) {
            if (task.getStatus() != TaskStatus.IN_PROGRESS) continue;

            long idleTime = task.getIdleTimeMs();

            if (idleTime >= taskTimeoutMs) {
                // 超时 → 自动 SUSPENDED
                logger.warn("Task timeout: {} (idle={}ms, timeout={}ms), suspending...",
                        task.getTaskId(), idleTime, taskTimeoutMs);
                taskManager.suspendTask(task.getTaskId(),
                        "Task auto-suspended due to timeout",
                        "", 0);
                task.addAttribute("timeoutReason", "execution_timeout");

            } else if (idleTime >= timeoutWarnMs) {
                // TIMEOUT_SOON 警告
                long remaining = taskTimeoutMs - idleTime;
                logger.warn("Task TIMEOUT_SOON: {} (idle={}ms, remaining={}ms)",
                        task.getTaskId(), idleTime, remaining);
                task.addAttribute("timeoutWarning", true);
                task.addAttribute("remainingTimeMs", remaining);
            }
        }
    }
}
