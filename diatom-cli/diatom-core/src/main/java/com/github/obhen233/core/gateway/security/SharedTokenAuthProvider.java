package com.github.obhen233.core.gateway.security;

import com.github.obhen233.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple shared-token authentication provider.
 * <p>
 * Token format: {@code {token}:{sourceId}:{timestamp}}
 * The shared token is read from the {@code diatom.auth.token} system property or
 * {@code AppConfig} property {@code auth.token}.
 */
public class SharedTokenAuthProvider implements AuthProvider {

    private static final Logger logger = LoggerFactory.getLogger(SharedTokenAuthProvider.class);
    private static final long TOKEN_MAX_AGE_MS = 30_000L;

    private final String sharedToken;

    public SharedTokenAuthProvider(AppConfig config) {
        String token = System.getProperty("diatom.auth.token");
        if (token == null || token.isEmpty()) {
            token = config.getProperty("auth.token", "");
        }
        if (token == null || token.isEmpty()) {
            token = generateRandomToken();
            logger.warn("No auth.token configured; generated random token: {}", token);
            logger.warn("Set this token on all workers via -Ddiatom.auth.token={}", token);
        }
        this.sharedToken = token;
    }

    public SharedTokenAuthProvider(String sharedToken) {
        this.sharedToken = sharedToken;
    }

    @Override
    public String generateToken(String sourceId, String targetId) {
        return sharedToken + ":" + sourceId + ":" + System.currentTimeMillis();
    }

    @Override
    public boolean authenticate(String token, String sourceId) {
        if (token == null || sharedToken.isEmpty()) return false;
        // Support both plain token format (from enrichHeaders) and token:sourceId:timestamp format (from generateToken)
        String[] parts = token.split(":");
        if (parts.length < 3) {
            // Old format: plain token only, no timestamp validation
            return sharedToken.equals(token);
        }
        // New format: token:sourceId:timestamp
        if (!sharedToken.equals(parts[0])) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(parts[2]);
            long now = System.currentTimeMillis();
            if (Math.abs(now - timestamp) > TOKEN_MAX_AGE_MS) {
                logger.warn("Token expired or timestamp out of window: age={}ms", now - timestamp);
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            logger.warn("Invalid timestamp in token: {}", parts[2]);
            return false;
        }
    }

    private static String generateRandomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public Map<String, String> enrichHeaders(String targetId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Diatom-Auth", sharedToken);
        return Collections.unmodifiableMap(headers);
    }
}
