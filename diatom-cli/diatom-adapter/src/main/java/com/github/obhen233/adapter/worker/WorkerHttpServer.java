package com.github.obhen233.adapter.worker;

import com.github.obhen233.adapter.internal.JsonUtil;
import com.github.obhen233.adapter.spi.AgentAdapter;
import com.github.obhen233.adapter.spi.AgentRequest;
import com.github.obhen233.adapter.spi.AgentResponse;
import com.github.obhen233.adapter.spi.HttpServerSpi;
import com.github.obhen233.adapter.spi.ServerRequest;
import com.github.obhen233.adapter.spi.ServerResponse;
import com.github.obhen233.adapter.spi.StreamConsumer;
import com.github.obhen233.adapter.worker.model.ChatRequest;
import com.github.obhen233.adapter.worker.model.SseEvent;
import com.github.obhen233.adapter.worker.model.WorkerChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Adapter HTTP server exposing Worker protocol endpoints.
 *
 * <p>Uses {@link HttpServerSpi} for the underlying HTTP implementation.
 * Default is JDK {@link com.sun.net.httpserver.HttpServer} via {@link JdkHttpServer}.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /worker/v1/chat} — execute a task</li>
 *   <li>{@code POST /worker/v1/chat/stream} — SSE streaming task execution</li>
 *   <li>{@code GET /worker/v1/health} — health check</li>
 *   <li>{@code POST /worker/v1/cancel} — cancel current task</li>
 *   <li>{@code POST /worker/v1/project/push} — receive project files as zip</li>
 *   <li>{@code POST /worker/v1/confirm-callback} — async confirmation callback</li>
 *   <li>{@code GET /worker/v1/rules} — provide capability.md</li>
 *   <li>{@code POST /worker/v1/shutdown-notice} — graceful shutdown notification</li>
 * </ul>
 */
public class WorkerHttpServer {
    private static final Logger logger = LoggerFactory.getLogger(WorkerHttpServer.class);

    private HttpServerSpi serverSpi;
    private final int port;
    private final AgentAdapter agentAdapter;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile String currentTaskId;
    private volatile String capabilityContent;
    private volatile boolean shuttingDown = false;

    /** TaskId -> file snapshot (relative path -> MD5). Used for detecting file diffs. */
    private final ConcurrentHashMap<String, Map<String, String>> projectSnapshots = new ConcurrentHashMap<>();

    public WorkerHttpServer(int port, AgentAdapter agentAdapter) throws IOException {
        this(port, agentAdapter, null);
    }

    public WorkerHttpServer(int port, AgentAdapter agentAdapter, HttpServerSpi serverSpi) throws IOException {
        this.port = port;
        this.agentAdapter = agentAdapter;
        this.serverSpi = resolveHttpServerSpi(port, serverSpi);
        setupEndpoints();
    }

    /**
     * Resolve the HttpServerSpi instance: prefer explicitly provided, then ServiceLoader,
     * then fall back to default JdkHttpServer.
     */
    private static HttpServerSpi resolveHttpServerSpi(int port, HttpServerSpi provided) throws IOException {
        if (provided != null) {
            return provided;
        }
        try {
            ServiceLoader<HttpServerSpi> loader = ServiceLoader.load(HttpServerSpi.class);
            for (HttpServerSpi spi : loader) {
                logger.info("Using custom HttpServerSpi from ServiceLoader: {}", spi.getClass().getName());
                return spi;
            }
        } catch (Exception e) {
            logger.warn("Failed to load HttpServerSpi via ServiceLoader, falling back to JdkHttpServer: {}", e.getMessage());
        }
        return new JdkHttpServer(port, Executors.newVirtualThreadPerTaskExecutor());
    }

    public void setCapabilityContent(String capabilityContent) {
        this.capabilityContent = capabilityContent;
    }

    public int getPort() {
        return port;
    }

    public void start() {
        serverSpi.start();
        logger.info("WorkerHttpServer started on port {}", port);
    }

    public void stop(int delay) {
        shuttingDown = true;
        serverSpi.stop(delay);
        logger.info("WorkerHttpServer stopped");
    }

    // ---- Endpoint setup ----

    private void setupEndpoints() {
        serverSpi.addHandler("POST", "/worker/v1/chat/stream", this::handleChatStream);
        serverSpi.addHandler("POST", "/worker/v1/chat", this::handleChat);
        serverSpi.addHandler("GET", "/worker/v1/health", this::handleHealth);
        serverSpi.addHandler("POST", "/worker/v1/cancel", this::handleCancel);
        serverSpi.addHandler("POST", "/worker/v1/project/push", this::handleProjectPush);
        serverSpi.addHandler("POST", "/worker/v1/confirm-callback", this::handleConfirmCallback);
        serverSpi.addHandler("GET", "/worker/v1/rules", this::handleRules);
        serverSpi.addHandler("POST", "/worker/v1/shutdown-notice", this::handleShutdownNotice);
    }

