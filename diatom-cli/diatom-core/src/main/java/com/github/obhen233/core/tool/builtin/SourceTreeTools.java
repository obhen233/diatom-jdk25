package com.github.obhen233.core.tool.builtin;

import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.util.InstallPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Tool to retrieve a complete source file tree from the custom-sources.jar
 * or sources directory. Replaces multiple list_files/search_files calls
 * with a single call that returns the full source tree structure.
 * 
 * Features:
 * - Cached result: subsequent calls return cached data
 * - Budget exempt: doesn't count against exploration budget
 * - Fast: one call gives full project overview
 * - Sources/ prefix: AI output paths include "sources/" prefix for clarity
 */
public class SourceTreeTools {
    private static final Logger logger = LoggerFactory.getLogger(SourceTreeTools.class);
    private static final String NEWLINE = System.lineSeparator();

    // NOTE: sourcesDir is resolved dynamically from SelfUpdateFileAccessor
    // (which uses diatom.jar.dir when running from JAR, user.dir as fallback)
    // The old APP_HOME/SOURCES_DIR constants are replaced with dynamic lookup
    // to correctly resolve the sources directory relative to the JAR location.
    private static final String SOURCES_JAR_NAME = "custom-sources.jar";

    // File accessor for path resolution (shared with SelfUpdateTools)
    private static final SelfUpdateFileAccessor fileAccessor = new SelfUpdateFileAccessor();
    
    // Cache for get_source_tree result
    private static String cachedSourceTree = null;
    private static long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 60000; // 1 minute cache TTL
    
    /**
     * Get the file accessor for external use.
     * This allows other tools to normalize paths consistently.
     */
    public static SelfUpdateFileAccessor getFileAccessor() {
        return fileAccessor;
    }

    @ToolMethod(name = "get_source_tree",
                description = "[SCENE: project-explore] Get complete source file tree. " +
                    "★★★ MUST USE FIRST for any project exploration ★★★ " +
                    "One call gives full project structure, FASTER and MORE COMPLETE than list_files/list_directory. " +
                    "BUDGET-EXEMPT: does not count against exploration limit. " +
                    "CACHED: subsequent calls return cached result. " +
                    "★★★ AFTER THIS CALL: DO NOT use list_files, list_directory, or search_files to explore the project layout — you already have the full tree! ★★★",
                parametersSchema = "{}",
                readOnly = true)
    public String getSourceTree() {
        // Check cache first
        long now = System.currentTimeMillis();
        if (cachedSourceTree != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            logger.debug("Returning cached source tree (age: {}ms)", now - cacheTimestamp);
            return cachedSourceTree + NEWLINE + "(cached result)";
        }
        
        String result = getSourceTreeInternal(false);
        
        // Cache the result
        cachedSourceTree = result;
        cacheTimestamp = now;
        
        return result;
    }
    
    /**
     * Clear the source tree cache (call when sources change).
     */
    public static void clearCache() {
        cachedSourceTree = null;
        cacheTimestamp = 0;
        logger.debug("Source tree cache cleared");
    }



    private String getSourceTreeInternal(boolean includeContent) {
        long startTime = System.currentTimeMillis();
        String baseDir = System.getProperty("diatom.jar.dir", System.getProperty("user.dir"));

        // Priority 1: Check if sources directory exists (next to diatom-cli.jar, or in user.dir)
        String sourcesDirStr = fileAccessor.getSourcesDir();
        Path sourcesDirPath = Paths.get(sourcesDirStr);
        if (Files.exists(sourcesDirPath) && Files.isDirectory(sourcesDirPath)) {
            logger.info("Found sources directory: {}", sourcesDirStr);
            return buildTreeFromDirectory(sourcesDirPath, includeContent, startTime);
        }

        // Priority 2: Check custom-sources.jar in JAR directory (or user.dir as fallback)
        Path jarDirJar = Paths.get(baseDir, SOURCES_JAR_NAME);
        if (Files.exists(jarDirJar)) {
            logger.info("Found sources jar at: {}", jarDirJar);
            return buildTreeFromJar(jarDirJar, includeContent, startTime);
        }

        // Priority 3: Check custom-sources.jar in {installHome}/sources/ (legacy fallback)
        Path customJar = InstallPaths.getInstallHome().resolve("sources").resolve(SOURCES_JAR_NAME);
        if (Files.exists(customJar)) {
            logger.info("Found sources jar at custom dir: {}", customJar);
            return buildTreeFromJar(customJar, includeContent, startTime);
        }

        // Not found
        long elapsed = System.currentTimeMillis() - startTime;
        return "=== Source Tree: NOT AVAILABLE ===" + NEWLINE +
               "Could not find " + SOURCES_JAR_NAME + " in:" + NEWLINE +
               "  - " + jarDirJar + NEWLINE +
               "  - " + customJar + NEWLINE +
               "  - Sources directory: " + sourcesDirStr + NEWLINE +
               "The sources JAR may not have been generated yet." + NEWLINE +
               "(took " + elapsed + "ms)";
    }

