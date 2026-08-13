package com.github.obhen233.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-level configuration path management for Diatom
 *
 * Configuration hierarchy (priority from low to high):
 *
 * Level 1 - Global User Config:     ~/.diatom/
 *   - User-wide skills and MCP configurations
 *   - Effective for ALL projects
 *
 * Level 2 - Self-Update Config:     {diatom-cli.jar}/.diatom/
 *   - Self-update related skills and MCP
 *   - Used during bootstrap and self-update process
 *   - Shared across all projects using this JAR
 *
 * Level 3 - Project Local Config:   {project}/.diatom/
 *   - Project-specific skills and MCP
 *   - Only effective for the current project
 *
 * Priority: Project Local > Self-Update > Global User
 * Higher level overrides lower level for same name.
 */
public class DiatomPaths {
    private static final Logger logger = LoggerFactory.getLogger(DiatomPaths.class);
    private static final String CONFIG_DIR_NAME = ".diatom";

    private final Path userHomeDir;
    private final Path jarLocationDir;
    private final Path projectDir;

    public DiatomPaths() {
        this(null);
    }

    public DiatomPaths(String projectDir) {
        this.userHomeDir = Paths.get(System.getProperty("user.home"), CONFIG_DIR_NAME);
        this.jarLocationDir = detectJarLocation();
        this.projectDir = projectDir != null ? Paths.get(projectDir, CONFIG_DIR_NAME) : null;
    }

    /**
     * Detect the JAR location directory (where diatom-cli.jar is located)
     */
    private Path detectJarLocation() {
        try {
            // Get the location of a class from diatom-core
            String classPath = System.getProperty("java.class.path");
            String[] paths = classPath.split(File.pathSeparator);

            for (String path : paths) {
                if (path.endsWith(".jar")) {
                    // Skip custom-current.jar - it's the bootstrap wrapper, not a diatom core jar
                    if (path.contains("custom-current")) {
                        continue;
                    }
                    File jarFile = new File(path);
                    return jarFile.getParentFile() != null ? jarFile.getParentFile().toPath() : null;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not detect JAR location: {}", e.getMessage());
        }

        // Fallback: use user home
        return userHomeDir;
    }

    /**
     * Get all config directories in priority order (lowest to highest)
     * Order: ~/.diatom -> {jar}/.diatom -> {project}/.diatom
     */
    public List<Path> getAllConfigDirs() {
        List<Path> dirs = new ArrayList<>();

        // Level 1: User home directory (lowest priority)
        dirs.add(userHomeDir);

        // Level 2: JAR location (medium priority)
        if (jarLocationDir != null && !jarLocationDir.equals(userHomeDir)) {
            dirs.add(jarLocationDir);
        }

        // Level 3: Project directory (highest priority)
        if (projectDir != null && Files.exists(projectDir)) {
            dirs.add(projectDir);
        }

        return dirs;
    }

    /**
     * Get global config directories (user home and jar location)
     */
    public List<Path> getGlobalConfigDirs() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(userHomeDir);
        if (jarLocationDir != null && !jarLocationDir.equals(userHomeDir)) {
            dirs.add(jarLocationDir);
        }
        return dirs;
    }

    /**
     * Get project config directory
     */
    public Path getProjectConfigDir() {
        return projectDir;
    }

    /**
     * Check if we're in a project context
     */
    public boolean hasProjectDir() {
        return projectDir != null && Files.exists(projectDir);
    }

    /**
     * Get the skills directory path for a given config root
     */
    public Path getSkillsDir(Path configRoot) {
        return configRoot.resolve("skills");
    }

    /**
     * Get the MCP servers directory path for a given config root
     */
    public Path getMcpserversDir(Path configRoot) {
        return configRoot.resolve("mcpservers");
    }

    // Getters
    public Path getUserHomeDir() { return userHomeDir; }
    public Path getJarLocationDir() { return jarLocationDir; }
    public Path getProjectDir() { return projectDir; }

    /**
     * Check if a path exists
     */
    private static boolean exists(Path path) {
        return path != null && Files.exists(path);
    }

    @Override
    public String toString() {
        return "DiatomPaths{" +
                "userHomeDir=" + userHomeDir +
                ", jarLocationDir=" + jarLocationDir +
                ", projectDir=" + projectDir +
                '}';
    }
}
