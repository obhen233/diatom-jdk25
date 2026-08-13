package com.github.obhen233.util;

import java.io.IOException;
import java.nio.file.*;

/**
 * Utility class for finding JAR files dynamically
 */
public class JarUtils {

    private static final String CORE_JAR_PREFIX = "diatom-core-";
    private static final String CORE_JAR_SUFFIX = ".jar";

    /**
     * Find the diatom-core jar in the lib directory
     * @param libDir the lib directory path
     * @return the path to the core jar, or null if not found
     */
    public static Path findCoreJar(Path libDir) {
        if (libDir == null || !Files.exists(libDir) || !Files.isDirectory(libDir)) {
            return null;
        }
        try {
            return Files.list(libDir)
                .filter(p -> p.toString().endsWith(".jar"))
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(CORE_JAR_PREFIX) && name.endsWith(CORE_JAR_SUFFIX);
                })
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Get the core jar filename (e.g., "diatom-core-1.0.0.jar")
     * @param libDir the lib directory path
     * @return the filename or "core.jar" if not found
     */
    public static String getCoreJarName(Path libDir) {
        Path coreJar = findCoreJar(libDir);
        return coreJar != null ? coreJar.getFileName().toString() : "core.jar";
    }
}
