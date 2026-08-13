package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.gateway.collaboration.LockInfo;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;
import com.github.obhen233.spi.IsolationContext;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Handles distributed lock endpoints (/gateway/v1/lock/*).
 */
class GatewayLockHandler {

    private final GatewayHttpServer server;

    GatewayLockHandler(GatewayHttpServer server) {
        this.server = server;
    }

    void registerRoutes() {
        server.getServerSpi().addHandler("POST", "/gateway/v1/lock/acquire", this::handleLockAcquire);
        server.getServerSpi().addHandler("POST", "/gateway/v1/lock/release", this::handleLockRelease);
        server.getServerSpi().addHandler("POST", "/gateway/v1/lock/renew", this::handleLockRenew);
        server.getServerSpi().addHandler("GET", "/gateway/v1/lock/status", this::handleLockStatus);
    }

    private void handleLockAcquire(ServerRequest request, ServerResponse response) throws IOException {
        ResourceLockManager lockManager = server.getLockManager();
        if (lockManager == null) {
            sendError(response, 503, "Lock manager not initialized");
            return;
        }
        String body = readBody(request);
        String resourceId = extractJsonValue(body, "resourceId");
        String workerId = extractJsonValue(body, "workerId");
        String modeStr = extractJsonValue(body, "mode");
        if (resourceId == null || workerId == null || modeStr == null) {
            sendError(response, 400, "Missing required fields: resourceId, workerId, mode");
            return;
        }
        LockInfo.LockMode mode = "READ".equalsIgnoreCase(modeStr)
                ? LockInfo.LockMode.READ : LockInfo.LockMode.WRITE;
        long leaseMs = parseLong(extractJsonValue(body, "leaseMs"), 30000);
        long waitMs = parseLong(extractJsonValue(body, "waitMs"), 0);

        IsolationContext.LockToken token = lockManager.acquire(resourceId, workerId, mode, leaseMs, waitMs);
        if (token != null) {
            sendJson(response, 200, "{\"success\":true,\"token\":\"" + escapeJson(token.getToken())
                    + "\",\"resourceId\":\"" + escapeJson(token.getResourceId())
                    + "\",\"mode\":\"" + token.getMode()
                    + "\",\"expiresAt\":" + token.getExpiresAt() + "}");
        } else {
            sendJson(response, 409, "{\"success\":false,\"error\":\"Lock acquisition failed (timeout or conflict)\"}");
        }
    }

    private void handleLockRelease(ServerRequest request, ServerResponse response) throws IOException {
        ResourceLockManager lockManager = server.getLockManager();
        if (lockManager == null) {
            sendError(response, 503, "Lock manager not initialized");
            return;
        }
        String body = readBody(request);
        String resourceId = extractJsonValue(body, "resourceId");
        String token = extractJsonValue(body, "token");
        String workerId = extractJsonValue(body, "workerId");
        if (resourceId == null || token == null || workerId == null) {
            sendError(response, 400, "Missing required fields: resourceId, token, workerId");
            return;
        }
        boolean released = lockManager.release(resourceId, token, workerId);
        sendJson(response, 200, "{\"success\":" + released + "}");
    }

    private void handleLockRenew(ServerRequest request, ServerResponse response) throws IOException {
        ResourceLockManager lockManager = server.getLockManager();
        if (lockManager == null) {
            sendError(response, 503, "Lock manager not initialized");
            return;
        }
        String body = readBody(request);
        String resourceId = extractJsonValue(body, "resourceId");
        String token = extractJsonValue(body, "token");
        long additionalMs = parseLong(extractJsonValue(body, "additionalMs"), 30000);
        if (resourceId == null || token == null) {
            sendError(response, 400, "Missing required fields: resourceId, token");
            return;
        }
        boolean renewed = lockManager.renewLease(resourceId, token, additionalMs);
        sendJson(response, 200, "{\"success\":" + renewed + "}");
    }

    private void handleLockStatus(ServerRequest request, ServerResponse response) throws IOException {
        ResourceLockManager lockManager = server.getLockManager();
        if (lockManager == null) {
            sendError(response, 503, "Lock manager not initialized");
            return;
        }
        String resourceId = request.getQueryParam("resourceId");
        List<IsolationContext.LockInfo> locks;
        if (resourceId != null && !resourceId.isEmpty()) {
            IsolationContext.LockInfo info = lockManager.getLockInfo(resourceId);
            locks = info != null ? java.util.Collections.singletonList(info) : java.util.Collections.emptyList();
        } else {
            locks = lockManager.getAllLocks();
        }
        String json = "{\"locks\":" + locks.stream()
                .map(l -> "{\"resourceId\":\"" + escapeJson(l.getResourceId())
                        + "\",\"holderWorkerId\":\"" + escapeJson(l.getHolderWorkerId())
                        + "\",\"mode\":\"" + l.getMode()
                        + "\",\"expiresAt\":" + l.getExpiresAt() + "}")
                .collect(Collectors.joining(",", "[", "]")) + "}";
        sendJson(response, 200, json);
    }
}
