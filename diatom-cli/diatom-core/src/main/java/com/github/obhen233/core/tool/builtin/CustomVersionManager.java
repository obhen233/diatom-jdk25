package com.github.obhen233.core.tool.builtin;

import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.util.InstallPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Custom version management tool.
 * Supports: list versions, rollback, backup current custom JAR.
 *
 * In the architecture:
 * - Core is a read-only Maven dependency (upgraded via Maven Central)
 * - Custom is the editable module with local version management
 */
public class CustomVersionManager {
    private static final Logger logger = LoggerFactory.getLogger(CustomVersionManager.class);
    private static final String versionsDir = InstallPaths.getCustomVersionsDir().toString();
    private static final String currentJar = InstallPaths.getCustomDir().resolve("custom-current.jar").toString();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String NEWLINE = System.lineSeparator();

    public CustomVersionManager() {
        try {
            Files.createDirectories(Paths.get(versionsDir));
        } catch (IOException e) {
            logger.error("Failed to create versions directory", e);
        }
    }

    @ToolMethod(name = "list_custom_versions",
                description = "List all available custom versions (backups)",
                parametersSchema = "{}",
                readOnly = true)
    public String listVersions() {
        try {
            Path versionsPath = Paths.get(versionsDir);
            if (!Files.exists(versionsPath)) {
                return "No backup versions found.";
            }

            List<String> versions = Files.list(versionsPath)
                .filter(p -> p.toString().endsWith(".jar"))
                .map(p -> {
                    String name = p.getFileName().toString();
                    long size = 0;
                    String date = "";
                    try {
                        size = Files.size(p);
                        // Try to extract date from filename
                        String dateStr = name.replace("custom-", "").replace(".jar", "");
                        date = " (backup: " + dateStr + ")";
                    } catch (IOException ignored) {}
                    return String.format("  - %s (%.2f MB)%s", name, size / 1024.0 / 1024.0, date);
                })
                .collect(Collectors.toList());

            if (versions.isEmpty()) {
                return "No backup versions found.";
            }

            // Add current version info
            Path current = Paths.get(currentJar);
            String currentInfo = "";
            if (Files.exists(current)) {
                long size = Files.size(current);
                currentInfo = String.format("\nCurrent: custom-current.jar (%.2f MB)", size / 1024.0 / 1024.0);
            }

            return "Available versions:\n" + String.join("\n", versions) + currentInfo;
        } catch (IOException e) {
            logger.error("Error listing versions", e);
            return "Error listing versions: " + e.getMessage();
        }
    }

    @ToolMethod(name = "backup_custom",
                description = "Backup the current custom JAR with timestamp",
                parametersSchema = "{}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String backup() {
        try {
            Path current = Paths.get(currentJar);
            if (!Files.exists(current)) {
                return "Error: No current custom JAR found";
            }

            String timestamp = LocalDateTime.now().format(formatter);
            String backupName = "custom-" + timestamp + ".jar";
            Path backupPath = Paths.get(versionsDir, backupName);

            Files.copy(current, backupPath, StandardCopyOption.REPLACE_EXISTING);

            long size = Files.size(backupPath);
            return "Backup created: " + backupName + " (" + size / 1024 + " KB)";
        } catch (IOException e) {
            logger.error("Failed to backup custom", e);
            return "Failed to backup: " + e.getMessage();
        }
    }

    @ToolMethod(name = "rollback_custom",
                description = "Rollback to a specific backup version",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"version\": {\"type\": \"string\", \"description\": \"Backup filename, e.g. custom-20240515_120000.jar (use list_custom_versions to see available)\"}}}",
                requiresConfirmation = true,
                riskLevel = "high")
    public String rollback(String version) {
        if (version == null || version.trim().isEmpty()) {
            return "Error: version required. Use list_custom_versions to see available backups.";
        }

        // Prevent path traversal
        if (version.contains("/") || version.contains("\\") || version.contains("..")) {
            return "Error: Invalid version name";
        }

        version = version.trim();
        if (!version.startsWith("custom-")) {
            version = "custom-" + version;
        }
        if (!version.endsWith(".jar")) {
            version = version + ".jar";
        }

        Path backupPath = Paths.get(versionsDir, version);
        if (!Files.exists(backupPath)) {
            return "Backup not found: " + version + "\nUse list_custom_versions to see available backups.";
        }

        try {
            // Validate backup JAR integrity
            if (!isValidJar(backupPath)) {
                return "Error: Backup JAR is corrupted or invalid: " + version +
                       "\nPlease choose a different backup version.";
            }

            // First backup current
            backup();

            // Then rollback (atomic operation)
            Path current = Paths.get(currentJar);
            Path tempJar = current.resolveSibling("custom-current.jar.tmp");
            Files.copy(backupPath, tempJar, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.deleteIfExists(current);
            } catch (Exception e) {
                logger.warn("Could not delete old JAR during rollback: {}", e.getMessage());
            }
            try {
                Files.move(tempJar, current, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tempJar, current, StandardCopyOption.REPLACE_EXISTING);
            }

            return "Rollback complete: " + version + "\nRestart to apply changes.";
        } catch (IOException e) {
            logger.error("Failed to rollback", e);
            return "Failed to rollback: " + e.getMessage();
        }
    }

    /**
     * Validate JAR file (check Manifest)
     */
    private boolean isValidJar(Path jarPath) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            jar.getManifest();
            return true;
        } catch (Exception e) {
            logger.warn("Backup JAR validation failed: {}", e.getMessage());
            return false;
        }
    }

    @ToolMethod(name = "get_custom_path",
                description = "Get the path to the current custom JAR",
                parametersSchema = "{}",
                readOnly = true)
    public String getCustomPath() {
        return currentJar;
    }

    @ToolMethod(name = "get_custom_version_info",
                description = "Get current custom version and backup info",
                parametersSchema = "{}",
                readOnly = true)
    public String getVersionInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== Custom Version Info ===").append(NEWLINE);

        Path current = Paths.get(currentJar);
        try {
            if (Files.exists(current)) {
                long size = Files.size(current);
                info.append("Current: custom-current.jar (").append(String.format("%.2f MB", size / 1024.0 / 1024.0)).append(")").append(NEWLINE);
            } else {
                info.append("Current: NOT FOUND").append(NEWLINE);
            }
        } catch (IOException ignored) {
            info.append("Current: ERROR reading size").append(NEWLINE);
        }

        Path versionFile = InstallPaths.getCustomDir().resolve("version.properties");
        if (Files.exists(versionFile)) {
            try {
                Properties props = new Properties();
                props.load(Files.newInputStream(versionFile));
                info.append("Version: ").append(props.getProperty("custom.version", "unknown")).append(NEWLINE);
            } catch (IOException ignored) {}
        }

        // Count backups
        try {
            long backupCount = Files.list(Paths.get(versionsDir))
                .filter(p -> p.toString().endsWith(".jar"))
                .count();
            info.append("Backups: ").append(backupCount).append(NEWLINE);
        } catch (IOException ignored) {}

        return info.toString();
    }
}
