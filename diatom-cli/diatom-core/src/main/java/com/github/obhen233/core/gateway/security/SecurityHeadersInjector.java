package com.github.obhen233.core.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 安全头注入器
 * 自动向所有 Gateway ↔ Worker 的 HTTP 请求注入安全头
 */
public class SecurityHeadersInjector {
    private static final Logger logger = LoggerFactory.getLogger(SecurityHeadersInjector.class);

    public static final String HEADER_AUTH = "X-Diatom-Auth";
    public static final String HEADER_INSTANCE_ID = "X-Diatom-Instance-Id";
    public static final String HEADER_ENCRYPTION = "X-Diatom-Encryption";
    public static final String HEADER_TIMESTAMP = "X-Diatom-Timestamp";

    private final AuthProvider authProvider;
    private final EncryptionProvider encryptionProvider;
    private final String instanceId;

    public SecurityHeadersInjector() {
        this(null, null);
    }

    public SecurityHeadersInjector(AuthProvider authProvider, EncryptionProvider encryptionProvider) {
        this.authProvider = authProvider;
        this.encryptionProvider = encryptionProvider;
        this.instanceId = System.getProperty("diatom.instance.id", "unknown");
    }

    /**
     * 生成完整的安全头 Map
     */
    public Map<String, String> generateHeaders(String targetId) {
        Map<String, String> headers = new HashMap<>();

        // 实例 ID
        headers.put(HEADER_INSTANCE_ID, instanceId);

        // 时间戳（防重放）
        headers.put(HEADER_TIMESTAMP, String.valueOf(System.currentTimeMillis()));

        // 鉴权 Token
        if (authProvider != null) {
            Map<String, String> authHeaders = authProvider.enrichHeaders(targetId);
            if (authHeaders != null) {
                headers.putAll(authHeaders);
            }
        }

        // 加密算法标识
        if (encryptionProvider != null) {
            headers.put(HEADER_ENCRYPTION, encryptionProvider.getAlgorithm());
        }

        return headers;
    }

    /**
     * 注入安全头到 HttpURLConnection
     */
    public void injectIntoConnection(HttpURLConnection conn, String targetId) {
        Map<String, String> headers = generateHeaders(targetId);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 验证请求中的安全头
     */
    public boolean authenticateRequest(Map<String, String> requestHeaders) {
        String authHeader = requestHeaders.get(HEADER_AUTH);
        String sourceInstanceId = requestHeaders.get(HEADER_INSTANCE_ID);

        if (authProvider != null && authHeader != null) {
            return authProvider.authenticate(authHeader, sourceInstanceId);
        }

        // 无 AuthProvider 时，同机通信默认通过
        return true;
    }

    /**
     * Encrypt outgoing body with EncryptionProvider.
     * Returns base64-encoded encrypted bytes (text-safe for JSON transport).
     * When encryption is noop, returns original bytes unchanged.
     */
    public static byte[] encryptBody(byte[] data, String targetId, Map<String, String> headers) {
        EncryptionProvider ep = SecurityProviderLoader.getEncryptionProvider();
        if (ep == null || "none".equals(ep.getAlgorithm())) {
            return data;
        }
        byte[] encrypted = ep.encrypt(data, targetId);
        byte[] result = Base64.getEncoder().encode(encrypted);
        if (headers != null) {
            headers.put(HEADER_ENCRYPTION, ep.getAlgorithm());
        }
        return result;
    }

    /**
     * Decrypt incoming body if X-Diatom-Encryption header indicates encryption.
     * When no encryption, returns original bytes.
     */
    public static byte[] decryptBody(byte[] data, String sourceId, Map<String, String> headers) {
        if (headers == null) {
            return data;
        }
        String encryptionAlgo = headers.get(HEADER_ENCRYPTION);
        if (encryptionAlgo == null || "none".equals(encryptionAlgo)) {
            return data;
        }
        EncryptionProvider ep = SecurityProviderLoader.getEncryptionProvider();
        if (ep == null) {
            return data;
        }
        byte[] decoded = Base64.getDecoder().decode(data);
        return ep.decrypt(decoded, sourceId);
    }
}
