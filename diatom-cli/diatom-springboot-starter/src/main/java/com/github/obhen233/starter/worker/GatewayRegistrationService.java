package com.github.obhen233.starter.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gateway 注册服务。
 *
 * <p>在 {@code diatam.mode=worker} 和 {@code diatam.mode=adapter} 模式下共享使用。
 * 职责：
 * <ol>
 *   <li>启动时向 Gateway 注册自身</li>
 *   <li>定时发送心跳</li>
 *   <li>关闭时从 Gateway 注销</li>
 * </ol>
 */
public class GatewayRegistrationService implements DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(GatewayRegistrationService.class);
    private static final long HEARTBEAT_INTERVAL_SECONDS = 10;

    private final DiatomWorkerProperties properties;
    private final Environment environment;
    private final WorkerLoadState loadState;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "worker-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean registered = new AtomicBoolean(false);

    /** 自动生成的 workerId */
    private String workerId;

    /** 外部可见的 host */
    private String externalHost;

    /** 外部可见的 port */
    private int externalPort;

    public GatewayRegistrationService(DiatomWorkerProperties properties, Environment environment,
                                      WorkerLoadState loadState) {
        this.properties = properties;
        this.environment = environment;
        this.loadState = loadState != null ? loadState : new WorkerLoadState(properties.getMaxConcurrency());
        initIdentity();
    }

    private void initIdentity() {
        String name = properties.getName();
        if (name == null || name.trim().isEmpty()) {
            name = "spring-worker-" + UUID.randomUUID().toString().substring(0, 8);
        }
        this.workerId = name;

        String configuredHost = properties.getExternalHost();
        if (configuredHost != null && !configuredHost.isEmpty()) {
            this.externalHost = configuredHost;
        } else {
            try {
                this.externalHost = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                this.externalHost = "127.0.0.1";
            }
        }

        String configuredPort = properties.getExternalPort();
        if (configuredPort != null && !configuredPort.isEmpty()) {
            this.externalPort = Integer.parseInt(configuredPort);
        } else {
            String serverPort = environment.getProperty("server.port", "8080");
            this.externalPort = Integer.parseInt(serverPort);
        }
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getExternalHost() {
        return externalHost;
    }

    public int getExternalPort() {
        return externalPort;
    }

    @PostConstruct
    public void start() {
        if (!hasGatewayUrl()) {
            logger.info("diatom.worker.gateway-url not configured, skipping direct Gateway registration "
                    + "(use Spring Cloud discovery or set gateway-url to register directly)");
            return;
        }
        registerWithGateway();
        startHeartbeat();
    }

    /**
     * 是否配置了直连 Gateway 地址。未配置时跳过直连注册/心跳/注销。
     */
    private boolean hasGatewayUrl() {
        return properties.getGatewayUrl() != null
                && !properties.getGatewayUrl().trim().isEmpty();
    }

    /**
     * 向 Gateway 注册。
     */
    public void registerWithGateway() {
        if (!hasGatewayUrl()) {
            logger.debug("Gateway URL not configured, skipping registration");
            return;
        }
        String gatewayUrl = normalizeUrl(properties.getGatewayUrl());
        String url = gatewayUrl + "/gateway/v1/workers";
        String json = buildRegistrationJson();

        try {
            logger.info("Registering worker to Gateway: {}", url);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                registered.set(true);
                logger.info("Worker registered successfully: {} at {}:{}", workerId, externalHost, externalPort);
            } else if (code == 409) {
                logger.warn("Worker ID '{}' already registered with Gateway (HTTP 409)", workerId);
                registered.set(true);
            } else {
                logger.warn("Worker registration failed (HTTP {}): {}", code, readResponse(conn));
            }
            conn.disconnect();
        } catch (Exception e) {
            logger.warn("Failed to register with Gateway ({}): {}", url, e.getMessage());
        }
    }

    /**
     * 启动定时心跳。
     */
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        logger.debug("Heartbeat scheduler started (interval: {}s)", HEARTBEAT_INTERVAL_SECONDS);
    }

    /**
     * 发送心跳到 Gateway。
     */
    private void sendHeartbeat() {
        if (!hasGatewayUrl()) {
            return;
        }
        if (!registered.get()) {
            // Gateway 可能重启过导致注册丢失，重新注册
            registerWithGateway();
            return;
        }
        String gatewayUrl = normalizeUrl(properties.getGatewayUrl());
        String url = gatewayUrl + "/gateway/v1/workers/" + workerId + "/heartbeat";

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            String body = buildHeartbeatJson();
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 404) {
                // Gateway 重启过，重新注册
                logger.info("Worker not found in Gateway registry (heartbeat 404), re-registering...");
                registerWithGateway();
            } else if (code != 200) {
                logger.debug("Heartbeat returned HTTP {}", code);
            }
            conn.disconnect();
        } catch (Exception e) {
            logger.debug("Failed to send heartbeat ({}): {}", url, e.getMessage());
        }
    }

    /**
     * 从 Gateway 注销。
     */
    public void deregisterFromGateway() {
        if (!hasGatewayUrl()) return;
        if (!registered.get()) return;
        String gatewayUrl = normalizeUrl(properties.getGatewayUrl());
        String url = gatewayUrl + "/gateway/v1/workers/" + workerId;

        try {
            logger.info("Deregistering worker from Gateway: {}", url);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(5000);

            int code = conn.getResponseCode();
            logger.info("Worker deregistered (HTTP {})", code);
            conn.disconnect();
        } catch (Exception e) {
            logger.warn("Failed to deregister from Gateway: {}", e.getMessage());
        }
        registered.set(false);
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deregisterFromGateway();
    }

    private String buildRegistrationJson() {
        String model = properties.getModel();
        if (model == null || model.isEmpty()) {
            model = System.getProperty("diatom.model", "unknown");
        }
        String group = properties.getGroup();
        if (group == null || group.isEmpty()) {
            group = "default";
        }
        return "{"
            + "\"workerId\":\"" + escapeJson(workerId) + "\","
            + "\"host\":\"" + escapeJson(externalHost) + "\","
            + "\"port\":" + externalPort + ","
            + "\"model\":\"" + escapeJson(model) + "\","
            + "\"group\":\"" + escapeJson(group) + "\","
            + "\"maxConcurrency\":" + loadState.getMaxConcurrency() + ","
            + "\"status\":\"ONLINE\""
            + "}";
    }

    /**
     * 心跳上报真实负载：活跃任务数 / 最大并发数 → currentLoad 0~1，
     * Gateway 侧 CapabilityRouter 的 effectiveScore 据此分流。
     */
    private String buildHeartbeatJson() {
        int active = loadState.getActiveTasks();
        double load = loadState.getCurrentLoad();
        return "{\"currentLoad\":" + load + ",\"activeTasks\":" + active
                + ",\"maxConcurrency\":" + loadState.getMaxConcurrency() + "}";
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "http://" + trimmed;
        }
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String readResponse(HttpURLConnection conn) {
        try {
            byte[] buf = new byte[1024];
            int n = conn.getInputStream().read(buf);
            if (n > 0) return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        try {
            byte[] buf = new byte[1024];
            int n = conn.getErrorStream().read(buf);
            if (n > 0) return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        return "";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
