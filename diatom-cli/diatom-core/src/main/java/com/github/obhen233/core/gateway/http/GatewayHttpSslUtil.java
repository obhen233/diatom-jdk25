package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.util.InstallPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * SSL/TLS utility methods extracted from GatewayHttpServer.
 * Provides support for JKS/PKCS12 keystore and PEM certificate loading.
 */
public final class GatewayHttpSslUtil {
    private static final Logger logger = LoggerFactory.getLogger(GatewayHttpSslUtil.class);

    private GatewayHttpSslUtil() {}

    // ==================== SSL Context Creation ====================

    /**
     * Create SSLContext from keystore path with auto-detected type.
     * Supports JKS, PKCS12 (.p12/.pfx).
     */
    public static SSLContext createSSLContext(String keystorePath, String keystorePassword, String keyPassword) {
        try {
            KeyStore ks = loadKeyStore(keystorePath, keystorePassword);
            return createSSLContextFromKeyStore(ks, keyPassword);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSLContext: " + e.getMessage(), e);
        }
    }

    /**
     * Load a KeyStore from file, auto-detecting type from extension.
     * .jks -> JKS, .p12/.pfx -> PKCS12, default -> JKS
     */
    public static KeyStore loadKeyStore(String path, String password) throws Exception {
        String lower = path.toLowerCase();
        String type;
        if (lower.endsWith(".p12") || lower.endsWith(".pfx")) {
            type = "PKCS12";
        } else {
            type = "JKS";
        }
        char[] pass = password != null ? password.toCharArray() : new char[0];
        KeyStore ks = KeyStore.getInstance(type);
        try (FileInputStream fis = new FileInputStream(path)) {
            ks.load(fis, pass);
        }
        return ks;
    }

    /**
     * Create SSLContext from an already-loaded KeyStore.
     */
    public static SSLContext createSSLContextFromKeyStore(KeyStore ks, String keyPassword) throws Exception {
        char[] keyPass = keyPassword != null ? keyPassword.toCharArray() : new char[0];
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPass);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    /**
     * Create SSLContext from PEM certificate and private key files.
     * Supports PKCS#8 and PKCS#1 RSA private keys.
     */
    public static SSLContext createSSLContextFromPem(String certFile, String keyFile,
                                                      String keystorePassword, String keyPassword) {
        try {
            KeyStore ks = createKeyStoreFromPem(certFile, keyFile, keystorePassword);
            return createSSLContextFromKeyStore(ks, keyPassword);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSLContext from PEM: " + e.getMessage(), e);
        }
    }

    /**
     * Create SSLContext from PEM certificate and private key content strings
     * (not file paths — the actual PEM text).
     */
    public static SSLContext createSSLContextFromPemContent(String certPem, String keyPem,
                                                            String keystorePassword, String keyPassword) {
        try {
            KeyStore ks = createKeyStoreFromPemContent(certPem, keyPem, keystorePassword);
            return createSSLContextFromKeyStore(ks, keyPassword);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSLContext from PEM content: " + e.getMessage(), e);
        }
    }

    /**
     * Build a PKCS12 KeyStore in memory from PEM certificate + private key files.
     */
    public static KeyStore createKeyStoreFromPem(String certFile, String keyFile, String password) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> certs;
        try (InputStream is = Files.newInputStream(Paths.get(certFile))) {
            certs = new ArrayList<>(cf.generateCertificates(is));
        }
        if (certs.isEmpty()) {
            throw new IllegalArgumentException("No certificates found in: " + certFile);
        }

        PrivateKey privateKey = parsePemPrivateKey(keyFile);

