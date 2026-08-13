package com.github.obhen233.core.gateway.security;

/**
 * Pass-through encryption provider that performs no encryption.
 * Suitable for localhost-only communication or development environments.
 */
public class NoopEncryptionProvider implements EncryptionProvider {

    @Override
    public byte[] encrypt(byte[] data, String targetId) {
        return data;
    }

    @Override
    public byte[] decrypt(byte[] data, String sourceId) {
        return data;
    }

    @Override
    public String getAlgorithm() {
        return "none";
    }
}
