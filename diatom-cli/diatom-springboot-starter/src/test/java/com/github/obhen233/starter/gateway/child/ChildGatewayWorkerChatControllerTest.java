package com.github.obhen233.starter.gateway.child;

import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.registry.RegistryEvent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.starter.gateway.SpringGatewayTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 子 Gateway Worker 代理端点契约测试。
 *
 * <p>直接实例化 {@link ChildGatewayWorkerChatController}，stub {@link GatewayAgent} /
 * {@link CapabilityRouter}，用真实 {@link SpringGatewayTransport} + fake worker HTTP 服务。
 * 断言：200 {status:ok, taskId, response}、无 worker 时 503、缺 message 时 400，
 * 且转发 body 的 gatewayUrl 被改写为子节点自身外部 URL。</p>
 */
public class ChildGatewayWorkerChatControllerTest {

    /** 正常链路：子 Gateway 分析 → 路由 → 转发下挂 worker → 返回统一响应 */
    @Test
    public void forwardsToWorkerAndReturnsUnifiedResponse() throws Exception {
        FakeWorkerServer workerServer = new FakeWorkerServer();
        try {
            workerServer.responseBody = "{\"status\":\"success\",\"taskId\":\"task-1\",\"result\":\"Hello from worker\"}";

            WorkerInfo worker = new WorkerInfo("w-1", "127.0.0.1", workerServer.port());
            worker.setModel("gpt-4o");
            worker.setGroup("default");

            ChildGatewayWorkerChatController controller = buildController(workerServer, worker, true);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "hello");
            body.put("taskId", "task-1");

            ResponseEntity<Map<String, Object>> resp = controller.handleWorkerChat(body);

            assertEquals(200, resp.getStatusCode().value());
            assertEquals("ok", resp.getBody().get("status"));
            assertEquals("task-1", resp.getBody().get("taskId"));
            assertEquals("Hello from worker", resp.getBody().get("response"));

            // 转发 body 的 gatewayUrl 应改写为子节点自身外部 URL
            String forwarded = workerServer.chatBodies.get(0);
            assertTrue("expected gatewayUrl rewrite, body=" + forwarded,
                    forwarded.contains("\"gatewayUrl\":\"http://127.0.0.1:8081\""));
            assertEquals(1, workerServer.chatCount.get());
        } finally {
            workerServer.stop();
        }
    }

    /** 没有可用下挂 worker → 503 */
    @Test
    public void returns503WhenNoWorkerAvailable() throws Exception {
        FakeWorkerServer workerServer = new FakeWorkerServer();
        try {
            WorkerInfo worker = new WorkerInfo("w-1", "127.0.0.1", workerServer.port());

            ChildGatewayWorkerChatController controller = buildController(workerServer, worker, false);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "hello");
            body.put("taskId", "task-1");

            ResponseEntity<Map<String, Object>> resp = controller.handleWorkerChat(body);

            assertEquals(503, resp.getStatusCode().value());
            assertEquals("error", resp.getBody().get("status"));
            assertEquals("No available workers", resp.getBody().get("error"));
            assertEquals(0, workerServer.chatCount.get());
        } finally {
            workerServer.stop();
        }
    }

    /** 缺 message → 400 */
    @Test
    public void returns400WhenMessageMissing() throws Exception {
        FakeWorkerServer workerServer = new FakeWorkerServer();
        try {
            WorkerInfo worker = new WorkerInfo("w-1", "127.0.0.1", workerServer.port());

            ChildGatewayWorkerChatController controller = buildController(workerServer, worker, true);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", "task-1");

            ResponseEntity<Map<String, Object>> resp = controller.handleWorkerChat(body);

            assertEquals(400, resp.getStatusCode().value());
            assertEquals("Missing message", resp.getBody().get("error"));
            assertEquals(0, workerServer.chatCount.get());
        } finally {
            workerServer.stop();
        }
    }

    /** 下挂 worker 返回 503（过载）→ 子节点向上游返回 503，父 Gateway 换 worker 重试 */
    @Test
    public void propagatesWorkerOverloadAs503() throws Exception {
        FakeWorkerServer workerServer = new FakeWorkerServer();
        try {
            workerServer.statusCode.set(503);
            WorkerInfo worker = new WorkerInfo("w-1", "127.0.0.1", workerServer.port());

            ChildGatewayWorkerChatController controller = buildController(workerServer, worker, true);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "hello");
            body.put("taskId", "task-1");

            ResponseEntity<Map<String, Object>> resp = controller.handleWorkerChat(body);

            assertEquals(503, resp.getStatusCode().value());
            assertEquals("error", resp.getBody().get("status"));
        } finally {
            workerServer.stop();
        }
    }

    // ============ helpers ============

    private ChildGatewayWorkerChatController buildController(FakeWorkerServer workerServer,
                                                             WorkerInfo worker, boolean workerAvailable) {
        WorkerRegistry registry = new StubRegistry(worker);
        CapabilityRouter router = new CapabilityRouter(registry) {
            @Override public WorkerInfo routeWithLLMSuggestion(TaskRequirement requirement) {
                return workerAvailable ? worker : null;
            }
            @Override public int incrementActive(String workerId) { return 1; }
            @Override public int decrementActive(String workerId) { return 0; }
        };
        GatewayAgent agent = new GatewayAgent(null, null, "test-model", "http://test", registry) {
            @Override public TaskRequirement analyzeRequest(String message) {
                TaskRequirement req = new TaskRequirement();
                req.setTaskType("development");
                req.setSyncStrategy("skip");
                return req;
            }
        };
        SpringGatewayTransport transport = new SpringGatewayTransport(registry);
        ChildGatewayProperties props = new ChildGatewayProperties();
        props.setExternalHost("127.0.0.1");
        props.setExternalPort("8081");
        return new ChildGatewayWorkerChatController(agent, router, transport, props, stubEnvironment());
    }

    private static Environment stubEnvironment() {
        return new StubEnvironment();
    }

    /** 极简 Environment 实现：仅提供测试用默认值。 */
    private static class StubEnvironment implements Environment {
        private final Map<String, String> props = new HashMap<>();

        @Override public boolean containsProperty(String key) { return props.containsKey(key); }
        @Override public String getProperty(String key) { return props.get(key); }
        @Override public String getProperty(String key, String defaultValue) {
            return props.getOrDefault(key, defaultValue);
        }
        @Override public <T> T getProperty(String key, Class<T> targetType) {
            String v = props.get(key);
            return v == null ? null : convert(v, targetType);
        }
        @Override public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
            String v = props.get(key);
            return v == null ? defaultValue : convert(v, targetType);
        }
        @Override public String getRequiredProperty(String key) {
            String v = props.get(key);
            if (v == null) throw new IllegalStateException("No property: " + key);
            return v;
        }
        @Override public <T> T getRequiredProperty(String key, Class<T> targetType) {
            T v = getProperty(key, targetType);
            if (v == null) throw new IllegalStateException("No property: " + key);
            return v;
        }
        @Override public String resolvePlaceholders(String text) { return text; }
        @Override public String resolveRequiredPlaceholders(String text) { return text; }
        @Override public String[] getActiveProfiles() { return new String[0]; }
        @Override public String[] getDefaultProfiles() { return new String[0]; }
        @Override public boolean acceptsProfiles(String... profiles) { return false; }
        @Override public boolean acceptsProfiles(Profiles profiles) { return false; }

        @SuppressWarnings("unchecked")
        private static <T> T convert(String value, Class<T> targetType) {
            if (targetType == String.class) return (T) value;
            if (targetType == Integer.class || targetType == int.class) return (T) Integer.valueOf(value);
            if (targetType == Boolean.class || targetType == boolean.class) return (T) Boolean.valueOf(value);
            throw new UnsupportedOperationException("Unsupported property type: " + targetType);
        }
    }

    /** fake 下挂 worker：响应可配置状态码/body，记录收到的 chat 请求。 */
    private static class FakeWorkerServer {
        final HttpServer server;
        final AtomicInteger statusCode = new AtomicInteger(200);
        volatile String responseBody = "{\"status\":\"success\",\"result\":\"ok\"}";
        final AtomicInteger chatCount = new AtomicInteger();
        final List<String> chatBodies = new ArrayList<>();

        FakeWorkerServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())
                    && "/worker/v1/chat".equals(exchange.getRequestURI().getPath())) {
                chatBodies.add(readBody(exchange));
                chatCount.incrementAndGet();
                respond(exchange, statusCode.get(), responseBody);
            } else {
                respond(exchange, 404, "{\"error\":\"not found\"}");
            }
        }

        int port() {
            return server.getAddress().getPort();
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

    /** 内存 WorkerRegistry：getWorker 返回固定 worker。 */
    private static class StubRegistry implements WorkerRegistry {
        private final WorkerInfo worker;

        StubRegistry(WorkerInfo worker) {
            this.worker = worker;
        }

        @Override public void register(WorkerInfo w) { }
        @Override public void deregister(String workerId) { }
        @Override public void heartbeat(String workerId, WorkerMetrics metrics) { }
        @Override public List<WorkerInfo> availableWorkers() {
            List<WorkerInfo> list = new ArrayList<>();
            if (worker != null) list.add(worker);
            return list;
        }
        @Override public WorkerInfo getWorker(String workerId) {
            return worker != null && workerId.equals(worker.getWorkerId()) ? worker : null;
        }
        @Override public void markShuttingDown(String workerId) { }
        @Override public void subscribe(java.util.function.Consumer<RegistryEvent> listener) { }
        @Override public void shutdown() { }
    }
}
