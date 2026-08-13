package com.github.obhen233.starter;

/**
 * SPI interface for providing the workspace path.
 * Implement this interface in IDE projects to provide the actual workspace path
 * from Spring configuration, instead of relying on AppConfig.
 *
 * If no implementation is registered, AppConfig.getWorkspaceDir() is used as fallback.
 */
public interface WorkspacePathProvider {
    /**
     * Get the workspace path (e.g., D:/temp).
     * This is the root directory where AI can operate on projects.
     *
     * @return the workspace path
     */
    String getWorkspacePath();
}
