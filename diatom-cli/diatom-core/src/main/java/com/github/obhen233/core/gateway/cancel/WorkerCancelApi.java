package com.github.obhen233.core.gateway.cancel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Worker 取消端点 /worker/{id}/v1/cancel
 * 实际处理在 WorkerHttpServer 中集成
 */
public class WorkerCancelApi {

    public void register(HttpServer server) {
        server.createContext("/worker/v1/cancel", this::handle);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String json = "{\"status\":\"ok\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
