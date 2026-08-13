package com.github.obhen233.util;

/**
 * Centralized build information.
 * Loads version from JAR manifest at runtime.
 */
public final class BuildInfo {
    private static final String VERSION;

    static {
        String version = "1.0.0";
        try {
            Package pkg = BuildInfo.class.getPackage();
            if (pkg != null) {
                version = pkg.getImplementationVersion();
                if (version == null || version.isEmpty()) {
                    version = "1.0.0";
                }
            }
        } catch (Exception e) {
            // Use default
        }
        VERSION = version;
    }

    private BuildInfo() {}

    /**
     * Get application version from JAR manifest.
     * @return version string, defaults to "1.0.0" if not found
     */
    public static String getVersion() {
        return VERSION;
    }
}
