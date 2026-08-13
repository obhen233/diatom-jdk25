package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.gateway.http.docs.ApiDocRegistry;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Handles API documentation endpoints.
 */
class GatewayDocHandler {

    private final GatewayHttpServer server;

    GatewayDocHandler(GatewayHttpServer server) {
        this.server = server;
    }

    void registerRoutes() {
        server.getServerSpi().addHandler("GET", "/gateway/v1/docs", this::handleApiDocs);
        server.getServerSpi().addHandler("GET", "/gateway/v1/docs/openapi.json", this::handleOpenApiJson);
    }

    private void handleApiDocs(ServerRequest request, ServerResponse response) throws IOException {
        serveStaticResource(request, response, "/gateway/docs/index.html");
    }

    private void handleOpenApiJson(ServerRequest request, ServerResponse response) throws IOException {
        ApiDocRegistry registry = server.getApiDocRegistry();
        if (registry == null) {
            sendError(response, 404, "API docs not available");
            return;
        }
        String json = registry.getOpenApiJson();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.setHeader("Content-Type", "application/json; charset=UTF-8");
        response.setStatus(200);
        response.send(json);
    }

    void serveStaticResource(ServerRequest request, ServerResponse response, String resourcePath) throws IOException {
        String path = resourcePath;
        String query = request.getQueryParam(null); // Not ideal but works via exchange access
        // Use direct JDK access for query string
        if (request instanceof JdkServerRequest) {
            String q = ((JdkServerRequest) request).getExchange().getRequestURI().getQuery();
            if (q != null && !q.isEmpty()) {
                path = path + "?" + q;
            }
        }

        InputStream is = getClass().getResourceAsStream(path);
        if (is == null) {
            String altPath = resourcePath;
            if (!altPath.endsWith("/")) {
                altPath = altPath.substring(0, altPath.lastIndexOf('/') + 1);
            }
            altPath = altPath + "index.html";
            is = getClass().getResourceAsStream(altPath);
        }
        if (is == null) {
            sendError(response, 404, "Not Found");
            return;
        }
        try {
            if (path.endsWith(".html") || path.endsWith(".htm")) {
                response.setHeader("Content-Type", "text/html; charset=UTF-8");
            } else if (path.endsWith(".js")) {
                response.setHeader("Content-Type", "application/javascript; charset=UTF-8");
            } else if (path.endsWith(".css")) {
                response.setHeader("Content-Type", "text/css; charset=UTF-8");
            } else if (path.endsWith(".png")) {
                response.setHeader("Content-Type", "image/png");
            } else if (path.endsWith(".svg")) {
                response.setHeader("Content-Type", "image/svg+xml");
            } else if (path.endsWith(".json")) {
                response.setHeader("Content-Type", "application/json; charset=UTF-8");
            }
            response.setStatus(200);
            OutputStream os = response.getOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
            os.close();
        } finally {
            is.close();
        }
    }
}
