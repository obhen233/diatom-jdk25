package com.github.obhen233.core.tool.builtin;

import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.util.InstallPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * External JAR library management tool
 * Supports: add, update, remove, list, search dependencies
 */
public class JarManager {
    private static final Logger logger = LoggerFactory.getLogger(JarManager.class);
    private static final Path LIBS_DIR = InstallPaths.getInstallHome().resolve("libs");
    private static final Path CUSTOM_LIB_DIR = InstallPaths.getLibDir();
    private static final String libsDir = LIBS_DIR.toString();

    public JarManager() {
        try {
            Files.createDirectories(LIBS_DIR);
            Files.createDirectories(CUSTOM_LIB_DIR);
        } catch (IOException e) {
            logger.error("Failed to create libs directory", e);
        }
    }

    @ToolMethod(name = "list_libs",
                description = "List all external JAR libraries currently managed",
                parametersSchema = "{}",
                readOnly = true)
    public String listLibs() {
        try {
            Path libsPath = Paths.get(libsDir);
            if (!Files.exists(libsPath)) {
                return "No libraries installed.";
            }

            List<String> jars = Files.list(libsPath)
                .filter(p -> p.toString().endsWith(".jar"))
                .map(p -> {
                    String name = p.getFileName().toString();
                    long size = 0;
                    try { size = Files.size(p); } catch (IOException ignored) {}
                    return String.format("  - %s (%.2f MB)", name, size / 1024.0 / 1024.0);
                })
                .collect(Collectors.toList());

            if (jars.isEmpty()) {
                return "No libraries installed.";
            }

            return "Installed libraries:\n" + String.join("\n", jars) + "\nTotal: " + jars.size() + " libs";
        } catch (IOException e) {
            logger.error("Error listing libs", e);
            return "Error listing libraries: " + e.getMessage();
        }
    }

    @ToolMethod(name = "add_lib",
                description = "Download and add a new JAR library from Maven Central",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"artifact\": {\"type\": \"string\", \"description\": \"Maven artifact in format groupId:artifactId:version, e.g. com.mysql:mysql-connector-j:8.0.33\"}}}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String addLib(String argsJson) {
        if (argsJson == null || argsJson.trim().isEmpty()) {
            return "Error: artifact required.\n" +
                   "Use: groupId:artifactId:version\n" +
                   "Examples:\n" +
                   "  - com.mysql:mysql-connector-j:8.0.33\n" +
                   "  - org.postgresql:postgresql:42.7.1\n" +
                   "  - org.xerial:sqlite-jdbc:3.45.1.0";
        }

        // Parse artifact from JSON: {"artifact":"groupId:artifactId:version"}
        String artifact;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(argsJson);
            if (root.has("artifact")) {
                artifact = root.get("artifact").asText();
            } else {
                // Try direct string (non-JSON format)
                artifact = argsJson.trim();
            }
        } catch (Exception e) {
            // Not valid JSON, treat as direct value
            artifact = argsJson.trim();
        }

        if (artifact == null || artifact.isEmpty()) {
            return "Error: artifact required.\n" +
                   "Use: groupId:artifactId:version\n" +
                   "Examples:\n" +
                   "  - com.mysql:mysql-connector-j:8.0.33\n" +
                   "  - org.postgresql:postgresql:42.7.1\n" +
                   "  - org.xerial:sqlite-jdbc:3.45.1.0";
        }

        // Validate artifact format
        if (!isValidArtifact(artifact)) {
            return "Error: Invalid artifact format.\n" +
                   "Use: groupId:artifactId:version\n" +
                   "Examples:\n" +
                   "  - com.mysql:mysql-connector-j:8.0.33\n" +
                   "  - org.postgresql:postgresql:42.7.1\n" +
                   "  - org.xerial:sqlite-jdbc:3.45.1.0";
        }

