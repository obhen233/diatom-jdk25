package com.github.obhen233.spi;

import java.nio.file.Path;

/**
 * SPI interface for managing ML model files used by local classifiers.
 * <p>
 * Provides versioned model downloads, local caching, and update checks.
 * Implementations may fetch models from remote storage (e.g., OSS, S3, NAS)
 * or from a local filesystem.
 * <p>
 * Registration: add the fully qualified class name to
 * {@code META-INF/services/com.github.obhen233.spi.ModelRepository}.
 */
public interface ModelRepository {

    /**
     * Retrieve the local filesystem path to a model file.
     * <p>
     * If the model is not cached locally, the implementation should
     * download it before returning.
     *
     * @param modelName the model identifier (e.g., {@code "text-classifier-onnx"})
     * @param version   the semantic version string (e.g., {@code "1.2.0"})
     * @return local path to the model file, or {@code null} if unavailable
     */
    Path getModel(String modelName, String version);

    /**
     * Check whether a newer version of the model is available.
     *
     * @param modelName      the model identifier
     * @param currentVersion the currently deployed version
     * @return {@code true} if a newer version exists
     */
    boolean hasUpdate(String modelName, String currentVersion);
}
