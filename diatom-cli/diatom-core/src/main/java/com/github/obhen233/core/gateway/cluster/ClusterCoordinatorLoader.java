package com.github.obhen233.core.gateway.cluster;

import com.github.obhen233.spi.ClusterCoordinator;
import com.github.obhen233.spi.PluginClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Factory for loading the {@link ClusterCoordinator} with auto-detection.
 *
 * <p>Discovery order:</p>
 * <ol>
 *   <li><b>Plugin JARs</b> — scan {@code PluginClassLoader} for custom SPI implementations</li>
 *   <li><b>Classpath ServiceLoader</b> — check {@code META-INF/services/} for registered implementations</li>
 *   <li><b>Default</b> — create {@link HazelcastClusterCoordinator}</li>
 * </ol>
 *
 * <p>This allows users to provide custom coordination backends (e.g., PostgreSQL-based)
 * by simply placing a JAR in the {@code plugins/} directory with the appropriate
 * {@code META-INF/services/com.github.obhen233.spi.ClusterCoordinator} declaration.</p>
 */
public class ClusterCoordinatorLoader {
    private static final Logger logger = LoggerFactory.getLogger(ClusterCoordinatorLoader.class);

    private ClusterCoordinatorLoader() {}

    /**
     * Load the ClusterCoordinator using auto-detection.
     *
     * @param config configuration properties passed to the coordinator's init()
     * @return a configured and initialized ClusterCoordinator, or null if clustering is disabled
     */
    public static ClusterCoordinator load(Map<String, String> config) {
        // Check if clustering is explicitly disabled
        String clusterEnabled = config != null ? config.get("cluster.enabled") : null;
        if ("false".equalsIgnoreCase(clusterEnabled)) {
            logger.info("Cluster coordination disabled via cluster.enabled=false");
            return null;
        }

        // 1. Try plugin JARs (per-JAR isolation)
        PluginClassLoader pcl = PluginClassLoader.getInstance();
        if (pcl != null && pcl.hasPlugins()) {
            try {
                ClusterCoordinator pluginImpl = pcl.loadFirst(ClusterCoordinator.class);
                if (pluginImpl != null) {
                    logger.info("Using ClusterCoordinator from plugin: {} ({})",
                            pluginImpl.getName(), pluginImpl.getClass().getName());
                    pluginImpl.init(config);
                    return pluginImpl;
                }
            } catch (Exception e) {
                logger.warn("Failed to load ClusterCoordinator from plugin: {}", e.getMessage());
            }
        }

        // 2. Try classpath ServiceLoader
        try {
            java.util.ServiceLoader<ClusterCoordinator> sl =
                    java.util.ServiceLoader.load(ClusterCoordinator.class);
            for (ClusterCoordinator coord : sl) {
                logger.info("Using ClusterCoordinator from classpath: {} ({})",
                        coord.getName(), coord.getClass().getName());
                coord.init(config);
                return coord;
            }
        } catch (Exception e) {
            logger.warn("Failed to load ClusterCoordinator from classpath: {}", e.getMessage());
        }

        // 3. Default: Hazelcast (optional — catch errors gracefully)
        try {
            logger.info("No custom ClusterCoordinator found, using default: Hazelcast");
            HazelcastClusterCoordinator hz = new HazelcastClusterCoordinator();
            hz.init(config);
            return hz;
        } catch (Exception e) {
            logger.warn("Failed to initialize Hazelcast ClusterCoordinator, clustering disabled: {}", e.getMessage());
            logger.debug("Hazelcast initialization failure details", e);
            return null;
        }
    }
}
