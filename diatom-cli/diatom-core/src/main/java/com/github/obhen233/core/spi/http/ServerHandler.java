package com.github.obhen233.core.spi.http;

import java.io.IOException;

/**
 * Functional interface for HTTP request handlers.
 *
 * <p>Replaces direct use of {@code com.sun.net.httpserver.HttpExchange} with
 * abstract {@link ServerRequest} and {@link ServerResponse} types.</p>
 */
@FunctionalInterface
public interface ServerHandler {

    /**
     * Handle an incoming HTTP request.
     * @param request  the incoming request
     * @param response the response to write to
     * @throws IOException if an I/O error occurs
     */
    void handle(ServerRequest request, ServerResponse response) throws IOException;
}
