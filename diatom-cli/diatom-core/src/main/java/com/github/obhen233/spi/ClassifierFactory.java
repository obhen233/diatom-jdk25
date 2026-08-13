package com.github.obhen233.spi;

import java.util.Map;

/**
 * SPI interface for creating classifier-based {@link LocalRequestRouter}
 * instances from configuration.
 * <p>
 * Enables dynamic swapping of classification implementations (e.g.,
 * keyword-only → ONNX → hybrid) via runtime configuration changes,
 * without restarting the Gateway.
 * <p>
 * Registration: add the fully qualified class name to
 * {@code META-INF/services/com.github.obhen233.spi.ClassifierFactory}.
 */
public interface ClassifierFactory {

    /**
     * Create a {@link LocalRequestRouter} from the given configuration.
     *
     * @param config a map of configuration properties (implementation-defined)
     * @return a configured LocalRequestRouter instance, or {@code null} if
     *         the configuration is invalid or not supported
     */
    LocalRequestRouter create(Map<String, Object> config);
}
