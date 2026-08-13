package com.github.obhen233.core.gateway.transport;

import com.github.obhen233.core.gateway.model.ChatRequest;
import com.github.obhen233.core.gateway.registry.WorkerInfo;

/**
 * SPI interface for asynchronous task transport.
 * <p>
 * Implementations handle the actual communication between Gateway and Worker,
 * supporting different transport mechanisms: HTTP, message queue, in-memory, etc.
 * </p>
 */
public interface AsyncTaskTransport {

    /**
     * Send a chat task to a worker asynchronously.
     * The implementation must not block the calling thread;
     * it should dispatch the request and invoke the callback upon completion.
     *
     * @param worker    the target worker
     * @param request   the chat request payload
     * @param timeoutMs read timeout in milliseconds
     * @param callback  callback for result notification
     */
    void sendTaskAsync(WorkerInfo worker, ChatRequest request,
                       long timeoutMs, TransportCallback callback);

    /**
     * Returns a short identifier for this transport type (e.g. "http", "mq", "memory").
     */
    String getTransportType();
}
