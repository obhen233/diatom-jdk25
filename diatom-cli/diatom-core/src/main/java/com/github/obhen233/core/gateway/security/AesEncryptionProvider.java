package com.github.obhen233.core.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES 加密实现（跨机场景）
 * 默认使用 AES-256-GCM，密钥通过配置 diatom.encryption.key 指定
 */
public class AesEncryptionProvider implements EncryptionProvider {
    private static final Logger logger = LoggerFactory.getLogger(AesEncryptionProvider.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final Key key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesEncryptionProvider() {
        String keyStr = System.getProperty("diatom.encryption.key",
                System.getenv("DIATOM_ENCRYPTION_KEY"));
        if (keyStr == null || keyStr.isEmpty()) {
            logger.warn("No encryption key configured (diatom.encryption.key), using default key");
            keyStr = "diatom-default-key";
        }
        this.key = deriveKey(keyStr);
    }

    public AesEncryptionProvider(String keyStr) {
        this.key = deriveKey(keyStr);
    }

    private static Key deriveKey(String keyStr) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(keyStr.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive AES key", e);
        }
    }

    @Override
    public byte[] encrypt(byte[] data, String targetId) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(data);
            byte[] result = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.length);
            return result;
        } catch (Exception e) {
            logger.error("AES encryption failed: {}", e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] data, String sourceId) {
        try {
            if (data.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Ciphertext too short");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[data.length - GCM_IV_LENGTH];
            System.arraycopy(data, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            logger.error("AES decryption failed: {}", e.getMessage());
            throw new RuntimeException("Decryption failed", e);
        }
    }

    @Override
    public String getAlgorithm() {
        return "AES-256-GCM";
    }

    public static String encodeToBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] decodeFromBase64(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
