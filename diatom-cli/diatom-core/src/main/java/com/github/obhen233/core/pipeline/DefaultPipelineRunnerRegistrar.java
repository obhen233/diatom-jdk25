package com.github.obhen233.core.pipeline;

import com.github.obhen233.spi.PipelineRunnerRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default SPI implementation that registers all built-in pipeline runners.
 *
 * Registered action types:
 * <ul>
 *   <li>{@code run_command} — {@link LocalRunner}</li>
 *   <li>{@code ssh_command} — {@link SshRunner}</li>
 *   <li>{@code scp} — {@link ScpRunner}</li>
 *   <li>{@code docker} — {@link DockerRunner}</li>
 *   <li>{@code maven} — {@link MavenRunner}</li>
 *   <li>{@code gradle} — {@link GradleRunner}</li>
 *   <li>{@code k8s} — {@link K8sRunner}</li>
 *   <li>{@code jenkins} — {@link JenkinsRunner}</li>
 *   <li>{@code svn} — {@link SvnRunner}</li>
 * </ul>
 *
 * <p>Registered via SPI in {@code META-INF/services/com.github.obhen233.spi.PipelineRunnerRegistrar}.
 * Custom registrars can override any action type by registering a different runner
 * for the same action type string.</p>
 */
public class DefaultPipelineRunnerRegistrar implements PipelineRunnerRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPipelineRunnerRegistrar.class);

    @Override
    public void registerRunners(RunnerRegistry registry) {
        logger.info("Registering built-in pipeline runners");

        registry.register(new LocalRunner());
        registry.register(new SshRunner());
        registry.register(new ScpRunner());
        registry.register(new DockerRunner());
        registry.register(new MavenRunner());
        registry.register(new GradleRunner());
        registry.register(new K8sRunner());
        registry.register(new JenkinsRunner());
        registry.register(new SvnRunner());

        logger.info("Built-in pipeline runners registered");
    }
}
