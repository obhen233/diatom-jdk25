package com.github.obhen233.spi;

import com.github.obhen233.core.pipeline.RunnerRegistry;

/**
 * SPI interface for registering custom PipelineRunner implementations.
 *
 * Implementations are discovered via Java ServiceLoader. Each implementation
 * receives the {@link RunnerRegistry} and can register runners for any
 * action type. Built-in runners (ssh_command, scp, maven, etc.) are
 * registered by {@code DefaultPipelineRunnerRegistrar}.
 *
 * To add or override a pipeline action:
 * <pre>{@code
 * public class MyRegistrar implements PipelineRunnerRegistrar {
 *     void registerRunners(RunnerRegistry registry) {
 *         registry.register(new MyCustomSshRunner());   // overrides ssh_command
 *         registry.register(new NewActionRunner());      // adds new action type
 *     }
 * }
 * }</pre>
 *
 * SPI registration — place a file at:
 * {@code META-INF/services/com.github.obhen233.spi.PipelineRunnerRegistrar}
 * containing the fully qualified class name of your implementation.
 */
public interface PipelineRunnerRegistrar {

    /**
     * Register pipeline runners into the given registry.
     * Called during application startup.
     */
    void registerRunners(RunnerRegistry registry);
}
