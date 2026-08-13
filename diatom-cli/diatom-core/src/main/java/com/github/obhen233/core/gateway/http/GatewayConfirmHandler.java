package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.gateway.http.dto.PendingConfirmListResponse;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;
import com.github.obhen233.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Handles confirmation request endpoints (/gateway/v1/confirm-request, /gateway/v1/confirm-resolve,
 * /gateway/v1/confirm-pending).
 */
public class GatewayConfirmHandler {

    private final GatewayHttpServer server;

    static final ObjectMapper confirmMapper = JsonUtils.getMapper();
    private final ConcurrentHashMap<String, PendingConfirmRequest> pendingConfirmations = new ConcurrentHashMap<>();
    private final BlockingQueue<String> pendingConfirmQueue = new LinkedBlockingQueue<>();
    private volatile boolean globalAutoApprove = false;

    /** 上游 Gateway URL（级联模式），为 null 表示当前为顶层 Gateway */
    private volatile String upstreamGatewayUrl;

    public static class PendingConfirmRequest {
        public final String requestId;
        public final String workerId;
        public final String toolName;
        public final String action;
        public final String arguments;
        public final String toolCallId;
        public final String callbackUrl;
        public final List<ChatMessage> messages;
        public final CompletableFuture<String> decisionFuture = new CompletableFuture<>();

        public PendingConfirmRequest(String requestId, String workerId, String toolName,
                              String action, String arguments, String toolCallId,
                              String callbackUrl, List<ChatMessage> messages) {
            this.requestId = requestId;
            this.workerId = workerId;
            this.toolName = toolName;
            this.action = action;
            this.arguments = arguments;
            this.toolCallId = toolCallId;
            this.callbackUrl = callbackUrl;
            this.messages = messages;
        }
    }

    GatewayConfirmHandler(GatewayHttpServer server) {
        this.server = server;
    }

    void registerRoutes() {
        server.getServerSpi().addHandler("POST", "/gateway/v1/confirm-request", this::handleConfirmRequest);
        server.getServerSpi().addHandler("POST", "/gateway/v1/confirm-resolve", this::handleConfirmResolve);
        server.getServerSpi().addHandler("GET", "/gateway/v1/confirm-pending", this::handleConfirmPending);
    }

    // ---- Confirmation request endpoints ----

    /**
     * POST /gateway/v1/confirm-request
     */
    private void handleConfirmRequest(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        String requestId = extractJsonValue(body, "requestId");
        String workerId = extractJsonValue(body, "workerId");
        String toolName = extractJsonValue(body, "toolName");
        String action = extractJsonValue(body, "action");
        String arguments = extractJsonValue(body, "arguments");
        String toolCallId = extractJsonValue(body, "toolCallId");
        String callbackUrl = extractJsonValue(body, "callbackUrl");
        String messagesRaw = extractRawJsonArray(body, "messages");

        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

        List<ChatMessage> messages = new ArrayList<>();
        if (messagesRaw != null && !messagesRaw.isEmpty()) {
            try {
                messages = confirmMapper.readValue(messagesRaw,
                        new TypeReference<List<ChatMessage>>() {});
            } catch (Exception e) {
                LoggerFactory.getLogger(GatewayConfirmHandler.class).warn("Failed to deserialize messages: {}", e.getMessage());
            }
        }

        LoggerFactory.getLogger(GatewayConfirmHandler.class).info("Confirm request: id={}, worker={}, tool={}, action={}, callbackUrl={}",
                requestId, workerId, toolName, action, callbackUrl);

        if (globalAutoApprove) {
            postDecisionToCallback(callbackUrl, requestId, "y");
            sendJson(response, 202, Collections.singletonMap("status", "accepted"));
            return;
        }

        String upstreamUrl = upstreamGatewayUrl;
        if (upstreamUrl != null && !upstreamUrl.isEmpty()) {
            LoggerFactory.getLogger(GatewayConfirmHandler.class).info("Forwarding confirm request {} to upstream Gateway: {}", requestId, upstreamUrl);
            forwardConfirmToUpstream(upstreamUrl, body, requestId);
            sendJson(response, 202, Collections.singletonMap("status", "forwarded"));
            return;
        }

        PendingConfirmRequest pendingReq = new PendingConfirmRequest(
                requestId, workerId, toolName, action, arguments, toolCallId, callbackUrl, messages);
        pendingConfirmations.put(requestId, pendingReq);
        pendingConfirmQueue.offer(requestId);
        sendJson(response, 202, Collections.singletonMap("status", "queued"));
        LoggerFactory.getLogger(GatewayConfirmHandler.class).info("Confirm request queued for CLI: id={}", requestId);
    }

