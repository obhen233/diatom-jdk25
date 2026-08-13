package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Handles worker registration and management endpoints (/gateway/v1/workers).
 */
class GatewayWorkerHandler {

    private final GatewayHttpServer server;

    GatewayWorkerHandler(GatewayHttpServer server) {
        this.server = server;
    }

    void registerRoutes() {
        server.getServerSpi().addHandler("GET", "/gateway/v1/workers", this::handleWorkers);
        server.getServerSpi().addHandler("POST", "/gateway/v1/workers", this::handleWorkers);
        server.getServerSpi().addHandler("DELETE", "/gateway/v1/workers", this::handleWorkers);
        server.getServerSpi().addHandler("PUT", "/gateway/v1/workers", this::handleWorkers);
    }

    private WorkerRegistry getRegistry() { return server.getRegistry(); }
    private CapabilityRouter getCapabilityRouter() { return server.getCapabilityRouter(); }

    private void handleWorkers(ServerRequest request, ServerResponse response) throws IOException {
        String path = "";
        // Get path from underlying exchange if available
        if (request instanceof JdkServerRequest) {
            path = ((JdkServerRequest) request).getExchange().getRequestURI().getPath();
        }

        String method = request.getMethod();

        // POST /gateway/v1/workers — register a new worker
        if ("POST".equals(method)) {
            handleWorkerRegister(request, response);
            return;
        }

        // DELETE /gateway/v1/workers/{workerId} — deregister a worker
        if ("DELETE".equals(method)) {
            String prefix = "/gateway/v1/workers/";
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                String workerId = path.substring(prefix.length());
                handleWorkerDeregister(request, response, workerId);
                return;
            }
        }

        // /gateway/v1/workers/{workerId}/heartbeat — update heartbeat (PUT)
        String prefix = "/gateway/v1/workers/";
        if (path.startsWith(prefix) && path.endsWith("/heartbeat") && path.length() > prefix.length() + "/heartbeat".length()) {
            String workerId = path.substring(prefix.length(), path.length() - "/heartbeat".length());
            handleWorkerHeartbeat(request, response, workerId);
            return;
        }

        if (!"GET".equals(method)) {
            sendError(response, 405, "Method not allowed");
            return;
        }

        // /gateway/v1/workers/{workerId}
        if (path.length() > prefix.length()) {
            String workerId = path.substring(prefix.length());
            WorkerInfo worker = getRegistry().getWorker(workerId);
            if (worker != null) {
                sendJson(response, 200, workerInfoToJson(worker));
            } else {
                sendError(response, 404, "Worker not found");
            }
            return;
        }

        // /gateway/v1/workers
        List<WorkerInfo> allWorkers = getRegistry().availableWorkers();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (WorkerInfo w : allWorkers) {
            if (!first) sb.append(",");
            sb.append(workerInfoToJson(w));
            first = false;
        }
        sb.append("]");
        sendJson(response, 200, sb.toString());
    }

    /**
     * POST /gateway/v1/workers — register a worker via HTTP.
     */
    private void handleWorkerRegister(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        if (body == null || body.isEmpty()) {
            sendError(response, 400, "{\"error\":\"Empty request body\"}");
            return;
        }
        try {
            WorkerInfo worker = parseWorkerRegistration(body);
            if (worker == null || worker.getWorkerId() == null || worker.getWorkerId().isEmpty()) {
                sendError(response, 400, "{\"error\":\"Missing workerId in registration\"}");
                return;
            }
            String workerId = worker.getWorkerId();
            WorkerInfo existing = getRegistry().getWorker(workerId);
            if (existing != null && existing.getStatus() == WorkerInfo.WorkerStatus.ONLINE) {
                String errMsg = "Worker ID '" + workerId + "' is already registered and online at "
                    + existing.getHost() + ":" + existing.getPort()
                    + ". Use a different ID or stop the existing worker first.";
                LoggerFactory.getLogger(GatewayWorkerHandler.class).warn("Duplicate worker registration rejected: {}", errMsg);
                sendError(response, 409, "{\"error\":\"" + escapeJson(errMsg) + "\"}");
                return;
            }
            getRegistry().register(worker);
            String json = "{\"status\":\"registered\",\"workerId\":\"" + safe(worker.getWorkerId()) + "\"}";
            sendJson(response, 200, json);
            LoggerFactory.getLogger(GatewayWorkerHandler.class).info("Worker registered via HTTP: {} at {}:{}", worker.getWorkerId(), worker.getHost(), worker.getPort());
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayWorkerHandler.class).warn("Failed to parse worker registration: {}", e.getMessage());
            sendError(response, 400, "{\"error\":\"Invalid registration JSON: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * PUT /gateway/v1/workers/{workerId}/heartbeat — update worker heartbeat.
     */
    private void handleWorkerHeartbeat(ServerRequest request, ServerResponse response, String workerId) throws IOException {
        WorkerInfo existing = getRegistry().getWorker(workerId);
        if (existing == null) {
            sendError(response, 404, "{\"error\":\"Worker not found\"}");
            return;
        }
        try {
            String body = readBody(request);

            if (body != null && !body.isEmpty()) {
                String useSslStr = extractJsonValue(body, "useSsl");
                if (useSslStr != null) {
                    boolean newUseSsl = Boolean.parseBoolean(useSslStr);
                    if (newUseSsl != existing.isUseSsl()) {
                        existing.setUseSsl(newUseSsl);
                        LoggerFactory.getLogger(GatewayWorkerHandler.class).info("Worker {} useSsl updated to {} via heartbeat", workerId, newUseSsl);
                    }
                }
            }

            WorkerMetrics metrics = parseWorkerMetrics(body);
            if (metrics != null) {
                getRegistry().heartbeat(workerId, metrics);
            } else {
                metrics = new WorkerMetrics();
                metrics.updateHeartbeat();
                getRegistry().heartbeat(workerId, metrics);
            }
            sendJson(response, 200, Collections.singletonMap("status", "ok"));
        } catch (Exception e) {
            sendError(response, 400, "{\"error\":\"Invalid metrics: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * DELETE /gateway/v1/workers/{workerId} — deregister a worker via HTTP.
     */
    private void handleWorkerDeregister(ServerRequest request, ServerResponse response, String workerId) throws IOException {
        getRegistry().deregister(workerId);
        Map<String, String> dereg = new HashMap<>();
        dereg.put("status", "deregistered");
        dereg.put("workerId", workerId);
        sendJson(response, 200, dereg);
        LoggerFactory.getLogger(GatewayWorkerHandler.class).info("Worker deregistered via HTTP: {}", workerId);
    }

    private String workerInfoToJson(WorkerInfo w) {
        Map<String, Object> map = new HashMap<>();
        map.put("workerId", w.getWorkerId());
        map.put("host", w.getHost());
        map.put("port", w.getPort());
        map.put("model", w.getModel());
        String ws = w.getWorkspace();
        if (ws != null && !ws.isEmpty()) {
            map.put("workspace", ws);
        }
        map.put("status", w.getStatus().name());
        map.put("currentLoad", w.getMetrics().getCurrentLoad());
        map.put("lastHeartbeat", w.getMetrics().getLastHeartbeat());
        int gwActive = getCapabilityRouter() != null ? getCapabilityRouter().getActiveRequests(w.getWorkerId()) : 0;
        map.put("gatewayActiveRequests", gwActive);
        String gp = w.getGatewayProfile();
        if (gp != null && !gp.isEmpty()) {
            map.put("gatewayProfile", gp);
        }
        return JsonUtils.toJson(map);
    }
}
