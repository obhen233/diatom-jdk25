package com.github.obhen233.starter.gateway.child;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.util.JsonUtils;
import com.github.obhen233.util.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子 Gateway 上行 HTTP 自注册服务。
 *
 * <p>当 {@code diatom.mode=gateway:child} 且配置了 {@code diatom.gateway.child.upstream-url}
 * 时，将当前 Gateway 作为父 Gateway 的 Worker 节点注册（tier=gateway-proxy）：
 * <ol>
 *   <li>启动时 POST {@code {upstream}/gateway/v1/workers} 注册自身</li>
 *   <li>定时 PUT {@code /workers/{workerId}/heartbeat} 心跳（404 → 自动重注册）</li>
 *   <li>关闭时 DELETE {@code /workers/{workerId}} 注销</li>
 * </ol>
 *
 * <p>与 worker 模式一致：{@code upstream-url} 为空时跳过直连注册（仅走注册中心路径）。
 * 全部请求 try/catch 优雅降级，父 Gateway 未启动只打日志，不阻断启动。</p>
 */
public class ChildGatewayRegistrationService implements DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(ChildGatewayRegistrationService.class);

    private final ChildGatewayProperties properties;
    private final Environment environment;
    private final AppConfig appConfig;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "child-gateway-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean registered = new AtomicBoolean(false);

    /** 注册到父 Gateway 的稳定 workerId */
    private String workerId;

    /** 外部可见 host（父 Gateway 回连用） */
    private String externalHost;

    /** 外部可见 port */
    private int externalPort;

    public ChildGatewayRegistrationService(ChildGatewayProperties properties, Environment environment,
                                           AppConfig appConfig) {
        this.properties = properties;
        this.environment = environment;
        this.appConfig = appConfig;
        initIdentity();
    }

    private void initIdentity() {
        String name = properties.getName();
        String serverPort = environment.getProperty("server.port", "8080");
        if (name == null || name.trim().isEmpty()) {
            name = NetworkUtils.getRealLocalIP() + ":" + serverPort;
        }
        this.workerId = name;

        String configuredHost = properties.getExternalHost();
        if (configuredHost != null && !configuredHost.isEmpty()) {
            this.externalHost = configuredHost;
        } else {
            this.externalHost = NetworkUtils.getRealLocalIP();
        }

        String configuredPort = properties.getExternalPort();
        if (configuredPort != null && !configuredPort.isEmpty()) {
            this.externalPort = Integer.parseInt(configuredPort);
        } else {
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
        if (!hasUpstreamUrl()) {
            logger.info("diatom.gateway.child.upstream-url not configured, skipping direct parent Gateway "
                    + "registration (use Spring Cloud discovery or set upstream-url to register directly)");
            return;
        }
        registerWithUpstream();
        int interval = properties.getHeartbeatIntervalSeconds() > 0
                ? properties.getHeartbeatIntervalSeconds() : 10;
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, interval, interval, TimeUnit.SECONDS);
        logger.debug("Child Gateway heartbeat scheduler started (interval: {}s)", interval);
    }

    /**
     * 是否配置了父 Gateway 地址。未配置时跳过自注册/心跳/注销（仅走注册中心）。
     */
    private boolean hasUpstreamUrl() {
        return properties.getUpstreamUrl() != null
                && !properties.getUpstreamUrl().trim().isEmpty();
    }

    /**
     * 向父 Gateway 注册自身。
     */
    public void registerWithUpstream() {
        if (!hasUpstreamUrl()) {
            logger.debug("Upstream URL not configured, skipping registration");
            return;
        }
        String upstreamUrl = normalizeUrl(properties.getUpstreamUrl());
        String url = upstreamUrl + "/gateway/v1/workers";
        String json = buildRegistrationJson();

        try {
            logger.info("Registering child gateway to parent: {}", url);
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
                logger.info("Child gateway registered successfully: {} at {}:{}",
                        workerId, externalHost, externalPort);
            } else if (code == 409) {
                logger.warn("Child gateway ID '{}' already registered with parent (HTTP 409)", workerId);
                registered.set(true);
            } else {
                logger.warn("Child gateway registration failed (HTTP {}): {}", code, readResponse(conn));
            }
            conn.disconnect();
        } catch (Exception e) {
            logger.warn("Failed to register child gateway with parent ({}): {}", url, e.getMessage());
        }
    }

    /**
     * 发送心跳到父 Gateway；404 表示父 Gateway 重启过导致注册丢失，自动重注册。
     */
    private void sendHeartbeat() {
        if (!hasUpstreamUrl()) {
            return;
        }
        if (!registered.get()) {
            registerWithUpstream();
            return;
        }
        String upstreamUrl = normalizeUrl(properties.getUpstreamUrl());
        String url = upstreamUrl + "/gateway/v1/workers/" + workerId + "/heartbeat";
        String json = "{\"currentLoad\":0.0,\"activeTasks\":0,\"maxConcurrency\":"
                + properties.getMaxConcurrency() + "}";

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 404) {
                logger.info("Child gateway not found in parent registry (heartbeat 404), re-registering...");
                registerWithUpstream();
            } else if (code != 200) {
                logger.debug("Heartbeat returned HTTP {}", code);
            }
            conn.disconnect();
        } catch (Exception e) {
            logger.debug("Failed to send heartbeat ({}): {}", url, e.getMessage());
        }
    }

    /**
     * 从父 Gateway 注销。
     */
    public void deregisterFromUpstream() {
        if (!hasUpstreamUrl()) return;
        if (!registered.get()) return;
        String upstreamUrl = normalizeUrl(properties.getUpstreamUrl());
        String url = upstreamUrl + "/gateway/v1/workers/" + workerId;

        try {
            logger.info("Deregistering child gateway from parent: {}", url);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(5000);

            int code = conn.getResponseCode();
            logger.info("Child gateway deregistered (HTTP {})", code);
            conn.disconnect();
        } catch (Exception e) {
            logger.warn("Failed to deregister child gateway from parent: {}", e.getMessage());
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
        deregisterFromUpstream();
    }

    private String buildRegistrationJson() {
        String model = properties.getModel();
        if (model == null || model.isEmpty()) {
            if (appConfig != null && appConfig.getModel() != null && !appConfig.getModel().isEmpty()) {
                model = appConfig.getModel();
            }
        }
        if (model == null || model.isEmpty()) {
            model = "unknown";
        }
        String group = properties.getGroup();
        if (group == null || group.isEmpty()) {
            group = "default";
        }
        String tier = properties.getTier();
        if (tier == null || tier.isEmpty()) {
            tier = "gateway-proxy";
        }

        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("workerId", workerId);
        reg.put("host", externalHost);
        reg.put("port", externalPort);
        reg.put("model", model);
        reg.put("group", group);
        reg.put("tier", tier);
        reg.put("maxConcurrency", properties.getMaxConcurrency());
        reg.put("status", "ONLINE");
        reg.put("currentLoad", 0.0);
        reg.put("activeTasks", 0);
        return JsonUtils.toJson(reg);
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
}