        String pass = password != null ? password : "";
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, pass.toCharArray());
        ks.setKeyEntry("server", privateKey, pass.toCharArray(), certs.toArray(new Certificate[0]));
        return ks;
    }

    /**
     * Build a PKCS12 KeyStore in memory from PEM certificate + private key content strings.
     */
    public static KeyStore createKeyStoreFromPemContent(String certPem, String keyPem, String password) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> certs;
        try (InputStream is = new java.io.ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8))) {
            certs = new ArrayList<>(cf.generateCertificates(is));
        }
        if (certs.isEmpty()) {
            throw new IllegalArgumentException("No certificates found in PEM content");
        }

        PrivateKey privateKey = parsePemPrivateKeyContent(keyPem);

        String pass = password != null ? password : "";
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, pass.toCharArray());
        ks.setKeyEntry("server", privateKey, pass.toCharArray(), certs.toArray(new Certificate[0]));
        return ks;
    }

    /**
     * Parse a PEM private key file. Supports PKCS#8 and PKCS#1 RSA formats.
     */
    public static PrivateKey parsePemPrivateKey(String keyFile) throws Exception {
        String pem = new String(Files.readAllBytes(Paths.get(keyFile)), StandardCharsets.UTF_8);
        return parsePemPrivateKeyContent(pem);
    }

    /**
     * Parse a PEM private key from a content string (not file path).
     * Supports PKCS#8 and PKCS#1 RSA formats.
     */
    public static PrivateKey parsePemPrivateKeyContent(String pem) throws Exception {
        String label = "-----BEGIN PRIVATE KEY-----";
        int start = pem.indexOf(label);
        if (start >= 0) {
            int end = pem.indexOf("-----END PRIVATE KEY-----", start);
            String b64 = pem.substring(start + label.length(), end).trim();
            byte[] encoded = Base64.getDecoder().decode(b64);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
            return tryCreatePrivateKey(spec);
        }

        label = "-----BEGIN RSA PRIVATE KEY-----";
        start = pem.indexOf(label);
        if (start >= 0) {
            int end = pem.indexOf("-----END RSA PRIVATE KEY-----", start);
            String b64 = pem.substring(start + label.length(), end).trim();
            byte[] pkcs1 = Base64.getDecoder().decode(b64);
            byte[] pkcs8 = convertPkcs1ToPkcs8(pkcs1);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        }

        throw new IllegalArgumentException("Unsupported private key format (only PKCS#8 and PKCS#1 RSA supported)");
    }

    /**
     * Try to create a PrivateKey from a PKCS#8 spec using common algorithms.
     */
    public static PrivateKey tryCreatePrivateKey(PKCS8EncodedKeySpec spec) throws Exception {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            try {
                return KeyFactory.getInstance("EC").generatePrivate(spec);
            } catch (Exception e2) {
                return KeyFactory.getInstance("DSA").generatePrivate(spec);
            }
        }
    }

    /**
     * Convert a DER-encoded PKCS#1 RSA private key to PKCS#8 format.
     */
    public static byte[] convertPkcs1ToPkcs8(byte[] pkcs1) {
        byte[] algoId = derSequence(
            derOid(0x2A, (byte)0x86, 0x48, (byte)0x86, (byte)0xF7, 0x0D, 0x01, 0x01, 0x01),
            derNull()
        );
        byte[] wrappedKey = derOctetString(pkcs1);
        return derSequence(derInteger(0), algoId, wrappedKey);
    }

    // ==================== DER encoding helpers ====================

    public static byte[] derSequence(byte[]... parts) { return derConstructed(0x30, parts); }
    public static byte[] derOctetString(byte[] content) { return derPrimitive(0x04, content); }
    public static byte[] derNull() { return new byte[]{0x05, 0x00}; }

    public static byte[] derInteger(int value) {
        int bits = 32 - Integer.numberOfLeadingZeros(value);
        int len = Math.max(1, (bits + 7) / 8);
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++) {
            bytes[len - 1 - i] = (byte) (value >> (8 * i));
        }
        if ((bytes[0] & 0x80) != 0) {
            byte[] tmp = new byte[bytes.length + 1];
            System.arraycopy(bytes, 0, tmp, 1, bytes.length);
            bytes = tmp;
        }
        return derPrimitive(0x02, bytes);
    }

    public static byte[] derOid(int... components) {
        byte[] body = derEncodeOidBody(components);
        return derPrimitive(0x06, body);
    }

    public static byte[] derConstructed(int tag, byte[]... parts) {
        return derEncode(tag, concat(parts));
    }

    public static byte[] derPrimitive(int tag, byte[] content) {
        return derEncode(tag, content);
    }

    public static byte[] derEncode(int tag, byte[] content) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(tag);
        derWriteLength(bos, content.length);
        try { bos.write(content); } catch (IOException e) { /* ByteArrayOutputStream never throws */ }
        return bos.toByteArray();
    }

    public static void derWriteLength(ByteArrayOutputStream bos, int length) {
        if (length < 128) {
            bos.write(length);
        } else {
            int bytes = 1, tmp = length;
            while ((tmp >>= 8) > 0) bytes++;
            bos.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                bos.write((length >> (8 * i)) & 0xFF);
            }
        }
    }

    public static byte[] derEncodeOidBody(int[] components) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(40 * components[0] + components[1]);
        for (int i = 2; i < components.length; i++) {
            int val = components[i] & 0xFF;
            if (val < 0) val += 256;
            if (val < 128) {
                bos.write(val);
            } else {
                int bits = 32 - Integer.numberOfLeadingZeros(val);
                int bytes = Math.max(1, (bits + 6) / 7);
                for (int j = bytes - 1; j >= 0; j--) {
                    int b = (val >> (7 * j)) & 0x7F;
                    if (j > 0) b |= 0x80;
                    bos.write(b);
                }
            }
        }
        return bos.toByteArray();
    }

    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, result, pos, p.length);
            pos += p.length;
        }
        return result;
    }

    // ==================== Config helpers ====================

    /**
     * Read a password from config, supporting both inline value and file-based value.
     * Priority: {key}-file -> {key}
     */
    public static String readPassword(ConfigManager cm, String key) {
        if (cm == null) return null;
        String filePath = cm.get(key + "-file");
        if (filePath != null && !filePath.trim().isEmpty()) {
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(filePath.trim()));
                return new String(bytes, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                logger.warn("Failed to read password from file '{}': {}. Falling back to inline password.", filePath, e.getMessage());
            }
        }
        return cm.get(key);
    }

    /**
     * Check if SSL is enabled via configManager.
     */
    public static boolean sslEnabled(ConfigManager cm) {
        if (cm == null) return false;
        String val = cm.get("gateway.ssl.enabled");
        return "true".equalsIgnoreCase(val != null ? val.trim() : "");
    }

    /**
     * Save the CA certificate PEM to the worker's certificate directory under install home.
     * Directory: {@code {installHome}/worker-certs/}
     *
     * <p>Uses install home ({jarDir}/.diatom/) rather than user home (~/.diatom/)
     * to keep certs isolated per-worker-jar when multiple workers share a server.</p>
     *
     * @param caCertPem the CA certificate in PEM format
     * @throws IOException if file writing fails
     */
    public static void saveCaCert(String caCertPem) throws IOException {
        java.nio.file.Path caDir = InstallPaths.getInstallHome().resolve("worker-certs");
        Files.createDirectories(caDir);
        Files.write(caDir.resolve("ca-cert.pem"), caCertPem.getBytes(StandardCharsets.UTF_8));
        logger.info("CA certificate saved to {}", caDir.resolve("ca-cert.pem"));
    }
}
