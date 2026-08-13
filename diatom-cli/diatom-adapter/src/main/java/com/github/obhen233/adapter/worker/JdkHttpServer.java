package com.github.obhen233.adapter.worker;

import com.github.obhen233.adapter.spi.HttpServerSpi;
import com.github.obhen233.adapter.spi.ServerHandler;
import com.github.obhen233.adapter.spi.ServerRequest;
import com.github.obhen233.adapter.spi.ServerResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Default {@link HttpServerSpi} implementation using JDK {@link HttpServer}
 * for the adapter module.
 */
public class JdkHttpServer implements HttpServerSpi {

    private static final Logger logger = LoggerFactory.getLogger(JdkHttpServer.class);

    private final int port;
    private final Executor executor;

    private HttpServer server;
    private final List<Route> routes = new ArrayList<Route>();

    static class Route {
        final String method;
        final String path;
        final ServerHandler handler;

        Route(String method, String path, ServerHandler handler) {
            this.method = method;
            this.path = path;
            this.handler = handler;
        }
    }

    public JdkHttpServer(int port, Executor executor) throws IOException {
        this.port = port;
        this.executor = executor;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        if (executor != null) {
            this.server.setExecutor(executor);
        }
        logger.info("Adapter JdkHttpServer created on port {}", port);
    }

    @Override
    public void start() {
        if (server == null) {
            throw new IllegalStateException("Server not initialized");
        }
        server.start();
        logger.info("Adapter JdkHttpServer started on port {}", port);
    }

    @Override
    public void stop(int delay) {
        if (server != null) {
            server.stop(delay);
            logger.info("Adapter JdkHttpServer stopped");
        }
    }

    @Override
    public int getPort() {
        if (server != null) {
            return server.getAddress().getPort();
        }
        return port;
    }

    @Override
    public void addHandler(String httpMethod, String path, ServerHandler handler) {
        routes.add(new Route(httpMethod, path, handler));
        server.createContext(path, exchange -> {
            try {
                if (!httpMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendMethodNotAllowed(exchange);
                    return;
                }
                AdapterServerRequest request = new AdapterServerRequest(exchange);
                AdapterServerResponse response = new AdapterServerResponse(exchange);
                handler.handle(request, response);
            } catch (Exception e) {
                logger.error("Error handling {} {}: {}", httpMethod, path, e.getMessage(), e);
                try {
                    sendInternalError(exchange);
                } catch (IOException ignored) {
                }
            }
        });
    }

