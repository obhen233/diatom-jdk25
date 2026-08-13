package com.github.obhen233.util;

/**
 * Centralized JAR internal path definitions.
 * All paths inside diatom-cli.jar and custom-current.jar are defined here.
 */
public final class JarPath {
    private JarPath() {}

    // ===== JAR Internal Paths =====

    /** Bootstrap classes prefix */
    public static final String BOOTSTRAP = "com/github/obhen233/bootstrap/";

    /** Custom module prefix */
    public static final String CUSTOM = "custom/";

    /** Sources prefix (custom-sources.jar inside JAR) */
    public static final String SOURCES = "sources/";

    /** Library dependencies prefix */
    public static final String LIB = "lib/";

    // ===== Combined Paths =====

    /** Custom shaded JAR inside JAR */
    public static final String CUSTOM_SHADED_JAR = CUSTOM + "custom-shaded.jar";

    /** Custom sources JAR inside JAR (used for self-update) */
    public static final String CUSTOM_SOURCES_JAR = SOURCES + "custom-sources.jar";

    /** Custom version file inside JAR */
    public static final String CUSTOM_VERSION_TXT = CUSTOM + "custom-version.txt";

    /** Core version file inside JAR */
    public static final String CORE_VERSION_TXT = CUSTOM + "core-version.txt";

    // ===== File Names =====

    /** Main executable JAR name */
    public static final String DIATOM_CLI_JAR = "diatom-cli.jar";

    /** Custom current JAR name */
    public static final String CUSTOM_CURRENT_JAR = "custom-current.jar";

    /** Pending update marker suffix */
    public static final String UPDATE_PENDING_MARKER = ".update-pending";

    /** Core JAR pattern prefix */
    public static final String CORE_JAR_PREFIX = "diatom-core-";

    /** Core JAR pattern suffix */
    public static final String CORE_JAR_SUFFIX = ".jar";
}
