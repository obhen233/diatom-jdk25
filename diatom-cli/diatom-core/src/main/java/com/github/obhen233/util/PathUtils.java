package com.github.obhen233.util;

/**
 * Utility for resolving the original working directory.
 * <p>
 * In standalone JAR mode, Bootstrap sets {@code diatom.original.user.dir}
 * to preserve the user's original working directory before overriding
 * {@code user.dir} to the JAR installation directory.
 * <p>
 * All tools that operate on the user's project files should use
 * {@link #getWorkingDir()} instead of {@code System.getProperty("user.dir")}
 * to ensure correct behavior when launched from the diatom JAR.
 */
public class PathUtils {

    private static final String ORIGINAL_USER_DIR = "diatom.original.user.dir";
    private static final String USER_DIR = "user.dir";

    /**
     * Get the user's original working directory.
     * <p>
     * When diatom runs as a standalone JAR, Bootstrap sets {@code user.dir}
     * to the JAR directory but preserves the original working directory in
     * {@code diatom.original.user.dir}. This method returns the original
     * value — the directory where the user actually ran the diatom command.
     *
     * @return the original working directory path
     */
    public static String getWorkingDir() {
        return System.getProperty(ORIGINAL_USER_DIR, System.getProperty(USER_DIR));
    }
}
