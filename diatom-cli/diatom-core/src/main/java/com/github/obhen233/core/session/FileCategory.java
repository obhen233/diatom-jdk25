package com.github.obhen233.core.session;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Classifies file changes by type, enabling filtered display and audit statistics.
 *
 * <p>Classification is purely path-based, no external dependencies (no JGit in CLI).
 * Used by {@link SessionTracker} to tag every {@link ChangeRecord} with a category,
 * and by {@link FileChangeListener} to propagate the category to consumers.
 */
public enum FileCategory {

    /** Source code files under src/, test/, resources/ or recognized source subdirectories */
    PROJECT_SOURCE,
    /** Build/config files at project root: pom.xml, package.json, Dockerfile, etc. */
    PROJECT_CONFIG,
    /** Standalone scripts at project root that look like temporary utilities */
    HELPER_SCRIPT,
    /** Build artifacts under target/, dist/, build/, node_modules/, out/ */
    BUILD_ARTIFACT,
    /** Hidden files/dirs or files under .diatom/ — AI-internal temp files */
    AI_TEMP;

    private static final Set<String> CONFIG_FILES = new HashSet<>(Arrays.asList(
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        "settings.gradle.kts", "package.json", "package-lock.json", "yarn.lock",
        "pnpm-lock.yaml", "Cargo.toml", "README.md", "LICENSE", "CHANGELOG.md",
        "CONTRIBUTING.md", ".gitignore", "Dockerfile", "docker-compose.yml",
        "docker-compose.yaml", "Makefile", "CMakeLists.txt",
        "mvnw", "mvnw.cmd", "gradlew", "gradlew.bat",
        ".editorconfig", ".env", ".env.example",
        "tsconfig.json", "vite.config.ts", "vite.config.js", "next.config.js",
        "webpack.config.js", ".eslintrc.js", ".prettierrc",
        "sonar-project.properties", "requirements.txt", "setup.py", "pyproject.toml"
    ));

    private static final Set<String> SOURCE_SUBDIRS = new HashSet<>(Arrays.asList(
        "main", "java", "kotlin", "scala", "groovy",
        "components", "views", "pages", "store", "utils", "helpers",
        "config", "conf", "db", "migration", "sql",
        "static", "public", "assets", "styles", "css", "img", "images",
        "fonts", "locales", "i18n", "lang",
        "api", "controller", "service", "model", "entity", "dao", "repository",
        "dto", "vo", "pojo", "domain", "mapper", "provider", "consumer"
    ));

    private static final Set<String> SCRIPT_EXTS = new HashSet<>(Arrays.asList(
        "py", "sh", "bat", "ps1", "js", "ts", "rb", "pl", "php"
    ));

    private static final Set<String> DATA_EXTS = new HashSet<>(Arrays.asList(
        "json", "xml", "yaml", "yml", "csv", "tsv", "txt", "log", "tmp"
    ));

    /**
     * Classify a file path into a category using path conventions.
     *
     * @param path file path (relative, may include project prefix)
     * @return the determined category
     */
    public static FileCategory classifyPath(String path) {
        if (path == null || path.isEmpty()) return HELPER_SCRIPT;

        String normalized = path.replace('\\', '/');

        // 1. Hidden files/dirs → AI_TEMP
        String[] segments = normalized.split("/");
        for (String seg : segments) {
            if (seg.startsWith(".")) {
                return AI_TEMP;
            }
        }

        // 2. Known build artifact directories → BUILD_ARTIFACT
        if (normalized.contains("/target/") || normalized.contains("/dist/")
            || normalized.contains("/build/") || normalized.contains("/node_modules/")
            || normalized.contains("/out/") || normalized.contains("/.diatom/")) {
            return BUILD_ARTIFACT;
        }

        // 3. In standard source/test/resource directories → PROJECT_SOURCE
        if (normalized.contains("/src/") || normalized.contains("/test/")
            || normalized.contains("/resources/")) {
            return PROJECT_SOURCE;
        }

        String basename = segments[segments.length - 1];

        // 4. Known build/config files → PROJECT_CONFIG
        if (CONFIG_FILES.contains(basename)) {
            return PROJECT_CONFIG;
        }

        // 5. Files in recognized source subdirectories → PROJECT_SOURCE
        if (segments.length >= 3) {
            String subdir = segments[1]; // projectName/subdir/...
            if (SOURCE_SUBDIRS.contains(subdir)) {
                return PROJECT_SOURCE;
            }
        }

        // 6. Files at project root (2 segments: project/file)
        if (segments.length == 2) {
            String ext = "";
            int dotIdx = basename.lastIndexOf('.');
            if (dotIdx > 0) {
                ext = basename.substring(dotIdx + 1).toLowerCase();
            }

            if (SCRIPT_EXTS.contains(ext)) {
                return HELPER_SCRIPT;
            }
            if (DATA_EXTS.contains(ext)) {
                return HELPER_SCRIPT;
            }
        }

        // 7. Everything else → HELPER_SCRIPT
        return HELPER_SCRIPT;
    }
}
