package com.github.obhen233.spi;

/**
 * SPI for SSH password encryption/decryption in deploy.yaml.
 * <p>
 * Implement this interface to provide custom password protection.
 * The default implementation uses AES-128 encryption with a configurable key.
 * <p>
 * Passwords stored in deploy.yaml follow this convention:
 * <ul>
 *   <li>Plaintext: {@code password: "myPass123"} (backward compatible)</li>
 *   <li>Encrypted: {@code password: "$ENC$&lt;base64-encrypted-value&gt;"}</li>
 * </ul>
 * <p>
 * To provide a custom implementation:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Register in {@code META-INF/services/com.github.obhen233.spi.SshPasswordCipher}</li>
 *   <li>Place the JAR on the classpath</li>
 * </ol>
 */
public interface SshPasswordCipher {

    /**
     * Encrypt a plaintext password for secure storage in deploy.yaml.
     *
     * @param plainPassword the plaintext password
     * @return the encrypted password (will be stored with {@code $ENC$} prefix)
     */
    String encrypt(String plainPassword);

    /**
     * Decrypt a stored password back to plaintext for SSH authentication.
     * <p>
     * The input may or may not have the {@code $ENC$} prefix.
     * Implementations should handle both cases or delegate to
     * {@link SshPasswordCipherHelper} for prefix-aware processing.
     *
     * @param encryptedPassword the stored password (possibly with {@code $ENC$} prefix)
     * @return the decrypted plaintext password
     */
    String decrypt(String encryptedPassword);
}