    /**
     * Build file tree from a directory structure.
     */
    private String buildTreeFromDirectory(Path sourcesDir, boolean includeContent, long startTime) {
        try {
            // Collect all .java files with their relative paths
            List<Path> javaFiles = new ArrayList<>();
            Files.walkFileTree(sourcesDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        javaFiles.add(sourcesDir.relativize(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (javaFiles.isEmpty()) {
                return "=== Source Tree ===" + NEWLINE +
                       "No .java files found in: " + sourcesDir;
            }

            // Sort files by path
            javaFiles.sort(Comparator.comparing(Path::toString));

            // Build tree structure
            Map<String, List<String>> packageMap = buildPackageMap(javaFiles);

            StringBuilder sb = new StringBuilder();
            sb.append("=== Source File Tree (").append(javaFiles.size()).append(" Java files) ===").append(NEWLINE);
            sb.append("NOTE: All paths below have 'sources/' prefix. Use write_source_file with the path (prefix auto-stripped).").append(NEWLINE);
            sb.append("NOTE: All paths below have 'sources/' prefix. Use write_source_file with the path (prefix auto-stripped).\n").append(NEWLINE).append(NEWLINE);

            // Add naming pattern summary
            List<String> fileNames = javaFiles.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
            sb.append(generateNamingSummary(fileNames));

            // Sort package names
            List<String> sortedPackages = new ArrayList<>(packageMap.keySet());
            sortedPackages.sort(Comparator.naturalOrder());

            for (String pkg : sortedPackages) {
                List<String> files = packageMap.get(pkg);
                sb.append(pkg).append(NEWLINE);
                for (int i = 0; i < files.size(); i++) {
                    String file = files.get(i);
                    boolean isLast = (i == files.size() - 1);
                    String prefix = isLast ? "    └── " : "    ├── ";
                    sb.append(prefix).append(file).append(NEWLINE);
                }
                sb.append(NEWLINE);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            sb.append("(took ").append(elapsed).append("ms)").append(NEWLINE);

            // Optionally include file contents
            if (includeContent) {
                sb.append(NEWLINE).append("=== File Contents ===").append(NEWLINE).append(NEWLINE);
                for (Path relPath : javaFiles) {
                    Path fullPath = sourcesDir.resolve(relPath);
                    try {
                        String content = new String(Files.readAllBytes(fullPath));
                        sb.append("--- ").append(relPath.toString().replace("\\", "/")).append(" ---").append(NEWLINE);
                        sb.append(content);
                        if (!content.endsWith("\n")) {
                            sb.append(NEWLINE);
                        }
                        sb.append(NEWLINE);
                    } catch (IOException e) {
                        sb.append("--- ").append(relPath).append(" --- [ERROR: ").append(e.getMessage()).append("]").append(NEWLINE);
                    }
                }
            }

            return sb.toString();
        } catch (IOException e) {
            logger.error("Error scanning sources directory", e);
            return "Error scanning sources directory: " + e.getMessage();
        }
    }

    /**
     * Build file tree from a JAR file.
     */
    private String buildTreeFromJar(Path jarPath, boolean includeContent, long startTime) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<String> javaEntries = new ArrayList<>();
            Map<String, byte[]> entryContents = new LinkedHashMap<>();

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".java")) {
                    javaEntries.add(name);
                    if (includeContent) {
                        byte[] content = readJarEntryBytes(jar, entry);
                        entryContents.put(name, content);
                    }
                }
            }

            if (javaEntries.isEmpty()) {
                return "=== Source Tree ===" + NEWLINE +
                       "No .java files found in: " + jarPath;
            }

            // Sort entries
            javaEntries.sort(Comparator.naturalOrder());

            // Build package map from JAR paths
            // JAR entries are like: com/github/obhen233/App.java
            Map<String, List<String>> packageMap = new TreeMap<>();

            for (String entry : javaEntries) {
                int lastSlash = entry.lastIndexOf('/');
                String pkg;
                String fileName;
                if (lastSlash >= 0) {
                    pkg = entry.substring(0, lastSlash).replace('/', '.');
                    fileName = entry.substring(lastSlash + 1);
                } else {
                    pkg = "(default)";
                    fileName = entry;
                }
                packageMap.computeIfAbsent(pkg, k -> new ArrayList<>()).add(fileName);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Source File Tree (").append(javaEntries.size()).append(" Java files) ===").append(NEWLINE);
            sb.append("NOTE: All paths below have 'sources/' prefix. Use write_source_file with the path (prefix auto-stripped).").append(NEWLINE);
            sb.append("NOTE: All paths below have 'sources/' prefix. Use write_source_file with the path (prefix auto-stripped).\n").append(NEWLINE).append(NEWLINE);

            // Add naming pattern summary
            List<String> fileNames = javaEntries.stream()
                .map(e -> {
                    int lastSlash = e.lastIndexOf('/');
                    return lastSlash >= 0 ? e.substring(lastSlash + 1) : e;
                })
                .collect(Collectors.toList());
            sb.append(generateNamingSummary(fileNames));

            // Use a tree-like format with nesting
            // Group packages by their top-level segments
            List<String> sortedPackages = new ArrayList<>(packageMap.keySet());
            sortedPackages.sort(Comparator.naturalOrder());

            // Build hierarchy from package names
            Map<String, Object> root = new LinkedHashMap<>();
            for (String pkg : sortedPackages) {
                String[] parts = pkg.split("\\.");
                Map<String, Object> current = root;
                for (String part : parts) {
                    current = (Map<String, Object>) current.computeIfAbsent(part, k -> new LinkedHashMap<>());
                }
                // Store files at the leaf
                for (String file : packageMap.get(pkg)) {
                    current.put(file, Boolean.TRUE);
                }
            }

            // Render tree
            renderTree(sb, root, 0, "");

            long elapsed = System.currentTimeMillis() - startTime;
            sb.append("(took ").append(elapsed).append("ms)").append(NEWLINE);

            // Optionally include file contents
            if (includeContent) {
                sb.append(NEWLINE).append("=== File Contents ===").append(NEWLINE).append(NEWLINE);
                for (String entryPath : javaEntries) {
                    byte[] content = entryContents.get(entryPath);
                    sb.append("--- ").append(entryPath).append(" ---").append(NEWLINE);
                    if (content != null) {
                        sb.append(new String(content));
                        if (!sb.toString().endsWith("\n")) {
                            sb.append(NEWLINE);
                        }
                    } else {
                        try {
                            JarEntry entry = jar.getJarEntry(entryPath);
                            if (entry != null) {
                                content = readJarEntryBytes(jar, entry);
                                sb.append(new String(content));
                            }
                        } catch (IOException e) {
                            sb.append("[ERROR: ").append(e.getMessage()).append("]");
                        }
                    }
                    sb.append(NEWLINE);
                }
            }

            return sb.toString();
        } catch (IOException e) {
            logger.error("Error reading sources JAR", e);
            return "Error reading sources JAR: " + e.getMessage();
        }
    }

