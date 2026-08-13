package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.quarkus.runtime.kernel.WorkerLoadState;
import org.jboss.logging.Logger;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gateway 直连注册服务（框架无关，镜像 starter {@code GatewayRegistrationService}）。
 *
 * <p>在 worker / adapter / child 模式下共享使用：启动时 POST
 * {@code {gateway}/gateway/v1/workers} 注册自身，定时 PUT 心跳（真实负载经
 * {@link WorkerLoadState} 上报，404 自动重注册），关闭时 DELETE 注销。
 * {@code gatewayUrl} 为空时跳过直连注册（仅打日志，不阻断启动）。
 * 全部请求 try/catch 优雅降级。</p>
 */
public class QuarkusRegistrationService {

    private static final Logger LOGGER = Logger.getLogger(QuarkusRegistrationService.class);
    private static final long HEARTBEAT_INTERVAL_SECONDS = 10;

    private final String workerId;
    private final String externalHost;
    private final int externalPort;
    private final String model;
    private final String group;
    private final String tier;
    private final int maxConcurrency;
    private final String gatewayUrl;
    private final WorkerLoadState loadState;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "diatom-worker-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public QuarkusRegistrationService(String workerId, String externalHost, int externalPort,
                                      String model, String group, String tier, int maxConcurrency,
                                      String gatewayUrl, WorkerLoadState loadState) {
        this.workerId = workerId;
        this.externalHost = externalHost;
        this.externalPort = externalPort;
        this.model = model;
        this.group = group;
        this.tier = tier;
        this.maxConcurrency = maxConcurrency;
        this.gatewayUrl = normalizeUrl(gatewayUrl);
        this.loadState = loadState;
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

    /** 是否配置了直连 Gateway 地址。未配置时跳过直连注册/心跳/注销。 */
    private boolean hasGatewayUrl() {
        return gatewayUrl != null && !gatewayUrl.isEmpty();
    }

    /** 启动注册 + 心跳。 */
    public void start() {
        if (!hasGatewayUrl()) {
            LOGGER.info("gateway-url not configured, skipping direct Gateway registration");
            return;
        }
        registerWithGateway();
        scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOGGER.debugf("Heartbeat scheduler started (interval: %ds)", HEARTBEAT_INTERVAL_SECONDS);
    }

    /** 注销 + 关闭心跳调度器。 */
    public void stop() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deregisterFromGateway();
    }

    /**
     * 向 Gateway 注册。Gateway 不可达时优雅降级，只打日志不抛异常。
     */
    public void registerWithGateway() {
        if (!hasGatewayUrl()) {
            return;
        }
        String url = gatewayUrl + "/gateway/v1/workers";
        String json = buildRegistrationJson();

        try {
            LOGGER.infof("Registering worker to Gateway: %s", url);
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
                LOGGER.infof("Worker registered successfully: %s at %s:%d", workerId, externalHost, externalPort);
            } else if (code == 409) {
                LOGGER.warnf("Worker ID '%s' already registered with Gateway (HTTP 409)", workerId);
                registered.set(true);
            } else {
                LOGGER.warnf("Worker registration failed (HTTP %d): %s", code, readResponse(conn));
            }
            conn.disconnect();
        } catch (Exception e) {
            LOGGER.warnf("Failed to register with Gateway (%s): %s", url, e.getMessage());
        }
    }

    /**
     * 定时心跳；404 表示 Gateway 重启过导致注册丢失，自动重注册。
     */
    private void sendHeartbeat() {
        if (!hasGatewayUrl()) {
            return;
        }
        if (!registered.get()) {
            registerWithGateway();
            return;
        }
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
                LOGGER.info("Worker not found in Gateway registry (heartbeat 404), re-registering...");
                registerWithGateway();
            } else if (code != 200) {
                LOGGER.debugf("Heartbeat returned HTTP %d", code);
            }
            conn.disconnect();
        } catch (Exception e) {
            LOGGER.debugf("Failed to send heartbeat (%s): %s", url, e.getMessage());
        }
    }

    /**
     * 从 Gateway 注销。
     */
    public void deregisterFromGateway() {
        if (!hasGatewayUrl()) {
            return;
        }
        if (!registered.get()) {
            return;
        }
        String url = gatewayUrl + "/gateway/v1/workers/" + workerId;

        try {
            LOGGER.infof("Deregistering worker from Gateway: %s", url);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(5000);

            int code = conn.getResponseCode();
            LOGGER.infof("Worker deregistered (HTTP %d)", code);
            conn.disconnect();
        } catch (Exception e) {
            LOGGER.warnf("Failed to deregister from Gateway: %s", e.getMessage());
        }
        registered.set(false);
    }

    private String buildRegistrationJson() {
        String m = (model == null || model.isEmpty()) ? "unknown" : model;
        String g = (group == null || group.isEmpty()) ? "default" : group;
        return "{"
                + "\"workerId\":\"" + escapeJson(workerId) + "\","
                + "\"host\":\"" + escapeJson(externalHost) + "\","
                + "\"port\":" + externalPort + ","
                + "\"model\":\"" + escapeJson(m) + "\","
                + "\"group\":\"" + escapeJson(g) + "\","
                + "\"tier\":\"" + escapeJson(tier) + "\","
                + "\"maxConcurrency\":" + maxConcurrency + ","
                + "\"status\":\"ONLINE\""
                + "}";
    }

    /**
     * 心跳上报真实负载：活跃任务数 / 最大并发数 → currentLoad 0~1，
     * Gateway 侧 CapabilityRouter 的 effectiveScore 据此分流。
     */
    private String buildHeartbeatJson() {
        int active = loadState != null ? loadState.getActiveTasks() : 0;
        double load = loadState != null ? loadState.getCurrentLoad() : 0.0;
        return "{\"currentLoad\":" + load + ",\"activeTasks\":" + active
                + ",\"maxConcurrency\":" + maxConcurrency + "}";
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
        } catch (Exception ignored) {
            // fall through to error stream
        }
        try {
            byte[] buf = new byte[1024];
            int n = conn.getErrorStream().read(buf);
            if (n > 0) return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            // ignore
        }
        return "";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
