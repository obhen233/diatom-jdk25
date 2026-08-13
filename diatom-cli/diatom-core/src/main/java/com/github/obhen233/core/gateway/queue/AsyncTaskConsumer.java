package com.github.obhen233.core.gateway.queue;

import com.github.obhen233.core.gateway.GatewayHttpServerCallback;
import com.github.obhen233.spi.TaskQueueProvider;
import com.github.obhen233.spi.TaskQueueProvider.QueuedTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台任务消费者，从 {@link TaskQueueProvider} 拉取任务并委托给
 * {@link GatewayHttpServerCallback} 处理。
 * <p>
 * 使用固定大小线程池并发处理多个任务。
 * </p>
 */
public class AsyncTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskConsumer.class);

    private final TaskQueueProvider queue;
    private final GatewayHttpServerCallback callback;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int concurrency;

    /**
     * @param queue       任务队列
     * @param callback    处理回调（实际执行请求路由和 Worker 调度的逻辑）
     * @param concurrency 最大并发处理数
     */
    public AsyncTaskConsumer(TaskQueueProvider queue, GatewayHttpServerCallback callback, int concurrency) {
        this.queue = queue;
        this.callback = callback;
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("async-task-consumer-", 0).factory());
        this.concurrency = concurrency;
    }

    /**
     * 启动后台消费者线程。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        log.info("Async task consumer started: concurrency={}, queue={}", concurrency, queue.getName());
        for (int i = 0; i < concurrency; i++) {
            executor.submit(new ConsumerTask());
        }
    }

    /**
     * 停止后台消费者线程。
     */
    public void shutdown() {
        running.set(false);
        executor.shutdownNow();
        log.info("Async task consumer stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 单个消费者任务：循环从队列拉取并处理。
     */
    private class ConsumerTask implements Runnable {
        @Override
        public void run() {
            while (running.get()) {
                try {
                    QueuedTask task = queue.dequeue();
                    if (task == null) {
                        continue; // timeout, re-loop
                    }
                    log.info("Processing queued task: {} (queueDepth={})", task.getTaskId(), queue.getQueueDepth());
                    callback.processTask(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Unexpected error processing queued task", e);
                }
            }
        }
    }
}
