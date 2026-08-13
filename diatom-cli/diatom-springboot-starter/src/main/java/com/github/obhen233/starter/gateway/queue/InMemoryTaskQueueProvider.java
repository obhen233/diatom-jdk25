package com.github.obhen233.starter.gateway.queue;

import com.github.obhen233.spi.TaskQueueProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 默认内存队列实现（不经过 SPI，由 Spring 直接管理）。
 *
 * <p>基于 {@link LinkedBlockingQueue}，适用于单机场景。</p>
 */
public class InMemoryTaskQueueProvider implements TaskQueueProvider {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryTaskQueueProvider.class);

    private final BlockingQueue<QueuedTask> queue = new LinkedBlockingQueue<>();

    @Override
    public void enqueue(QueuedTask task) {
        queue.offer(task);
        logger.debug("Task enqueued: {} (queueDepth={})", task.getTaskId(), queue.size());
    }

    @Override
    public QueuedTask dequeue() throws InterruptedException {
        return queue.take();
    }

    @Override
    public int getQueueDepth() {
        return queue.size();
    }

    @Override
    public void init(Properties config) {
        logger.info("InMemoryTaskQueue initialized");
    }

    @Override
    public void shutdown() {
        logger.info("InMemoryTaskQueue shut down, {} tasks remaining", queue.size());
        queue.clear();
    }

    @Override
    public String getName() {
        return "in-memory";
    }
}
