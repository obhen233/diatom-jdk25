package com.github.obhen233.spi;

import com.github.obhen233.config.AppConfig;

/**
 * Lifecycle hook for application initialization and shutdown.
 * Custom implementations can add pre/post init logic
 * and shutdown cleanup via SPI.
 */
public interface AppLifecycleHook {

    /**
     * Called before core initialization begins.
     * @param config the application configuration (partially loaded)
     */
    default void onBeforeInit(AppConfig config) {}

    /**
     * Called after core initialization completes.
     * All core services are available at this point.
     */
    default void onAfterInit() {}

    /**
     * Called when the application is shutting down.
     */
    default void onShutdown() {}
}
