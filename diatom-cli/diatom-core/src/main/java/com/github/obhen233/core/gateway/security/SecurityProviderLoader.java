package com.github.obhen233.core.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * 安全 SPI 加载器
 * 按配置动态加载 AuthProvider 和 EncryptionProvider 实现
 *
 * 配置项:
 * - diatom.auth.provider: AuthProvider 实现类全名（默认 SharedTokenAuthProvider）
 * - diatom.encryption.provider: EncryptionProvider 实现类全名（默认 NoopEncryptionProvider）
 * - diatom.auth.token: 共享鉴权 Token（SharedTokenAuthProvider 使用）
 * - diatom.encryption.key: 加密密钥（AesEncryptionProvider 使用）
 */
public class SecurityProviderLoader {
    private static final Logger logger = LoggerFactory.getLogger(SecurityProviderLoader.class);

    private static volatile AuthProvider authProvider;
    private static volatile EncryptionProvider encryptionProvider;

    /**
     * 获取 AuthProvider 实例
     * 优先使用 ServiceLoader 加载 SPI 实现，否则根据配置实例化
     */
    public static AuthProvider getAuthProvider() {
        if (authProvider == null) {
            synchronized (SecurityProviderLoader.class) {
                if (authProvider == null) {
                    authProvider = loadAuthProvider();
                }
            }
        }
        return authProvider;
    }

    /**
     * 获取 EncryptionProvider 实例
     * 优先使用 ServiceLoader 加载 SPI 实现，否则根据配置实例化
     */
    public static EncryptionProvider getEncryptionProvider() {
        if (encryptionProvider == null) {
            synchronized (SecurityProviderLoader.class) {
                if (encryptionProvider == null) {
                    encryptionProvider = loadEncryptionProvider();
                }
            }
        }
        return encryptionProvider;
    }

    private static AuthProvider loadAuthProvider() {
        // 1. 尝试 ServiceLoader SPI
        try {
            ServiceLoader<AuthProvider> loader = ServiceLoader.load(AuthProvider.class);
            for (AuthProvider provider : loader) {
                logger.info("Loaded AuthProvider from SPI: {}", provider.getClass().getName());
                return provider;
            }
        } catch (Exception e) {
            logger.debug("No AuthProvider found via SPI: {}", e.getMessage());
        }

        // 2. 按配置类名实例化
        String className = System.getProperty("diatom.auth.provider",
                "com.github.obhen233.core.gateway.security.SharedTokenAuthProvider");
        try {
            Class<?> clazz = Class.forName(className);
            AuthProvider provider = (AuthProvider) clazz.getDeclaredConstructor().newInstance();
            logger.info("Instantiated AuthProvider: {}", className);
            return provider;
        } catch (Exception e) {
            logger.warn("Failed to instantiate AuthProvider {}: {}, using default",
                    className, e.getMessage());
            return new SharedTokenAuthProvider(
                    System.getProperty("diatom.auth.token", "default-diatom-token"));
        }
    }

    private static EncryptionProvider loadEncryptionProvider() {
        // 1. 尝试 ServiceLoader SPI
        try {
            ServiceLoader<EncryptionProvider> loader = ServiceLoader.load(EncryptionProvider.class);
            for (EncryptionProvider provider : loader) {
                logger.info("Loaded EncryptionProvider from SPI: {}", provider.getClass().getName());
                return provider;
            }
        } catch (Exception e) {
            logger.debug("No EncryptionProvider found via SPI: {}", e.getMessage());
        }

        // 2. 按配置类名实例化
        String className = System.getProperty("diatom.encryption.provider",
                "com.github.obhen233.core.gateway.security.NoopEncryptionProvider");
        try {
            Class<?> clazz = Class.forName(className);
            EncryptionProvider provider = (EncryptionProvider) clazz.getDeclaredConstructor().newInstance();
            logger.info("Instantiated EncryptionProvider: {}", className);
            return provider;
        } catch (Exception e) {
            logger.warn("Failed to instantiate EncryptionProvider {}: {}, using default",
                    className, e.getMessage());
            return new NoopEncryptionProvider();
        }
    }

    /**
     * 重置（用于测试或配置热更新）
     */
    public static void reset() {
        authProvider = null;
        encryptionProvider = null;
    }
}