    /**
     * Dynamically upgrade the HTTP server to HTTPS on the same port.
     */
    public void upgradeToHttps(String certPem, String keyPem, String caCertPem, String password) {
        serverSpi.upgradeToHttps(certPem, keyPem, caCertPem, password);
    }

    // ---- /worker/v1/chat (POST) ----

    private void handleChat(ServerRequest request, ServerResponse response) {
        try {
            String body = request.getBody();
            logger.debug("=== Adapter handleChat: raw body received, length={} bytes", body.getBytes(StandardCharsets.UTF_8).length);
            logger.debug("Raw body string: {}", body);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            int dumpLen = Math.min(bodyBytes.length, 200);
            StringBuilder hexDump = new StringBuilder();
            for (int i = 0; i < dumpLen; i++) {
                hexDump.append(String.format("%02x ", bodyBytes[i]));
                if ((i + 1) % 16 == 0) hexDump.append('\n');
            }
            logger.debug("Body hex dump (first {} bytes):\n{}", dumpLen, hexDump.toString());

            ChatRequest chatReq = JsonUtil.fromJson(body, ChatRequest.class);
            logger.info("Received chat request: taskId={}, messageLen={}", chatReq.taskId(),
                    chatReq.message() != null ? chatReq.message().length() : 0);
            logger.debug("Message content: [{}]", chatReq.message());

            if (chatReq.taskId() == null || chatReq.message() == null) {
                sendJsonError(response, 400, "taskId and message are required");
                return;
            }

            currentTaskId = chatReq.taskId();
            cancelled.set(false);

            List<AgentRequest.ChatMessage> history = null;
            if (chatReq.conversationHistory() != null) {
                history = new ArrayList<>();
                for (Map<String, String> msg : chatReq.conversationHistory()) {
                    history.add(new AgentRequest.ChatMessage(
                            msg.getOrDefault("role", "user"),
                            msg.getOrDefault("content", "")));
                }
            }
            AgentRequest agentReq = new AgentRequest(
                    chatReq.taskId(),
                    null,
                    chatReq.message(),
                    chatReq.workspacePath(),
                    history,
                    chatReq.metadata());

            Map<String, String> beforeSnapshot = null;
            if (chatReq.workspacePath() != null && !chatReq.workspacePath().isEmpty()) {
                beforeSnapshot = snapshotProject(Paths.get(chatReq.workspacePath()));
            }

            logger.debug("Calling agentAdapter.execute() with message: [{}]", chatReq.message());
            AgentResponse agentResp = agentAdapter.execute(agentReq);
            logger.debug("Agent response: status={}, responseLen={}, response=[{}]",
                    agentResp.status(),
                    agentResp.response() != null ? agentResp.response().length() : 0,
                    agentResp.response());

            List<Map<String, Object>> fileDiffs = new ArrayList<>();
            if (beforeSnapshot != null && chatReq.workspacePath() != null) {
                Map<String, String> afterSnapshot = snapshotProject(Paths.get(chatReq.workspacePath()));
                fileDiffs = computeFileDiffs(beforeSnapshot, afterSnapshot, Paths.get(chatReq.workspacePath()));
            }

            WorkerChatResponse resp = new WorkerChatResponse(
                    agentResp.status() != null ? agentResp.status() : "completed",
                    chatReq.taskId(),
                    agentResp.response() != null ? agentResp.response() : "",
                    fileDiffs.isEmpty() ? null : fileDiffs);

            String respJson = JsonUtil.toJsonNonNull(resp);
            logger.debug("=== Adapter sending response: JSON length={} bytes", respJson.getBytes(StandardCharsets.UTF_8).length);
            logger.debug("Response JSON: {}", respJson);
            byte[] respBytes = respJson.getBytes(StandardCharsets.UTF_8);
            int respDumpLen = Math.min(respBytes.length, 300);
            StringBuilder respHex = new StringBuilder();
            for (int i = 0; i < respDumpLen; i++) {
                respHex.append(String.format("%02x ", respBytes[i]));
                if ((i + 1) % 16 == 0) respHex.append('\n');
            }
            logger.debug("Response hex dump (first {} bytes):\n{}", respDumpLen, respHex.toString());
            sendJsonResponse(response, 200, respJson);

        } catch (Exception e) {
            logger.error("Error handling chat request", e);
            try {
                WorkerChatResponse errResp = new WorkerChatResponse("error", null, e.getMessage());
                sendJsonResponse(response, 500, JsonUtil.toJsonNonNull(errResp));
            } catch (IOException ioe) {
                logger.error("Failed to send error response: {}", ioe.getMessage());
            }
        }
    }

