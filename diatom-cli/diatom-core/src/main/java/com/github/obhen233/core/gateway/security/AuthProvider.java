package com.github.obhen233.core.gateway.security;

import java.util.Map;

/**
 * SPI interface for authentication between gateway and worker nodes.
 */
public interface AuthProvider {

    /**
     * Generate an authentication token for communication from sourceId to targetId.
     */
    String generateToken(String sourceId, String targetId);

    /**
     * Authenticate a received token from the given sourceId.
     */
    boolean authenticate(String token, String sourceId);

    /**
     * Enrich outgoing HTTP headers with authentication information for the given targetId.
     */
    Map<String, String> enrichHeaders(String targetId);
}
