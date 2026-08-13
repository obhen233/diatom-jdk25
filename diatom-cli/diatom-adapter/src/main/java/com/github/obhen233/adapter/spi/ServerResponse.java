package com.github.obhen233.adapter.spi;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Abstraction over an HTTP response (adapter module).
 *
 * <p>Decouples handler code from {@code com.sun.net.httpserver.HttpExchange}.</p>
 */
public interface ServerResponse {

    /**
     * Set the HTTP status code.
     */
    void setStatus(int statusCode);

    /**
     * Set a response header.
     * @param name  header name
     * @param value header value
     */
    void setHeader(String name, String value);

    /**
     * Send a UTF-8 JSON response body.
     * Automatically sets Content-Type to application/json if not already set.
     * @param body the response body string
     */
    void send(String body) throws IOException;

    /**
     * Get the output stream for writing chunked (SSE) or binary responses.
     */
    OutputStream getOutputStream() throws IOException;
}
