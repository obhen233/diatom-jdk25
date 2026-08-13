package com.github.obhen233.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves a project path into workspace (parent directory) and project name.
 * <p>
 * CLI scheme: workspace is the parent directory of the project root.
 * Examples:
 * <ul>
 *   <li>{@code E:/project/a} -> workspace {@code E:/project}, project {@code a}</li>
 *   <li>{@code D:/b} -> workspace {@code D:/}, project {@code b}</li>
 * </ul>
 */
public class WorkspaceProjectResolver {

    /**
     * Resolve the given project path.
     *
     * @param projectPath the project directory path
     * @return a two-element array: [workspace, projectName]
     */
    public static String[] resolve(String projectPath) {
        Path path = Paths.get(projectPath).toAbsolutePath().normalize();
        Path parent = path.getParent();
        String workspace = parent != null ? parent.toString() : path.toString();
        String projectName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        return new String[]{workspace, projectName};
    }
}
