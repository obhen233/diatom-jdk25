package com.github.obhen233.starter.gateway.child;

import com.github.obhen233.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 子 Gateway 上行 HTTP 自注册契约测试。
 *
 * <p>用 {@code com.sun.net.httpserver.HttpServer} 起一个 fake 父 Gateway，验证：
 * 注册 body 含 tier=gateway-proxy、心跳周期性到达、404 → 自动重注册、注销 DELETE、
 * upstreamUrl 为空时零 HTTP 请求。</p>
 */
public class ChildGatewayRegistrationServiceTest {

    /** 注册 POST body 含 tier=gateway-proxy 且心跳周期性到达 */
    @Test
    public void registersWithTierAndSendsPeriodicHeartbeats() throws Exception {
        FakeParentGateway parent = new FakeParentGateway();
        ChildGatewayRegistrationService service = newService(parent.getBaseUrl(), 1);
        try {
            service.start();

            await(() -> parent.heartbeatCount.get() >= 1, 5000);

            assertEquals(1, parent.registerCount.get());
            Map<String, Object> reg = parent.registerBodies.get(0);
            assertEquals("gateway-proxy", reg.get("tier"));
            assertEquals("ONLINE", reg.get("status"));
            assertNotNull(reg.get("host"));
            assertNotNull(reg.get("port"));
            assertTrue("expected heartbeatCount >= 1, got " + parent.heartbeatCount.get(),
                    parent.heartbeatCount.get() >= 1);
        } finally {
            service.destroy();
            parent.stop();
        }
    }

    /** 心跳返回 404（父 Gateway 重启）→ 自动重新注册 */
    @Test
    public void heartbeat404TriggersReRegister() throws Exception {
        FakeParentGateway parent = new FakeParentGateway();
        parent.heartbeatRespond404.set(true);
        ChildGatewayRegistrationService service = newService(parent.getBaseUrl(), 1);
        try {
            service.start();

            await(() -> parent.registerCount.get() >= 2, 6000);

            assertTrue("expected re-register after heartbeat 404, registerCount=" + parent.registerCount.get(),
                    parent.registerCount.get() >= 2);
            assertTrue(parent.heartbeatCount.get() >= 1);
        } finally {
            service.destroy();
            parent.stop();
        }
    }

    /** destroy() 时从父 Gateway 注销（DELETE） */
    @Test
    public void destroyDeregistersFromUpstream() throws Exception {
        FakeParentGateway parent = new FakeParentGateway();
        ChildGatewayRegistrationService service = newService(parent.getBaseUrl(), 1);
        try {
            service.start();
            await(() -> parent.registerCount.get() == 1, 5000);
        } finally {
            service.destroy();
            parent.stop();
        }
        assertEquals(1, parent.deregisterCount.get());
    }

    /** upstreamUrl 为空 → 仅走注册中心，零 HTTP 请求 */
    @Test
    public void emptyUpstreamUrlSendsNoHttpRequests() throws Exception {
        FakeParentGateway parent = new FakeParentGateway();
        ChildGatewayRegistrationService service = newService("", 1);
        try {
            service.start();
            Thread.sleep(1500);
        } finally {
            service.destroy();
            parent.stop();
        }
        assertEquals(0, parent.registerCount.get());
        assertEquals(0, parent.heartbeatCount.get());
        assertEquals(0, parent.deregisterCount.get());
    }

    // ============ helpers ============

    private ChildGatewayRegistrationService newService(String upstreamUrl, int heartbeatSeconds) {
        ChildGatewayProperties props = new ChildGatewayProperties();
        props.setUpstreamUrl(upstreamUrl);
        props.setName("child-gw-test");
        props.setModel("test-model");
        props.setHeartbeatIntervalSeconds(heartbeatSeconds);
        return new ChildGatewayRegistrationService(props, stubEnvironment(), null);
    }

    private static Environment stubEnvironment() {
        return new StubEnvironment().withProperty("server.port", "8081");
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
    }

    /** 极简 Environment 实现：仅测试所需的 getProperty(String, String)。 */
    private static class StubEnvironment implements Environment {
        private final Map<String, String> props = new HashMap<>();

        StubEnvironment withProperty(String key, String value) {
            props.put(key, value);
            return this;
        }

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

    /** fake 父 Gateway：记录 POST/PUT/DELETE 请求并返回可配置状态码。 */
    private static class FakeParentGateway {
        final HttpServer server;
        final AtomicInteger registerCount = new AtomicInteger();
        final AtomicInteger heartbeatCount = new AtomicInteger();
        final AtomicInteger deregisterCount = new AtomicInteger();
        final List<Map<String, Object>> registerBodies = new ArrayList<>();
        final AtomicBoolean heartbeatRespond404 = new AtomicBoolean(false);

        FakeParentGateway() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && "/gateway/v1/workers".equals(path)) {
                String body = readBody(exchange);
                registerBodies.add(JsonUtils.fromJson(body, Map.class));
                registerCount.incrementAndGet();
                respond(exchange, 200, "{\"status\":\"registered\"}");
            } else if ("PUT".equals(method) && path.matches("/gateway/v1/workers/.*/heartbeat")) {
                readBody(exchange);
                heartbeatCount.incrementAndGet();
                respond(exchange, heartbeatRespond404.get() ? 404 : 200,
                        heartbeatRespond404.get() ? "{\"error\":\"not found\"}" : "{\"status\":\"ok\"}");
            } else if ("DELETE".equals(method) && path.matches("/gateway/v1/workers/.*")) {
                deregisterCount.incrementAndGet();
                respond(exchange, 200, "{\"status\":\"deregistered\"}");
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
