package com.github.obhen233.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized SPI loader for discovering custom implementations.
 *
 * <p>Two-phase loading with per-JAR isolation for plugins:</p>
 * <ol>
 *   <li><b>Classpath</b> via Java {@link ServiceLoader} (built-in implementations)</li>
 *   <li><b>Plugins</b> via {@link PluginClassLoader#loadAll(Class)} (external JARs,
 *       each with its own isolated classloader to avoid dependency conflicts)</li>
 * </ol>
 *
 * <p>Classpath implementations take priority over plugin implementations
 * for {@link #getFirst(Class, Object)} queries.</p>
 */
public class SpiLoader {

    private static final Logger logger = LoggerFactory.getLogger(SpiLoader.class);

    /** Cache: first loaded instance per SPI type (used by getFirst()) */
    private static final Map<Class<?>, Object> firstInstanceCache = new ConcurrentHashMap<>();
    /** All loaded instances across all SPI types (used by getAll()) */
    private static final List<Object> allExtensions = new ArrayList<>();
    /** Shared PluginClassLoader for plugin directory scanning */
    private static PluginClassLoader pluginClassLoader;

    private static boolean loaded = false;

    // ---- Loadable SPI types ----

    private static final List<Class<?>> SPI_TYPES = Arrays.asList(
            CacheFactory.class,
            AppLifecycleHook.class,
            ToolRegistrar.class,
            ConfigProvider.class,
            UiCustomizer.class,
            UpgradePolicy.class,
            DatabaseExtension.class,
            DatabaseDialectProvider.class,
            SystemConfigProvider.class,
            CoreCommandProvider.class,
            ToolSecurityProvider.class,
            SshPasswordCipher.class,
            PipelineRunnerRegistrar.class,
            ConcurrencyControlProvider.class,
            ResourceContentionProvider.class,
            RoutingFallbackHandler.class,
            LocalRequestRouter.class,
            ClusterCoordinator.class
    );

    /**
     * Load all SPI extensions. Called once at application startup.
     */
    public static synchronized void loadAll() {
        if (loaded) return;

        // Phase 1: Initialize PluginClassLoader (shared + per-JAR isolated)
        String jarDirPath = System.getProperty("diatom.jar.dir",
                System.getProperty("user.dir", "."));
        Path jarDir = java.nio.file.Paths.get(jarDirPath);
        List<Path> pluginDirs = PluginClassLoader.getDefaultPluginDirs(jarDir);
        pluginClassLoader = PluginClassLoader.init(pluginDirs.toArray(new Path[0]));
        if (pluginClassLoader != null && pluginClassLoader.hasPlugins()) {
            pluginClassLoader.registerJdbcDrivers();
            // TCCL already set to shared classloader by PluginClassLoader.init()
        }

        // Phase 2: Load SPI extensions — classpath first (parent-first), then plugins (per-JAR isolated)
        for (Class<?> type : SPI_TYPES) {
            loadClasspathExtensions(type);
            loadPluginExtensions(type);
        }

        loaded = true;
        int pluginCount = pluginClassLoader != null
                ? pluginClassLoader.getPluginUnits().size() : 0;
        logger.info("SPI extensions loaded: {} total from {} source(s) ({} plugin(s))",
                allExtensions.size(),
                pluginCount > 0 ? "classpath+plugins" : "classpath",
                pluginCount);
    }

    /**
     * Load implementations from classpath via ServiceLoader.
     * The first classpath instance is cached for {@link #getFirst(Class, Object)}.
     */
    @SuppressWarnings("unchecked")
    private static <T> void loadClasspathExtensions(Class<T> type) {
        ServiceLoader<T> loader = ServiceLoader.load(type);
        try {
            Iterator<T> iterator = loader.iterator();
            boolean isFirst = true;
            while (iterator.hasNext()) {
                try {
                    T instance = iterator.next();
                    allExtensions.add(instance);
                    if (isFirst) {
                        // Cache only the first classpath instance for getFirst()
                        firstInstanceCache.put(type, instance);
                        isFirst = false;
                    }
                    logger.debug("Loaded SPI (classpath): {} -> {}",
                            type.getSimpleName(), instance.getClass().getName());
                } catch (ServiceConfigurationError e) {
                    logger.warn("Failed to load SPI {}: {}", type.getSimpleName(), e.getMessage());
                }
            }
        } catch (ServiceConfigurationError e) {
            logger.warn("ServiceLoader error for {}: {}", type.getSimpleName(), e.getMessage());
        }
    }

    /**
     * Load implementations from plugins with per-JAR isolation.
     * These supplement classpath instances but do NOT overwrite the
     * first-instance cache (classpath takes priority for getFirst()).
     */
    private static <T> void loadPluginExtensions(Class<T> type) {
        if (pluginClassLoader == null || !pluginClassLoader.hasPlugins()) return;
        List<T> pluginImpls = pluginClassLoader.loadAll(type);
        for (T instance : pluginImpls) {
            allExtensions.add(instance);
            logger.debug("Loaded SPI (plugin): {} -> {}",
                    type.getSimpleName(), instance.getClass().getName());
        }
    }

    /**
     * Get the first loaded implementation of a given SPI type.
     * Returns the default implementation if no custom one is found.
     */
    @SuppressWarnings("unchecked")
    public static <T> T getFirst(Class<T> type, T defaultInstance) {
        T custom = (T) firstInstanceCache.get(type);
        return (custom != null) ? custom : defaultInstance;
    }

    /**
     * Get all loaded implementations of a given SPI type (classpath + plugins).
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> getAll(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Object ext : allExtensions) {
            if (type.isInstance(ext)) {
                result.add((T) ext);
            }
        }
        return result;
    }

    /**
     * Get the shared PluginClassLoader instance.
     */
    public static PluginClassLoader getPluginClassLoader() {
        return pluginClassLoader;
    }

    /**
     * Reload SPI extensions (useful for testing).
     */
    public static synchronized void reload() {
        firstInstanceCache.clear();
        allExtensions.clear();
        if (pluginClassLoader != null) {
            pluginClassLoader.deregisterJdbcDrivers();
        }
        pluginClassLoader = null;
        loaded = false;
        loadAll();
    }
}
