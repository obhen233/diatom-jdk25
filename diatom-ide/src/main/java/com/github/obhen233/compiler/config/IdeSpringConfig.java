package com.github.obhen233.compiler.config;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.pipeline.GitRunner;
import com.github.obhen233.core.pipeline.PipelineRunner;
import com.github.obhen233.starter.WorkspacePathProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for IDE-specific SPI implementations.
 * Provides WorkspacePathProvider to expose the Spring-configured workspace.path
 * to the springboot-starter, so that MCP server and other components use
 * the correct workspace directory (e.g., D:/temp) instead of falling back
 * to AppConfig's default.
 *
 * Also registers IDE-specific PipelineRunner beans (e.g., GitRunner) which
 * are automatically collected by the starter's PipelineConfiguration.
 */
@Configuration
public class IdeSpringConfig {

    @Bean
    public WorkspacePathProvider workspacePathProvider() {
        return () -> Constants.workspacePath;
    }

    @Bean
    public PipelineRunner gitRunner() {
        return new GitRunner();
    }
}
