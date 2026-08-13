package com.github.obhen233.spi;

import com.github.obhen233.core.config.ConfigManager;

import java.nio.file.Path;

/**
 * SPI interface for providing signed certificates to Worker nodes.
 *
 * <p>Gateway uses implementations of this interface to issue certificates
 * that Workers use to upgrade their HTTP connections to HTTPS.</p>
 *
 * <p>Default implementation uses JDK built-in {@code sun.security.x509} classes.
 * Custom implementations (e.g., BouncyCastle, external CA API) can be registered
 * via {@code META-INF/services/com.github.obhen233.spi.GatewayCertProvider}.</p>
 */
public interface GatewayCertProvider {

    /**
     * Initialize the certificate provider.
     *
     * @param caDir  directory for storing CA certificate and key
     * @param config configuration manager for reading provider-specific settings
     */
    void init(Path caDir, ConfigManager config);

    /**
     * Whether this provider is ready to issue certificates.
     */
    boolean isEnabled();

    /**
     * Sign a certificate for a Worker node.
     *
     * @param workerId unique worker identifier (used as CN)
     * @param host     worker hostname or IP (used as SAN)
     * @return signed certificate bundle containing cert, key, and CA cert in PEM format
     */
    SignedCert signWorkerCertificate(String workerId, String host);

    /**
     * Get the CA certificate in PEM format, for distribution to Workers.
     */
    String getCaCertPem();

    /**
     * Clean up resources held by this provider.
     */
    void destroy();

    /**
     * Result of signing a worker certificate.
     */
    class SignedCert {
        public final String certPem;
        public final String keyPem;
        public final String caCertPem;

        public SignedCert(String certPem, String keyPem, String caCertPem) {
            this.certPem = certPem;
            this.keyPem = keyPem;
            this.caCertPem = caCertPem;
        }
    }
}
