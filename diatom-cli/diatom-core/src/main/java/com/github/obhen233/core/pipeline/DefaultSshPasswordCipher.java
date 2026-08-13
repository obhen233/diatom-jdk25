package com.github.obhen233.core.pipeline;

import com.github.obhen233.spi.SshPasswordCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Default implementation of {@link SshPasswordCipher}.
 * <p>
 * Uses AES-128/ECB/PKCS5Padding encryption.
 * The encryption key is read from the environment variable {@code DIATOM_SSH_CIPHER_KEY}.
 * If the environment variable is not set, a built-in default key is used
 * (users are strongly encouraged to set a custom key).
 * <p>
 * Encrypted passwords are stored in deploy.yaml with the {@code $ENC$} prefix:
 * <pre>
 * password: "$ENC$A7x3kQ9Zp2mN4vB6..."
 * </pre>
 */
public class DefaultSshPasswordCipher implements SshPasswordCipher {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSshPasswordCipher.class);

    private static final String ENC_PREFIX = "$ENC$";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private static final String ENV_KEY = "DIATOM_SSH_CIPHER_KEY";
    private static final int KEY_LENGTH = 16; // AES-128

    private final SecretKeySpec keySpec;

    public DefaultSshPasswordCipher() {
        String keySource = System.getenv(ENV_KEY);
        byte[] keyBytes = new byte[KEY_LENGTH];
        if (keySource != null && !keySource.isEmpty()) {
            byte[] src = keySource.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(src, 0, keyBytes, 0, Math.min(src.length, KEY_LENGTH));
            logger.debug("SSH password cipher initialized with key from env {} ({} bytes)", ENV_KEY, keySource.length());
        } else {
            // Built-in default key — users should set DIATOM_SSH_CIPHER_KEY for production
            byte[] defaultKey = "D1@t0m_C1pherKey".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(defaultKey, 0, keyBytes, 0, Math.min(defaultKey.length, KEY_LENGTH));
            logger.warn("SSH password cipher using built-in default key. Set env {} for production use.", ENV_KEY);
        }
        this.keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    @Override
    public String encrypt(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            return plainPassword;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));
            return ENC_PREFIX + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt SSH password", e);
        }
    }

    @Override
    public String decrypt(String encryptedPassword) {
        if (encryptedPassword == null || encryptedPassword.isEmpty()) {
            return encryptedPassword;
        }
        // If not encrypted, return as-is (backward compatibility with plaintext passwords)
        if (!encryptedPassword.startsWith(ENC_PREFIX)) {
            return encryptedPassword;
        }
        try {
            String base64Data = encryptedPassword.substring(ENC_PREFIX.length());
            byte[] encryptedBytes = Base64.getDecoder().decode(base64Data);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(encryptedBytes);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt SSH password", e);
        }
    }
}
