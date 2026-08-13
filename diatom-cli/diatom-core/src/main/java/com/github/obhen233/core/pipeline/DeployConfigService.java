package com.github.obhen233.core.pipeline;

import com.github.obhen233.core.context.ProjectContext;

/**
 * Service for analyzing projects and generating/deleting deploy.yaml configurations.
 * <p>
 * Intended to be stateless — all methods receive paths as parameters so both CLI
 * and IDE callers can resolve project directories in their own way.
 * <p>
 * CLI resolves via {@code user.dir}; IDE resolves via
 * {@code Constants.workspacePath + "/" + projectName} from EditorContextService.
 */
public interface DeployConfigService {

    /**
     * Analyze a project directory and return its context (type, directory tree, build files).
     *
     * @param projectDir absolute path to the project directory
     * @return project context with type, directory tree, and build file content
     */
    ProjectContext analyzeProject(String projectDir);

    /**
     * Generate deploy.yaml content from structured parameters.
     * Uses template-based generation, no AI call.
     *
     * @param params deployment configuration parameters
     * @return YAML string content ready to write to file
     */
    String generateYaml(DeployConfigParams params);

    /**
     * Write deploy.yaml content to the project's .diatom/ directory.
     *
     * @param workspacePath workspace root path (parent of project directories)
     * @param projectName   project name (subdirectory under workspacePath)
     * @param yamlContent   the YAML content to write
     * @return true if written successfully
     */
    boolean writeYaml(String workspacePath, String projectName, String yamlContent);

    /**
     * Delete the deploy.yaml file from a project's .diatom/ directory.
     *
     * @param workspacePath workspace root path
     * @param projectName   project name
     * @return true if deleted or did not exist
     */
    boolean deleteYaml(String workspacePath, String projectName);
}
