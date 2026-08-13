package com.github.obhen233.core.gateway.config;

import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway 配置注册器
 * 将 gateway.* 配置项注册到系统配置
 */
public class GatewayConfigRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(GatewayConfigRegistrar.class);

    public static final String CONFIG_KEY_GATEWAY_URL = "gateway.url";
    public static final String CONFIG_KEY_GATEWAY_HOST = "gateway.host";
    public static final String CONFIG_KEY_GATEWAY_PORT = "gateway.port";

    /**
     * 注册 Gateway 配置项到 ConfigManager
     */
    public static void registerConfig(ConfigManager configManager) {
        if (configManager == null) return;

        // gateway.url 已存在则跳过
        String existingUrl = configManager.get(CONFIG_KEY_GATEWAY_URL);
        if (existingUrl == null || existingUrl.isEmpty()) {
            configManager.set(CONFIG_KEY_GATEWAY_URL,
                    System.getProperty("gateway.url", "http://127.0.0.1:8080"));
        }

        // gateway.host
        String existingHost = configManager.get(CONFIG_KEY_GATEWAY_HOST);
        if (existingHost == null || existingHost.isEmpty()) {
            configManager.set(CONFIG_KEY_GATEWAY_HOST, "127.0.0.1");
        }

        // gateway.port
        String existingPort = configManager.get(CONFIG_KEY_GATEWAY_PORT);
        if (existingPort == null || existingPort.isEmpty()) {
            configManager.set(CONFIG_KEY_GATEWAY_PORT, "8080");
        }

        logger.info("Gateway config registered: {}={}, {}={}, {}={}",
                CONFIG_KEY_GATEWAY_URL, configManager.get(CONFIG_KEY_GATEWAY_URL),
                CONFIG_KEY_GATEWAY_HOST, configManager.get(CONFIG_KEY_GATEWAY_HOST),
                CONFIG_KEY_GATEWAY_PORT, configManager.get(CONFIG_KEY_GATEWAY_PORT));
    }

    /**
     * 从 ConfigManager 读取 gateway.url
     */
    public static String getGatewayUrl(ConfigManager configManager) {
        if (configManager == null) return "http://127.0.0.1:8080";
        String url = configManager.get(CONFIG_KEY_GATEWAY_URL);
        return url != null && !url.isEmpty() ? url : "http://127.0.0.1:8080";
    }

    /**
     * 更新 gateway.url（通过 config set 时调用）
     */
    public static boolean setGatewayUrl(ConfigManager configManager, String url) {
        if (configManager == null) return false;
        configManager.set(CONFIG_KEY_GATEWAY_URL, url);
        System.setProperty("gateway.url", url);
        logger.info("Gateway URL updated to: {}", url);
        return true;
    }
}
