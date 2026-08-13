package com.github.obhen233.adapter.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Generic binary/executable resolver with priority chain:
 * <ol>
 *   <li>System property {@code diatom.binary.{name}.path}</li>
 *   <li>Environment variable {@code {NAME}_BINARY} or {@code {NAME}_HOME}/{@code {NAME}_bin}</li>
 *   <li>{@code which}/{@code where} command</li>
 *   <li>PATH environment variable scan</li>
 *   <li>Direct execution test ({@code {name} --version})</li>
 *   <li>Default install locations scan</li>
 *   <li>Throw {@code IllegalStateException} with clear message</li>
 * </ol>
 *
 * <p>Usage in AgentAdapter implementations:</p>
 * <pre>{@code
 * String binaryPath = BinaryResolver.resolve("claude", config);
 * }</pre>
 */
public class BinaryResolver {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("win");

    // Windows default search roots
    private static final List<String> WINDOWS_SEARCH_ROOTS = Arrays.asList(
            "C:\\Program Files",
            "C:\\Program Files (x86)",
            "C:\\Users",
            "C:\\tools"
    );

    // Unix default search roots
    private static final List<String> UNIX_SEARCH_ROOTS = Arrays.asList(
            "/usr/local",
            "/usr",
            "/opt",
            "/home"
    );

    private static final int SEARCH_DEPTH = 4;

    /**
     * Resolve the absolute path to a binary executable using the full priority chain.
     *
     * @param binaryName the binary name (e.g. "claude", "cursor")
     * @param config     adapter config map (may be null); checked for {@code diatom.binary.{name}.path}
     * @return resolved absolute path to the executable
     * @throws IllegalStateException if the binary cannot be found anywhere
     */
    public static String resolve(String binaryName, Map<String, String> config) {
        // Priority 1: System property diatom.binary.{name}.path
        String sysPropKey = "diatom.binary." + binaryName + ".path";
        String fromSysProp = System.getProperty(sysPropKey);
        if (fromSysProp != null && !fromSysProp.isEmpty()) {
            String resolved = normalizeAndVerify(fromSysProp, binaryName);
            if (resolved != null) return resolved;
        }

        // Also check config map (loaded from application.properties/yml)
        if (config != null) {
            String fromConfig = config.get(sysPropKey);
            if (fromConfig != null && !fromConfig.isEmpty()) {
                String resolved = normalizeAndVerify(fromConfig, binaryName);
                if (resolved != null) return resolved;
            }
        }

        // Priority 2: Environment variable {NAME}_BINARY
        String envVarName = binaryName.toUpperCase().replace('-', '_') + "_BINARY";
        String fromEnv = System.getenv(envVarName);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            String resolved = normalizeAndVerify(fromEnv, binaryName);
            if (resolved != null) return resolved;
        }

        // Also try {NAME}_HOME/{NAME}_bin
        String homeVar = binaryName.toUpperCase().replace('-', '_') + "_HOME";
        String fromHome = System.getenv(homeVar);
        if (fromHome != null && !fromHome.isEmpty()) {
            Path homePath = Paths.get(fromHome);
            Path binDir = homePath.resolve("bin");
            if (Files.isDirectory(binDir)) {
                String exeName = IS_WINDOWS ? binaryName + ".exe" : binaryName;
                Path exePath = binDir.resolve(exeName);
                if (Files.isExecutable(exePath)) {
                    return exePath.normalize().toAbsolutePath().toString();
                }
            }
        }

        // Priority 3: which/where command
        String fromWhich = findByWhich(binaryName);
        if (fromWhich != null) return fromWhich;

        // Priority 4: PATH environment variable scan
        String fromPath = findByPath(binaryName);
        if (fromPath != null) return fromPath;

        // Priority 5: Direct execution test (let OS resolve via exec)
        String fromDirect = tryDirectExecution(binaryName);
        if (fromDirect != null) return fromDirect;

        // Priority 6: Default install locations scan
        String fromLocations = findByDefaultLocations(binaryName);
        if (fromLocations != null) return fromLocations;

