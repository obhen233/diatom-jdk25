package com.github.obhen233.core.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.obhen233.cli.TerminalUI;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.spi.SpiMetadataReader;
import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.util.I18n;
import com.github.obhen233.util.InstallPaths;
import com.github.obhen233.util.JarUtils;
import com.github.obhen233.util.ProgressSpinner;
import com.github.obhen233.util.SoftwareLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.obhen233.util.JsonUtils;

/**
 * Tools for self-updating diatom-custom.
 * Uses independent file accessor (SelfUpdateFileAccessor) that is
 * completely separated from the main workspace FileTools.
 *
 * In multi-module architecture:
 * - diatom-core is a read-only Maven dependency (upgraded via CoreUpgrader from Maven Central)
 * - diatom-custom is the editable module (sources extracted from custom-sources.jar)
 * - Build targets diatom-custom module, produces diatom-custom.jar
 */
public class SelfUpdateTools {
    private static final Logger logger = LoggerFactory.getLogger(SelfUpdateTools.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();
    private static final String NEWLINE = System.lineSeparator();
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private final SkillManager skillManager;
    private final SystemPromptManager promptManager;

    // Independent file accessor for self-update operations
    private final SelfUpdateFileAccessor fileAccessor;

    // Maven diagnostics collected when availability check fails
    private String mavenDiagnosticInfo = "";

    // Last Maven build output lines for error reporting (captured on failure)
    private String lastBuildOutput = "";

    // Track if Maven offline mode has ever succeeded; if false, try online first
    private static boolean mavenOfflineEverSucceeded = false;

    /**
     * Check if development mode is enabled.
     * Self-update tools require development mode to be enabled.
     */
    private String checkDevelopmentMode() {
        if (!SystemPromptManager.isDevelopmentMode()) {
            return "Error: Development mode is not enabled. Use 'dev' command to enable it first.";
        }
        return null; // null means check passed
    }

    // Path configuration for core and custom
    private final String appHomeDir;
    private final String sourcesDir;
    // Custom directory (contains lib/ with diatom-core-*.jar and dependencies)
    private final String customDir;
    private final String customVersionFile;
    // Backup directory for rollback
    private final String backupDir;

    /**
     * Get the current core jar path dynamically.
     * The core jar is located in ~/.diatom/custom/lib/ and named diatom-core-{version}.jar
     */
    private String getCoreCurrentJar() {
        Path libDir = Paths.get(customDir, "lib");
        Path coreJar = JarUtils.findCoreJar(libDir);
        if (coreJar != null && Files.exists(coreJar)) {
            return coreJar.toString();
        }
        // Fallback: try to find any diatom-core-*.jar in lib directory
        try {
            if (Files.exists(libDir)) {
                java.util.Optional<Path> coreFile = Files.list(libDir)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> p.getFileName().toString().startsWith("diatom-core-"))
                    .findFirst();
                if (coreFile.isPresent()) {
                    return coreFile.get().toString();
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to list lib directory", e);
        }
        // Last resort fallback - this should rarely be hit
        return libDir.resolve("diatom-core-1.0.0.jar").toString();
    }

    /**
     * Get Maven local repository path from settings.xml, with fallback to default.
     * Uses SoftwareLocator to find Maven installation first, then looks for settings.xml
     * in Maven's conf directory or ~/.m2/.
     */
    private Path getMavenLocalRepository() {
        // Default location
        Path defaultRepo = Paths.get(System.getProperty("user.home"), ".m2", "repository");

        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            Path mavenHome = null;

            // Find Maven installation using SoftwareLocator
            if (isWindows) {
                SoftwareLocator.MavenInfo mavenInfo = SoftwareLocator.findMavenOnWindows().orElse(null);
                if (mavenInfo != null) {
                    mavenHome = Paths.get(mavenInfo.getHomePath());
                }
            } else {
                java.util.Optional<Path> mavenPath = SoftwareLocator.findInstallation("mvn");
                if (mavenPath.isPresent()) {
                    mavenHome = mavenPath.get();
                }
            }

            // Try to find settings.xml in Maven's conf directory first
            Path settingsFile = null;
            if (mavenHome != null) {
                settingsFile = mavenHome.resolve("conf").resolve("settings.xml");
                if (!Files.exists(settingsFile)) {
                    settingsFile = null;
                }
            }

            // Fallback to ~/.m2/settings.xml
            if (settingsFile == null) {
                Path userSettings = Paths.get(System.getProperty("user.home"), ".m2", "settings.xml");
                if (Files.exists(userSettings)) {
                    settingsFile = userSettings;
                }
            }

            if (settingsFile == null) {
                logger.debug("Maven settings.xml not found, using default local repository: {}", defaultRepo);
                return defaultRepo;
            }

            // Parse settings.xml to find localRepository element
            // First, remove all XML comments to avoid matching commented-out elements
            String content = new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8);
            content = content.replaceAll("(?s)<!--.*?-->", "");

            int localRepoIndex = content.indexOf("<localRepository>");
            if (localRepoIndex == -1) {
                logger.debug("No <localRepository> element in settings.xml, using default: {}", defaultRepo);
                return defaultRepo;
            }

            int start = localRepoIndex + "<localRepository>".length();
            int end = content.indexOf("</localRepository>", start);
            if (end == -1) {
                logger.debug("Malformed <localRepository> element in settings.xml, using default: {}", defaultRepo);
                return defaultRepo;
            }

            String localRepoPath = content.substring(start, end).trim();
            if (localRepoPath.isEmpty()) {
                logger.debug("Empty <localRepository> in settings.xml, using default: {}", defaultRepo);
                return defaultRepo;
            }

            // Normalize path for Windows - convert Unix-style paths like /c/Users to C:/Users
            if (isWindows && localRepoPath.startsWith("/")) {
                // Convert /c/path to C:/path format for Windows
                if (localRepoPath.length() >= 3 && localRepoPath.charAt(2) == '/') {
                    char driveLetter = Character.toUpperCase(localRepoPath.charAt(1));
                    localRepoPath = driveLetter + ":" + localRepoPath.substring(2);
                }
            }

            Path repoPath = Paths.get(localRepoPath);
            logger.debug("Found Maven local repository in settings.xml: {}", repoPath);
            return repoPath;
        } catch (Exception e) {
            logger.warn("Failed to parse Maven settings.xml, using default: {}", e.getMessage());
            return defaultRepo;
        }
    }

    public SelfUpdateTools(SkillManager skillManager, SystemPromptManager promptManager) {
        this.skillManager = skillManager;
        this.promptManager = promptManager;
        this.fileAccessor = new SelfUpdateFileAccessor();
        this.sourcesDir = fileAccessor.getSourcesDir();
        this.appHomeDir = InstallPaths.getInstallHome().toString();
        this.customDir = InstallPaths.getCustomDir().toString();
        this.customVersionFile = Paths.get(customDir, "custom-version.txt").toString();
        this.backupDir = InstallPaths.getBackupDir().toString();
    }

    public SelfUpdateTools() {
        this.skillManager = null;
        this.promptManager = null;
        this.fileAccessor = new SelfUpdateFileAccessor();
        this.sourcesDir = fileAccessor.getSourcesDir();
        this.appHomeDir = InstallPaths.getInstallHome().toString();
        this.customDir = InstallPaths.getCustomDir().toString();
        this.customVersionFile = Paths.get(customDir, "custom-version.txt").toString();
        this.backupDir = InstallPaths.getBackupDir().toString();
    }

    /**
     * Get the file accessor for external use (e.g., by SourceTreeTools).
     */
    public SelfUpdateFileAccessor getFileAccessor() {
        return fileAccessor;
    }

