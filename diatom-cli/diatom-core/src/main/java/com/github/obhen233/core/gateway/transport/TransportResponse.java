package com.github.obhen233.core.gateway.transport;

/**
 * DTO representing the result of an asynchronous transport operation.
 */
public class TransportResponse {

    private final int statusCode;
    private final String body;
    private final long durationMs;

    public TransportResponse(int statusCode, String body, long durationMs) {
        this.statusCode = statusCode;
        this.body = body;
        this.durationMs = durationMs;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBody() {
        return body;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
