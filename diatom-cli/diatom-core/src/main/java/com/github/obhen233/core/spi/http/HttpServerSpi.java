package com.github.obhen233.core.spi.http;

/**
 * SPI interface for HTTP server lifecycle management.
 *
 * <p>Default implementation uses JDK {@code com.sun.net.httpserver.HttpServer}.
 * Users can provide alternate implementations (Tomcat, Jetty, Netty, etc.)
 * via {@code META-INF/services/com.github.obhen233.core.spi.http.HttpServerSpi}.</p>
 */
public interface HttpServerSpi {

    /**
     * Start the HTTP server.
     */
    void start();

    /**
     * Stop the HTTP server with the given delay in seconds.
     * @param delay seconds to wait for outstanding requests to complete
     */
    void stop(int delay);

    /**
     * Get the port the server is listening on.
     */
    int getPort();

    /**
     * Register a handler for a given HTTP method and path.
     * @param httpMethod HTTP method (GET, POST, PUT, DELETE, etc.)
     * @param path URL path (e.g., "/gateway/v1/health")
     * @param handler the handler to invoke
     */
    void addHandler(String httpMethod, String path, ServerHandler handler);

    /**
     * Dynamically upgrade the server to HTTPS on the same port.
     * @param certPem  the signed certificate in PEM format
     * @param keyPem   the private key in PEM format (PKCS#8)
     * @param caCertPem the CA certificate in PEM format (for trust chain)
     * @param password password for the key
     */
    void upgradeToHttps(String certPem, String keyPem, String caCertPem, String password);
}