    @ToolMethod(name = "update_system_prompt",
                description = "Update the AI's system prompt. Use with caution.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"content\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                riskLevel = "high")
    public String updateSystemPrompt(String content) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            if (promptManager != null) {
                promptManager.updatePrompt(content);
                return I18n.get("self_update_prompt_success");
            }
            return I18n.get("self_update_prompt_not_init");
        } catch (Exception e) {
            logger.error("Error updating system prompt", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "create_skill",
                description = "Create a new skill file",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"content\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String createSkill(String argsJson) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            JsonNode obj = mapper.readTree(argsJson);
            String name = obj.get("name").asText();
            String content = obj.get("content").asText();

            if (skillManager != null) {
                skillManager.createSkill(name, content);
                return I18n.get("self_update_skill_created") + name;
            }
            return I18n.get("self_update_skill_manager_not_init");
        } catch (Exception e) {
            logger.error("Error creating skill", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "update_skill",
                description = "Update an existing skill file",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"content\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String updateSkill(String argsJson) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            JsonNode obj = mapper.readTree(argsJson);
            String name = obj.get("name").asText();
            String content = obj.get("content").asText();

            if (skillManager != null) {
                skillManager.updateSkill(name, content);
                return I18n.get("self_update_skill_updated") + name;
            }
            return I18n.get("self_update_skill_manager_not_init");
        } catch (Exception e) {
            logger.error("Error updating skill", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "read_skill",
                description = "Read a skill file by name",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}",
                readOnly = true)
    public String readSkill(String name) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        if (skillManager != null) {
            return skillManager.getSkillContent(name);
        }
        return I18n.get("self_update_skill_manager_not_init");
    }

    @ToolMethod(name = "list_skills",
                description = "List all available skills",
                parametersSchema = "{}",
                readOnly = true)
    public String listSkills() {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        if (skillManager != null) {
            return skillManager.listSkills();
        }
        return I18n.get("self_update_skill_manager_not_init");
    }

    @ToolMethod(name = "extract_sources",
                description = "Extract custom sources to sources/. REQUIRED before write_source_file/compile_sources. Only custom module (core is read-only).",
                parametersSchema = "{}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String extractSources(String argsJson) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            String baseDir = System.getProperty("diatom.jar.dir", System.getProperty("user.dir"));
            Path targetDir = Paths.get(sourcesDir);

            // Skip if sources already exist and have content
            if (Files.exists(targetDir) && hasSourceFiles(targetDir)) {
                return "Sources are already extracted at: " + targetDir.toAbsolutePath();
            }

            // Locate diatom-cli.jar (the executable JAR containing source jars)
            Path execJar = Paths.get(baseDir, "diatom-cli.jar");
            if (!Files.exists(execJar)) {
                execJar = Paths.get(customDir, "diatom-cli.jar");
            }
            if (!Files.exists(execJar)) {
                return "diatom-cli.jar not found in " + baseDir + " or " + customDir + NEWLINE +
                       "Cannot locate source jars.";
            }

            // Clean and create sources directory
            if (Files.exists(targetDir)) {
                deleteDirectory(targetDir);
            }
            Files.createDirectories(targetDir);

            // Extract custom-sources.jar from diatom-cli.jar's sources/ directory
            extractSourceJarFromExec(execJar, "sources/custom-sources.jar", targetDir, "custom-sources.jar");

            // Extract sources from custom-sources.jar
            Path customSourcesJar = targetDir.resolve("custom-sources.jar");
            if (Files.exists(customSourcesJar)) {
                extractSourcesFromJar(customSourcesJar, targetDir);
                Files.delete(customSourcesJar); // Clean up the jar file after extraction
            } else {
                return "custom-sources.jar not found inside diatom-cli.jar";
            }

            // Extract core SPI metadata from the current diatom-core JAR in lib/
            extractSpiMetadataFromCoreJar(targetDir);

            return "Custom module sources extracted successfully to: " + targetDir.toAbsolutePath() + NEWLINE +
                   "Core SPI metadata extracted to: " + targetDir.resolve("core-spi.json").toAbsolutePath() + " (read-only)" + NEWLINE +
                   "You can now use write_source_file, replace_source_in_file, and compile_sources." + NEWLINE +
                   "Note: Only custom module sources are editable. Core SPI metadata is read-only reference.";
        } catch (Exception e) {
            logger.error("Error extracting sources", e);
            return I18n.get("error") + ": " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    /**
     * Extract SPI metadata JSON from the current diatom-core JAR in lib/.
     */
    private void extractSpiMetadataFromCoreJar(Path targetDir) throws IOException {
        Path coreJarPath = Paths.get(getCoreCurrentJar());
        if (!Files.exists(coreJarPath)) {
            logger.warn("diatom-core JAR not found at {}, SPI metadata will not be extracted", coreJarPath);
            return;
        }

        try {
            SpiMetadataReader.SpiMetadata metadata = SpiMetadataReader.loadFromJar(coreJarPath).getMetadata();
            ObjectMapper mapper = JsonUtils.getMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            String json = mapper.writeValueAsString(metadata);

            Path spiJsonFile = targetDir.resolve("core-spi.json");
            Files.write(spiJsonFile, json.getBytes(StandardCharsets.UTF_8));
            logger.info("Extracted SPI metadata to: {}", spiJsonFile);
        } catch (Exception e) {
            logger.warn("Failed to extract SPI metadata: {}", e.getMessage());
        }
    }

    /**
     * Extract a source jar from inside the executable JAR to the target directory.
     */
    private void extractSourceJarFromExec(Path execJar, String entryPath, Path targetDir, String outputName) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(execJar.toFile())) {
            java.util.jar.JarEntry entry = jar.getJarEntry(entryPath);
            if (entry != null) {
                Path outputPath = targetDir.resolve(outputName);
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, outputPath, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Extracted {} from {} to {}", entryPath, execJar, outputPath);
                }
            } else {
                logger.warn("{} not found inside {}", entryPath, execJar);
            }
        }
    }

    @ToolMethod(name = "upgrade_core",
                description = "[upgrade] Check for core update and enter dev mode for compatibility check. Use when core has a new version. In dev mode, model can check if new core is compatible with current custom code. Use mark_core_pending when ready.",
                parametersSchema = "{}",
                requiresConfirmation = true,
                riskLevel = "high")
    public String upgradeCore(String argsJson) {
        try {
            // Check if already in core upgrade check mode
            if (isInCoreUpgradeCheck()) {
                return I18n.get("self_update_already_in_dev_mode");
            }

            // Check Maven Central for latest core version
            String latestVersion = checkLatestCoreVersion();
            if (latestVersion == null) {
                return I18n.get("self_update_core_check_failed");
            }

            String currentVersion = getCurrentCoreVersion();
            if (latestVersion.equals(currentVersion)) {
                return I18n.get("self_update_core_up_to_date", currentVersion);
            }

            // Download new core to temp for compatibility check
            String downloadedPath = downloadCoreForCheck(latestVersion);
            if (downloadedPath == null) {
                return I18n.get("self_update_core_download_failed");
            }

            // Enter dev mode for model to do compatibility check
            enterDevMode();

            return I18n.get("self_update_core_available", latestVersion, currentVersion) + NEWLINE +
                   I18n.get("self_update_core_dev_mode_entered", downloadedPath) + NEWLINE +
                   I18n.get("self_update_core_check_tip");

        } catch (Exception e) {
            logger.error("Error checking core upgrade", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "mark_core_pending",
                description = "[upgrade] Mark core as pending update after compatibility check. Call this when new core is compatible with current custom code. Core will be updated on next restart.",
                parametersSchema = "{}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String markCorePendingTool(String argsJson) {
        try {
            if (!isInCoreUpgradeCheck()) {
                return I18n.get("self_update_not_in_dev_mode");
            }

            String pendingVersion = getPendingCoreVersion();
            markCorePending(pendingVersion);
            // Exit core upgrade check mode
            exitDevMode();

            return I18n.get("self_update_core_marked_pending", pendingVersion != null ? pendingVersion : "latest");

        } catch (Exception e) {
            logger.error("Error marking core pending", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "compile_sources",
                description = "[build] Compile custom→diatom-custom.jar. REQUIRED after write_source_file. Auto-extracts, builds with Maven, updates JAR, restarts.",
                parametersSchema = "{}",
                requiresConfirmation = true,
                riskLevel = "high")
    public String compileSources(String argsJson) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            String baseDir = System.getProperty("diatom.jar.dir", System.getProperty("user.dir"));
            Path userDir = Paths.get(System.getProperty("user.dir"));
            Path buildDir;
            boolean extractSources = false;

            // Priority 1: Use existing sources directory if it has content (written by write_source_file)
            Path diatomSourcesDir = Paths.get(sourcesDir);
            if (Files.exists(diatomSourcesDir) && hasSourceFiles(diatomSourcesDir)) {
                logger.info("Using existing sources from: " + diatomSourcesDir);
                buildDir = diatomSourcesDir;
            } else if (Files.exists(userDir.resolve("src/main/java"))) {
                // Priority 2: Development mode - use the current project structure
                logger.info("Development mode detected, using working directory");
                buildDir = userDir;
            } else {
                // Priority 3: Production mode - extract sources from custom-sources.jar
                buildDir = diatomSourcesDir;
                extractSources = true;

                // Find sources JAR: try custom first, then legacy
                Path sourcesJar = findCustomSourcesJar(baseDir);
                if (sourcesJar == null) {
                    return I18n.get("self_update_no_sources") + NEWLINE +
                           I18n.get("self_update_no_sources_locations", userDir.toString(), customDir) + NEWLINE +
                           I18n.get("self_update_no_sources_tip");
                }

                // Clean and create sources directory
                if (Files.exists(buildDir)) {
                    deleteDirectory(buildDir);
                }
                Files.createDirectories(buildDir);

                // Extract sources from JAR
                logger.info("Extracting sources from " + sourcesJar + " to " + buildDir);
                extractSourcesFromJar(sourcesJar, buildDir);
            }

            // Check if pom.xml exists and is valid in build directory
            Path pomPath = buildDir.resolve("pom.xml");
            if (!Files.exists(pomPath)) {
                if (extractSources) {
                    deleteDirectory(buildDir);
                }
                return I18n.get("self_update_no_pom") + NEWLINE +
                       "The custom-sources.jar should contain pom.xml at the root level. " +
                       "Please ensure you have a valid sources JAR built by Maven.";
            }
            // Validate pom.xml starts with valid XML declaration (guard against JAR corruption)
            byte[] pomHeader = new byte[5];
            try (java.io.InputStream is = Files.newInputStream(pomPath)) {
                int read = is.read(pomHeader);
                String headerStr = new String(pomHeader, 0, read, StandardCharsets.UTF_8);
                if (!headerStr.startsWith("<?xml")) {
                    logger.warn("pom.xml appears corrupted (starts with '{}'), re-extracting from custom-sources.jar", headerStr);
                    if (extractSources) {
                        // Re-extract from the sources JAR
                        Path sourcesJar = findCustomSourcesJar(baseDir);
                        if (sourcesJar != null) {
                            extractSourcesFromJar(sourcesJar, buildDir);
                        }
                    }
                }
            } catch (IOException e) {
                logger.warn("Failed to validate pom.xml, continuing anyway: {}", e.getMessage());
            }

            // Ensure custom directory exists
            Files.createDirectories(Paths.get(customDir));

            // Check if Maven is available
            if (!isMavenAvailable()) {
                String diagMsg = mavenDiagnosticInfo.isEmpty() ? "" : NEWLINE + mavenDiagnosticInfo;
                return I18n.get("self_update_maven_not_found") + diagMsg + NEWLINE +
                       I18n.get("self_update_maven_options") + NEWLINE +
                       I18n.get("self_update_maven_option1") + NEWLINE +
                       I18n.get("self_update_maven_option2") + NEWLINE +
                       I18n.get("self_update_maven_option3");
            }

            // Build the project using Maven
            // Backup sources first in case we need to rollback later (model will decide)
            Path sourcesBackup = backupSources();
            if (sourcesBackup != null) {
                // Increment and save build failure count
                incrementBuildFailureCount();
            }

            String buildResult;
            try {
                buildResult = runMavenBuild(buildDir);
            } catch (BuildFailedException e) {
                // Build failed - do NOT auto rollback, let model try to fix
                // Just notify the model about the failure and current failure count
                int failureCount = getBuildFailureCount();

                return I18n.get("self_update_build_failed") + NEWLINE +
                       I18n.get("self_update_build_failure_count", String.valueOf(failureCount)) + NEWLINE +
                       I18n.get("self_update_build_fix_tip") + NEWLINE +
                       e.getMessage();
            }

            // Build succeeded — set cleanup marker so next App startup removes extracted sources
            setCleanupMarker(baseDir);

            // Reset build failure count on success
            resetBuildFailureCount();

            // Mark custom as pending update (will be applied on restart)
            markCustomPending();

            // Update is now staged - will be applied on next startup (no automatic restart)
            // User can continue working normally, or exit and restart to apply the update immediately

            return I18n.get("self_update_title") + NEWLINE +
                   I18n.get("self_update_subtitle") + NEWLINE +
                   I18n.get("self_update_separator") + NEWLINE +
                   NEWLINE +
                   buildResult + NEWLINE +
                   NEWLINE +
                   I18n.get("self_update_staged_tip") + NEWLINE +
                   NEWLINE +
                   I18n.get("self_update_upgrade_complete_title") + NEWLINE +
                   I18n.get("self_update_feature_context") + NEWLINE +
                   I18n.get("self_update_feature_skills") + NEWLINE +
                   I18n.get("self_update_feature_undo") + NEWLINE +
                   I18n.get("self_update_separator");
        } catch (Exception e) {
            logger.error("Error compiling sources", e);
            return I18n.get("error") + ": " + e.getClass().getSimpleName() + " - " + e.getMessage() + NEWLINE +
                   "user.dir: " + System.getProperty("user.dir") + NEWLINE +
                   "MVN_HOME: " + (System.getenv("MVN_HOME") != null ? System.getenv("MVN_HOME") : "(not set)") + NEWLINE +
                   "JAVA_HOME: " + (System.getProperty("java.home") != null ? System.getProperty("java.home") : "(not set)");
        }
    }

    /**
     * Extract source files from custom-sources.jar to target directory.
     * Also extracts pom.xml from META-INF/maven/ for Maven build.
     */
    private void extractSourcesFromJar(Path sourceJar, Path targetDir) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(sourceJar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // Extract pom.xml from META-INF/maven/ to root directory
                // Maven sources jar puts pom.xml in META-INF/maven/<groupId>/<artifactId>/pom.xml
                if (entryName.startsWith("META-INF/maven/") && entryName.endsWith("pom.xml")) {
                    // Copy pom.xml to root of target directory
                    Path pomTarget = targetDir.resolve("pom.xml");
                    Files.createDirectories(pomTarget.getParent());
                    try (InputStream is = jar.getInputStream(entry)) {
                        Files.copy(is, pomTarget, StandardCopyOption.REPLACE_EXISTING);
                    }
                    logger.info("Extracted pom.xml from {} to {}", entryName, pomTarget);
                    continue;
                }

                // Skip META-INF entries except we need to create directories if they're empty
                // Empty directories in JAR have no files to trigger their creation
                if (entryName.startsWith("META-INF")) {
                    if (entry.isDirectory()) {
                        // Create empty META-INF subdirectories that have no files
                        String platformEntryName = entryName.replace('/', File.separatorChar);
                        Path targetPath = targetDir.resolve(platformEntryName);
                        Files.createDirectories(targetPath);
                    }
                    // Skip file entries in META-INF (like MANIFEST.MF, signatures, etc.)
                    continue;
                }

                // JAR entries use '/' (ZIP format specification) but Path.resolve() handles
                // this correctly on all platforms, so we pass the entry name directly
                if (entry.isDirectory()) {
                    Path targetPath = targetDir.resolve(entryName);
                    Files.createDirectories(targetPath);
                } else {
                    // Validate path BEFORE resolution for defense-in-depth
                    // First normalize base directory
                    Path canonicalBase = targetDir.toAbsolutePath().normalize();
                    // Then resolve entry name (Path.resolve handles '/' correctly)
                    Path targetPath = canonicalBase.resolve(entryName);
                    // Final validation - ensure resolved path is still within base
                    Path canonicalTarget = targetPath.normalize();
                    if (!canonicalTarget.startsWith(canonicalBase)) {
                        throw new IOException("Path traversal attempt detected: " + entryName);
                    }
                    Files.createDirectories(targetPath.getParent());
                    try (InputStream is = jar.getInputStream(entry)) {
                        Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        logger.info("Sources extracted to: " + targetDir);
    }

    /**
     * Recursively delete a directory
     */
    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            java.util.stream.Stream<Path> walk = Files.walk(dir);
            walk.sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        logger.warn("Failed to delete: " + p);
                    }
                });
        } catch (IOException e) {
            logger.warn("Failed to delete directory: " + dir, e);
        }
    }

    /**
     * Write a cleanup marker file so the next App startup removes extracted sources.
     * The marker contains the base directory whose sources/ should be deleted.
     */
    private void setCleanupMarker(String baseDir) {
        try {
            Path markerPath = InstallPaths.getInstallHome().resolve(".cleanup-pending");
            Files.createDirectories(markerPath.getParent());
            Files.write(markerPath, baseDir.getBytes(StandardCharsets.UTF_8));
            logger.info("Cleanup marker set at: {}", markerPath);
        } catch (IOException e) {
            logger.warn("Failed to set cleanup marker", e);
        }
    }

    /**
     * Save the custom version after successful compilation.
     * Uses timestamp as version identifier for each compilation.
     */
    private void saveCustomVersion() {
        try {
            Path versionFilePath = Paths.get(customVersionFile);
            String currentVersion = "1.0.0";

            // Read current version if exists
            if (Files.exists(versionFilePath)) {
                try {
                    currentVersion = new String(Files.readAllBytes(versionFilePath), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    logger.warn("Failed to read current custom version, starting from 1.0.0", e);
                }
            }

            // Increment semantic version (patch level)
            String newVersion = incrementVersion(currentVersion);

            Files.write(versionFilePath, newVersion.getBytes(StandardCharsets.UTF_8));
            logger.info("Custom version saved: " + newVersion);
        } catch (IOException e) {
            logger.warn("Failed to save custom version", e);
        }
    }

    /**
     * Increment semantic version (patch level)
     */
    private String incrementVersion(String version) {
        try {
            String[] parts = version.split("\\.");
            if (parts.length >= 3) {
                int patch = Integer.parseInt(parts[2]) + 1;
                return parts[0] + "." + parts[1] + "." + patch;
            }
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse version, returning 1.0.1", e);
        }
        return "1.0.1";
    }

    /**
     * Enter development mode for core upgrade compatibility check.
     * Enables source modification tools and notifies the model.
     * Uses separate flag from user's manual dev mode.
     */
    private void enterDevMode() {
        System.setProperty("diatom.core.upgrade.check", "true");
        SystemPromptManager.enableDevelopmentMode();
        logger.info("Entered core upgrade compatibility check mode");
    }

    /**
     * Exit core upgrade check mode.
     * Does NOT disable development mode - that is controlled by user's quit dev command.
     */
    private void exitDevMode() {
        System.setProperty("diatom.core.upgrade.check", "false");
        logger.info("Exited core upgrade check mode");

        // Check if there are pending updates to apply
        if (hasCorePending()) {
            logger.info("Core update pending, will be applied on restart");
        }
        if (hasCustomPending()) {
            logger.info("Custom update pending, will be applied on restart");
        }
    }

    /**
     * Check if running in core upgrade compatibility check mode.
     * This is separate from the user's manual dev mode.
     */
    private boolean isInCoreUpgradeCheck() {
        return "true".equals(System.getProperty("diatom.core.upgrade.check"));
    }

    /**
     * Mark core as pending update (core will be updated on next restart).
     */
    private void markCorePending(String newVersion) {
        try {
            Path markerPath = Paths.get(appHomeDir, "core-update-pending.marker");
            Files.createDirectories(markerPath.getParent());
            Files.write(markerPath, (newVersion != null ? newVersion : "").getBytes(StandardCharsets.UTF_8));
            logger.info("Core pending update marker set: {}", newVersion);
        } catch (IOException e) {
            logger.warn("Failed to set core pending marker", e);
        }
    }

    /**
     * Mark custom as pending update (custom will be updated on next restart).
     */
    private void markCustomPending() {
        try {
            Path customDirPath = Paths.get(customDir);
            Path markerPath = customDirPath.resolve("custom-update-pending.marker");
            Files.createDirectories(customDirPath);
            Files.write(markerPath, "1".getBytes(StandardCharsets.UTF_8));
            logger.info("Custom pending update marker set");
        } catch (IOException e) {
            logger.warn("Failed to set custom pending marker", e);
        }
    }

    /**
     * Get the current core version from version file.
     */
    private String getCurrentCoreVersion() {
        try {
            Path versionFile = Paths.get(appHomeDir, "core-version");
            if (Files.exists(versionFile)) {
                return new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            logger.warn("Failed to read current core version", e);
        }
        return "";
    }

    /**
     * Check Maven Central for the latest core version.
     */
    private String checkLatestCoreVersion() {
        try {
            java.net.URL url = new java.net.URL("https://repo1.maven.org/maven2/com/github/obhen233/diatom-core/maven-metadata.xml");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    String releaseVersion = null;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("<release>")) {
                            releaseVersion = line.substring("<release>".length(),
                                    line.indexOf("</release>")).trim();
                            break;
                        }
                    }
                    if (releaseVersion != null && !releaseVersion.isEmpty()) {
                        logger.info("Latest core version on Maven Central: {}", releaseVersion);
                        return releaseVersion;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to check Maven Central for core version: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Download core JAR to temp location for compatibility check.
     */
    private String downloadCoreForCheck(String version) {
        try {
            String jarUrl = "https://repo1.maven.org/maven2/com/github/obhen233/diatom-core/" + version + "/diatom-core-" + version + ".jar";
            java.net.URL url = new java.net.URL(jarUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            if (conn.getResponseCode() == 200) {
                Path tempFile = Files.createTempFile("diatom-core-", ".jar");
                try (java.io.InputStream in = conn.getInputStream()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
                logger.info("Downloaded core for check: {} -> {}", version, tempFile);
                return tempFile.toString();
            }
        } catch (Exception e) {
            logger.error("Failed to download core: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check if core has pending update.
     */
    private boolean hasCorePending() {
        Path markerPath = Paths.get(appHomeDir, "core-update-pending.marker");
        return Files.exists(markerPath);
    }

    /**
     * Check if custom has pending update.
     */
    private boolean hasCustomPending() {
        Path markerPath = Paths.get(customDir, "custom-update-pending.marker");
        return Files.exists(markerPath);
    }

    /**
     * Get the pending core version, or null if no pending update.
     */
    private String getPendingCoreVersion() {
        try {
            Path markerPath = Paths.get(appHomeDir, "core-update-pending.marker");
            if (Files.exists(markerPath)) {
                return new String(Files.readAllBytes(markerPath), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            logger.warn("Failed to read core pending version", e);
        }
        return null;
    }

    /**
     * Backup the sources directory before a potentially destructive operation.
     * Creates a timestamped backup in ~/.diatom/backup/sources-{timestamp}/
     * @return the path to the backup directory, or null if backup failed
     */
    private Path backupSources() {
        try {
            Path sourcesPath = Paths.get(sourcesDir);
            if (!Files.exists(sourcesPath)) {
                logger.info("No sources directory to backup");
                return null;
            }

            String timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backupPath = Paths.get(backupDir, "sources-" + timestamp);

            Files.createDirectories(backupPath.getParent());
            copyDirectory(sourcesPath, backupPath);
            logger.info("Sources backed up to: {}", backupPath);

            // Write backup marker for rollback tracking
            Path markerPath = Paths.get(backupDir, ".latest-backup");
            Files.write(markerPath, backupPath.toString().getBytes(StandardCharsets.UTF_8));

            return backupPath;
        } catch (IOException e) {
            logger.warn("Failed to backup sources: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Rollback sources from the latest backup.
     * @return true if rollback succeeded
     */
    private boolean rollbackSources() {
        try {
            Path markerPath = Paths.get(backupDir, ".latest-backup");
            if (!Files.exists(markerPath)) {
                logger.warn("No backup marker found, cannot rollback");
                return false;
            }

            String backupPathStr = new String(Files.readAllBytes(markerPath), StandardCharsets.UTF_8).trim();
            Path backupPath = Paths.get(backupPathStr);

            if (!Files.exists(backupPath)) {
                logger.warn("Backup directory not found: {}", backupPath);
                return false;
            }

            Path sourcesPath = Paths.get(sourcesDir);

            // Delete current sources
            if (Files.exists(sourcesPath)) {
                deleteDirectory(sourcesPath);
            }
            Files.createDirectories(sourcesPath);

            // Restore from backup
            copyDirectory(backupPath, sourcesPath);
            logger.info("Sources rolled back from: {}", backupPath);

            // Clean up backup marker
            Files.deleteIfExists(markerPath);

            return true;
        } catch (IOException e) {
            logger.error("Failed to rollback sources: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the build failure count file path.
     */
    private Path getBuildFailureCountFile() {
        return Paths.get(backupDir, ".build-failure-count");
    }

    /**
     * Get current build failure count.
     */
    private int getBuildFailureCount() {
        try {
            Path countFile = getBuildFailureCountFile();
            if (Files.exists(countFile)) {
                String count = new String(Files.readAllBytes(countFile), StandardCharsets.UTF_8).trim();
                return Integer.parseInt(count);
            }
        } catch (Exception e) {
            logger.warn("Failed to read build failure count", e);
        }
        return 0;
    }

    /**
     * Increment build failure count.
     */
    private void incrementBuildFailureCount() {
        try {
            int count = getBuildFailureCount();
            Path countFile = getBuildFailureCountFile();
            Files.createDirectories(countFile.getParent());
            Files.write(countFile, String.valueOf(count + 1).getBytes(StandardCharsets.UTF_8));
            logger.info("Build failure count: {}", count + 1);
        } catch (IOException e) {
            logger.warn("Failed to increment build failure count", e);
        }
    }

    /**
     * Reset build failure count.
     */
    private void resetBuildFailureCount() {
        try {
            Path countFile = getBuildFailureCountFile();
            Files.deleteIfExists(countFile);
            logger.info("Build failure count reset");
        } catch (IOException e) {
            logger.warn("Failed to reset build failure count", e);
        }
    }

    /**
     * Check if a backup exists.
     */
    private boolean hasBackup() {
        Path markerPath = Paths.get(backupDir, ".latest-backup");
        return Files.exists(markerPath);
    }

    /**
     * Rollback sources from backup. Called by model when compilation keeps failing.
     * Threshold: after 3 failures, user will be prompted to confirm rollback.
     */
    @ToolMethod(name = "rollback_sources",
                description = "[development] Rollback sources to the backup state. Use when compilation keeps failing and you cannot fix the issues. This will restore the sources to the state before the last compile attempt.",
                parametersSchema = "{}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String rollbackSourcesTool(String argsJson) {
        try {
            if (!hasBackup()) {
                return I18n.get("self_update_no_backup");
            }

            boolean rolledBack = rollbackSources();
            if (rolledBack) {
                resetBuildFailureCount();
                return I18n.get("self_update_rollback_success");
            } else {
                return I18n.get("self_update_rollback_failed");
            }
        } catch (Exception e) {
            logger.error("Error rolling back sources", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    /**
     * Recursively search for pom.xml in directory tree.
     * Returns the first pom.xml found, or null if none exists.
     */
    private Path findPomFile(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return null;
        }
        
        try {
            java.util.stream.Stream<Path> walk = Files.walk(dir, 3); // Limit depth to avoid deep search
            java.util.Optional<Path> pomFile = walk
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .findFirst();
            walk.close();
            return pomFile.orElse(null);
        } catch (IOException e) {
            logger.warn("Error searching for pom.xml in: " + dir, e);
            return null;
        }
    }

    /**
     * Check if directory contains source files (pom.xml or src/main/java)
     */
    private boolean hasSourceFiles(Path dir) {
        if (!Files.exists(dir)) return false;
        if (Files.exists(dir.resolve("pom.xml"))) return true;
        if (Files.exists(dir.resolve("src/main/java"))) return true;
        return false;
    }

    /**
     * Copy directory contents recursively
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(src -> {
            try {
                Path relative = source.relativize(src);
                Path dest = target.resolve(relative);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.warn("Failed to copy: " + src + " -> " + target);
            }
        });
    }

    @ToolMethod(name = "install_maven",
                description = "Install Maven automatically or set custom Maven path",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"action\": {\"type\": \"string\"}, \"path\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String installMaven(String argsJson) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            JsonNode obj = mapper.readTree(argsJson);
            String action = obj.get("action").asText();
            String customPath = obj.has("path") ? obj.get("path").asText() : null;

            if ("auto".equals(action)) {
                return autoInstallMaven();
            } else if ("path".equals(action) && customPath != null) {
                return setMavenPath(customPath);
            } else {
                return I18n.get("self_update_maven_usage");
            }
        } catch (Exception e) {
            logger.error("Error installing Maven", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "get_sources_path",
                description = "Get the path to the custom sources JAR (custom-sources.jar, contains source code for compilation)",
                parametersSchema = "{}",
                readOnly = true)
    public String getSourcesPath() {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        String baseDir = System.getProperty("diatom.jar.dir", System.getProperty("user.dir"));
        // Return path to custom-sources.jar (next to diatom-custom.jar or in core/ directory)
        Path sourcesJar = findCustomSourcesJar(baseDir);
        if (sourcesJar != null) return sourcesJar.toString();
        return Paths.get(baseDir, "custom-sources.jar").toString();
    }

    @ToolMethod(name = "get_custom_path",
                description = "Get the path to the current custom directory",
                parametersSchema = "{}",
                readOnly = true)
    public String getCustomPath() {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        return customDir;
    }

    @ToolMethod(name = "restart_application",
                description = "Restart the application to apply updates. After compile_sources completes, this is called automatically to restart with the new diatom-custom.jar.",
                parametersSchema = "{}",
                requiresConfirmation = true,
                riskLevel = "high")
    public String restartApplication() {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;

        try {
            // Check if there's a pending update
            Path appHome = InstallPaths.getInstallHome();
            Path customDir = InstallPaths.getCustomDir();
            Path updateMarker = customDir.resolve("custom-current.jar.update-pending.marker");
            boolean hasPending = Files.exists(updateMarker);

            if (hasPending) {
                return I18n.get("self_update_restart_pending");
            } else {
                return I18n.get("self_update_restart_no_update");
            }

        } catch (Exception e) {
            logger.error("Error restarting", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "write_source_file",
                description = "[self-update] Write diatom-cli source. For OTHER projects use write_file. Path: relative under com/github/obhen233/custom/..., src/main/java/ is auto-prepended. Organize by function into sub-packages (e.g. custom/excel/, custom/db/, custom/tool/). sources/ prefix auto-stripped.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"content\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String writeSourceFile(String argsJson) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            JsonNode obj = mapper.readTree(argsJson);
            String relativePath = obj.get("path").asText();
            String content = obj.get("content").asText();

            // Use the independent file accessor
            // It handles sources/ prefix automatically and validates security
            return fileAccessor.writeSourceFile(relativePath, content);
        } catch (Exception e) {
            logger.error("Error writing source file", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "read_source_file",
                description = "[self-update] Read diatom source. For OTHER projects → use read_file. Path: RELATIVE only, sources/ prefix auto-stripped.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}",
                readOnly = true)
    public String readSourceFile(String argsJson) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            String relativePath = extractPathArg(argsJson);

            // Use the independent file accessor
            // It handles sources/ prefix automatically and validates security
            return fileAccessor.readSourceFile(relativePath);
        } catch (Exception e) {
            logger.error("Error reading source file", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    /**
     * Delete a source file using Java's Files.deleteIfExists() instead of shell rm command.
     * This is the SAFE way to delete files - it does NOT use shell commands.
     */
    @ToolMethod(name = "delete_source_file",
                description = "[self-update] Delete diatom source file. SAFE alternative to shell rm. Path: RELATIVE only.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String deleteSourceFile(String argsJson) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            String relativePath = extractPathArg(argsJson);

            // Use the independent file accessor for security validation
            Path targetPath = Paths.get(sourcesDir, relativePath);

            // Security check: ensure path is within sources directory
            if (!targetPath.toAbsolutePath().normalize().startsWith(Paths.get(sourcesDir).toAbsolutePath().normalize())) {
                return I18n.get("error") + ": Path outside sources directory not allowed: " + relativePath;
            }

            if (Files.deleteIfExists(targetPath)) {
                logger.info("Deleted source file: {}", targetPath);
                return "Deleted: " + relativePath;
            } else {
                return "File not found (already deleted or didn't exist): " + relativePath;
            }
        } catch (Exception e) {
            logger.error("Error deleting source file", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "replace_source_in_file",
                description = "[self-update] Replace string in diatom source. old_str→new_str. For OTHER projects → use replace_in_file. Path: RELATIVE only, with full package path under src/main/java/, sources/ prefix auto-stripped.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"old_str\": {\"type\": \"string\"}, \"new_str\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                riskLevel = "medium")
    public String replaceSourceInFile(String argsJson) {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            JsonNode obj = mapper.readTree(argsJson);
            String path = obj.get("path").asText();
            String oldStr = obj.get("old_str").asText();
            String newStr = obj.get("new_str").asText();

            if (oldStr == null || oldStr.isEmpty()) {
                return "Error: old_str cannot be empty. Please provide the exact text to replace.";
            }

            return fileAccessor.replaceSourceFile(path, oldStr, newStr);
        } catch (Exception e) {
            logger.error("Error replacing source file", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    @ToolMethod(name = "init_sources",
                description = "Check sources status. Sources in custom-sources.jar. Need extract → call extract_sources() first.",
                parametersSchema = "{}",
                readOnly = true)
    public String initSources() {
        String devCheck = checkDevelopmentMode();
        if (devCheck != null) return devCheck;
        try {
            String baseDir = System.getProperty("diatom.jar.dir", System.getProperty("user.dir"));

            // Check development mode (has full project source tree)
            Path devSrc = Paths.get(System.getProperty("user.dir")).resolve("src/main/java");
            if (Files.exists(devSrc)) {
                return I18n.get("self_update_dev_mode", System.getProperty("user.dir")) + NEWLINE +
                       I18n.get("self_update_dev_mode_tip");
            }

            // Check for custom sources JAR (extracted from diatom-cli.jar)
            Path sourcesJar = findCustomSourcesJar(baseDir);
            if (sourcesJar != null) {
                return I18n.get("self_update_sources_ready") + NEWLINE +
                       I18n.get("self_update_sources_jar", sourcesJar.toString()) + NEWLINE +
                       NEWLINE +
                       I18n.get("self_update_upgrade_steps") + NEWLINE +
                       I18n.get("self_update_step1") + NEWLINE +
                       I18n.get("self_update_step2") + NEWLINE +
                       NEWLINE +
                       I18n.get("self_update_upgrade_tip");
            }

            return I18n.get("self_update_no_sources") + NEWLINE +
                   I18n.get("self_update_no_sources_locations", baseDir, customDir) + NEWLINE +
                   I18n.get("self_update_no_sources_tip");
        } catch (Exception e) {
            logger.error("Error checking sources", e);
            return I18n.get("error") + ": " + e.getMessage();
        }
    }

    /**
     * Extract path argument from args which can be either:
     * 1. A JSON object with "path" key: {"path": "src/main/java/App.java"}
     * 2. A plain string path: "src/main/java/App.java" or "com/github/Example.java"
     * 
     * @param args The argument string (JSON or plain path)
     * @return The extracted path
     */
    private String extractPathArg(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            throw new IOException("Path argument is required");
        }
        
        String trimmed = args.trim();
        
        // Check if it looks like JSON (starts with '{')
        if (trimmed.startsWith("{")) {
            try {
                JsonNode obj = mapper.readTree(args);
                if (obj.has("path")) {
                    return obj.get("path").asText();
                }
                throw new IOException("JSON object must have 'path' field");
            } catch (Exception e) {
                throw new IOException("Failed to parse JSON arguments: " + e.getMessage());
            }
        }
        
        // Otherwise, treat the entire string as the path
        // This handles cases like: "com/github/Example.java" or "src/main/java/App.java"
        return trimmed;
    }

    private boolean isMavenAvailable() {
        StringBuilder diag = new StringBuilder();
        diag.append("--- Maven Diagnostics ---").append(NEWLINE);

        // Check MVN_HOME and M2_HOME
        String mvnHomeEnv = System.getenv("MVN_HOME");
        String m2HomeEnv = System.getenv("M2_HOME");
        diag.append("MVN_HOME: ").append(mvnHomeEnv != null ? mvnHomeEnv : "(not set)").append(NEWLINE);
        diag.append("M2_HOME: ").append(m2HomeEnv != null ? m2HomeEnv : "(not set)").append(NEWLINE);

        // Determine the mvn command we would use
        // On Windows, must use "mvn.cmd" not "mvn" for ProcessBuilder to find it
        String mvnCmd = mvnHomeEnv != null ? mvnHomeEnv + "/bin/mvn" : "mvn";
        if (IS_WINDOWS && !mvnCmd.contains("/") && !mvnCmd.contains("\\")) {
            mvnCmd = "mvn.cmd";
        }
        diag.append("mvn command: ").append(mvnCmd).append(NEWLINE);

        // Show the effective PATH from ProcessEnvironment
        diag.append("Process environment PATH: ").append(ProcessEnvironment.getSafePath()).append(NEWLINE);

        // Check if mvnCmd resolves to a real file
        if (mvnCmd.contains("/") || mvnCmd.contains("\\")) {
            Path mvnPath = Paths.get(mvnCmd);
            diag.append("mvn binary exists: ").append(Files.exists(mvnPath)).append(NEWLINE);
            if (ProcessEnvironment.getSafePath().contains(mvnPath.getParent() != null ? mvnPath.getParent().toString() : "")) {
                diag.append("mvn parent dir in PATH: yes").append(NEWLINE);
            } else {
                diag.append("mvn parent dir in PATH: no").append(NEWLINE);
            }
        } else {
            // mvn is unqualified — check if it would be found on PATH
            String pathStr = ProcessEnvironment.getSafePath();
            boolean foundOnPath = false;
            for (String dir : pathStr.split(IS_WINDOWS ? ";" : ":")) {
                Path candidate = Paths.get(dir, IS_WINDOWS ? "mvn.cmd" : "mvn");
                if (Files.exists(candidate)) {
                    foundOnPath = true;
                    diag.append("  Found at: ").append(candidate.toAbsolutePath()).append(NEWLINE);
                }
            }
            diag.append("mvn found on PATH: ").append(foundOnPath).append(NEWLINE);
        }

        // Try running mvn -version
        try {
            ProcessBuilder pb = ProcessEnvironment.createProcessBuilder(mvnCmd, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Capture output for diagnostics
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(NEWLINE);
            }

            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                int exitCode = process.exitValue();
                diag.append("mvn -version exit code: ").append(exitCode).append(NEWLINE);
                if (exitCode == 0) {
                    diag.append("mvn -version output: ").append(output.toString().trim()).append(NEWLINE);
                    mavenDiagnosticInfo = "";
                    return true;
                }
                diag.append("mvn -version stderr: ").append(output.toString().trim()).append(NEWLINE);
            } else {
                process.destroyForcibly();
                diag.append("mvn -version timed out after 5 seconds").append(NEWLINE);
            }
        } catch (Exception e) {
            diag.append("mvn -version exception: ").append(e.getClass().getSimpleName())
                .append(": ").append(e.getMessage()).append(NEWLINE);
        }

        mavenDiagnosticInfo = diag.toString();
        logger.warn("Maven not available. Diagnostics:\n{}", mavenDiagnosticInfo);
        return false;
    }

    /**
     * JDK to Maven version compatibility matrix.
     * Maps JDK major version to minimum required Maven version.
     * Maven can run on JDK versions >= its requirement, but not lower.
     */
    private static String getMinMavenForJdk(int jdkMajor) {
        // JDK 25+: Maven 3.9.0+
        // JDK 24: Maven 3.9.0+
        // JDK 23: Maven 3.9.0+
        // JDK 22: Maven 3.9.0+
        // JDK 21: Maven 3.9.0+ (LTS)
        // JDK 20: Maven 3.9.0+
        // JDK 19: Maven 3.9.0+
        // JDK 18: Maven 3.9.0+
        // JDK 17: Maven 3.9.0+ (requires 3.9.0+ for JDK 17 support)
        // JDK 16: Maven 3.6.0+
        // JDK 15: Maven 3.6.0+
        // JDK 14: Maven 3.6.0+
        // JDK 13: Maven 3.6.0+
        // JDK 12: Maven 3.6.0+
        // JDK 11: Maven 3.6.0+ (LTS)
        // JDK 10: Maven 3.6.0+
        // JDK 9: Maven 3.6.0+
        // JDK 8: Maven 3.2.0+ (default)
        // JDK 7: Maven 3.0.0+
        // JDK 6: Maven 3.0.0+

        if (jdkMajor >= 17) {
            return "3.9.0";  // JDK 17+ needs Maven 3.9.0+ for proper support
        } else if (jdkMajor >= 11) {
            return "3.6.0";  // JDK 11+ needs Maven 3.6.0+
        } else if (jdkMajor >= 8) {
            return "3.2.0";  // JDK 8 needs at least Maven 3.2.0
        } else if (jdkMajor >= 6) {
            return "3.0.0";  // JDK 6/7 needs Maven 3.0.0+
        } else {
            return "3.2.0";  // Default to 3.2.0 for safety
        }
    }

    /**
     * Get available Maven versions from Maven Central, filtered by JDK compatibility.
     * @return list of compatible Maven versions, sorted descending (newest first)
     */
    private List<String> getAvailableMavenVersions() {
        // Detect current JDK major version (default to JDK 8)
        int majorVersion = 8;

        try {
            String javaVersion = System.getProperty("java.version");
            if (javaVersion != null) {
                // Parse java.version like "1.8.0_321" or "11.0.1" or "17-ea"
                String[] parts = javaVersion.split("[.\\-]");
                if (parts.length > 0) {
                    if (parts[0].equals("1") && parts.length > 1) {
                        // Legacy format: 1.8.0_xxx -> JDK 8
                        majorVersion = Integer.parseInt(parts[1]);
                    } else {
                        // Modern format: 11, 17, 21, etc.
                        majorVersion = Integer.parseInt(parts[0]);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse Java version, defaulting to JDK 8: {}", e.getMessage());
        }

        final String minMavenVersion = getMinMavenForJdk(majorVersion);
        logger.info("Detected JDK {}, requiring Maven >= {}", majorVersion, minMavenVersion);

        // Fallback versions if Maven Central is unreachable (comprehensive list)
        List<String> fallback = Arrays.asList(
            "3.9.9", "3.9.8", "3.9.7", "3.9.6", "3.9.5", "3.9.4", "3.9.3", "3.9.2", "3.9.1", "3.9.0",
            "3.8.8", "3.8.7", "3.8.6", "3.8.5", "3.8.4", "3.8.3", "3.8.2", "3.8.1", "3.8.0",
            "3.7.1", "3.7.0",
            "3.6.3", "3.6.2", "3.6.1", "3.6.0",
            "3.5.4", "3.5.3", "3.5.2", "3.5.1", "3.5.0",
            "3.4.1", "3.4.0",
            "3.3.9", "3.3.8", "3.3.7", "3.3.6", "3.3.5", "3.3.4", "3.3.3", "3.3.2", "3.3.1", "3.3.0",
            "3.2.6", "3.2.5", "3.2.4", "3.2.3", "3.2.2", "3.2.1", "3.2.0",
            "3.1.1", "3.1.0",
            "3.0.5", "3.0.4", "3.0.3", "3.0.2", "3.0.1", "3.0.0"
        );

        try {
            java.net.URL url = new java.net.URL("https://repo1.maven.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                List<String> versions = new java.util.ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Parse <version>3.9.9</version> tags
                        if (line.trim().startsWith("<version>") && line.trim().endsWith("</version>")) {
                            String version = line.trim().substring("<version>".length(), line.trim().length() - "</version>".length());
                            // Filter to only 3.x versions >= minimum required version
                            if (version.startsWith("3.") && version.compareTo(minMavenVersion) >= 0) {
                                versions.add(version);
                            }
                        }
                    }
                }

                if (!versions.isEmpty()) {
                    // Sort descending (newest first)
                    versions.sort((a, b) -> b.compareTo(a));
                    logger.info("Fetched {} compatible Maven versions from Maven Central", versions.size());
                    return versions;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch Maven versions from Maven Central: {}", e.getMessage());
        }

        // Return filtered fallback list based on JDK compatibility
        List<String> filteredFallback = new ArrayList<>();
        for (String v : fallback) {
            if (v.compareTo(minMavenVersion) >= 0) {
                filteredFallback.add(v);
            }
        }
        return filteredFallback;
    }

    private String autoInstallMaven() {
        Path mavenHome = InstallPaths.getInstallHome().resolve("maven");
        Path mavenBin = mavenHome.resolve("bin");

        // Check if already installed
        if (Files.exists(mavenBin) && Files.exists(mavenBin.resolve("mvn"))) {
            return setMavenPath(mavenHome.toString());
        }

        // Try multiple download sources
        String[] mavenVersions = {"3.9.9", "3.9.6", "3.9.5"};
        String[][] downloadUrls = {
            // Aliyun mirrors (faster in China)
            {"https://mirrors.aliyun.com/maven/apache-maven/%s/binaries/apache-maven-%s-bin.tar.gz", "aliyun"},
            // Tencent mirror
            {"https://mirrors.cloud.tencent.com/maven/apache-maven/%s/binaries/apache-maven-%s-bin.tar.gz", "tencent"},
            // Apache archive (fallback)
            {"https://archive.apache.org/dist/maven/maven-3/%s/binaries/apache-maven-%s-bin.tar.gz", "apache"}
        };

        String mavenVersion = mavenVersions[0];
        Path tarGz = null;
        String usedSource = null;
        boolean downloadSuccess = false;
        long downloadedSize = 0;

        for (String[] urlConfig : downloadUrls) {
            String urlTemplate = urlConfig[0];
            usedSource = urlConfig[1];

            for (String version : mavenVersions) {
                if (downloadSuccess) break;
                try {
                    String tarUrl = String.format(urlTemplate, version, version);
                    tarGz = mavenHome.resolve("maven.tar.gz");

                    System.out.println("Downloading from " + usedSource + ": " + tarUrl + "...");
                    downloadFile(tarUrl, tarGz);

                    // Verify download was successful
                    if (Files.exists(tarGz)) {
                        downloadedSize = Files.size(tarGz);
                        if (downloadedSize > 0) {
                            System.out.println("Download successful: " + downloadedSize + " bytes");
                            mavenVersion = version;
                            downloadSuccess = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Download failed from " + usedSource + ": " + e.getMessage());
                    tarGz = null;
                }
            }
        }

        if (!downloadSuccess || tarGz == null || downloadedSize <= 0) {
            return I18n.get("self_update_maven_download_failed") + NEWLINE +
                   I18n.get("self_update_maven_download_failed_tip1") + NEWLINE +
                   I18n.get("self_update_maven_download_failed_tip2");
        }

        // Extract tar.gz
        System.out.println("Extracting Maven...");
        try {
            extractTarGz(tarGz, mavenHome);
        } catch (Exception e) {
            // Try .zip format as fallback on Windows
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                return I18n.get("self_update_tar_failed") + ": " + e.getMessage() + NEWLINE +
                       I18n.get("self_update_tar_failed_windows_tip");
            }
            return I18n.get("self_update_tar_failed") + ": " + e.getMessage();
        }

        // Remove tar.gz
        try { Files.delete(tarGz); } catch (Exception ignored) {}

        // Find extracted directory
        Path extractedMaven = null;
        try {
            java.util.List<Path> dirs = Files.list(mavenHome)
                .filter(p -> p.getFileName().toString().startsWith("apache-maven"))
                .collect(java.util.stream.Collectors.toList());
            if (!dirs.isEmpty()) {
                extractedMaven = dirs.get(0);
            }
        } catch (Exception e) {
            logger.warn("Could not find extracted maven dir", e);
        }

        if (extractedMaven == null || !Files.exists(extractedMaven)) {
            return I18n.get("self_update_maven_extract_failed");
        }

        // Configure settings.xml with Aliyun mirror for China
        Path settingsXml = extractedMaven.resolve("conf").resolve("settings.xml");
        if (Files.exists(settingsXml)) {
            try {
                String content = new String(Files.readAllBytes(settingsXml), StandardCharsets.UTF_8);
                if (!content.contains("aliyun")) {
                    content = content.replace("<mirrors>", "<mirrors>\n" +
                        "      <mirror>\n" +
                        "        <id>aliyun</id>\n" +
                        "        <name>Aliyun Maven</name>\n" +
                        "        <url>https://maven.aliyun.com/repository/public</url>\n" +
                        "        <mirrorOf>central</mirrorOf>\n" +
                        "      </mirror>");
                    Files.write(settingsXml, content.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Configured Aliyun mirror for faster downloads");
                }
            } catch (Exception e) {
                logger.warn("Could not configure settings.xml", e);
            }
        }

        // Set MVN_HOME
        System.setProperty("MVN_HOME", extractedMaven.toString());
        System.out.println("Maven installed to: " + extractedMaven);

        return I18n.get("self_update_maven_installed") + NEWLINE +
               I18n.get("self_update_maven_installed_source") + ": " + usedSource + NEWLINE +
               I18n.get("self_update_maven_installed_home") + ": " + extractedMaven + NEWLINE +
               I18n.get("self_update_maven_installed_tip");
    }

    private String setMavenPath(String customPath) {
        if (customPath == null || customPath.trim().isEmpty()) {
            return I18n.get("self_update_maven_path_empty");
        }

        Path mvnPath = Paths.get(customPath, "bin", "mvn");
        if (!Files.exists(mvnPath)) {
            mvnPath = Paths.get(customPath, "mvn");
            if (!Files.exists(mvnPath)) {
                return I18n.get("self_update_maven_not_at_path") + customPath;
            }
        }

        String absolutePath = mvnPath.getParent().getParent().toString();
        System.setProperty("MVN_HOME", absolutePath);
        System.out.println("MVN_HOME set to: " + absolutePath);

        return I18n.get("self_update_maven_path_configured") + absolutePath + NEWLINE +
               I18n.get("self_update_maven_installed_tip");
    }

    private void downloadFile(String url, Path target) throws IOException {
        // Add connection timeout (15s) and read timeout (60s) to prevent hanging on network issues
        java.net.URLConnection conn = new java.net.URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        try (java.io.InputStream in = conn.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void extractTarGz(Path tarGz, Path targetDir) throws IOException, InterruptedException {
        // Use system tar command for cross-platform extraction
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            // On Windows, try tar from Git Bash or use 7z if available
            pb = new ProcessBuilder("tar", "-xzf", tarGz.toAbsolutePath().toString(), "-C", targetDir.toAbsolutePath().toString());
        } else {
            pb = new ProcessBuilder("tar", "-xzf", tarGz.toAbsolutePath().toString(), "-C", targetDir.toAbsolutePath().toString());
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("tar extraction failed with exit code: " + exitCode);
        }
    }

    /**
     * Find or extract custom-sources.jar from diatom-cli.jar's sources/ directory.
     * The sources jar contains a standalone pom.xml + custom module source files
     * for building diatom-custom independently during self-update.
     */
    private Path findCustomSourcesJar(String baseDir) {
        // Check if already extracted in baseDir
        Path cachedJar = Paths.get(baseDir, "custom-sources.jar");
        if (Files.exists(cachedJar)) {
            return cachedJar;
        }

        // Extract from diatom-cli.jar's sources/ directory
        Path execJar = Paths.get(baseDir, "diatom-cli.jar");
        if (!Files.exists(execJar)) {
            execJar = Paths.get(customDir, "diatom-cli.jar");
        }
        if (!Files.exists(execJar)) {
            return null;
        }

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(execJar.toFile())) {
            java.util.jar.JarEntry entry = jar.getJarEntry("sources/custom-sources.jar");
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, cachedJar, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Extracted custom-sources.jar from {} to {}", execJar, cachedJar);
                    return cachedJar;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract custom-sources.jar from {}: {}", execJar, e.getMessage());
        }

        return null;
    }

    private String runMavenBuild(Path projectDir) throws IOException, InterruptedException, BuildFailedException {
        // Determine base directory for JAR operations
        String baseDir = System.getProperty("diatom.jar.dir", System.getProperty("user.dir"));

        // Find Maven executable using SoftwareLocator (follows: env var > PATH > default locations)
        String mvnCmd;
        if (IS_WINDOWS) {
            SoftwareLocator.MavenInfo mavenInfo = SoftwareLocator.findMavenOnWindows().orElse(null);
            if (mavenInfo != null) {
                mvnCmd = mavenInfo.getExecutablePath();
                logger.info("Found Maven at: {}", mvnCmd);
            } else {
                // Maven not found - tell model to search first before installing
                throw new BuildFailedException("Maven not found. Try searching first:\n" +
                    "1. Use run_command to search:\n" +
                    "   - cmd /c: where mvn\n" +
                    "   - bash: which mvn || whereis mvn\n" +
                    "   - python: import shutil; print(shutil.which('mvn'))\n" +
                    "2. Check common install paths:\n" +
                    "   - Windows: D:\\apache-maven\\bin\\mvn.cmd, C:\\Program Files\\maven\\bin\\mvn.cmd\n" +
                    "3. If found, verify it works: run_command('mvn -version')\n" +
                    "4. If not in PATH, use install_maven: install_maven('{\"action\":\"path\",\"path\":\"C:\\\\maven-path\"}')");
            }
        } else {
            // Unix: findInstallation returns Maven home directory
            java.util.Optional<Path> mavenPath = SoftwareLocator.findInstallation("mvn");
            if (mavenPath.isPresent()) {
                mvnCmd = mavenPath.get().resolve("bin").resolve("mvn").toString();
                logger.info("Found Maven at: {}", mvnCmd);
            } else {
                // Maven not found - tell model to search first before installing
                throw new BuildFailedException("Maven not found. Try searching first:\n" +
                    "1. Use run_command to search:\n" +
                    "   - bash: which mvn || whereis mvn || find /usr -name mvn 2>/dev/null\n" +
                    "   - python: import shutil; print(shutil.which('mvn'))\n" +
                    "2. Check common install paths:\n" +
                    "   - Unix: /usr/local/bin/mvn, /opt/maven/bin/mvn, ~/maven/bin/mvn, /usr/share/maven/bin/mvn\n" +
                    "3. If found, verify it works: run_command('mvn -version')\n" +
                    "4. If not in PATH, use install_maven: install_maven('{\"action\":\"path\",\"path\":\"/maven-path\"}')");
            }
        }

        // Step 1: Install lib/core.jar to local Maven repo so the diatom-core dependency resolves
        Path coreCurrent = Paths.get(getCoreCurrentJar());
        if (Files.exists(coreCurrent)) {
            // Extract version from jar filename (e.g., diatom-core-1.0.0.jar -> 1.0.0)
            String coreVersion = coreCurrent.getFileName().toString()
                    .replace("diatom-core-", "")
                    .replace(".jar", "");
            logger.info("Installing {} to local Maven repository with version {}...", coreCurrent.getFileName(), coreVersion);

            // Check if same version already exists in local Maven repository (using dynamic path)
            Path mavenLocalRepo = getMavenLocalRepository();
            Path existingCoreInRepo = mavenLocalRepo.resolve("com/github/obhen233/diatom-core")
                    .resolve(coreVersion)
                    .resolve("diatom-core-" + coreVersion + ".jar");
            // Also check if the installed POM has real dependencies (not the old generated one)
            Path existingPomInRepo = mavenLocalRepo.resolve("com/github/obhen233/diatom-core")
                    .resolve(coreVersion)
                    .resolve("diatom-core-" + coreVersion + ".pom");
            boolean pomHasDeps = false;
            if (Files.exists(existingPomInRepo)) {
                String pomContent = new String(Files.readAllBytes(existingPomInRepo), StandardCharsets.UTF_8);
                pomHasDeps = pomContent.contains("<dependencies>");
            }
            if (Files.exists(existingCoreInRepo) && pomHasDeps) {
                logger.info("Core {} already exists in local Maven repository at {}, skipping install",
                        coreVersion, existingCoreInRepo);
            } else {
                // Extract pom.xml from inside the core JAR (META-INF/maven/.../pom.xml)
                // so install:install-file preserves transitive dependencies (slf4j, jackson, etc.)
                Path tempPom = Files.createTempFile("diatom-core-pom", ".xml");
                boolean pomExtracted = false;
                try {
                    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(coreCurrent.toFile())) {
                        java.util.jar.JarEntry pomEntry = jar.getJarEntry(
                                "META-INF/maven/com.github.obhen233/diatom-core/pom.xml");
                        if (pomEntry != null) {
                            Files.copy(jar.getInputStream(pomEntry), tempPom,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            pomExtracted = true;
                            logger.info("Extracted pom.xml from {} for installation", coreCurrent.getFileName());
                        }
                    }
                    // Run Maven install with the real POM so transitive dependencies are available
                    // If pom.xml was not found inside the JAR, fall back to -DgeneratePom=true
                    // Run in a neutral directory (not projectDir) to avoid Maven trying to read
                    // a potentially corrupted sources/pom.xml as the project POM.
                    Path installWorkDir = Paths.get(System.getProperty("user.home"));
                    ProcessBuilder installPb;
                    if (pomExtracted) {
                        installPb = new ProcessBuilder(mvnCmd,
                                "install:install-file",
                                "-Dfile=" + coreCurrent.toAbsolutePath(),
                                "-DpomFile=" + tempPom.toAbsolutePath(),
                                "-DgroupId=com.github.obhen233",
                                "-DartifactId=diatom-core",
                                "-Dversion=" + coreVersion,
                                "-Dpackaging=jar",
                                "-DcreateChecksum=true",
                                "-q");
                        installPb.directory(installWorkDir.toFile());
                    } else {
                        logger.warn("pom.xml not found inside core JAR, falling back to -DgeneratePom=true");
                        installPb = new ProcessBuilder(mvnCmd,
                                "install:install-file",
                                "-Dfile=" + coreCurrent.toAbsolutePath(),
                                "-DgroupId=com.github.obhen233",
                                "-DartifactId=diatom-core",
                                "-Dversion=" + coreVersion,
                                "-Dpackaging=jar",
                                "-DgeneratePom=true",
                                "-DcreateChecksum=true",
                                "-q");
                        installPb.directory(projectDir.toFile());
                    }
                    installPb.redirectErrorStream(true);
                    ProcessEnvironment.configureEnvironment(installPb);
                    installPb.environment().put("PATH", ProcessEnvironment.getSafePath());

                    Process installProcess = installPb.start();
                    StringBuilder installOutput = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(installProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            installOutput.append(line).append(NEWLINE);
                            System.out.println("[Maven install] " + line);
                        }
                    }

                    boolean installFinished = installProcess.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
                    if (!installFinished) {
                        installProcess.destroyForcibly();
                        throw new BuildFailedException("Core JAR installation timed out");
                    }
                    if (installProcess.exitValue() != 0) {
                        logger.warn("Core JAR installation may have failed (exit code: {}), continuing anyway...",
                                installProcess.exitValue());
                    } else {
                        logger.info("Core JAR installed to local Maven repository successfully");
                    }
                } catch (IOException e) {
                    throw new BuildFailedException("Failed to install core JAR to local Maven repository: " + e.getMessage());
                } finally {
                    try {
                        Files.deleteIfExists(tempPom);
                    } catch (IOException e) {
                        // Ignore temp file cleanup failure
                    }
                }
            }
        } else {
            logger.warn("lib/diatom-core not found at {}, core dependency may not resolve", getCoreCurrentJar());
        }

        // Step 2: Compile check (fast feedback)
        // Store more detailed error output for diagnosis
        int compileExitCode = runMavenWithOfflineFallback(mvnCmd, Arrays.asList("compile", "-q"),
                projectDir, "compile", 300);
        if (compileExitCode != 0) {
            String workDir = projectDir.toAbsolutePath().toString();
            String pathInfo = ProcessEnvironment.getSafePath();

            // Include last Maven output (last 30 lines) for the model to see actual errors
            String mavenErrors = "";
            if (!lastBuildOutput.isEmpty()) {
                String[] lines = lastBuildOutput.split(NEWLINE);
                int start = Math.max(0, lines.length - 30);
                StringBuilder sb = new StringBuilder();
                sb.append("Last Maven output:").append(NEWLINE);
                for (int i = start; i < lines.length; i++) {
                    sb.append("  ").append(lines[i]).append(NEWLINE);
                }
                mavenErrors = sb.toString();
            }

            throw new BuildFailedException(I18n.get("self_update_build_failed_code", String.valueOf(compileExitCode)) + NEWLINE +
                   "Command: " + mvnCmd + " compile -q" + NEWLINE +
                   "Work dir: " + workDir + NEWLINE +
                   "PATH: " + pathInfo + NEWLINE +
                   mavenErrors +
                   NEWLINE +
                   "Tip: Check that the pom.xml includes diatom-core as a dependency:" + NEWLINE +
                   "  <dependency>" + NEWLINE +
                   "    <groupId>com.github.obhen233</groupId>" + NEWLINE +
                   "    <artifactId>diatom-core</artifactId>" + NEWLINE +
                   "    <version>${diatom-core.version}</version>" + NEWLINE +
                   "    <scope>provided</scope>" + NEWLINE +
                   "  </dependency>");
        }

        System.out.println("[Maven] Compilation successful, proceeding to package...");

        // Step 3: Package (produces diatom-custom.jar)
        int packageExitCode = runMavenWithOfflineFallback(mvnCmd, Arrays.asList("package", "-DskipTests", "-q"),
                projectDir, "package", 300);
        if (packageExitCode == 0) {
            // Look for custom-shaded.jar (shade plugin output name)
            // The shaded plugin outputs to ${project.build.directory}/custom-shaded.jar
            Path customJar = projectDir.resolve("diatom-custom").resolve("target").resolve("custom-shaded.jar");
            // Fallback: direct target (legacy single-module structure)
            if (!Files.exists(customJar)) {
                customJar = projectDir.resolve("target").resolve("custom-shaded.jar");
            }
            // Fallback: target/core-initial.jar (old single-module output)
            Path coreInitialJar = projectDir.resolve("target").resolve("core-initial.jar");
            if (!Files.exists(customJar) && Files.exists(coreInitialJar)) {
                // Legacy mode: use core-initial.jar and update lib/core.jar only
                return handleLegacyBuildOutput(coreInitialJar);
            }

            if (!Files.exists(customJar)) {
                throw new BuildFailedException("Build output not found: " + customJar +
                        ". Expected custom-shaded.jar in target directory.");
            }

            // Find custom-sources.jar (built by maven-antrun-plugin during package phase)
            Path customSourcesJar = projectDir.resolve("diatom-custom").resolve("target").resolve("custom-sources.jar");
            if (!Files.exists(customSourcesJar)) {
                customSourcesJar = projectDir.resolve("target").resolve("custom-sources.jar");
            }

            // Copy new dependencies (libs) to ~/.diatom/custom/lib/
            // Maven's dependency:copy-dependencies puts libs in target/lib/
            Path targetLibDir = projectDir.resolve("diatom-custom").resolve("target").resolve("lib");
            if (!Files.exists(targetLibDir)) {
                targetLibDir = projectDir.resolve("target").resolve("lib");
            }
            if (Files.exists(targetLibDir)) {
                copyNewDependencies(targetLibDir, Paths.get(customDir, "lib"));
            }

            // Repack the newly built shaded JAR and sources into the full executable custom-current.jar
            // IMPORTANT: Stage the update to ~/.diatom/custom/ (not the running jar location)
            // to avoid Windows "file in use" locking issues. Update will be applied on next startup.
            Path customDir = Paths.get(appHomeDir, "custom");
            Path pendingJar = customDir.resolve("custom-current.jar.update-pending");
            Path updateMarker = customDir.resolve("custom-current.jar.update-pending.marker");

            // Repack to pending location in app home directory (with custom-sources.jar)
            repackCustomJar(pendingJar, customJar, customSourcesJar, baseDir);
            logger.info("Staged custom-current.jar update at: {} (will be applied on restart)", pendingJar);

            // Update diatom-cli.jar with new custom-sources.jar for next self-update cycle
            // Backup original before modification to allow restore on failure
            Path diatomCliJar = Paths.get(baseDir, "diatom-cli.jar");
            Path diatomCliJarBackup = diatomCliJar.resolveSibling("diatom-cli.jar.original");
            try {
                if (Files.exists(diatomCliJar)) {
                    Files.copy(diatomCliJar, diatomCliJarBackup, StandardCopyOption.REPLACE_EXISTING);
                }
                updateDiatomCliJarWithNewSources(customSourcesJar, baseDir);
            } catch (Exception e) {
                // Restore original on failure to avoid leaving signed JAR in broken state
                if (Files.exists(diatomCliJarBackup)) {
                    try {
                        Files.deleteIfExists(diatomCliJar);
                        Files.move(diatomCliJarBackup, diatomCliJar, StandardCopyOption.REPLACE_EXISTING);
                        logger.info("Restored original diatom-cli.jar after update failure");
                    } catch (Exception restoreEx) {
                        logger.error("Failed to restore original diatom-cli.jar", restoreEx);
                    }
                }
                throw e;
            } finally {
                // Clean up backup on success
                try {
                    Files.deleteIfExists(diatomCliJarBackup);
                } catch (Exception ignored) {}
            }

            // Create marker file atomically to trigger update on restart
            // Use atomic move to avoid inconsistent state on crash
            Files.createDirectories(customDir);
            Path tempMarker = customDir.resolve("custom-current.jar.update-pending.marker.tmp");
            Files.write(tempMarker, "1".getBytes());
            Files.move(tempMarker, updateMarker, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Update marker created atomically: {}", updateMarker);

            // Save custom version (semantic versioning: increment patch level)
            saveCustomVersion();

            // Clean up Maven build target directory to free space
            cleanupMavenTarget(projectDir);

            return I18n.get("self_update_success") + NEWLINE +
                   I18n.get("self_update_cli_updated", pendingJar.toString()) + NEWLINE +
                   I18n.get("self_update_staged_tip");
        } else {
            String workDir = projectDir.toAbsolutePath().toString();
            String pathInfo = ProcessEnvironment.getSafePath();
            throw new BuildFailedException(I18n.get("self_update_build_failed_code", String.valueOf(packageExitCode)) + NEWLINE +
                          "Command: " + mvnCmd + " package -DskipTests -q" + NEWLINE +
                          "Work dir: " + workDir + NEWLINE +
                          "PATH: " + pathInfo + NEWLINE +
                          I18n.get("self_update_last_lines") + NEWLINE +
                          "(Maven output was printed above)");
        }
    }

    /**
     * Handle legacy single-module build output (core-initial.jar).
     * Used for backward compatibility when building from old custom-sources.jar.
     */
    private String handleLegacyBuildOutput(Path coreInitialJar) throws IOException {
        logger.info("Legacy build detected (core-initial.jar), updating lib/core.jar only");
        String baseDir = System.getProperty("diatom.jar.dir", System.getProperty("user.dir"));

        // Backup old core
        Path currentJar = Paths.get(getCoreCurrentJar());
        if (Files.exists(currentJar)) {
            String timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path backupPath = Paths.get(appHomeDir, "versions", "core-" + timestamp + ".jar");
            Files.createDirectories(backupPath.getParent());
            Files.copy(currentJar, backupPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // Update lib/core.jar
        Path tempJar = currentJar.resolveSibling("core.jar.tmp");
        Files.copy(coreInitialJar, tempJar, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.deleteIfExists(currentJar);
        } catch (Exception e) {
            logger.warn("Could not delete old lib/core.jar: {}", e.getMessage());
        }
        try {
            Files.move(tempJar, currentJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tempJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
        }

        saveCustomVersion();
        return I18n.get("self_update_success") + NEWLINE +
               I18n.get("self_update_core_updated", currentJar.toString()) + NEWLINE +
               I18n.get("self_update_restart_tip");
    }

    /**
     * Exception thrown when Maven build fails, carrying the error message
     * to be returned to the AI caller. This avoids fragile string-based
     * success/failure detection.
     */
    private static class BuildFailedException extends Exception {
        public BuildFailedException(String message) {
            super(message);
        }
    }

    private String getLastLines(String text, int count) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, lines.length - count);
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append(NEWLINE);
        }
        return sb.toString();
    }

    /**
     * Copy new dependencies from Maven build output to the custom lib directory.
     * Only copies JAR files that don't already exist in the target directory.
     */
    private void copyNewDependencies(Path sourceLibDir, Path targetLibDir) throws IOException {
        if (!Files.exists(sourceLibDir)) {
            logger.info("No dependencies to copy from: {}", sourceLibDir);
            return;
        }

        Files.createDirectories(targetLibDir);

        java.util.stream.Stream<Path> files = Files.list(sourceLibDir);
        files.filter(p -> p.toString().endsWith(".jar"))
            .forEach(sourceFile -> {
                try {
                    Path targetFile = targetLibDir.resolve(sourceFile.getFileName());
                    if (!Files.exists(targetFile)) {
                        Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        logger.info("Copied new dependency: {} -> {}", sourceFile.getFileName(), targetLibDir);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to copy dependency: {}", sourceFile, e);
                }
            });
        logger.info("Dependencies copied to: {}", targetLibDir);
    }

    /**
     * Repack the newly built shaded JAR into the full executable custom-current.jar.
     * Always builds from diatom-cli.jar (not from existing productionJar, which may be corrupted).
     * Output: custom-current.jar = Bootstrap classes + new custom-shaded.jar
     * Dependencies (lib/*.jar) are NOT packaged - they stay in ~/.diatom/custom/lib/
     * Note: sources are stored in diatom-cli.jar (sources/custom-sources.jar), not here
     */
    private void repackCustomJar(Path productionJar, Path newShadedJar, Path newSourcesJar, String baseDir) throws IOException {
        // Always build from diatom-cli.jar to get clean Bootstrap classes
        Path diatomCliJar = Paths.get(baseDir, "diatom-cli.jar");
        if (!Files.exists(diatomCliJar)) {
            throw new IOException("diatom-cli.jar not found at: " + diatomCliJar);
        }

        Path tempJar = productionJar.resolveSibling("custom-current.jar.tmp");

        try (java.util.zip.ZipOutputStream targetJar = new java.util.zip.ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tempJar)))) {

            byte[] buffer = new byte[8192];
            int bytesRead;

            // Add Bootstrap manifest
            java.util.jar.Manifest manifest = new java.util.jar.Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class", "com.github.obhen233.bootstrap.Bootstrap");
            manifest.getMainAttributes().putValue("Implementation-Version", "1.0.0");
            targetJar.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(targetJar);
            targetJar.closeEntry();

            // Extract and add Bootstrap classes from diatom-cli.jar
            try (java.util.jar.JarFile sourceJar = new java.util.jar.JarFile(diatomCliJar.toFile())) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = sourceJar.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // Only include Bootstrap classes
                    if (name.startsWith("com/github/obhen233/bootstrap/") && !entry.isDirectory()) {
                        targetJar.putNextEntry(new java.util.zip.ZipEntry(name));
                        try (InputStream is = sourceJar.getInputStream(entry)) {
                            while ((bytesRead = is.read(buffer)) != -1) {
                                targetJar.write(buffer, 0, bytesRead);
                            }
                        }
                        targetJar.closeEntry();
                    }
                }

                // Add the newly built custom-shaded.jar contents (not as nested JAR)
                // Standard URLClassLoader cannot load classes from nested JARs,
                // which would prevent ServiceLoader from discovering custom SPI implementations.
                if (Files.exists(newShadedJar)) {
                    int entryCount = 0;
                    try (java.util.jar.JarFile shadedJar = new java.util.jar.JarFile(newShadedJar.toFile())) {
                        java.util.Enumeration<java.util.jar.JarEntry> shadedEntries = shadedJar.entries();
                        while (shadedEntries.hasMoreElements()) {
                            java.util.jar.JarEntry shadedEntry = shadedEntries.nextElement();
                            String name = shadedEntry.getName();
                            if (name.equals("META-INF/MANIFEST.MF") || shadedEntry.isDirectory()) {
                                continue;
                            }
                            // Skip Bootstrap classes — already extracted from diatom-cli.jar above
                            if (name.startsWith("com/github/obhen233/bootstrap/")) {
                                continue;
                            }
                            targetJar.putNextEntry(new java.util.zip.ZipEntry(name));
                            try (InputStream is = shadedJar.getInputStream(shadedEntry)) {
                                while ((bytesRead = is.read(buffer)) != -1) {
                                    targetJar.write(buffer, 0, bytesRead);
                                }
                            }
                            targetJar.closeEntry();
                            entryCount++;
                        }
                    }
                    logger.info("Extracted {} entries from custom-shaded.jar into custom-current.jar", entryCount);
                }
            }
        }

        // Atomically replace
        try {
            Files.deleteIfExists(productionJar);
        } catch (Exception e) {
            logger.warn("Could not delete old custom-current.jar: {}", e.getMessage());
        }
        try {
            Files.move(tempJar, productionJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tempJar, productionJar, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("custom-current.jar rebuilt from diatom-cli.jar");
    }

    /**
     * Update diatom-cli.jar with the new custom-sources.jar.
     * This ensures that subsequent self-update cycles use the updated sources.
     */
    private void updateDiatomCliJarWithNewSources(Path newSourcesJar, String baseDir) throws IOException {
        Path diatomCliJar = Paths.get(baseDir, "diatom-cli.jar");
        if (!Files.exists(diatomCliJar)) {
            logger.warn("diatom-cli.jar not found at {}, skipping sources update", diatomCliJar);
            return;
        }
        if (!Files.exists(newSourcesJar)) {
            logger.warn("New custom-sources.jar not found at {}, skipping diatom-cli.jar update", newSourcesJar);
            return;
        }

        Path tempJar = diatomCliJar.resolveSibling("diatom-cli.jar.tmp");

        try (java.util.zip.ZipOutputStream targetJar = new java.util.zip.ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tempJar)))) {

            byte[] buffer = new byte[8192];
            int bytesRead;

            // Copy all entries from diatom-cli.jar except sources/custom-sources.jar
            try (java.util.jar.JarFile sourceJar = new java.util.jar.JarFile(diatomCliJar.toFile())) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = sourceJar.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // Validate entry name to prevent path traversal attacks
                    // JAR entry names should not contain ".." or escape the jar root
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                        logger.warn("Skipping potentially malicious JAR entry: {}", name);
                        continue;
                    }

                    // Skip the old sources (will be replaced with new)
                    if (name.startsWith("sources/")) {
                        continue;
                    }

                    if (!entry.isDirectory()) {
                        targetJar.putNextEntry(new java.util.zip.ZipEntry(name));
                        try (InputStream is = sourceJar.getInputStream(entry)) {
                            while ((bytesRead = is.read(buffer)) != -1) {
                                targetJar.write(buffer, 0, bytesRead);
                            }
                        }
                        targetJar.closeEntry();
                    }
                }

                // Add the new custom-sources.jar
                targetJar.putNextEntry(new java.util.zip.ZipEntry("sources/custom-sources.jar"));
                try (InputStream is = Files.newInputStream(newSourcesJar)) {
                    while ((bytesRead = is.read(buffer)) != -1) {
                        targetJar.write(buffer, 0, bytesRead);
                    }
                }
                targetJar.closeEntry();
                logger.info("Updated diatom-cli.jar with new custom-sources.jar");
            }
        }

        // Atomically replace
        try {
            Files.deleteIfExists(diatomCliJar);
        } catch (Exception e) {
            logger.warn("Could not delete old diatom-cli.jar: {}", e.getMessage());
        }
        try {
            Files.move(tempJar, diatomCliJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            logger.warn("Atomic move failed, attempting non-atomic move: {}", e.getMessage());
            Files.move(tempJar, diatomCliJar, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("diatom-cli.jar updated with new sources");
    }

    /**
     * Clean up Maven build target directory after successful compilation.
     * This removes compiled classes and generated files to free up space.
     */
    private void cleanupMavenTarget(Path projectDir) {
        // Only clean if building from extracted sources (not development mode)
        // Development mode uses userDir which may be a real project
        // Use normalize() and toAbsolutePath() for reliable path comparison on Windows
        Path normalizedProjectDir = projectDir.toAbsolutePath().normalize();
        Path normalizedSourcesDir = Paths.get(sourcesDir).toAbsolutePath().normalize();

        if (normalizedProjectDir.equals(normalizedSourcesDir)) {
            // Clean target directory (Maven compilation output)
            Path targetDir = projectDir.resolve("target");
            if (Files.exists(targetDir)) {
                logger.info("Cleaning up Maven target directory: {}", targetDir);
                deleteDirectory(targetDir);
                logger.info("Maven target directory cleaned");
            }

            // Clean any temporary files in the project directory
            try {
                Files.list(projectDir).forEach(path -> {
                    String name = path.getFileName().toString();
                    // Clean common temp patterns generated during build
                    if (name.startsWith(".decorator") || name.startsWith(".fileLists") ||
                        name.endsWith(".original") || name.endsWith(".bak")) {
                        try {
                            if (Files.isDirectory(path)) {
                                deleteDirectory(path);
                            } else {
                                Files.deleteIfExists(path);
                            }
                            logger.info("Cleaned temp file: {}", path);
                        } catch (IOException e) {
                            logger.warn("Failed to clean temp file: {}", path);
                        }
                    }
                });
            } catch (IOException e) {
                logger.warn("Failed to list project directory for cleanup: {}", e.getMessage());
            }
        }
    }

    /**
     * Run Maven with offline mode (-o) first; fallback to online if offline fails.
     * This avoids unnecessary network checks when all dependencies are already cached locally.
     *
     * @param mvnCmd        Maven executable path
     * @param args          Maven arguments (e.g., "compile", "-q")
     * @param projectDir    Working directory for Maven
     * @param stageName     Human-readable stage name for log output (e.g., "compile", "package")
     * @param timeoutSeconds Timeout in seconds
     * @return Maven exit code (0 = success)
     */
    private int runMavenWithOfflineFallback(String mvnCmd, List<String> args, Path projectDir, String stageName, int timeoutSeconds) {
        // If offline has succeeded before, try offline first (fast path)
        if (mavenOfflineEverSucceeded) {
            List<String> offlineCmd = new ArrayList<>();
            offlineCmd.add(mvnCmd);
            offlineCmd.add("-o");
            offlineCmd.addAll(args);

            MavenResult result = runMavenProcess(offlineCmd, projectDir, stageName, timeoutSeconds, true);
            if (result.exitCode == 0) {
                return 0;
            }
            // Offline failed, fall through to online
            String msg = I18n.get("maven_offline_failed_retry_online", stageName);
            System.out.println("[Maven] " + msg);
            logger.info("Maven {} offline failed (code: {}), retrying online...", stageName, result.exitCode);
        } else {
            // First build: try online directly (dependencies not cached yet)
            logger.info("Maven {}: first build detected, trying online mode first", stageName);
        }

        // Online attempt
        List<String> onlineCmd = new ArrayList<>();
        onlineCmd.add(mvnCmd);
        onlineCmd.addAll(args);

        MavenResult result = runMavenProcess(onlineCmd, projectDir, stageName, timeoutSeconds, false);
        if (result.exitCode == 0) {
            mavenOfflineEverSucceeded = true;
            return 0;
        }

        // On failure, store the last output lines for error reporting
        lastBuildOutput = result.output;
        return result.exitCode;
    }

    /**
     * Run a Maven process with the given command and arguments.
     */
    private MavenResult runMavenProcess(List<String> cmd, Path projectDir, String stageName, int timeoutSeconds, boolean offline) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);
        ProcessEnvironment.configureEnvironment(pb);
        pb.environment().put("PATH", ProcessEnvironment.getSafePath());

        String mode = offline ? " (offline)" : "";
        System.out.println("[Maven] Running " + String.join(" ", cmd) + mode);

        ProgressSpinner.ProgressSession mvnSession = ProgressSpinner.start("Maven " + stageName + "...");
        java.util.concurrent.ExecutorService outputReader = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            java.util.concurrent.Future<?> outputFuture = outputReader.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append(NEWLINE);
                        }
                        System.out.println("[Maven " + stageName + "] " + line);
                    }
                } catch (IOException e) {
                    logger.debug("Maven {} output reader stopped: {}", stageName, e.getMessage());
                }
            });

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    outputFuture.get(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    outputFuture.cancel(true);
                }
                String msg = "Maven " + stageName + " timed out after " + timeoutSeconds + "s";
                System.out.println("[Maven] " + msg);
                logger.warn(msg);
                mvnSession.stop("Maven " + stageName + " TIMEOUT");
                synchronized (output) {
                    return new MavenResult(-1, output.toString());
                }
            }

            try {
                outputFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                outputFuture.cancel(true);
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                System.out.println("[Maven] " + stageName + " successful" + mode);
                logger.info("Maven {} successful (offline: {})", stageName, offline);
            }
            mvnSession.stop("Maven " + stageName + " " + (exitCode == 0 ? "OK" : "FAILED"));
            synchronized (output) {
                return new MavenResult(exitCode, output.toString());
            }
        } catch (IOException e) {
            String msg = "Failed to start Maven " + stageName + ": " + e.getMessage();
            System.out.println("[Maven] " + msg);
            logger.error(msg, e);
            if (mvnSession != null) mvnSession.stop("Maven " + stageName + " ERROR");
            return new MavenResult(-1, msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String msg = "Maven " + stageName + " was interrupted";
            System.out.println("[Maven] " + msg);
            logger.warn(msg, e);
            if (mvnSession != null) mvnSession.stop("Maven " + stageName + " INTERRUPTED");
            return new MavenResult(-1, msg);
        } finally {
            outputReader.shutdownNow();
        }
    }

    /**
     * Result holder for Maven process execution.
     */
    private static class MavenResult {
        final int exitCode;
        final String output;
        MavenResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output != null ? output : "";
        }
    }
}