        // Not found — throw with clear message
        String exeName = IS_WINDOWS ? binaryName + ".exe" : binaryName;
        throw new IllegalStateException("""
                Could not find %s executable. Set %s system property, %s environment variable, \
                add %s to PATH, or install %s in a standard location.""".formatted(
                binaryName, sysPropKey, envVarName, exeName, binaryName));
    }

    /**
     * Verify that a path points to a valid executable.
     * If the path is a directory, append the binary name.
     *
     * @param path       the user-specified path
     * @param binaryName the binary name for fallback
     * @return resolved path, or null if not valid
     */
    private static String normalizeAndVerify(String path, String binaryName) {
        if (path == null || path.isEmpty()) return null;
        Path p = Paths.get(path);
        if (Files.isDirectory(p)) {
            // User specified a directory, append binary name
            String exeName = IS_WINDOWS ? binaryName + ".exe" : binaryName;
            p = p.resolve(exeName);
        }
        if (Files.isRegularFile(p) && Files.isExecutable(p)) {
            return p.normalize().toAbsolutePath().toString();
        }
        // Also check without modification (user might have given full path)
        p = Paths.get(path);
        if (Files.isRegularFile(p) && Files.isExecutable(p)) {
            return p.normalize().toAbsolutePath().toString();
        }
        return null;
    }

    /**
     * Use which/where command to locate the binary on PATH.
     */
    private static String findByWhich(String binaryName) {
        try {
            String command = IS_WINDOWS ? "where" : "which";
            ProcessBuilder pb = new ProcessBuilder(command, binaryName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String firstLine = reader.readLine();
                    if (firstLine != null && !firstLine.trim().isEmpty()) {
                        Path execPath = Paths.get(firstLine.trim());
                        if (Files.exists(execPath) && Files.isExecutable(execPath)) {
                            return execPath.normalize().toAbsolutePath().toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // which/where not available or failed, fall through
        }
        return null;
    }

    /**
     * Scan PATH directories for the binary.
     */
    private static String findByPath(String binaryName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        String exeName = IS_WINDOWS ? binaryName + ".exe" : binaryName;
        String[] dirs = pathEnv.split(IS_WINDOWS ? ";" : ":");
        for (String dir : dirs) {
            if (dir == null || dir.trim().isEmpty()) continue;
            try {
                Path fullPath = Paths.get(dir.trim()).resolve(exeName);
                if (Files.isRegularFile(fullPath) && Files.isExecutable(fullPath)) {
                    return fullPath.normalize().toAbsolutePath().toString();
                }
            } catch (Exception e) {
                // Invalid path entry, skip
            }
        }
        return null;
    }

    /**
     * Try executing {@code {binaryName} --version} directly via the OS path resolution.
     * If it works, let the OS find the binary each time (return the bare name).
     */
    private static String tryDirectExecution(String binaryName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryName, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                // Binary is on the system PATH, return bare name to let OS resolve
                return binaryName;
            }
        } catch (Exception e) {
            // Not found or not executable
        }
        return null;
    }

    /**
     * Scan default install locations for the binary.
     */
    private static String findByDefaultLocations(String binaryName) {
        String exeName = IS_WINDOWS ? binaryName + ".exe" : binaryName;
        List<String> searchRoots = IS_WINDOWS ? WINDOWS_SEARCH_ROOTS : UNIX_SEARCH_ROOTS;

        // Also add npm global bin directories (common for claude, cursor, etc.)
        List<String> extendedRoots = new ArrayList<String>(searchRoots);
        if (!IS_WINDOWS) {
            // Common npm global bin paths on Linux/Mac
            String home = System.getProperty("user.home", "/home");
            extendedRoots.add(home + "/.npm-global");
            extendedRoots.add(home + "/.nvm/versions");
            extendedRoots.add("/opt/homebrew"); // Apple Silicon Homebrew
        } else {
            String home = System.getProperty("user.home", "C:\\Users\\Default");
            // Windows npm global and scoop paths
            extendedRoots.add(home + "\\AppData\\Roaming\\npm");
            extendedRoots.add(home + "\\scoop\\apps");
            extendedRoots.add("C:\\npm");
        }

        for (String root : extendedRoots) {
            Path rootPath = Paths.get(root);
            if (!Files.isDirectory(rootPath)) continue;
            try {
                // Walk up to SEARCH_DEPTH levels
                java.util.stream.Stream<Path> stream = Files.walk(rootPath, SEARCH_DEPTH);
                try {
                    java.util.Optional<Path> found = stream
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equalsIgnoreCase(exeName))
                            .findFirst();
                    if (found.isPresent()) {
                        return found.get().normalize().toAbsolutePath().toString();
                    }
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                // Permission error or invalid path, skip
            }
        }
        return null;
    }

    private BinaryResolver() {
        // utility class
    }
}
