package com.github.obhen233.core.tool;

import com.github.obhen233.util.InstallPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages authorized paths that the AI can access without confirmation.
 */
public class AuthorizedPathManager {
    private static final Logger logger = LoggerFactory.getLogger(AuthorizedPathManager.class);

    private static final String AUTHORIZED_PATHS_FILE = "authorized-paths.txt";

    private final Set<String> authorizedPaths = new HashSet<>();
    private final Path configFile;
    private long lastFileLoadTime = 0;

    public AuthorizedPathManager() {
        Path configDir = InstallPaths.getInstallHome();
        this.configFile = configDir.resolve(AUTHORIZED_PATHS_FILE);
        load();
    }

    /**
     * Check if a path is authorized (either explicitly or as a subpath of authorized directory)
     */
    public boolean isAuthorized(String path) {
        if (path == null) return false;

        // Re-load from file if file was modified (handles case where another instance updated the file)
        try {
            if (Files.exists(configFile)) {
                long currentFileTime = Files.getLastModifiedTime(configFile).toMillis();
                if (currentFileTime > lastFileLoadTime) {
                    logger.debug("File modified since last load, reloading authorized paths");
                    load();
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to check file modification time", e);
        }

        // Normalize the path
        Path requestedPath = Paths.get(path).normalize().toAbsolutePath();

        // Check exact match
        if (authorizedPaths.contains(requestedPath.toString())) {
            return true;
        }

        // Check if it's a subpath of any authorized directory
        for (String authorized : authorizedPaths) {
            Path authPath = Paths.get(authorized);
            if (requestedPath.startsWith(authPath)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Grant authorization for a path (directory or file)
     */
    public void authorize(String path) {
        if (path == null || path.isEmpty()) return;

        // Validate path format - must be a valid file path, not a command string
        if (!isValidFilePath(path)) {
            logger.warn("Invalid path format, skipping authorization: {}", path);
            return;
        }

        try {
            Path normalizedPath = Paths.get(path).normalize().toAbsolutePath();
            String pathStr = normalizedPath.toString();

            if (!authorizedPaths.contains(pathStr)) {
                authorizedPaths.add(pathStr);
                save();
                logger.info("Authorized path: {}", pathStr);
            }
        } catch (Exception e) {
            logger.warn("Failed to authorize path: {}", path, e);
        }
    }

    private boolean isValidFilePath(String path) {
        if (path == null || path.isEmpty()) return false;
        // Check for obvious command patterns that are not file paths
        if (path.contains("|") || path.contains("&&") || path.contains(";")) {
            return false;  // Command operators
        }
        if (path.contains("$(") || path.contains("`")) {
            return false;  // Command substitution
        }
        if (path.startsWith("grep ") || path.startsWith("cat ") || path.startsWith("ls ")
            || path.startsWith("find ") || path.startsWith("echo ")) {
            return false;  // Command names at start
        }
        // Check for quotes which indicate it's a command, not a path
        if (path.contains("\"") || path.contains("'")) {
            return false;
        }
        return true;
    }

    /**
     * Revoke authorization for a path
     */
    public void revoke(String path) {
        if (path == null) return;

        Path normalizedPath = Paths.get(path).normalize().toAbsolutePath();
        authorizedPaths.remove(normalizedPath.toString());
        save();
        logger.info("Revoked path: {}", path);
    }

    /**
     * List all authorized paths
     */
    public Set<String> getAuthorizedPaths() {
        return new HashSet<>(authorizedPaths);
    }

    /**
     * Clear all authorized paths
     */
    public void clearAll() {
        authorizedPaths.clear();
        save();
        logger.info("Cleared all authorized paths");
    }

    private void load() {
        authorizedPaths.clear();

        if (!Files.exists(configFile)) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(configFile.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    Path p = Paths.get(line).normalize().toAbsolutePath();
                    authorizedPaths.add(p.toString());
                }
            }
            lastFileLoadTime = Files.getLastModifiedTime(configFile).toMillis();
            logger.info("Loaded {} authorized paths", authorizedPaths.size());
        } catch (IOException e) {
            logger.warn("Failed to load authorized paths: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(configFile.getParent());
            try (PrintWriter writer = new PrintWriter(new FileWriter(configFile.toFile()))) {
                writer.println("# Authorized paths for Diatom CLI");
                writer.println("# Each line contains an absolute path");
                for (String path : authorizedPaths) {
                    writer.println(path);
                }
            }
            lastFileLoadTime = Files.getLastModifiedTime(configFile).toMillis();
            logger.debug("Saved {} authorized paths", authorizedPaths.size());
        } catch (IOException e) {
            logger.warn("Failed to save authorized paths: {}", e.getMessage());
        }
    }
}