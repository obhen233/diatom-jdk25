package com.github.obhen233.adapter.spi;

import java.io.IOException;

/**
 * Abstraction over an HTTP request (adapter module).
 *
 * <p>Decouples handler code from {@code com.sun.net.httpserver.HttpExchange}.</p>
 */
public interface ServerRequest {

    /**
     * Read the full request body as a UTF-8 string.
     */
    String getBody() throws IOException;

    /**
     * Read the full request body as raw bytes.
     */
    byte[] getBodyBytes() throws IOException;

    /**
     * Get a query parameter value by name.
     * @param name the parameter name
     * @return the parameter value, or null if not present
     */
    String getQueryParam(String name);

    /**
     * Get the HTTP method (GET, POST, PUT, DELETE, etc.).
     */
    String getMethod();

    /**
     * Get a request header value by name.
     * @param name the header name
     * @return the header value, or null if not present
     */
    String getHeader(String name);
}
