package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.spi.http.ServerRequest;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;

/**
 * JDK {@link HttpExchange} to {@link ServerRequest} adapter.
 */
class JdkServerRequest implements ServerRequest {

    private final HttpExchange exchange;

    JdkServerRequest(HttpExchange exchange) {
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
            return new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
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
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try {
                    return URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception e) {
                    return kv[1];
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

    /**
     * Get the underlying HttpExchange (for GatewayAdmissionControl which needs headers).
     */
    HttpExchange getExchange() {
        return exchange;
    }
}