        String[] parts = artifact.trim().split(":");
        if (parts.length != 3) {
            return "Error: Invalid format. Need 3 parts separated by ':'\n" +
                   "Format: groupId:artifactId:version\n" +
                   "Example: com.mysql:mysql-connector-j:8.0.33";
        }

        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];

        // Validate groupId format (must be valid package names)
        if (!isValidGroupId(groupId)) {
            return "Error: Invalid groupId format.\n" +
                   "groupId should be like: com.mysql, org.postgresql, org.xerial\n" +
                   "Received: " + groupId;
        }

        String fileName = artifactId + "-" + version + ".jar";
        Path targetPath = LIBS_DIR.resolve(fileName);
        Path runtimeTargetPath = CUSTOM_LIB_DIR.resolve(fileName);

        // Maven Central URL
        String mavenUrl = String.format(
            "https://repo1.maven.org/maven2/%s/%s/%s/%s",
            groupId.replace('.', '/'), artifactId, version, fileName
        );

        try {
            Files.createDirectories(LIBS_DIR);
            Files.createDirectories(CUSTOM_LIB_DIR);
            if (!Files.exists(targetPath)) {
                System.out.println("Downloading " + mavenUrl + "...");
                downloadFile(mavenUrl, targetPath);
            }
            Files.copy(targetPath, runtimeTargetPath, StandardCopyOption.REPLACE_EXISTING);
            PomUpdateResult pomUpdate = updateExtractedSourcePoms(groupId, artifactId, version);
            return "Library added: " + fileName + " (" + (Files.size(targetPath) / 1024) + " KB)\n" +
                   "Copied to runtime lib: " + runtimeTargetPath + "\n" +
                   pomUpdate.message + "\n" +
                   "Run compile_sources, then restart_application for self-update changes to take effect.";
        } catch (Exception e) {
            logger.error("Failed to download library", e);
            // Clean up partial download
            try { Files.deleteIfExists(targetPath); } catch (Exception ignored) {}
            try { Files.deleteIfExists(runtimeTargetPath); } catch (Exception ignored) {}
            return "Error: Download failed.\n" +
                   "Check if artifact exists: " + mavenUrl + "\n" +
                   "Original error: " + e.getMessage();
        }
    }

    @ToolMethod(name = "remove_lib",
                description = "Remove an installed JAR library by filename",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\", \"description\": \"JAR filename to remove, e.g. mysql-connector-java-8.0.33.jar\"}}}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String removeLib(String argsJson) {
        // Parse name from JSON: {"name":"filename.jar"}
        String name;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(argsJson);
            if (root.has("name")) {
                name = root.get("name").asText();
            } else {
                name = argsJson.trim();
            }
        } catch (Exception e) {
            name = argsJson.trim();
        }

        if (name == null || name.isEmpty()) {
            return "Error: library name required";
        }

        // Prevent path traversal
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return "Error: Invalid library name";
        }

        Path libPath = Paths.get(libsDir, name.trim());
        if (!Files.exists(libPath)) {
            return "Library not found: " + name;
        }

        try {
            Files.delete(libPath);
            return "Library removed: " + name;
        } catch (IOException e) {
            logger.error("Failed to remove library", e);
            return "Failed to remove: " + e.getMessage();
        }
    }

    @ToolMethod(name = "search_maven",
                description = "Search Maven Central for JAR libraries by keyword",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"keyword\": {\"type\": \"string\", \"description\": \"Search keyword, e.g. mysql connector\"}}}",
                readOnly = true)
    public String searchMaven(String argsJson) {
        // Parse keyword from JSON: {"keyword":"apache poi"}
        String keyword;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(argsJson);
            if (root.has("keyword")) {
                keyword = root.get("keyword").asText();
            } else {
                keyword = argsJson.trim();
            }
        } catch (Exception e) {
            keyword = argsJson.trim();
        }

        if (keyword == null || keyword.trim().length() < 3) {
            return "Error: keyword must be at least 3 characters";
        }

        try {
            // Call Maven Central Solr API
            String encodedKeyword = java.net.URLEncoder.encode(keyword.trim(), "UTF-8");
            String apiUrl = "https://search.maven.org/solrsearch/select?q=" + encodedKeyword
                + "&rows=20&core=gav&wt=json";

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return "Error: Maven Central API returned code " + responseCode + "\n"
                     + "Options:\n"
                     + "1. Enter local Maven path: use install_maven tool\n"
                     + "2. Download Maven automatically: use install_maven tool with action=\"auto\"";
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            // Parse JSON response
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.toString());
            com.fasterxml.jackson.databind.JsonNode docs = root.path("response").path("docs");

            if (docs.isArray() && docs.size() > 0) {
                StringBuilder result = new StringBuilder();
                result.append("Found ").append(docs.size()).append(" results for \"").append(keyword).append("\"\n\n");

                for (int i = 0; i < Math.min(docs.size(), 10); i++) {
                    com.fasterxml.jackson.databind.JsonNode doc = docs.get(i);
                    String g = doc.has("g") ? doc.get("g").asText() : "";
                    String a = doc.has("a") ? doc.get("a").asText() : "";
                    String latestVersion = doc.has("latestVersion") ? doc.get("latestVersion").asText() : "";
                    String txt = doc.has("txt") ? doc.get("txt").asText() : "";

                    result.append(i + 1).append(". ").append(a).append("\n");
                    result.append("   Group: ").append(g).append("\n");
                    result.append("   Version: ").append(latestVersion).append("\n");
                    if (!txt.isEmpty() && !txt.equals(latestVersion)) {
                        result.append("   Description: ").append(txt).append("\n");
                    }
                    result.append("   Add with: add_lib(\"").append(g).append(":").append(a).append(":").append(latestVersion).append("\")\n\n");
                }

                if (docs.size() > 10) {
                    result.append("... and ").append(docs.size() - 10).append(" more results\n");
                }

                result.append("\nUse add_lib tool to add any of these libraries.");
                return result.toString();
            } else {
                return "No results found for \"" + keyword + "\"\n\n"
                     + "Options:\n"
                     + "1. Enter local Maven path: use install_maven(\"auto\") to auto-download or install_maven(\"path\", \"C:/path/to/maven\") to use existing\n"
                     + "2. Search with different keyword\n"
                     + "3. If you have a specific artifact, use: add_lib(\"groupId:artifactId:version\")";
            }
        } catch (java.net.SocketTimeoutException e) {
            return "Error: Connection to Maven Central timed out\n"
                 + "Options:\n"
                 + "1. Enter local Maven path: use install_maven tool\n"
                 + "2. Download Maven automatically: use install_maven(\"auto\")";
        } catch (Exception e) {
            logger.error("Maven search failed", e);
            return "Error searching Maven Central: " + e.getMessage() + "\n\n"
                 + "Options:\n"
                 + "1. Enter local Maven path: use install_maven tool\n"
                 + "2. Download Maven automatically: use install_maven(\"auto\")";
        }
    }

    /**
     * Get the libs directory path for classpath loading
     */
    public static String getLibsPath() {
        return libsDir;
    }

    /**
     * Get all JAR files in libs directory for classpath
     */
    public static List<String> getAllLibJars() {
        List<String> jars = new ArrayList<>();
        Path libsPath = Paths.get(libsDir);
        if (Files.exists(libsPath)) {
            try {
                Files.list(libsPath)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .forEach(p -> jars.add(p.toAbsolutePath().toString()));
            } catch (IOException ignored) {}
        }
        return jars;
    }

    static String addOrUpdatePomDependency(String pom, String groupId, String artifactId, String version) {
        String groupTag = "<groupId>" + groupId + "</groupId>";
        String artifactTag = "<artifactId>" + artifactId + "</artifactId>";
        // Only check within <dependencies>...</dependencies> to avoid false matches in <parent>/<plugin>
        int depsStart = pom.indexOf("<dependencies>");
        int depsEnd = pom.indexOf("</dependencies>");
        if (depsStart >= 0 && depsEnd > depsStart) {
            String depsSection = pom.substring(depsStart, depsEnd);
            if (depsSection.contains(groupTag) && depsSection.contains(artifactTag)) {
                return pom;
            }
        }

        String dependency = "        <dependency>\n" +
                "            <groupId>" + groupId + "</groupId>\n" +
                "            <artifactId>" + artifactId + "</artifactId>\n" +
                "            <version>" + version + "</version>\n" +
                "        </dependency>\n";

        int dependenciesEnd = pom.indexOf("</dependencies>");
        if (dependenciesEnd >= 0) {
            return pom.substring(0, dependenciesEnd) + dependency + pom.substring(dependenciesEnd);
        }

        int buildStart = pom.indexOf("<build>");
        String dependencies = "    <dependencies>\n" + dependency + "    </dependencies>\n\n";
        if (buildStart >= 0) {
            return pom.substring(0, buildStart) + dependencies + pom.substring(buildStart);
        }

        int projectEnd = pom.indexOf("</project>");
        if (projectEnd >= 0) {
            return pom.substring(0, projectEnd) + dependencies + pom.substring(projectEnd);
        }
        return pom + "\n" + dependencies;
    }

    private PomUpdateResult updateExtractedSourcePoms(String groupId, String artifactId, String version) {
        List<Path> candidates = new ArrayList<>();
        String jarDir = System.getProperty("diatom.jar.dir");
        if (jarDir != null && !jarDir.trim().isEmpty()) {
            candidates.add(Paths.get(jarDir).resolve("sources").resolve("pom.xml"));
        }
        candidates.add(InstallPaths.getInstallHome().resolve("sources").resolve("pom.xml"));

        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        for (Path pomPath : candidates) {
            try {
                if (!Files.exists(pomPath)) {
                    continue;
                }
                String current = new String(Files.readAllBytes(pomPath), StandardCharsets.UTF_8);
                String next = addOrUpdatePomDependency(current, groupId, artifactId, version);
                if (!current.equals(next)) {
                    Files.write(pomPath, next.getBytes(StandardCharsets.UTF_8));
                    updated.add(pomPath.toString());
                } else {
                    unchanged.add(pomPath.toString());
                }
            } catch (IOException e) {
                logger.warn("Failed to update source POM: " + pomPath, e);
            }
        }

        if (!updated.isEmpty()) {
            return new PomUpdateResult("Updated sources/pom.xml: " + String.join(", ", updated));
        }
        if (!unchanged.isEmpty()) {
            return new PomUpdateResult("sources/pom.xml already contains dependency: " + String.join(", ", unchanged));
        }
        return new PomUpdateResult("sources/pom.xml was not found; add this dependency there before compile_sources if this is for self-update.");
    }

    private static class PomUpdateResult {
        private final String message;

        private PomUpdateResult(String message) {
            this.message = message;
        }
    }

    private boolean isValidArtifact(String artifact) {
        // Only allow lowercase alphanumeric, dots, colons, hyphens
        // Maven artifacts must be lowercase
        if (!artifact.matches("^[a-z0-9.:-]+$")) {
            return false;
        }
        // groupId should start with a valid package prefix (com., org., io., etc.)
        String[] parts = artifact.split(":");
        if (parts.length != 3) {
            return false;
        }
        return true;
    }

    /**
     * Validate groupId format (Maven package naming)
     */
    private boolean isValidGroupId(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            return false;
        }
        // Must start with a known prefix
        if (!groupId.startsWith("com.") && !groupId.startsWith("org.") &&
            !groupId.startsWith("io.") && !groupId.startsWith("net.") &&
            !groupId.startsWith("java.") && !groupId.startsWith("javax.")) {
            return false;
        }
        // All parts should be valid identifiers
        String[] parts = groupId.split("\\.");
        for (String part : parts) {
            if (!part.matches("[a-z][a-z0-9]*")) {
                return false;
            }
        }
        return true;
    }

    private void downloadFile(String url, Path target) throws IOException {
        // Clear stale interrupt flag from previous timeout/interruption
        // (e.g. ToolExecutor timeout -> Future.cancel(true) sets thread interrupt flag,
        //  which causes ClosedByInterruptException on subsequent Files.copy calls)
        Thread.interrupted();

        // Add connection timeout (15s) and read timeout (60s) to prevent hanging on network issues
        java.net.URLConnection conn = new java.net.URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        try (java.io.InputStream in = conn.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}