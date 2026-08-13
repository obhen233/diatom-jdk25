package com.github.obhen233.starter;

import com.github.obhen233.core.pipeline.PipelineCallback;
import com.github.obhen233.core.pipeline.*;
import com.github.obhen233.spi.DeployCallback;
import com.github.obhen233.spi.DeployProvider;
import com.github.obhen233.spi.PasswordProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Boot configuration for the pipeline execution engine.
 *
 * Registers all core PipelineRunner implementations (Local, SSH, Docker, Maven,
 * Gradle, K8s, Jenkins, SVN) into a RunnerRegistry, and creates the PipelineService
 * bean for deploy.yaml orchestration.
 *
 * Also registers a default {@link DeployProvider} SPI bean backed by PipelineService,
 * which can be overridden by IDE projects.
 *
 * IDE projects can contribute additional runners (e.g., GitRunner) by declaring
 * {@link PipelineRunner} beans, which are automatically collected and registered.
 * The workspace path is resolved via {@link WorkspacePathProvider} if available,
 * otherwise falls back to {@code user.dir}/workspace.
 */
@Configuration
public class PipelineConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(PipelineConfiguration.class);

    @Autowired(required = false)
    private WorkspacePathProvider workspacePathProvider;

    private String getWorkspacePath() {
        if (workspacePathProvider != null) {
            String path = workspacePathProvider.getWorkspacePath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
        }
        return System.getProperty("user.dir") + "/workspace";
    }

    /**
     * Create a RunnerRegistry with all pipeline runners.
     * Built-in runners are discovered via {@link com.github.obhen233.spi.PipelineRunnerRegistrar} SPI.
     * Additional runners registered as Spring beans of type {@link PipelineRunner}
     * (e.g., GitRunner from IDE projects) are automatically collected and added,
     * and take precedence over SPI-discovered runners.
     */
    @Bean
    @ConditionalOnMissingBean
    public RunnerRegistry runnerRegistry(
            @Autowired(required = false) List<PipelineRunner> additionalRunners) {

        RunnerRegistry registry = new RunnerRegistry();

        // Discover built-in + custom runners via SPI
        registry.discoverFromSpi();

        // Additional runners from Spring beans (IDE projects, etc.)
        // Registered after SPI so they can override default runners.
        if (additionalRunners != null) {
            for (PipelineRunner runner : additionalRunners) {
                registry.register(runner);
                logger.info("Registered additional pipeline runner: {} -> {}",
                        runner.getActionType(), runner.getClass().getSimpleName());
            }
        }

        logger.info("RunnerRegistry initialized with {} runners", registry.getRunners().size());
        return registry;
    }

    /**
     * Create the PipelineService bean using the configured workspace path.
     * Can be overridden by IDE projects via {@link ConditionalOnMissingBean}.
     */
    @Bean
    @ConditionalOnMissingBean
    public PipelineService pipelineService(RunnerRegistry runnerRegistry,
                                           @Autowired(required = false) PasswordProvider passwordProvider) {
        PipelineService service = new PipelineService(runnerRegistry, getWorkspacePath());
        if (passwordProvider != null) {
            service.setPasswordProvider(passwordProvider);
        }
        return service;
    }

    /**
     * Register a default {@link DeployProvider} SPI bean backed by PipelineService.
     * IDE projects can override by declaring their own {@code DeployProvider} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public DeployProvider deployProvider(PipelineService pipelineService) {
        return new DeployProvider() {
            @Override
            public boolean hasDeployConfig(String projectName) {
                return pipelineService.hasDeployConfig(projectName);
            }

            @Override
            public void execute(String projectName, DeployCallback callback) {
                execute(projectName, callback, null);
            }

            @Override
            public void execute(String projectName, DeployCallback callback, String profile) {
                pipelineService.execute(projectName, new PipelineCallback() {
                    @Override
                    public void onOutput(String text) {
                        callback.onOutput(text);
                    }

                    @Override
                    public void onStepComplete(String stepName, boolean success) {
                        callback.onStepComplete(stepName, success);
                    }

                    @Override
                    public void onPipelineComplete(boolean success) {
                        callback.onPipelineComplete(success);
                    }

                    @Override
                    public void onError(String message) {
                        callback.onError(message);
                    }

                    @Override
                    public void onProgress(String stepName, long current, long total, long speedBps) {
                        callback.onProgress(stepName, current, total, speedBps);
                    }
                }, profile);
            }

            @Override
            public java.util.List<String> getAvailableProfiles(String projectName) {
                try {
                    return pipelineService.getAvailableProfiles(projectName);
                } catch (Exception e) {
                    return java.util.Collections.emptyList();
                }
            }
        };
    }

    /**
     * Create a default {@link DeployConfigService} bean for generating and managing
     * deploy.yaml configurations. IDE projects can override by declaring their own
     * {@code DeployConfigService} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public DeployConfigService deployConfigService() {
        return new DefaultDeployConfigService();
    }
}
