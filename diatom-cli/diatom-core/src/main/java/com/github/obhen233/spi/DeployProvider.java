package com.github.obhen233.spi;

/**
 * SPI for one-click deployment capability ("一键部署").
 *
 * Provides methods to detect and execute deployment pipelines for projects.
 * The {@link com.github.obhen233.starter.PipelineConfiguration} in the
 * springboot-starter registers a default implementation backed by
 * {@link com.github.obhen233.core.pipeline.PipelineService}.
 *
 * IDE projects can override the default by declaring their own
 * {@code DeployProvider} bean, or implement this interface and register
 * it via {@link com.github.obhen233.spi.SpiLoader}.
 *
 * Usage:
 * <pre>{@code
 * // IDE provides custom implementation
 * &#064;Bean
 * &#064;Primary
 * public DeployProvider myDeployProvider() {
 *     return new MyDeployProvider();
 * }
 * }</pre>
 */
public interface DeployProvider {

    /**
     * Check if the given project has a deploy configuration (e.g., deploy.yaml).
     *
     * @param projectName the project name
     * @return true if deploy configuration exists
     */
    boolean hasDeployConfig(String projectName);

    /**
     * Execute the deployment pipeline for the given project.
     * Results are reported through the provided {@link DeployCallback}.
     * This method should return quickly, typically by submitting the
     * actual work to a separate thread.
     *
     * @param projectName the project name
     * @param callback    callback for streaming output and completion events
     */
    void execute(String projectName, DeployCallback callback);

    /**
     * Execute the deployment pipeline for the given project with an optional profile.
     * The default implementation ignores the profile and delegates to
     * {@link #execute(String, DeployCallback)} for backward compatibility.
     * Implementations should override this to support multi-environment profiles.
     *
     * @param projectName the project name
     * @param callback    callback for streaming output and completion events
     * @param profile     the profile name (e.g., "dev", "uat"), or null for default
     */
    default void execute(String projectName, DeployCallback callback, String profile) {
        execute(projectName, callback);
    }

    /**
     * Get the list of available profile names for a project's deploy configuration.
     * Used by the IDE to render a profile selector dropdown next to the Deploy button.
     * Returns an empty list if no profiles are configured or the project has no deploy config.
     *
     * @param projectName the project name
     * @return list of profile names (e.g., ["default", "dev", "uat", "pro"]), never null
     */
    default java.util.List<String> getAvailableProfiles(String projectName) {
        return java.util.Collections.emptyList();
    }
}
