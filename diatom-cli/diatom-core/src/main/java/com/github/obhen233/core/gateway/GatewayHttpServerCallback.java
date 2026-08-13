package com.github.obhen233.core.gateway;

import com.github.obhen233.spi.TaskQueueProvider.QueuedTask;

/**
 * 回调接口，用于 {@link com.github.obhen233.core.gateway.queue.AsyncTaskConsumer}
 * 将队列中的任务交给 Gateway 处理。
 * <p>
 * {@link com.github.obhen233.core.gateway.http.GatewayHttpServer} 实现此接口，
 * 在 {@link #processTask(QueuedTask)} 中执行实际的请求路由和 Worker 调度逻辑。
 * </p>
 */
public interface GatewayHttpServerCallback {

    /**
     * Process a queued task asynchronously.
     * <p>
     * This method is called by the {@code AsyncTaskConsumer} on a background thread.
     * It should perform the same routing and worker dispatch logic as the synchronous
     * {@code /gateway/v1/chat} handler.
     * </p>
     *
     * @param task the queued task to process
     */
    void processTask(QueuedTask task);
}