    @Override
    public void upgradeToHttps(String certPem, String keyPem, String caCertPem, String password) {
        InetSocketAddress addr = server.getAddress();
        int currentPort = addr.getPort();
        logger.info("Upgrading adapter JdkHttpServer to HTTPS on port {}...", currentPort);

        try {
            // Save CA certificate
            String jarDir = System.getProperty("diatom.jar.dir",
                    System.getProperty("user.home", "."));
            java.nio.file.Path caDir = java.nio.file.Paths.get(jarDir, ".diatom", "worker-certs");
            Files.createDirectories(caDir);
            Files.write(caDir.resolve("ca-cert.pem"), caCertPem.getBytes(StandardCharsets.UTF_8));
            logger.info("CA certificate saved to {}", caDir.resolve("ca-cert.pem"));

            // Save old executor
            Executor oldExecutor = server.getExecutor();

            // Stop old server
            server.stop(0);

            // On Windows, allow rebinding
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                System.setProperty("sun.net.useExclusiveBind", "false");
            }

            // Create SSLContext from PEM
            SSLContext sslCtx = createSSLContextFromPem(certPem, keyPem, password, password);

            // Create HttpsServer
            HttpsServer httpsServer = HttpsServer.create(addr, 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslCtx) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sslParams = sslCtx.getDefaultSSLParameters();
                    params.setSSLParameters(sslParams);
                }
            });

            // Re-register all routes
            for (Route route : routes) {
                httpsServer.createContext(route.path, exchange -> {
                    try {
                        if (!route.method.equalsIgnoreCase(exchange.getRequestMethod())) {
                            sendMethodNotAllowed(exchange);
                            return;
                        }
                        AdapterServerRequest request = new AdapterServerRequest(exchange);
                        AdapterServerResponse response = new AdapterServerResponse(exchange);
                        route.handler.handle(request, response);
                    } catch (Exception e) {
                        logger.error("Error handling {} {}: {}", route.method, route.path, e.getMessage(), e);
                        try {
                            sendInternalError(exchange);
                        } catch (IOException ignored) {
                        }
                    }
                });
            }

            // Set executor
            if (oldExecutor != null) {
                httpsServer.setExecutor(oldExecutor);
            }

            // Start
            httpsServer.start();
            this.server = httpsServer;
            logger.info("Adapter JdkHttpServer HTTPS upgrade complete on port {}", currentPort);
        } catch (Exception e) {
            logger.error("Failed to upgrade adapter JdkHttpServer to HTTPS on port {}", currentPort, e);
            throw new RuntimeException("Failed to upgrade to HTTPS on port " + currentPort, e);
        }
    }

    /**
     * Get the underlying JDK HttpServer.
     */
    public HttpServer getServer() {
        return server;
    }

    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"error\":\"Method not allowed\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(405, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendInternalError(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"error\":\"Internal server error\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(500, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Create SSLContext from PEM-encoded certificate and private key.
     */
    private static SSLContext createSSLContextFromPem(String certPem, String keyPem,
                                                       String keyStorePassword, String keyPassword) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        java.security.cert.X509Certificate cert;
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(
                certPem.getBytes(StandardCharsets.UTF_8))) {
            cert = (java.security.cert.X509Certificate) cf.generateCertificate(in);
        }

        String keyData = keyPem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = java.util.Base64.getDecoder().decode(keyData);
        java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        java.security.PrivateKey privateKey = kf.generatePrivate(spec);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, keyStorePassword.toCharArray());
        ks.setKeyEntry("worker", privateKey, keyPassword.toCharArray(),
                new java.security.cert.Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPassword.toCharArray());

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), null, null);
        return sslCtx;
    }

    // ---- Request/Response adapter classes ----

    static class AdapterServerRequest implements ServerRequest {
        private final HttpExchange exchange;

        AdapterServerRequest(HttpExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public String getBody() throws IOException {
            try (InputStream is = exchange.getRequestBody();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                return new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        @Override
        public byte[] getBodyBytes() throws IOException {
            try (InputStream is = exchange.getRequestBody();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                return baos.toByteArray();
            }
        }

        @Override
        public String getQueryParam(String name) {
            String query = exchange.getRequestURI().getQuery();
            if (query == null) return null;
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) {
                    try {
                        return URLDecoder.decode(pair[1], "UTF-8");
                    } catch (Exception e) {
                        return pair[1];
                    }
                }
            }
            return null;
        }

        @Override
        public String getMethod() {
            return exchange.getRequestMethod();
        }

        @Override
        public String getHeader(String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }
    }

    static class AdapterServerResponse implements ServerResponse {
        private final HttpExchange exchange;
        private int statusCode = 200;
        private boolean headersSent = false;

        AdapterServerResponse(HttpExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public void setStatus(int statusCode) {
            this.statusCode = statusCode;
        }

        @Override
        public void setHeader(String name, String value) {
            exchange.getResponseHeaders().set(name, value);
        }

        @Override
        public void send(String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (!headersSent) {
                if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                }
                exchange.sendResponseHeaders(statusCode, bytes.length);
                headersSent = true;
            }
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (!headersSent) {
                exchange.sendResponseHeaders(statusCode, 0);
                headersSent = true;
            }
            return exchange.getResponseBody();
        }
    }
}
