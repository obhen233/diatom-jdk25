package com.github.obhen233.adapter.worker;

import com.github.obhen233.adapter.internal.JsonUtil;
import com.github.obhen233.adapter.worker.model.HeartbeatPayload;
import com.github.obhen233.adapter.worker.model.RegistrationPayload;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client for registering with and sending heartbeats to a diatom Gateway.
 *
 * <p>Uses OkHttp for HTTP communication. Registers once on startup,
 * then sends heartbeats every 10 seconds.</p>
 */
public class WorkerRegistrationClient {
    private static final Logger logger = LoggerFactory.getLogger(WorkerRegistrationClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String gatewayUrl;
    private final RegistrationPayload registrationPayload;
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicReference<String> workerIdRef = new AtomicReference<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().factory()
    );

    private volatile boolean shuttingDown = false;
    private volatile boolean upgradedToHttps = false;

    /** Reference to the HTTP server for dynamic HTTPS upgrade. Set via {@link #setHttpServer(WorkerHttpServer)}. */
    private WorkerHttpServer httpServer;

    public WorkerRegistrationClient(String gatewayUrl, RegistrationPayload payload) {
        this.gatewayUrl = gatewayUrl.endsWith("/") ? gatewayUrl.substring(0, gatewayUrl.length() - 1) : gatewayUrl;
        this.registrationPayload = payload;
        this.workerIdRef.set(payload.workerId());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Register with the Gateway. Blocks until registration completes or fails.
     *
     * @return true if registration succeeded, false otherwise
     */
    public boolean register() {
        String url = gatewayUrl + "/gateway/v1/workers";
        String json = JsonUtil.toJsonNonNull(registrationPayload);
        RequestBody body = RequestBody.create(JSON, json);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                registered.set(true);
                logger.info("Successfully registered with Gateway at {} (workerId: {})",
                        gatewayUrl, registrationPayload.workerId());
                return true;
            } else {
                String respBody = response.body() != null ? response.body().string() : "";
                logger.error("Gateway registration failed: HTTP {} - {}",
                        response.code(), respBody);
                return false;
            }
        } catch (IOException e) {
            logger.error("Failed to register with Gateway at {}: {}", gatewayUrl, e.getMessage());
            return false;
        }
    }

    /**
     * Set the HTTP server reference for dynamic HTTPS upgrade.
     */
    public void setHttpServer(WorkerHttpServer httpServer) {
        this.httpServer = httpServer;
    }

    /**
     * Register with the Gateway and, if SSL is requested, request a certificate
     * and upgrade to HTTPS.
     *
     * @return true if registration (and optional HTTPS upgrade) succeeded
     */
    public boolean registerAndRequestCert() {
        if (!register()) return false;

        if (registrationPayload.requestCert() && httpServer != null) {
            requestCertFromGateway();
        }
        return true;
    }

    /**
     * Request a signed certificate from the Gateway and upgrade to HTTPS.
     */
    private void requestCertFromGateway() {
        String workerId = workerIdRef.get();
        if (workerId == null || workerId.isEmpty()) {
            logger.warn("Cannot request certificate: workerId is not set");
            return;
        }

        String certUrl = gatewayUrl + "/gateway/v1/cert/issue";
        String host = getLocalHost();
        String jsonBody = JsonUtil.toJsonNonNull(Map.of("workerId", workerId, "host", host));
        RequestBody body = RequestBody.create(JSON, jsonBody);
        Request request = new Request.Builder()
                .url(certUrl)
                .post(body)
                .build();

        logger.info("Requesting certificate from Gateway at {}", certUrl);
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String respBody = response.body() != null ? response.body().string() : "";
                java.util.Map<String, String> respMap = JsonUtil.toStringMap(respBody);
                String certPem = respMap != null ? respMap.get("certPem") : null;
                String keyPem = respMap != null ? respMap.get("keyPem") : null;
                String caCertPem = respMap != null ? respMap.get("caCertPem") : null;

                if (certPem != null && keyPem != null && caCertPem != null) {
                    httpServer.upgradeToHttps(certPem, keyPem, caCertPem, "");
                    upgradedToHttps = true;
                    logger.info("Adapter worker upgraded to HTTPS successfully via Gateway certificate");
                } else {
                    logger.warn("Incomplete certificate response from Gateway, continuing with HTTP");
                }
            } else {
                String respBody = response.body() != null ? response.body().string() : "";
                logger.warn("Certificate request failed (HTTP {}): {}, continuing with HTTP",
                        response.code(), respBody);
            }
        } catch (IOException e) {
            logger.warn("Failed to request certificate from Gateway ({}), continuing with HTTP", e.getMessage());
        }
    }

    /**
     * Get the local host address to use as the certificate SAN.
     */
    private static String getLocalHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * Start the heartbeat loop (every 10 seconds).
     */
    public void startHeartbeat() {
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, 10, 10, TimeUnit.SECONDS);
        logger.debug("Heartbeat loop started (interval: 10s)");
    }

    /**
     * Send a single heartbeat to the Gateway.
     */
    public void sendHeartbeat() {
        if (shuttingDown || !registered.get()) return;

        String workerId = workerIdRef.get();
        String url = gatewayUrl + "/gateway/v1/workers/" + workerId + "/heartbeat";

        HeartbeatPayload payload = new HeartbeatPayload(
                workerId,
                0.0,
                0,
                System.currentTimeMillis(),
                shuttingDown ? "SHUTTING_DOWN" : "ACTIVE",
                upgradedToHttps ? Boolean.TRUE : null);

        String json = JsonUtil.toJsonNonNull(payload);
        RequestBody body = RequestBody.create(JSON, json);
        Request request = new Request.Builder()
                .url(url)
                .put(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.warn("Heartbeat failed: HTTP {}", response.code());
            }
        } catch (IOException e) {
            logger.debug("Heartbeat send failed: {}", e.getMessage());
        }
    }

    /**
     * Send a deregistration notice and stop heartbeats.
     */
    public void deregister() {
        shuttingDown = true;
        String workerId = workerIdRef.get();
        String url = gatewayUrl + "/gateway/v1/workers/" + workerId;

        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                logger.info("Deregistered from Gateway (workerId: {})", workerId);
            }
        } catch (IOException e) {
            logger.warn("Deregistration request failed: {}", e.getMessage());
        }

        heartbeatScheduler.shutdown();
        try {
            heartbeatScheduler.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