    // ---- /worker/v1/chat/stream (POST) ----

    private void handleChatStream(ServerRequest request, ServerResponse response) {
        try {
            String body = request.getBody();
            ChatRequest chatReq = JsonUtil.fromJson(body, ChatRequest.class);
            logger.info("Received stream chat request: taskId={}, messageLen={}", chatReq.taskId(),
                    chatReq.message() != null ? chatReq.message().length() : 0);

            if (chatReq.taskId() == null || chatReq.message() == null) {
                sendJsonError(response, 400, "taskId and message are required");
                return;
            }

            currentTaskId = chatReq.taskId();
            cancelled.set(false);

            AgentRequest agentReq = new AgentRequest(
                    chatReq.taskId(),
                    null,
                    chatReq.message(),
                    chatReq.workspacePath(),
                    null,
                    chatReq.metadata());

            // Set SSE response headers
            response.setHeader("Content-Type", "text/event-stream; charset=utf-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            response.setStatus(200);
            OutputStream os = response.getOutputStream();

            // Send start event
            writeSseEvent(os, SseEvent.start(chatReq.taskId()));

            final Map<String, String>[] beforeSnapshot = new Map[]{null};
            final String wp = chatReq.workspacePath();
            final String taskId = chatReq.taskId();
            if (wp != null && !wp.isEmpty()) {
                beforeSnapshot[0] = snapshotProject(Paths.get(wp));
            }

            agentAdapter.executeStream(agentReq, new StreamConsumer() {
                @Override
                public void onToken(String token) {
                    if (!cancelled.get()) {
                        writeSseEvent(os, SseEvent.token(token, null));
                    }
                }

                @Override
                public void onComplete() {
                    List<Map<String, Object>> fileDiffs = new ArrayList<>();
                    if (beforeSnapshot[0] != null && wp != null) {
                        try {
                            Map<String, String> afterSnapshot = snapshotProject(Paths.get(wp));
                            fileDiffs = computeFileDiffs(beforeSnapshot[0], afterSnapshot, Paths.get(wp));
                        } catch (Exception e) {
                            logger.warn("Failed to compute file diffs during streaming: {}", e.getMessage());
                        }
                    }

                    SseEvent completeEvent = new SseEvent(
                            "complete", taskId, null, fileDiffs.isEmpty() ? null : fileDiffs, null);
                    writeSseEvent(os, completeEvent);
                }

                @Override
                public void onError(String error) {
                    writeSseEvent(os, SseEvent.error(error));
                }
            });

            os.flush();
            os.close();

        } catch (Exception e) {
            logger.error("Error handling stream chat request", e);
            sendJsonError(response, 500, e.getMessage());
        }
    }

    // ---- /worker/v1/health (GET) ----

    private void handleHealth(ServerRequest request, ServerResponse response) throws IOException {
        logger.debug("Health check from client");

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", shuttingDown ? "SHUTTING_DOWN" : "ACTIVE");
        health.put("workerType", agentAdapter.getAgentType());
        health.put("timestamp", System.currentTimeMillis());

        sendJsonResponse(response, 200, JsonUtil.toJsonNonNull(health));
    }

    // ---- /worker/v1/cancel (POST) ----

    private void handleCancel(ServerRequest request, ServerResponse response) throws IOException {
        cancelled.set(true);
        agentAdapter.cancel();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("taskId", currentTaskId);

        sendJsonResponse(response, 200, JsonUtil.toJsonNonNull(result));
    }

    // ---- /worker/v1/project/push (POST) ----

    private void handleProjectPush(ServerRequest request, ServerResponse response) {
        try {
            String query = request.getQueryParam("workspacePath");
            String workspacePath = null;
            if (query != null && !query.isEmpty()) {
                workspacePath = query;
            } else {
                sendJsonError(response, 400, "workspacePath query parameter is required");
                return;
            }

            if (workspacePath == null || workspacePath.isEmpty()) {
                sendJsonError(response, 400, "workspacePath query parameter is required");
                return;
            }

            Path targetDir = Paths.get(workspacePath);
            Files.createDirectories(targetDir);

            byte[] zipBytes = request.getBodyBytes();

            unpackZip(zipBytes, targetDir);

            Map<String, String> snapshot = snapshotProject(targetDir);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("snapshot", snapshot);

            if (currentTaskId != null) {
                projectSnapshots.put(currentTaskId, new ConcurrentHashMap<>(snapshot));
            }

            sendJsonResponse(response, 200, JsonUtil.toJsonNonNull(result));

        } catch (Exception e) {
            logger.error("Error handling project push", e);
            sendJsonError(response, 500, "Failed to unpack project: " + e.getMessage());
        }
    }

    // ---- /worker/v1/confirm-callback (POST) ----

    private void handleConfirmCallback(ServerRequest request, ServerResponse response) throws IOException {
        String body = request.getBody();
        logger.debug("Received confirm callback: {}", body);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        sendJsonResponse(response, 200, JsonUtil.toJsonNonNull(result));
    }

    // ---- /worker/v1/rules (GET) ----

    private void handleRules(ServerRequest request, ServerResponse response) throws IOException {
        if (capabilityContent != null) {
            response.setHeader("Content-Type", "text/plain; charset=utf-8");
            response.setStatus(200);
            response.send(capabilityContent);
        } else {
            sendJsonError(response, 404, "No capability rules available");
        }
    }

    // ---- /worker/v1/shutdown-notice (POST) ----

    private void handleShutdownNotice(ServerRequest request, ServerResponse response) throws IOException {
        shuttingDown = true;
        logger.info("Received shutdown notice from Gateway");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        sendJsonResponse(response, 200, JsonUtil.toJsonNonNull(result));
    }

    // ---- Utility methods ----

    private void sendJsonResponse(ServerResponse response, int statusCode, String body) throws IOException {
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        response.setStatus(statusCode);
        response.send(body);
    }

    private void sendJsonError(ServerResponse response, int statusCode, String message) {
        try {
            String body = JsonUtil.toJson(java.util.Collections.singletonMap("error", message));
            sendJsonResponse(response, statusCode, body);
        } catch (IOException e) {
            logger.error("Failed to send error response (status={}): {}", statusCode, e.getMessage());
        }
    }

    private void writeSseEvent(OutputStream os, SseEvent event) {
        try {
            String json = JsonUtil.toJsonNonNull(event);
            String sseLine = "data: " + json + "\n\n";
            os.write(sseLine.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            logger.warn("Failed to write SSE event: {}", e.getMessage());
        }
    }

    // ---- Project snapshot & diff ----

    private Map<String, String> snapshotProject(Path projectDir) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        if (!Files.exists(projectDir)) return snapshot;

        try {
            Files.walk(projectDir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        String relativePath = projectDir.relativize(file).toString().replace('\\', '/');
                        if (!isIgnoredPath(relativePath)) {
                            snapshot.put(relativePath, md5Hex(file));
                        }
                    });
        } catch (IOException e) {
            logger.warn("Failed to snapshot project at {}: {}", projectDir, e.getMessage());
        }

        return snapshot;
    }

