package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.spi.http.ServerResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JDK {@link HttpExchange} to {@link ServerResponse} adapter.
 */
class JdkServerResponse implements ServerResponse {

    private final HttpExchange exchange;
    private int statusCode = 200;
    private boolean headersSent = false;

    JdkServerResponse(HttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void setStatus(int statusCode) {
        this.statusCode = statusCode;
    }

    @Override
    public void setHeader(String name, String value) {
        exchange.getResponseHeaders().set(name, value);
    }

    @Override
    public void send(String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (!headersSent) {
            if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            }
            exchange.sendResponseHeaders(statusCode, bytes.length);
            headersSent = true;
        }
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        if (!headersSent) {
            exchange.sendResponseHeaders(statusCode, 0);
            headersSent = true;
        }
        return exchange.getResponseBody();
    }

    /**
     * Send response headers (used when streaming/chunked response needed).
     */
    void sendResponseHeaders(long contentLength) throws IOException {
        if (!headersSent) {
            exchange.sendResponseHeaders(statusCode, contentLength);
            headersSent = true;
        }
    }
}
