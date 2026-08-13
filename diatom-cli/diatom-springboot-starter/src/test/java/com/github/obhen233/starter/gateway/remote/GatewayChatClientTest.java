package com.github.obhen233.starter.gateway.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link GatewayChatClient} 契约测试。
 *
 * <p>用 {@code com.sun.net.httpserver.HttpServer} 起一个 fake 远端 Gateway，验证：
 * SSE 正常流式解析（routed/token/complete）、SSE error 事件经 {@code content} 字段解析、
 * {@code /gateway/v1/chat/stream} 404 时降级到非流式 {@code chat()}、
 * 以及非流式响应中 {@code worker} 对象（starter 格式）的解析。</p>
 */
public class GatewayChatClientTest {

    /** SSE 正常流：routed → token → complete */
    @Test
    public void parsesSseStream() throws Exception {
        FakeRemoteGateway server = new FakeRemoteGateway();
        server.sseResponse = "data: {\"type\":\"routed\",\"taskId\":\"t-1\",\"worker\":\"w-1\"}\n\n"
                + "data: {\"type\":\"token\",\"content\":\"Hello\"}\n\n"
                + "data: {\"type\":\"complete\",\"taskId\":\"t-1\",\"worker\":\"w-1\"}\n\n";
        try {
            GatewayChatClient client = new GatewayChatClient(server.getBaseUrl());
            CollectingHandler handler = new CollectingHandler();
            client.chatStream("hi", "s-1", null, handler);

            assertEquals("t-1", handler.routedTaskId.get());
            assertEquals("w-1", handler.routedWorker.get());
            assertEquals(1, handler.tokens.size());
            assertEquals("Hello", handler.tokens.get(0));
            assertTrue("expected onComplete, got: " + handler.error.get(), handler.completed.get() > 0);
            assertNull(handler.error.get());
        } finally {
            server.stop();
        }
    }

    /** SSE error 事件经 content 字段携带错误信息（core SseEvent.error 的格式） */
    @Test
    public void parsesSseErrorFromContentField() throws Exception {
        FakeRemoteGateway server = new FakeRemoteGateway();
        server.sseResponse = "data: {\"type\":\"error\",\"content\":\"boom\"}\n\n";
        try {
            GatewayChatClient client = new GatewayChatClient(server.getBaseUrl());
            CollectingHandler handler = new CollectingHandler();
            client.chatStream("hi", "s-1", null, handler);

            assertEquals("boom", handler.error.get());
            assertEquals(0, handler.tokens.size());
        } finally {
            server.stop();
        }
    }

    /** /gateway/v1/chat/stream 404 → 降级到非流式 chat()，一次性回传全部内容 */
    @Test
    public void fallsBackToNonStreamingChatOn404() throws Exception {
        FakeRemoteGateway server = new FakeRemoteGateway();
        server.streamStatus.set(404);
        server.chatResponse = "{\"taskId\":\"t-1\",\"worker\":\"w-1\","
                + "\"status\":\"completed\",\"response\":\"full result\"}";
        try {
            GatewayChatClient client = new GatewayChatClient(server.getBaseUrl());
            CollectingHandler handler = new CollectingHandler();
            client.chatStream("hi", "s-1", null, handler);

            assertTrue(handler.tokens.contains("full result"));
            assertTrue("expected onComplete after fallback", handler.completed.get() > 0);
            assertNull(handler.error.get());
        } finally {
            server.stop();
        }
    }

    /** 非流式响应中 worker 为对象（starter 格式 {id,url,model}）→ 提取 id */
    @Test
    public void parsesWorkerObjectInNonStreamingChat() throws Exception {
        FakeRemoteGateway server = new FakeRemoteGateway();
        server.chatResponse = "{\"taskId\":\"t-1\",\"worker\":{\"id\":\"w-1\",\"url\":\"http://127.0.0.1:9000\",\"model\":\"gpt-4o\"},"
                + "\"status\":\"completed\",\"response\":\"hi\"}";
        try {
            GatewayChatClient client = new GatewayChatClient(server.getBaseUrl());
            GatewayChatClient.ChatResponse resp = client.chat("hi", "s-1", null);

            assertEquals("w-1", resp.getWorker());
            assertEquals("hi", resp.getResponse());
            assertNotNull(resp.getTaskId());
        } finally {
            server.stop();
        }
    }

    // ============ helpers ============

    private static class CollectingHandler implements GatewayChatClient.SseEventHandler {
        final AtomicReference<String> routedTaskId = new AtomicReference<>();
        final AtomicReference<String> routedWorker = new AtomicReference<>();
        final List<String> tokens = new ArrayList<>();
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicInteger completed = new AtomicInteger();

        @Override public void onRouted(String taskId, String worker) {
            routedTaskId.set(taskId);
            routedWorker.set(worker);
        }
        @Override public void onToken(String content) {
            tokens.add(content);
        }
        @Override public void onComplete(String taskId, String worker, Object fileDiffs) {
            completed.incrementAndGet();
        }
        @Override public void onError(String err) {
            error.set(err);
        }
    }

    /** fake 远端 Gateway：/gateway/v1/chat/stream 返回 SSE，/gateway/v1/chat 返回 JSON。 */
    private static class FakeRemoteGateway {
        final HttpServer server;
        final AtomicInteger streamStatus = new AtomicInteger(200);
        volatile String sseResponse = "";
        volatile String chatResponse = "{\"status\":\"ok\"}";

        FakeRemoteGateway() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod())
                    && "/gateway/v1/chat/stream".equals(path)) {
                readBody(exchange);
                if (streamStatus.get() == 404) {
                    respond(exchange, 404, "{\"error\":\"not found\"}");
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, sseResponse.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(sseResponse.getBytes(StandardCharsets.UTF_8));
                }
            } else if ("POST".equals(exchange.getRequestMethod())
                    && "/gateway/v1/chat".equals(path)) {
                readBody(exchange);
                respond(exchange, 200, chatResponse);
            } else {
                respond(exchange, 404, "{\"error\":\"not found\"}");
            }
        }

        String getBaseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private static String readBody(HttpExchange exchange) throws IOException {
            byte[] buf = new byte[4096];
            try (InputStream is = exchange.getRequestBody()) {
                int n = is.read(buf);
                return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
            }
        }

        private static void respond(HttpExchange exchange, int code, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
