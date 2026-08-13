package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.spi.GatewayCertProvider;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of {@link GatewayCertProvider} using BouncyCastle.
 *
 * <p>Creates a self-signed CA on first initialization, then uses it to sign
 * worker certificates. (JDK's internal {@code sun.security.x509} API was
 * encapsulated and changed in JDK 9+, so BouncyCastle is used instead.)</p>
 *
 * <p>CA storage layout under {@code caDir}:</p>
 * <ul>
 *   <li>{@code ca-cert.pem} — CA certificate (distributed to workers)</li>
 *   <li>{@code ca-key.pem} — CA private key (gateway only, PKCS#8 PEM)</li>
 *   <li>{@code ca-serial.txt} — serial number counter (auto-created)</li>
 * </ul>
 */
public class DefaultJavaCertProvider implements GatewayCertProvider {

    private static final Logger logger = LoggerFactory.getLogger(DefaultJavaCertProvider.class);

    private static final int CA_KEY_SIZE = 2048;
    private static final int WORKER_KEY_SIZE = 2048;
    private static final int CA_VALIDITY_DAYS = 3650; // 10 years
    private static final int WORKER_VALIDITY_DAYS = 365; // 1 year
    private static final String SIGNATURE_ALGORITHM = "SHA256WithRSA";
    private static final String CA_CN = "Diatom Internal CA";

    private Path caDir;
    private PrivateKey caPrivateKey;
    private X509Certificate caCert;
    private AtomicLong serialCounter;

    @Override
    public void init(Path caDir, ConfigManager config) {
        this.caDir = caDir;
        try {
            Files.createDirectories(caDir);
            Path certFile = caDir.resolve("ca-cert.pem");
            Path keyFile = caDir.resolve("ca-key.pem");
            Path serialFile = caDir.resolve("ca-serial.txt");

            if (Files.exists(certFile) && Files.exists(keyFile)) {
                loadExistingCa(certFile, keyFile, serialFile);
                logger.info("Loaded existing CA from {}", caDir);
            } else {
                generateSelfSignedCa(certFile, keyFile, serialFile);
                logger.info("Generated new self-signed CA at {}", caDir);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize DefaultJavaCertProvider", e);
        }
    }

    @Override
    public boolean isEnabled() {
        return caPrivateKey != null && caCert != null;
    }

    @Override
    public SignedCert signWorkerCertificate(String workerId, String host) {
        try {
            logger.info("Signing certificate for worker {} (host: {})", workerId, host);

            // Generate worker key pair
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(WORKER_KEY_SIZE, new SecureRandom());
            KeyPair workerKp = kpg.generateKeyPair();

            // Build certificate
            long serial = serialCounter.getAndIncrement();
            Date now = new Date();
            Date expiry = new Date(now.getTime() + WORKER_VALIDITY_DAYS * 86400000L);
            X500Name subject = new X500Name("CN=" + workerId);
            X500Name issuer = new X500Name(caCert.getSubjectX500Principal().getName());

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    issuer, BigInteger.valueOf(serial), now, expiry, subject, workerKp.getPublic());

            // Extensions: add SAN for host
            if (host != null && !host.isEmpty()) {
                GeneralName hostName = isIpAddress(host)
                        ? new GeneralName(GeneralName.iPAddress, normalizeHostForSan(host))
                        : new GeneralName(GeneralName.dNSName, host);
                builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(hostName));
            }

            ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(caPrivateKey);
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

            // Encode to PEM
            String certPem = pemEncode("CERTIFICATE", cert.getEncoded());
            String keyPem = pemEncode("PRIVATE KEY", workerKp.getPrivate().getEncoded());
            String caCertPem = pemEncode("CERTIFICATE", caCert.getEncoded());

            // Persist serial
            persistSerial();

            logger.info("Certificate signed for worker {} (serial: {})", workerId, serial);
            return new SignedCert(certPem, keyPem, caCertPem);

        } catch (Exception e) {
            throw new RuntimeException("Failed to sign certificate for worker " + workerId, e);
        }
    }

    @Override
    public String getCaCertPem() {
        try {
            return pemEncode("CERTIFICATE", caCert.getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode CA certificate", e);
        }
    }

    @Override
    public void destroy() {
        // No resources to clean up
    }

    // ---- Private helpers ----

    private void loadExistingCa(Path certFile, Path keyFile, Path serialFile) throws Exception {
        // Load CA certificate
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(certFile)) {
            caCert = (X509Certificate) cf.generateCertificate(in);
        }

        // Load CA private key
        byte[] keyBytes = Files.readAllBytes(keyFile);
        String keyPem = new String(keyBytes, StandardCharsets.UTF_8);
        byte[] der = pemDecode(keyPem);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        caPrivateKey = kf.generatePrivate(spec);

        // Load serial or detect from cert
        if (Files.exists(serialFile)) {
            String serialStr = new String(Files.readAllBytes(serialFile), StandardCharsets.UTF_8).trim();
            serialCounter = new AtomicLong(Long.parseLong(serialStr));
        } else {
            serialCounter = new AtomicLong(caCert.getSerialNumber().longValue() + 1);
        }
    }

    private void generateSelfSignedCa(Path certFile, Path keyFile, Path serialFile) throws Exception {
        // Generate CA key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(CA_KEY_SIZE, new SecureRandom());
        KeyPair caKp = kpg.generateKeyPair();

        // Build certificate
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date now = new Date();
        Date expiry = new Date(now.getTime() + CA_VALIDITY_DAYS * 86400000L);
        X500Name subject = new X500Name("CN=" + CA_CN);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, now, expiry, subject, caKp.getPublic());

        // Extensions: CA=true, KeyUsage=keyCertSign+cRLSign
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(caKp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        // Store
        this.caCert = cert;
        this.caPrivateKey = caKp.getPrivate();
        this.serialCounter = new AtomicLong(serial.longValue() + 1);

        // Write PEM files
        Files.write(certFile, pemEncode("CERTIFICATE", cert.getEncoded()).getBytes(StandardCharsets.UTF_8));
        Files.write(keyFile, pemEncode("PRIVATE KEY", caKp.getPrivate().getEncoded()).getBytes(StandardCharsets.UTF_8));
        persistSerial();
    }

    private void persistSerial() throws IOException {
        Path serialFile = caDir.resolve("ca-serial.txt");
        Files.write(serialFile, String.valueOf(serialCounter.get()).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isIpAddress(String host) {
        return host.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
                || host.matches("^\\[.*\\]$");
    }

    /**
     * Strip surrounding brackets for IPv6 (e.g. {@code [::1]} → {@code ::1}),
     * which BouncyCastle requires for {@link GeneralName#iPAddress}.
     */
    private static String normalizeHostForSan(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    // ---- PEM encoding/decoding ----

    static String pemEncode(String type, byte[] der) {
        String sep = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----").append(sep);
        String b64 = Base64.getEncoder().encodeToString(der);
        // Wrap at 64 chars
        for (int i = 0; i < b64.length(); i += 64) {
            int end = Math.min(i + 64, b64.length());
            sb.append(b64, i, end).append(sep);
        }
        sb.append("-----END ").append(type).append("-----").append(sep);
        return sb.toString();
    }

    static byte[] pemDecode(String pem) {
        String cleaned = pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }
}
