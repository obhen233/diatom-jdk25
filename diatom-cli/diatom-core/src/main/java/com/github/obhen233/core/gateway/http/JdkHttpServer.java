package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.spi.http.HttpServerSpi;
import com.github.obhen233.core.spi.http.ServerHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Default {@link HttpServerSpi} implementation using JDK {@link HttpServer}.
 *
 * <p>Supports dynamic HTTPS upgrade on the same port.
 * Multiple HTTP methods can be registered on the same path.</p>
 */
public class JdkHttpServer implements HttpServerSpi {

    private static final Logger logger = LoggerFactory.getLogger(JdkHttpServer.class);

    private final int port;
    private final boolean ssl;
    private final SSLContext sslContext;
    private final Executor executor;

    private HttpServer server;

    /** Path -> (Method -> Handler) */
    private final Map<String, Map<String, ServerHandler>> pathHandlerMap = new ConcurrentHashMap<String, Map<String, ServerHandler>>();

    /** Track which paths already have an HttpContext created */
    private final Set<String> registeredContexts = new HashSet<String>();

    /**
     * Create a JdkHttpServer (HTTP mode).
     */
    public JdkHttpServer(int port, Executor executor) throws IOException {
        this(port, false, null, executor);
    }

    /**
     * Create a JdkHttpServer with optional SSL.
     * @param port       listening port
     * @param ssl        whether to create an HTTPS server
     * @param sslContext SSLContext for HTTPS (required if ssl=true)
     * @param executor   thread pool executor
     */
    public JdkHttpServer(int port, boolean ssl, SSLContext sslContext, Executor executor) throws IOException {
        this.port = port;
        this.ssl = ssl;
        this.sslContext = sslContext;
        this.executor = executor;
        initServer();
    }

    private void initServer() throws IOException {
        InetSocketAddress addr = new InetSocketAddress(port);
        if (ssl && sslContext != null) {
            HttpsServer httpsServer = HttpsServer.create(addr, 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sslParams = sslContext.getDefaultSSLParameters();
                    params.setSSLParameters(sslParams);
                }
            });
            this.server = httpsServer;
            logger.info("JdkHttpServer created in HTTPS mode on port {}", port);
        } else {
            this.server = HttpServer.create(addr, 0);
            logger.info("JdkHttpServer created in HTTP mode on port {}", port);
        }
        if (executor != null) {
            this.server.setExecutor(executor);
        }
    }

    @Override
    public void start() {
        if (server == null) {
            throw new IllegalStateException("Server not initialized");
        }
        server.start();
        logger.info("JdkHttpServer started on 127.0.0.1:{}", port);
    }

    @Override
    public void stop(int delay) {
        if (server != null) {
            server.stop(delay);
            logger.info("JdkHttpServer stopped");
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
        // Store in the method-specific handler map for this path
        Map<String, ServerHandler> handlers = pathHandlerMap.get(path);
        if (handlers == null) {
            handlers = new ConcurrentHashMap<String, ServerHandler>();
            Map<String, ServerHandler> existing = pathHandlerMap.putIfAbsent(path, handlers);
            if (existing != null) {
                handlers = existing;
            }
        }
        final String methodKey = httpMethod.toUpperCase();
        handlers.put(methodKey, handler);

        // Only create the JDK HttpContext once per path
        // (paths with multiple methods share one context that dispatches by method)
        synchronized (registeredContexts) {
            if (!registeredContexts.contains(path)) {
                registeredContexts.add(path);
                final Map<String, ServerHandler> finalHandlers = handlers;
                server.createContext(path, exchange -> {
                    try {
                        Map<String, ServerHandler> h = pathHandlerMap.get(path);
                        if (h == null) {
                            sendMethodNotAllowed(exchange);
                            return;
                        }
                        ServerHandler targetHandler = h.get(exchange.getRequestMethod().toUpperCase());
                        if (targetHandler == null) {
                            sendMethodNotAllowed(exchange);
                            return;
                        }
                        JdkServerRequest request = new JdkServerRequest(exchange);
                        JdkServerResponse response = new JdkServerResponse(exchange);
                        targetHandler.handle(request, response);
                    } catch (Exception e) {
                        logger.error("Error handling {} {}: {}", exchange.getRequestMethod(),
                                path, e.getMessage(), e);
                        try {
                            sendInternalError(exchange);
                        } catch (IOException ignored) {
                        }
                    }
                });
            }
        }
    }

    @Override
    public void upgradeToHttps(String certPem, String keyPem, String caCertPem, String password) {
        InetSocketAddress addr = server.getAddress();
        int currentPort = addr.getPort();
        logger.info("Upgrading JdkHttpServer to HTTPS on port {}...", currentPort);

        try {
            // Save CA certificate
            java.nio.file.Path jarDir = java.nio.file.Paths.get(
                    System.getProperty("diatom.jar.dir", System.getProperty("user.home", ".")));
            java.nio.file.Path caDir = jarDir.resolve(".diatom").resolve("worker-certs");
            java.nio.file.Files.createDirectories(caDir);
            java.nio.file.Files.write(caDir.resolve("ca-cert.pem"),
                    caCertPem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            logger.info("CA certificate saved to {}", caDir.resolve("ca-cert.pem"));

            // Save old executor before stopping
            java.util.concurrent.Executor oldExecutor = server.getExecutor();

            // Stop old server
            server.stop(0);

            // On Windows, allow rebinding the same address
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                System.setProperty("sun.net.useExclusiveBind", "false");
            }

            // Create SSLContext from PEM
            SSLContext sslCtx = GatewayHttpSslUtil.createSSLContextFromPemContent(certPem, keyPem, password, password);

            // Create HttpsServer
            HttpsServer httpsServer = HttpsServer.create(addr, 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslCtx) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sslParams = sslCtx.getDefaultSSLParameters();
                    params.setSSLParameters(sslParams);
                }
            });

            // Re-register all routes (method-dispatch based on pathHandlerMap)
            for (Map.Entry<String, Map<String, ServerHandler>> pathEntry : pathHandlerMap.entrySet()) {
                final String path = pathEntry.getKey();
                final Map<String, ServerHandler> handlers = pathEntry.getValue();
                httpsServer.createContext(path, exchange -> {
                    try {
                        ServerHandler targetHandler = handlers.get(exchange.getRequestMethod().toUpperCase());
                        if (targetHandler == null) {
                            sendMethodNotAllowed(exchange);
                            return;
                        }
                        JdkServerRequest request = new JdkServerRequest(exchange);
                        JdkServerResponse response = new JdkServerResponse(exchange);
                        targetHandler.handle(request, response);
                    } catch (Exception e) {
                        logger.error("Error handling {} {}: {}", exchange.getRequestMethod(),
                                path, e.getMessage(), e);
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
            logger.info("JdkHttpServer HTTPS upgrade complete on port {}", currentPort);
        } catch (Exception e) {
            logger.error("Failed to upgrade JdkHttpServer to HTTPS on port {}", currentPort, e);
            throw new RuntimeException("Failed to upgrade to HTTPS on port " + currentPort, e);
        }
    }

    /**
     * Get the underlying JDK HttpServer (for backward compatibility).
     */
    public HttpServer getServer() {
        return server;
    }

    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"error\":\"Method not allowed\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(405, bytes.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendInternalError(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"error\":\"Internal server error\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(500, bytes.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
