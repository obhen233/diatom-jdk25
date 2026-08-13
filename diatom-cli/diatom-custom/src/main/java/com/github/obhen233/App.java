package com.github.obhen233;

import com.github.obhen233.bootstrap.CoreLauncher;
import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.CoreUpgrader;
import com.github.obhen233.spi.AppLifecycleHook;
import com.github.obhen233.spi.ConfigProvider;
import com.github.obhen233.spi.SpiLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom application entry point.
 *
 * Wraps the core initialization with:
 * 1. SPI extension loading (custom implementations)
 * 2. Core version upgrade check and execution
 * 3. Lifecycle hook notifications
 *
 * Uses CoreLauncher to unambiguously delegate to the core App.main(),
 * avoiding class name ambiguity that could cause infinite recursion.
 */
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            // 1. Load config to check upgrade settings
            AppConfig config = new AppConfig();

            // 2. Load SPI extensions (ConfigProvider, UpgradePolicy, etc.)
            SpiLoader.loadAll();

            // 3. Apply ConfigProvider extensions
            for (ConfigProvider cp : SpiLoader.getAll(ConfigProvider.class)) {
                try {
                    cp.onConfigLoaded(config);
                } catch (Exception e) {
                    logger.warn("ConfigProvider {} failed: {}", cp.getClass().getName(), e.getMessage());
                }
            }

            // 4. Notify lifecycle hooks: before init
            for (AppLifecycleHook hook : SpiLoader.getAll(AppLifecycleHook.class)) {
                try {
                    hook.onBeforeInit(config);
                } catch (Exception e) {
                    logger.warn("Lifecycle hook onBeforeInit failed: {}", e.getMessage());
                }
            }

            // 5. Check and perform core upgrade if enabled
            if (isCoreUpgradeEnabled(config)) {
                CoreUpgrader upgrader = new CoreUpgrader(config);
                boolean upgraded = upgrader.checkAndUpgrade();
                if (upgraded) {
                    // Upgrade will restart the application, so exit current process
                    return;
                }
            }

            // 6. Delegate to core's main initialization
            // Use CoreLauncher to unambiguously invoke the core App.main()
            // (avoids class name ambiguity that could cause recursion)
            CoreLauncher.launch(args);

        } catch (Exception e) {
            logger.error("Fatal error during application startup", e);
            System.err.println("Fatal error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Check if core upgrade is enabled in configuration.
     */
    private static boolean isCoreUpgradeEnabled(AppConfig config) {
        // Default: enabled
        String enabled = config.getProperty("core.upgrade.enabled", "true");
        return "true".equalsIgnoreCase(enabled);
    }
}
