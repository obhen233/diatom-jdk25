package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;
import com.github.obhen233.spi.GatewayCertProvider;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Handles certificate issue endpoint (/gateway/v1/cert/issue).
 */
class GatewayCertHandler {

    private final GatewayHttpServer server;

    GatewayCertHandler(GatewayHttpServer server) {
        this.server = server;
    }

    void registerRoutes() {
        GatewayCertProvider certProvider = server.getCertProvider();
        if (certProvider != null && certProvider.isEnabled()) {
            server.getServerSpi().addHandler("POST", "/gateway/v1/cert/issue", this::handleCertIssue);
            LoggerFactory.getLogger(GatewayCertHandler.class).info("Certificate issue endpoint enabled at /gateway/v1/cert/issue");
        } else {
            LoggerFactory.getLogger(GatewayCertHandler.class).info("Certificate issue endpoint disabled (no cert provider or cert distribution not enabled)");
        }
    }

    private GatewayCertProvider getCertProvider() { return server.getCertProvider(); }

    /**
     * Handle POST /gateway/v1/cert/issue — issue a signed certificate to a Worker.
     * Body: {"workerId": "...", "host": "..."}
     */
    private void handleCertIssue(ServerRequest request, ServerResponse response) throws IOException {
        // Authenticate using the underlying HttpExchange
        if (request instanceof JdkServerRequest) {
            if (!server.getAdmissionControl().authenticateRequest(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        GatewayCertProvider certProvider = getCertProvider();
        if (certProvider == null || !certProvider.isEnabled()) {
            sendError(response, 400, "{\"error\":\"Cert distribution disabled\"}");
            return;
        }

        String body = readBody(request);
        if (body == null || body.isEmpty()) {
            sendError(response, 400, "{\"error\":\"Empty request body\"}");
            return;
        }

        String workerId = extractJsonValue(body, "workerId");
        String host = extractJsonValue(body, "host");

        if (workerId == null || workerId.isEmpty()) {
            sendError(response, 400, "{\"error\":\"Missing workerId\"}");
            return;
        }

        if (host == null || host.isEmpty()) {
            sendError(response, 400, "{\"error\":\"Missing host\"}");
            return;
        }

        try {
            GatewayCertProvider.SignedCert signed = certProvider.signWorkerCertificate(workerId, host);
            Map<String, String> result = new HashMap<>();
            result.put("certPem", signed.certPem);
            result.put("keyPem", signed.keyPem);
            result.put("caCertPem", signed.caCertPem);
            sendJson(response, 200, result);
            LoggerFactory.getLogger(GatewayCertHandler.class).info("Certificate issued to worker {} (host: {})", workerId, host);
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayCertHandler.class).error("Failed to issue certificate for worker {}: {}", workerId, e.getMessage());
            sendError(response, 500, "{\"error\":\"Failed to issue certificate: " + escapeJson(e.getMessage()) + "\"}");
        }
    }
}
