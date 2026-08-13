package com.github.obhen233.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralized path provider for diatom runtime installation paths.
 *
 * - Install home: {jarDir}/.diatom/ — self-update artifacts (custom JARs, libs, backups, versions)
 * - User home: ~/.diatom/ — global configuration (skills, MCP, application.properties)
 *
 * Bootstrap computes install home inline (zero-dependency launcher);
 * all runtime components use this utility class.
 */
public class InstallPaths {

    private static final String DIATOM_DIR = ".diatom";

    private InstallPaths() {
    }

    /**
     * Get the install home directory for self-update artifacts.
     * <p>
     * Path: {@code {jarDir}/.diatom/}
     * Uses the {@code diatom.jar.dir} system property, which is set by Bootstrap.
     */
    public static Path getInstallHome() {
        String jarDir = System.getProperty("diatom.jar.dir");
        if (jarDir != null && !jarDir.isEmpty()) {
            return Paths.get(jarDir, DIATOM_DIR);
        }
        // Fallback for testing and development (not running from Bootstrap)
        return Paths.get(System.getProperty("user.home"), DIATOM_DIR);
    }

    /**
     * Get the user home directory for global configuration.
     * <p>
     * Path: {@code ~/.diatom/}
     */
    public static Path getUserHome() {
        return Paths.get(System.getProperty("user.home"), DIATOM_DIR);
    }

    /**
     * Get the custom directory.
     * <p>
     * Path: {@code {installHome}/custom/}
     */
    public static Path getCustomDir() {
        return getInstallHome().resolve("custom");
    }

    /**
     * Get the lib directory (core dependencies).
     * <p>
     * Path: {@code {installHome}/custom/lib/}
     */
    public static Path getLibDir() {
        return getCustomDir().resolve("lib");
    }

    /**
     * Get the backup directory.
     * <p>
     * Path: {@code {installHome}/backup/}
     */
    public static Path getBackupDir() {
        return getInstallHome().resolve("backup");
    }

    /**
     * Get the core versions backup directory.
     * <p>
     * Path: {@code {installHome}/versions/}
     */
    public static Path getVersionsDir() {
        return getInstallHome().resolve("versions");
    }

    /**
     * Get the custom versions backup directory.
     * <p>
     * Path: {@code {installHome}/versions-custom/}
     */
    public static Path getCustomVersionsDir() {
        return getInstallHome().resolve("versions-custom");
    }

    /**
     * Get the logs directory for Bootstrap.
     * <p>
     * Path: {@code {installHome}/logs/}
     */
    public static Path getLogsDir() {
        return getInstallHome().resolve("logs");
    }

    /**
     * Get the gateway registry directory for worker registration files.
     * <p>
     * Path: {@code {installHome}/gateway/registry/}
     */
    public static Path getGatewayRegistryDir() {
        return getInstallHome().resolve("gateway").resolve("registry");
    }

    /**
     * Get the gateway CA directory for certificate authority files.
     * <p>
     * Path: {@code {installHome}/gateway/ca/}
     */
    public static Path getGatewayCaDir() {
        return getInstallHome().resolve("gateway").resolve("ca");
    }
}
