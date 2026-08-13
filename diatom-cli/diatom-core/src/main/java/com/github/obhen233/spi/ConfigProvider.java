package com.github.obhen233.spi;

import java.util.Properties;

/**
 * Custom configuration provider.
 * Allows custom modules to override or extend configuration properties
 * after the default AppConfig has loaded.
 */
public interface ConfigProvider {

    /**
     * Provide additional or overriding configuration properties.
     * These are applied after the standard config loading chain.
     * @return properties to merge into the application config
     */
    default Properties getAdditionalProperties() {
        return new Properties();
    }

    /**
     * Called after all configuration is loaded and synced.
     * @param config the fully loaded application configuration
     */
    default void onConfigLoaded(Object config) {}
}