    /**
     * Render a tree structure with Unicode box-drawing characters.
     */
    @SuppressWarnings("unchecked")
    private void renderTree(StringBuilder sb, Map<String, Object> node, int depth, String prefix) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(node.entrySet());
        // Sort: directories first, then files
        entries.sort((a, b) -> {
            boolean aIsDir = a.getValue() instanceof Map;
            boolean bIsDir = b.getValue() instanceof Map;
            if (aIsDir != bIsDir) return aIsDir ? -1 : 1;
            return a.getKey().compareTo(b.getKey());
        });

        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Object> entry = entries.get(i);
            boolean isLast = (i == entries.size() - 1);

            String connector = isLast ? "└── " : "├── ";
            if (depth == 0) {
                connector = "";
            }

            // For deeper levels, use the prefix
            String line;
            if (depth == 0) {
                // Add "sources/" prefix to root level entries for clarity
                // This helps AI understand the path context when using write_source_file
                line = "sources/" + entry.getKey();
            } else {
                line = prefix + connector + entry.getKey();
            }

            if (entry.getValue() instanceof Map) {
                sb.append(line).append("/").append(NEWLINE);
                String childPrefix;
                if (depth == 0) {
                    childPrefix = "";  // Reset prefix for children (already under sources/)
                } else {
                    childPrefix = prefix + (isLast ? "    " : "│   ");
                }
                renderTree(sb, (Map<String, Object>) entry.getValue(), depth + 1, childPrefix);
            } else {
                sb.append(line).append(NEWLINE);
            }
        }
    }

    /**
     * Generate a naming pattern summary from a list of file names.
     */
    private String generateNamingSummary(List<String> fileNames) {
        Map<String, Integer> prefixCounts = new HashMap<>();
        Map<String, Integer> suffixCounts = new HashMap<>();
        int pascalCount = 0;
        int camelCount = 0;
        int snakeCount = 0;
        int kebabCount = 0;

        for (String fn : fileNames) {
            String name = fn;
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            if (name.isEmpty()) continue;

            List<String> segments = splitSegments(name);
            if (segments.isEmpty()) continue;

            // Prefix = first segment, only count PascalCase/capitalized prefixes
            String prefix = segments.get(0);
            if (prefix.length() >= 2 && Character.isUpperCase(prefix.charAt(0))) {
                prefixCounts.merge(prefix, 1, Integer::sum);
            }

            // Suffix = last segment
            String suffix = segments.get(segments.size() - 1);
            if (suffix.length() >= 2) {
                suffixCounts.merge(suffix, 1, Integer::sum);
            }

            if (name.contains("_")) {
                snakeCount++;
            } else if (name.contains("-")) {
                kebabCount++;
            } else if (Character.isUpperCase(name.charAt(0))) {
                pascalCount++;
            } else if (Character.isLowerCase(name.charAt(0))) {
                camelCount++;
            }
        }

        List<Map.Entry<String, Integer>> topPrefixes = prefixCounts.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(3)
            .collect(Collectors.toList());
        List<Map.Entry<String, Integer>> topSuffixes = suffixCounts.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(5)
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("=== Detected Naming Patterns ===").append(NEWLINE);

        sb.append("- Common prefixes: ");
        if (topPrefixes.isEmpty()) {
            sb.append("(none)");
        } else {
            for (int i = 0; i < topPrefixes.size(); i++) {
                if (i > 0) sb.append(", ");
                Map.Entry<String, Integer> e = topPrefixes.get(i);
                sb.append(e.getKey()).append(" (").append(e.getValue()).append(" files)");
            }
        }
        sb.append(NEWLINE);

        sb.append("- Common suffixes: ");
        if (topSuffixes.isEmpty()) {
            sb.append("(none)");
        } else {
            for (int i = 0; i < topSuffixes.size(); i++) {
                if (i > 0) sb.append(", ");
                Map.Entry<String, Integer> e = topSuffixes.get(i);
                sb.append(e.getKey()).append(" (").append(e.getValue()).append(" files)");
            }
        }
        sb.append(NEWLINE);

        List<String> examples = fileNames.stream()
            .filter(f -> f.endsWith(".java"))
            .limit(3)
            .collect(Collectors.toList());
        sb.append("- Example files: ");
        if (examples.isEmpty()) {
            sb.append("(none)");
        } else {
            sb.append(String.join(", ", examples));
        }
        sb.append(NEWLINE);

        String style;
        int total = fileNames.size();
        if (total > 0 && snakeCount * 2 >= total) {
            style = "snake_case";
        } else if (total > 0 && kebabCount * 2 >= total) {
            style = "kebab-case";
        } else if (pascalCount >= camelCount) {
            style = "PascalCase";
        } else {
            style = "camelCase";
        }
        sb.append("- Naming style: ").append(style).append(NEWLINE);
        sb.append(NEWLINE);

        return sb.toString();
    }

    /**
     * Split a file name into semantic segments (camel/PascalCase boundaries, _, -).
     */
    private List<String> splitSegments(String name) {
        List<String> segments = new ArrayList<>();
        if (name == null || name.isEmpty()) return segments;

        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || c == '.') {
                if (current.length() > 0) {
                    segments.add(current.toString());
                }
                current = new StringBuilder();
            } else if (Character.isUpperCase(c)) {
                if (current.length() > 0) {
                    segments.add(current.toString());
                }
                current = new StringBuilder();
                current.append(c);
            } else if (Character.isDigit(c)) {
                if (current.length() > 0 && !Character.isDigit(current.charAt(0))) {
                    segments.add(current.toString());
                    current = new StringBuilder();
                }
                current.append(c);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments;
    }

    /**
     * Build a map: package path -> list of file names.
     */
    private Map<String, List<String>> buildPackageMap(List<Path> files) {
        Map<String, List<String>> packageMap = new TreeMap<>();
        for (Path file : files) {
            Path parent = file.getParent();
            String pkg = parent != null ? parent.toString().replace("\\", "/").replace("/", ".") : "(default)";
            String fileName = file.getFileName().toString();
            packageMap.computeIfAbsent(pkg, k -> new ArrayList<>()).add(fileName);
        }
        // Sort files within each package
        for (List<String> fileList : packageMap.values()) {
            fileList.sort(Comparator.naturalOrder());
        }
        return packageMap;
    }

    /**
     * Read all bytes from a JAR entry.
     */
    private byte[] readJarEntryBytes(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream is = jar.getInputStream(entry)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int n;
            while ((n = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, n);
            }
            return buffer.toByteArray();
        }
    }
}
