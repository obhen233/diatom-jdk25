package com.github.obhen233.core.gateway.security;

/**
 * SPI interface for encryption of inter-node communication payloads.
 */
public interface EncryptionProvider {

    /**
     * Encrypt data for the given target node.
     */
    byte[] encrypt(byte[] data, String targetId);

    /**
     * Decrypt data received from the given source node.
     */
    byte[] decrypt(byte[] data, String sourceId);

    /**
     * Get the algorithm name (e.g. "AES/GCM/NoPadding").
     */
    String getAlgorithm();
}
