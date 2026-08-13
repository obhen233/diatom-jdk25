package com.github.obhen233.adapter.internal;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Directory path constants, aligned with diatom worker directory layout.
 *
 * <p>All paths are relative to {@code {jarDir}/.diatom/} or {@code ~/.diatom/}.</p>
 */
public class DirectoryLayout {

    /** Application-level .diatom directory (next to the JAR). */
    public static final String DIATOM_DIR = ".diatom";
    public static final String APPLICATION_PROPERTIES = "application.properties";
    public static final String CAPABILITY_MD = "capability.md";
    public static final String PID_FILE = "diatom.pid";
    public static final String BOOTSTRAP_LOCK = "bootstrap.lock";
    public static final String DB_FILE = "diatom.db";
    public static final String LOGS_DIR = "logs";
    public static final String ADAPTER_LOG = "adapter.log";
    public static final String PLUGINS_DIR = "plugins";

    /** Global ~/.diatom/ paths. */
    public static final String GLOBAL_DIATOM_DIR = ".diatom";
    public static final String GLOBAL_PLUGINS_DIR = GLOBAL_DIATOM_DIR + "/plugins";

    private DirectoryLayout() {}

    public static Path getJarDir() {
        String jarPath = System.getProperty("diatom.jar.dir", "");
        if (!jarPath.isEmpty()) {
            return Paths.get(jarPath);
        }
        // Fallback: current working directory
        return Paths.get(System.getProperty("user.dir", "."));
    }

    public static Path getDiatomDir(Path jarDir) {
        return jarDir.resolve(DIATOM_DIR);
    }

    public static Path getGlobalDiatomDir() {
        String home = System.getProperty("user.home", "~");
        return Paths.get(home, GLOBAL_DIATOM_DIR);
    }

    public static Path getPluginsDir(Path jarDir) {
        return getDiatomDir(jarDir).resolve(PLUGINS_DIR);
    }

    public static Path getGlobalPluginsDir() {
        return getGlobalDiatomDir().resolve("plugins");
    }

    public static Path getApplicationPropertiesPath(Path jarDir) {
        return getDiatomDir(jarDir).resolve(APPLICATION_PROPERTIES);
    }

    public static Path getCapabilityPath(Path jarDir) {
        return getDiatomDir(jarDir).resolve(CAPABILITY_MD);
    }

    public static Path getDbPath(Path jarDir) {
        return getDiatomDir(jarDir).resolve(DB_FILE);
    }
}