    private List<Map<String, Object>> computeFileDiffs(Map<String, String> before,
                                                       Map<String, String> after,
                                                       Path projectDir) {
        List<Map<String, Object>> diffs = new ArrayList<>();

        for (Map.Entry<String, String> entry : after.entrySet()) {
            String path = entry.getKey();
            String afterMd5 = entry.getValue();
            String beforeMd5 = before.get(path);

            if (beforeMd5 == null) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("relativePath", path);
                diff.put("changeType", "CREATED");
                diff.put("newContent", readFileContent(projectDir.resolve(path)));
                diffs.add(diff);
            } else if (!beforeMd5.equals(afterMd5)) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("relativePath", path);
                diff.put("changeType", "MODIFIED");
                diff.put("newContent", readFileContent(projectDir.resolve(path)));
                diffs.add(diff);
            }
        }

        for (Map.Entry<String, String> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("relativePath", entry.getKey());
                diff.put("changeType", "DELETED");
                diffs.add(diff);
            }
        }

        return diffs;
    }

    private boolean isIgnoredPath(String relativePath) {
        String lower = relativePath.toLowerCase();
        return lower.startsWith(".git/")
                || lower.startsWith(".diatom/")
                || lower.equals(".git")
                || lower.equals(".diatom")
                || lower.startsWith("node_modules/")
                || lower.startsWith(".mvn/")
                || lower.endsWith(".class")
                || lower.endsWith(".jar");
    }

    private String md5Hex(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String readFileContent(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void unpackZip(byte[] zipBytes, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                Path targetFile = targetDir.resolve(entry.getName()).normalize();
                if (!targetFile.startsWith(targetDir.normalize())) {
                    throw new SecurityException("Zip entry outside target dir: " + entry.getName());
                }

                Files.createDirectories(targetFile.getParent());
                try (OutputStream os = Files.newOutputStream(targetFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = zis.read(buf)) != -1) {
                        os.write(buf, 0, len);
                    }
                }
            }
        }
    }
}