    private void forwardConfirmToUpstream(String upstreamUrl, String requestBody, String requestId) {
        String targetUrl = upstreamUrl + "/gateway/v1/confirm-request";
        try {
            URL url = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            conn.disconnect();
            LoggerFactory.getLogger(GatewayConfirmHandler.class).info("Upstream Gateway for request {} responded HTTP {}", requestId, responseCode);
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayConfirmHandler.class).error("Failed to forward confirm request {} to upstream Gateway {}: {}",
                    requestId, upstreamUrl, e.getMessage());
        }
    }

    /**
     * POST /gateway/v1/confirm-resolve
     */
    private void handleConfirmResolve(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        String requestId = extractJsonValue(body, "requestId");
        String decision = extractJsonValue(body, "decision");

        if (requestId == null || decision == null) {
            Map<String, String> missing = new HashMap<>();
            missing.put("status", "error");
            missing.put("error", "Missing requestId or decision");
            sendJson(response, 400, missing);
            return;
        }

        resolveConfirm(requestId, decision);
        sendJson(response, 200, Collections.singletonMap("status", "ok"));
        LoggerFactory.getLogger(GatewayConfirmHandler.class).info("Confirm resolve via API: requestId={}, decision={}", requestId, decision);
    }

    /**
     * GET /gateway/v1/confirm-pending
     */
    private void handleConfirmPending(ServerRequest request, ServerResponse response) throws IOException {
        PendingConfirmListResponse resp = new PendingConfirmListResponse();
        resp.pending = new ArrayList<PendingConfirmListResponse.PendingConfirmItem>();
        for (PendingConfirmRequest req : pendingConfirmations.values()) {
            PendingConfirmListResponse.PendingConfirmItem item = new PendingConfirmListResponse.PendingConfirmItem();
            item.requestId = req.requestId;
            item.workerId = req.workerId;
            item.toolName = req.toolName;
            item.action = req.action;
            item.arguments = req.arguments;
            item.toolCallId = req.toolCallId;
            resp.pending.add(item);
        }
        sendJson(response, 200, resp);
    }

    public PendingConfirmRequest pollPendingConfirm() {
        String requestId = pendingConfirmQueue.poll();
        if (requestId == null) return null;
        PendingConfirmRequest req = pendingConfirmations.get(requestId);
        if (req == null) return null;
        return req;
    }

    public void resolveConfirm(String requestId, String decision) {
        PendingConfirmRequest req = pendingConfirmations.remove(requestId);
        if (req == null) {
            LoggerFactory.getLogger(GatewayConfirmHandler.class).warn("resolveConfirm: unknown requestId={}", requestId);
            return;
        }
        if (req.callbackUrl != null && !req.callbackUrl.isEmpty()) {
            postDecisionToCallback(req.callbackUrl, requestId, decision);
        } else {
            req.decisionFuture.complete(decision);
        }
    }

    public void cancelAllPendingConfirmations() {
        for (PendingConfirmRequest req : pendingConfirmations.values()) {
            if (req.callbackUrl != null && !req.callbackUrl.isEmpty()) {
                postDecisionToCallback(req.callbackUrl, req.requestId, "c");
            } else {
                req.decisionFuture.complete("c");
            }
        }
        pendingConfirmations.clear();
        pendingConfirmQueue.clear();
    }

    private void postDecisionToCallback(String callbackUrl, String requestId, String decision) {
        if (callbackUrl == null || callbackUrl.isEmpty()) return;
        try {
            URL url = new URL(callbackUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            String jsonBody = "{\"requestId\":\"" + requestId + "\",\"decision\":\"" + decision + "\"}";
            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int responseCode = conn.getResponseCode();
            conn.disconnect();
            LoggerFactory.getLogger(GatewayConfirmHandler.class).debug("Callback to {} returned HTTP {} for requestId={}, decision={}",
                    callbackUrl, responseCode, requestId, decision);
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayConfirmHandler.class).warn("Failed to callback {} for requestId={}: {}",
                    callbackUrl, requestId, e.getMessage());
        }
    }

    public boolean isGlobalAutoApprove() { return globalAutoApprove; }
    public void setGlobalAutoApprove(boolean globalAutoApprove) { this.globalAutoApprove = globalAutoApprove; }
    public void setUpstreamGatewayUrl(String upstreamGatewayUrl) { this.upstreamGatewayUrl = upstreamGatewayUrl; }
    public String getUpstreamGatewayUrl() { return upstreamGatewayUrl; }

    ConcurrentHashMap<String, PendingConfirmRequest> getPendingConfirmations() { return pendingConfirmations; }
    BlockingQueue<String> getPendingConfirmQueue() { return pendingConfirmQueue; }
}
