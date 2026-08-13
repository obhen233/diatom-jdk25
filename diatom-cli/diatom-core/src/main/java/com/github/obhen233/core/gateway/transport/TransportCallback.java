package com.github.obhen233.core.gateway.transport;

/**
 * Callback interface for asynchronous transport results.
 */
public interface TransportCallback {

    /**
     * Called when the transport successfully receives a response from the worker.
     */
    void onSuccess(String workerId, TransportResponse response);

    /**
     * Called when the transport encounters a failure communicating with the worker.
     */
    void onFailure(String workerId, String error);

    /**
     * Called when the request times out waiting for a response from the worker.
     */
    void onTimeout(String workerId);
}
