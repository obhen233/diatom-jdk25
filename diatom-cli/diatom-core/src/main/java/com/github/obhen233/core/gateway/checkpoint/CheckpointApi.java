package com.github.obhen233.core.gateway.checkpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * /gateway/v1/checkpoint 端点处理
 * 实际处理在 GatewayHttpServer 中集成
 */
public class CheckpointApi {
    private final CheckpointService checkpointService;

    public CheckpointApi(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    public void register(HttpServer server) {
        server.createContext("/gateway/v1/checkpoint", this::handle);
    }

    private void handle(HttpExchange exchange) throws IOException {
        // Delegated to GatewayHttpServer for simplicity
        String json = "{\"status\":\"ok\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
