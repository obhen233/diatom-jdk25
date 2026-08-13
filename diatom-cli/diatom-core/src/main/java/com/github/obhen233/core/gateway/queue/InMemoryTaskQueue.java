package com.github.obhen233.core.gateway.queue;

import com.github.obhen233.spi.TaskQueueProvider;

import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 基于 {@link BlockingQueue} 的内存队列，Gateway 队列模式的内置默认实现。
 * <p>
 * 适用于单机场景，队列数据不持久化，Gateway 重启后队列中的任务会丢失。
 * 生产环境建议使用 Kafka、RabbitMQ 等持久化队列，通过 SPI 注册自定义
 * {@link TaskQueueProvider} 实现。
 * </p>
 *
 * <p>优先级：0（默认），自定义 SPI 实现优先级高于此值时将被优先选择。</p>
 */
public class InMemoryTaskQueue implements TaskQueueProvider {

    private final BlockingQueue<QueuedTask> queue = new LinkedBlockingQueue<QueuedTask>();
    private volatile int capacity = 10000;

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void enqueue(QueuedTask task) {
        if (!queue.offer(task)) {
            throw new IllegalStateException("Task queue is full (capacity=" + capacity + ")");
        }
    }

    @Override
    public QueuedTask dequeue() throws InterruptedException {
        return queue.poll(5, TimeUnit.SECONDS);
    }

    @Override
    public int getQueueDepth() {
        return queue.size();
    }

    @Override
    public void init(Properties config) {
        if (config != null) {
            String capStr = config.getProperty("capacity");
            if (capStr != null) {
                try {
                    this.capacity = Integer.parseInt(capStr.trim());
                } catch (NumberFormatException e) {
                    // ignore, use default
                }
            }
        }
    }

    @Override
    public void shutdown() {
        queue.clear();
    }

    @Override
    public String getName() {
        return "in-memory";
    }
}
