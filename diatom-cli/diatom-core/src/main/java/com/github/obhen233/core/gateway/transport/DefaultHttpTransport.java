package com.github.obhen233.core.gateway.transport;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.gateway.model.ChatRequest;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.security.SecurityHeadersInjector;
import com.github.obhen233.core.gateway.security.SecurityProviderLoader;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * Default HTTP implementation of {@link AsyncTaskTransport}.
 * <p>
 * Uses an internal thread pool to perform HTTP calls without blocking
 * the caller. Timeout is enforced via {@link Future#get(long, TimeUnit)}.
 * </p>
 */
public class DefaultHttpTransport implements AsyncTaskTransport {

    private static final Logger logger = LoggerFactory.getLogger(DefaultHttpTransport.class);

    private final ThreadPoolExecutor executor;
    private final int connectTimeoutMs;

    public DefaultHttpTransport() {
        this(30000);
    }

    public DefaultHttpTransport(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.executor = new ThreadPoolExecutor(
                4, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "diatom-http-transport");
                    t.setDaemon(true);
                    return t;
                });
    }

    @Override
    public void sendTaskAsync(WorkerInfo worker, ChatRequest request,
                              long timeoutMs, TransportCallback callback) {
        Future<?> future = executor.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                String responseBody = doHttpPost(worker, request, timeoutMs);
                long duration = System.currentTimeMillis() - start;
                TransportResponse response = new TransportResponse(200, responseBody, duration);
                callback.onSuccess(worker.getWorkerId(), response);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                logger.error("HTTP transport failed for worker {}: {}", worker.getWorkerId(), e.getMessage());
                callback.onFailure(worker.getWorkerId(), e.getMessage());
            }
        });

        // Schedule timeout enforcement
        Thread timeoutWatcher = new Thread(() -> {
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                callback.onTimeout(worker.getWorkerId());
            } catch (Exception e) {
                // Handled inside the task already via callback.onFailure
            }
        }, "diatom-http-timeout-watcher");
        timeoutWatcher.setDaemon(true);
        timeoutWatcher.start();
    }

    @Override
    public String getTransportType() {
        return "http";
    }

    /**
     * Execute the HTTP POST to the worker endpoint.
     * <p>
     * 注意：不调用 conn.disconnect()，以允许底层连接池复用 keep-alive 连接。
     * Java 的 HttpURLConnection 默认使用连接池，disconnect() 会禁止复用。
     * </p>
     */
    private String doHttpPost(WorkerInfo worker, ChatRequest request, long readTimeoutMs) throws Exception {
        URL url = new URL(worker.getBaseUrl() + "/worker/v1/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout((int) Math.min(readTimeoutMs, Integer.MAX_VALUE));

        // Inject security headers
        SecurityHeadersInjector injector = new SecurityHeadersInjector(
                SecurityProviderLoader.getAuthProvider(),
                SecurityProviderLoader.getEncryptionProvider());
        injector.injectIntoConnection(conn, worker.getWorkerId());

        String requestBody = JsonUtils.toJson(request);
        byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);

        // Encrypt body if encryption is configured
        java.util.Map<String, String> encHeaders = new java.util.HashMap<>();
        bodyBytes = SecurityHeadersInjector.encryptBody(bodyBytes, worker.getWorkerId(), encHeaders);
        // Ensure encryption header is set on connection
        for (java.util.Map.Entry<String, String> e : encHeaders.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        int code = conn.getResponseCode();
        String responseBody = readConnectionBody(conn, code);

        if (code != 200) {
            throw new IOException("Worker returned HTTP " + code + ": " + truncate(responseBody, 500));
        }

        return responseBody;
    }

    private static String readConnectionBody(HttpURLConnection conn, int code) throws java.io.IOException {
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = is.read(buf, 0, buf.length)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Gracefully shut down the internal thread pool.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
