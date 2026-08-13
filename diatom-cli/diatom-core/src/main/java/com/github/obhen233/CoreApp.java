package com.github.obhen233;

import com.github.obhen233.cli.TerminalUI;
import com.github.obhen233.cli.execute.ExecuteModeRunner;
import com.github.obhen233.cli.execute.OutputFormatter;
import com.github.obhen233.config.AppConfig;
import com.github.obhen233.config.SystemInfo;
import com.github.obhen233.core.CoreInitializer;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.database.DatabaseInitializer;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.SourceCodeExtensionsDao;
import com.github.obhen233.core.database.HibernateDatabaseManager;
import com.github.obhen233.core.database.HistoryManager;
import com.github.obhen233.core.database.TaskCheckpointManager;
import com.github.obhen233.core.database.ContextCacheManager;
import com.github.obhen233.core.database.ChangeLogDao;
import com.github.obhen233.core.database.SnapshotDao;
import com.github.obhen233.core.database.TaskDao;
import com.github.obhen233.core.engine.CommandPermissionEngine;
import com.github.obhen233.core.knowledge.CommandKnowledgeManager;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.adapter.OpenAIAdapter;
import com.github.obhen233.core.tool.AuthorizedPathManager;
import com.github.obhen233.core.tool.ToolRegistry;
import com.github.obhen233.core.tool.ToolRegistryCenter;
import com.github.obhen233.core.tool.builtin.CommandTools;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.mcp.McpClientManager;
import com.github.obhen233.core.security.ApprovalPolicy;
import com.github.obhen233.core.security.ApprovalStrategyResolver;
import com.github.obhen233.core.security.SandboxLevel;
import com.github.obhen233.spi.AppLifecycleHook;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.ToolRegistrar;
import com.github.obhen233.spi.UiCustomizer;
import com.github.obhen233.util.I18n;
import com.github.obhen233.util.InstallPaths;
import com.github.obhen233.util.JarUtils;
import com.github.obhen233.util.SoftwareLocator;
import com.github.obhen233.util.WorkspaceDirResolver;
import com.github.obhen233.core.gateway.GatewayModeLauncher;
import com.github.obhen233.core.gateway.ServerModeLauncher;
import com.github.obhen233.core.tool.builtin.ProcessEnvironment;
import com.github.obhen233.core.workspace.WorkspaceRegistry;
import com.github.obhen233.core.database.entity.ProjectContextEntity;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class CoreApp {
    private static final Logger logger = LoggerFactory.getLogger(CoreApp.class);

    // Retained across createAgent() → timeout callback flow (CLI/gateway modes)
    private static ToolRegistryCenter _appRegistryCenter;

    // Project marker files used to detect projects under a workspace
    // Shared with ProjectIndexer.PROJECT_TYPE_FILES
    private static final java.util.Map<String, String> PROJECT_MARKERS = ProjectIndexer.PROJECT_TYPE_FILES;

    /**
     * Clean up extracted sources from a previous self-upgrade.
     * Checks for ~/.diatom/.cleanup-pending marker file, reads the baseDir from it,
     * deletes {baseDir}/sources/ entirely, then removes the marker.
     */
    private static void cleanupAfterUpgrade() {
        try {
            Path markerPath = InstallPaths.getInstallHome().resolve(".cleanup-pending");
            if (!Files.exists(markerPath)) {
                return;
            }

            String baseDir = new String(Files.readAllBytes(markerPath), StandardCharsets.UTF_8).trim();
            logger.info("Cleanup marker found, cleaning up sources from: {}", baseDir);

            // Delete the entire sources directory (includes extracted sources + Maven target/)
            Path sourcesPath = Paths.get(baseDir, "sources");
            if (Files.exists(sourcesPath)) {
                try {
                    java.nio.file.Files.walkFileTree(sourcesPath, new java.nio.file.SimpleFileVisitor<Path>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                            Files.delete(file);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                        @Override
                        public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            Files.delete(dir);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
                    logger.info("Deleted sources directory: {}", sourcesPath);
                } catch (IOException e) {
                    logger.warn("Failed to cleanup sources directory: {}", e.getMessage());
                }
            } else {
                logger.info("Sources directory already removed: {}", sourcesPath);
            }

            // Remove the marker file
            Files.deleteIfExists(markerPath);
            logger.info("Cleanup marker removed.");
        } catch (Exception e) {
            logger.warn("Failed to cleanup after upgrade: {}", e.getMessage());
        }
    }

    /**
     * Check and log the custom version from custom-version.txt.
     * Path: {installHome}/custom/custom-version.txt
     */
    private static void checkCustomVersion() {
        try {
            Path customVersionFile = InstallPaths.getCustomDir().resolve("custom-version.txt");
            if (Files.exists(customVersionFile)) {
                String version = new String(Files.readAllBytes(customVersionFile), StandardCharsets.UTF_8).trim();
                logger.info("Custom version: {}", version);
            } else {
                logger.info("Custom version: not found (fresh install)");
            }
        } catch (Exception e) {
            logger.warn("Failed to check custom version: {}", e.getMessage());
        }
    }

    /**
     * Apply pending core update if marker exists.
     * Downloads the new core from Maven Central, installs to local Maven repo,
     * and replaces lib/core.jar.
     */
    private static void applyPendingCoreUpdate() {
        try {
            Path appHome = InstallPaths.getInstallHome();
            Path updateMarker = appHome.resolve("core-update-pending.marker");
            if (!Files.exists(updateMarker)) {
                return;
            }

            String version = "";
            try {
                version = new String(Files.readAllBytes(updateMarker), StandardCharsets.UTF_8).trim();
            } catch (Exception e) {}

            logger.info("Applying pending core update: {}", version);

            // Download new core from Maven Central
            String jarUrl = "https://repo1.maven.org/maven2/com/github/obhen233/diatom-core/" + version + "/diatom-core-" + version + ".jar";
            Path tempFile = Files.createTempFile("diatom-core-", ".jar");

            java.net.URL url = new java.net.URL(jarUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                try {
                    if (conn.getResponseCode() == 200) {
                        try (java.io.InputStream in = conn.getInputStream()) {
                            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        logger.info("Downloaded core: {} -> {}", version, tempFile);

                        // Validate downloaded JAR before proceeding
                        if (!isValidJar(tempFile)) {
                            Files.deleteIfExists(tempFile);
                            throw new RuntimeException("Downloaded core JAR is invalid or corrupted");
                        }

                        // Step 1: Install to local Maven repository (required for compile_sources to work)
                        installCoreToLocalMaven(tempFile, version);

                        // Step 2: Replace lib/core.jar
                        Path libDir = InstallPaths.getLibDir();
                        Path coreJar = JarUtils.findCoreJar(libDir);
                        if (coreJar != null && Files.exists(coreJar)) {
                            // Backup old
                            String timestamp = java.time.LocalDateTime.now().format(
                                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                            Path backupPath = InstallPaths.getVersionsDir().resolve("core-" + timestamp + ".jar");
                            Files.createDirectories(backupPath.getParent());
                            Files.copy(coreJar, backupPath);
                            logger.info("Backed up old core to: {}", backupPath);

                            // Delete old and move new
                            Files.delete(coreJar);
                            Files.move(tempFile, coreJar);
                            logger.info("Core updated to version: {}", version);
                        } else {
                            // No existing core jar, just place the new one
                            Path newCoreJar = libDir.resolve("diatom-core-" + version + ".jar");
                            Files.createDirectories(libDir);
                            Files.move(tempFile, newCoreJar);
                            logger.info("Core installed: {}", newCoreJar);
                        }

                        // Update version file
                        Path versionFile = InstallPaths.getInstallHome().resolve("core-version");
                        Files.write(versionFile, version.getBytes(StandardCharsets.UTF_8));

                        // Clean up marker
                        Files.delete(updateMarker);
                        logger.info("Core pending update completed");
                    } else {
                        throw new RuntimeException("Failed to download core: HTTP " + conn.getResponseCode());
                    }
                } finally {
                    conn.disconnect();
                }
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply pending core update: " + e.getMessage(), e);
        }
    }

    /**
     * Validate that a downloaded JAR file is valid (has manifest and is readable).
     */
    private static boolean isValidJar(Path jarPath) {
        if (jarPath == null || !Files.exists(jarPath)) return false;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            jar.getManifest();
            return true;
        } catch (Exception e) {
            logger.warn("JAR validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get Maven local repository path from settings.xml, with fallback to default.
     * Uses SoftwareLocator to find Maven installation first, then looks for settings.xml
     * in Maven's conf directory or ~/.m2/.
     */
    private static Path getMavenLocalRepository() {
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

    /**
     * Install core JAR to local Maven repository.
     * This is required so that compile_sources (mvn compile) can resolve the diatom-core dependency.
     */
    private static void installCoreToLocalMaven(Path coreJarPath, String version) {
        // Check if same version already exists in local Maven repository
        Path mavenLocalRepo = getMavenLocalRepository();
        Path existingCore = mavenLocalRepo.resolve("com/github/obhen233/diatom-core")
                .resolve(version)
                .resolve("diatom-core-" + version + ".jar");
        if (Files.exists(existingCore)) {
            logger.info("Core {} already exists in local Maven repository, skipping install", version);
            return;
        }

        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            String mvnCmd;

            // Find Maven executable using SoftwareLocator
            if (isWindows) {
                SoftwareLocator.MavenInfo mavenInfo = SoftwareLocator.findMavenOnWindows().orElse(null);
                if (mavenInfo != null) {
                    mvnCmd = mavenInfo.getExecutablePath();
                } else {
                    mvnCmd = "mvn.cmd";
                }
            } else {
                java.util.Optional<Path> mavenPath = SoftwareLocator.findInstallation("mvn");
                if (mavenPath.isPresent()) {
                    mvnCmd = mavenPath.get().resolve("bin").resolve("mvn").toString();
                } else {
                    mvnCmd = "mvn";
                }
            }

            logger.info("Installing core {} to local Maven repository...", version);

            ProcessBuilder pb = new ProcessBuilder(mvnCmd,
                    "install:install-file",
                    "-Dfile=" + coreJarPath.toAbsolutePath(),
                    "-DgroupId=com.github.obhen233",
                    "-DartifactId=diatom-core",
                    "-Dversion=" + version,
                    "-Dpackaging=jar",
                    "-DgeneratePom=true",
                    "-DcreateChecksum=true",
                    "-q");
            pb.redirectErrorStream(true);
            ProcessEnvironment.configureEnvironment(pb);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }

            boolean finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Core Maven install timed out after 120 seconds");
            }

            if (process.exitValue() == 0) {
                logger.info("Core installed to local Maven repository successfully");
            } else {
                throw new RuntimeException("Core Maven install failed (exit code: " + process.exitValue() + "): " + output);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to install core to local Maven repository: " + e.getMessage(), e);
        }
    }

    /**
     * Apply pending custom-current.jar update if marker exists.
     * Called at startup to apply updates that were staged during compile_sources.
     * On Windows, the running JAR may be locked, so we use delete-then-move strategy.
     */
    private static void applyPendingCustomUpdate() {
        try {
            Path customDir = InstallPaths.getCustomDir();
            Path updateMarker = customDir.resolve("custom-current.jar.update-pending.marker");
            if (!Files.exists(updateMarker)) {
                return;
            }

            Path pendingJar = customDir.resolve("custom-current.jar.update-pending");
            Path currentJar = customDir.resolve("custom-current.jar");

            if (!Files.exists(pendingJar)) {
                logger.warn("Pending custom-current.jar not found: {}", pendingJar);
                Files.deleteIfExists(updateMarker);
                return;
            }

            logger.info("Applying pending custom-current.jar update...");

            // Backup current (rename old)
            if (Files.exists(currentJar)) {
                String timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                Path backupPath = customDir.resolve("versions").resolve("custom-current-" + timestamp + ".jar.backup");
                Files.createDirectories(backupPath.getParent());
                try {
                    Files.move(currentJar, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Backed up old custom-current.jar to: {}", backupPath);
                } catch (Exception e) {
                    logger.warn("Could not backup old jar: {}", e.getMessage());
                    // Try to delete old jar (on Windows, a process can delete its own locked file)
                    try {
                        Files.deleteIfExists(currentJar);
                        logger.info("Deleted old locked jar");
                    } catch (Exception ex) {
                        logger.warn("Could not delete old jar either: {}", ex.getMessage());
                    }
                }
            }

            // Apply pending update - use copy then delete strategy for locked files
            try {
                Files.move(pendingJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
                logger.info("custom-current.jar updated successfully");
            } catch (Exception e) {
                // Fallback: copy pending to current, then delete pending
                try {
                    Files.copy(pendingJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(pendingJar);
                    logger.info("custom-current.jar updated via copy+delete");
                } catch (Exception ex) {
                    logger.warn("Could not apply pending update: {}. Will retry on next restart.", ex.getMessage());
                    return;
                }
            }

            // Clean up marker
            Files.deleteIfExists(updateMarker);
            logger.info("Pending update marker removed");
        } catch (Exception e) {
            logger.warn("Failed to apply pending custom update: {}", e.getMessage());
        }
    }

    /**
     * 检测是否以独立JAR模式运行
     * 只有通过 Bootstrap 启动（java -jar diatom-cli.jar）时才返回 true
     * 作为依赖被其他项目引用时返回 false
     */
    private static boolean isStandaloneJarMode() {
        return "true".equals(System.getProperty("diatom.standalone.jar"));
    }

    private static void printBanner(String role) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            InputStream is = cl.getResourceAsStream("banner.txt");
            if (is != null) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    // For worker/gateway mode, skip CLI-specific help lines
                    if (("worker".equals(role) || "gateway".equals(role))
                            && (line.contains("Type 'help'") || line.contains("Type 'exit'"))) {
                        continue;
                    }
                    System.out.println(line);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Parse --help / -h from CLI arguments.
     */
    private static boolean parseHelpArg(String[] args) {
        if (args == null) return false;
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Print help text listing all CLI flags, their short forms, env vars, and config properties.
     */
    private static void printHelp() {
        System.out.println("Usage: java -jar diatom-cli.jar [options]");
        System.out.println();
        System.out.println("General options:");
        System.out.println("  --profile, -p <name>                Active config profile");
        System.out.println("  --workspace-dir, -w <dir>           Working directory");
        System.out.println("                                       Env: DIATOM_WORKSPACE_DIR");
        System.out.println("                                       Config: workspace.dir");
        System.out.println("  --help, -h                          Show this help message");
        System.out.println();
        System.out.println("Mode selection:");
        System.out.println("  --role, -r <role>                   Instance role: cli, worker, gateway");
        System.out.println("                                       Env: DIATOM_ROLE");
        System.out.println("                                       Config: diatom.role");
        System.out.println("  --port, -P <port>                   HTTP server port (gateway/worker, default: 8080)");
        System.out.println("  --instance-id, -i, -id <id>         Unique instance identifier");
        System.out.println("  --gateway-url, -u <url>             Gateway URL for worker registration");
        System.out.println("                                       Sets system property: gateway.url");
        System.out.println("  --capability, -c <file>             Worker capability file path");
        System.out.println("  --description, -desc <text>         Instance description");
        System.out.println("  --daemonize (or -d)                  Run gateway in daemon mode (background)");
        System.out.println("                                       Note: -d conflicts with --description;");
        System.out.println("                                       daemonize only applies in gateway mode");
        System.out.println("  --queue, -q <true|false>             Enable async queue mode (gateway only)");
        System.out.println("                                       Env: DIATOM_GATEWAY_QUEUE_ENABLED");
        System.out.println("                                       Config: diatom.gateway.queue.enabled");
        System.out.println("                                       Default: false (sync processing)");
        System.out.println();
        System.out.println("Execute mode (one-shot prompt):");
        System.out.println("  --execute, -e <prompt>              Run a single prompt and exit");
        System.out.println("  --format, -f <fmt>                  Output format: text, json, xml, html, md (markdown), bin");
        System.out.println("                                       (default: text)");
        System.out.println("  --encode, -E <encoding>             Output charset (default: UTF-8)");
        System.out.println("  --file-path, -F <path>              Write output to file instead of stdout");
        System.out.println("  --mode, -m <preset>                 Mode preset:");
        System.out.println("                                       normal, auto, silent,");
        System.out.println("                                       read-only, unrestricted");
        System.out.println("  --level, -l <level>                 Sandbox level:");
        System.out.println("                                       read-only, workspace, full");
        System.out.println("                                       (default: full)");
        System.out.println("  --approval-policy, -a <policy>      Approval policy:");
        System.out.println("                                       ask, auto, silent, custom");
        System.out.println("                                       (default: silent)");
        System.out.println("  --task-id, -t <id>                   Resume from previous task checkpoint");
        System.out.println("  --resume                             Resume from the latest checkpoint");
    }

    /**
     * Parse --profile / -p from CLI arguments.
     * Supports: --profile gpt, -p gpt, --profile=gpt
     */
    private static String parseProfileArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--profile".equals(arg) || "-p".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
            }
            if (arg.startsWith("--profile=")) {
                String val = arg.substring("--profile=".length()).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Parse --role / -r from CLI arguments.
     * Returns "gateway", "worker", "cli", or null if not specified.
     */
    private static String parseRoleArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--role".equals(arg) || "-r".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim().toLowerCase();
                }
            }
            if (arg.startsWith("--role=")) {
                String val = arg.substring("--role=".length()).trim().toLowerCase();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Parse --port / -P from CLI arguments.
     * Returns the port number, or defaultPort if not specified.
     */
    private static int parsePortArg(String[] args, int defaultPort) {
        if (args == null) return defaultPort;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--port".equals(arg) || "-P".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    try {
                        return Integer.parseInt(args[i + 1].trim());
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid port value: {}", args[i + 1]);
                    }
                }
            }
            if (arg.startsWith("--port=")) {
                try {
                    return Integer.parseInt(arg.substring("--port=".length()).trim());
                } catch (NumberFormatException e) {
                    logger.warn("Invalid port value: {}", arg);
                }
            }
        }
        return defaultPort;
    }

    /**
     * Parse --instance-id / -i from CLI arguments.
     * Returns the instance ID string, or null if not specified.
     */
    private static String parseInstanceIdArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--instance-id".equals(arg) || "-i".equals(arg) || "-id".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
            }
            if (arg.startsWith("--instance-id=")) {
                String val = arg.substring("--instance-id=".length()).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Parse --gateway-url / -u from CLI arguments.
     * Sets the gateway.url system property used by worker config sync and registration.
     */
    private static String parseGatewayUrlArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--gateway-url".equals(arg) || "-u".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
            }
            if (arg.startsWith("--gateway-url=")) {
                String val = arg.substring("--gateway-url=".length()).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Parse --capability / -c from CLI arguments.
     */
    private static String parseCapabilityFileArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--capability".equals(arg) || "-c".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
            }
            if (arg.startsWith("--capability=")) {
                String val = arg.substring("--capability=".length()).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Parse --description / -d from CLI arguments.
     */
    private static String parseDescriptionArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--description".equals(arg) || "-desc".equals(arg) || "-d".equals(arg)) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
            }
            if (arg.startsWith("--description=")) {
                String val = arg.substring("--description=".length()).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Parse --daemonize / -d from CLI arguments.
     * Supports: --daemonize, --daemonize=true, -d, -d true
     * Note: -d overlaps with --description, but daemonize only applies in gateway mode.
     */
    private static boolean parseDaemonizeArg(String[] args) {
        if (args == null) return false;
        for (int i = 0; i < args.length; i++) {
            if ("--daemonize".equals(args[i]) || "-d".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return "true".equalsIgnoreCase(args[i + 1]);
                }
                return true;
            }
            if (args[i].startsWith("--daemonize=")) {
                return "true".equalsIgnoreCase(args[i].substring("--daemonize=".length()));
            }
        }
        return false;
    }

    // ==================== Execute Mode Arg Parsing ====================

    /**
     * Parse --execute / -e from CLI arguments.
     * Returns the prompt string, empty string (bare -e), or null if not specified.
     */
    private static String parseExecuteArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--execute".equals(args[i]) || "-e".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
                return ""; // bare -e means read from stdin
            }
            if (args[i].startsWith("--execute=")) {
                String val = args[i].substring("--execute=".length()).trim();
                return val.isEmpty() ? "" : val;
            }
        }
        return null;
    }

    /**
     * Parse --format / -f from CLI arguments. Default: "text"
     * Values: text, json, xml, html, bin
     */
    private static String parseFormatArg(String[] args) {
        if (args == null) return "text";
        for (int i = 0; i < args.length; i++) {
            if ("--format".equals(args[i]) || "-f".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim().toLowerCase();
                }
            }
            if (args[i].startsWith("--format=")) {
                String val = args[i].substring("--format=".length()).trim();
                return val.isEmpty() ? "text" : val.toLowerCase();
            }
        }
        return "text";
    }

    /**
     * Parse --encode / -E from CLI arguments. Default: null (UTF-8)
     */
    private static String parseEncodeArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--encode".equals(args[i]) || "-E".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
            }
            if (args[i].startsWith("--encode=")) {
                String val = args[i].substring("--encode=".length()).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Parse --file-path / -F from CLI arguments. Default: null (stdout)
     */
    private static String parseFilePathArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--file-path".equals(args[i]) || "-F".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
            }
            if (args[i].startsWith("--file-path=")) {
                String val = args[i].substring("--file-path=".length()).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Parse --task-id / -t from CLI arguments. Returns null if not specified.
     */
    private static String parseTaskIdArg(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--task-id".equals(args[i]) || "-t".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return args[i + 1].trim();
                }
            }
            if (args[i].startsWith("--task-id=")) {
                String val = args[i].substring("--task-id=".length()).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Check if --resume flag is present in CLI arguments.
     */
    private static boolean isResumeArg(String[] args) {
        if (args == null) return false;
        for (int i = 0; i < args.length; i++) {
            if ("--resume".equals(args[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse --level / -l for execute mode. Default: FULL (execute mode trusts the prompt).
     * Supports: read-only, workspace, full
     */
    private static SandboxLevel parseSandboxLevelForExecute(String[] args) {
        // First check for -m preset
        ModePresetForExecute preset = parseModePresetForExecute(args);
        if (preset != null) return preset.level;

        if (args == null) return SandboxLevel.FULL;
        for (int i = 0; i < args.length; i++) {
            if ("--level".equals(args[i]) || "-l".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return parseSandboxLevelValue(args[i + 1]);
                }
            }
            if (args[i].startsWith("--level=")) {
                return parseSandboxLevelValue(args[i].substring("--level=".length()));
            }
        }
        return SandboxLevel.FULL;
    }

    /**
     * Parse --approval-policy / -a for execute mode. Default: SILENT.
     * Supports: ask, auto, silent, custom
     * Backward compat: bare -a → SILENT
     */
    private static ApprovalPolicy parseApprovalPolicyForExecute(String[] args) {
        // First check for -m preset
        ModePresetForExecute preset = parseModePresetForExecute(args);
        if (preset != null) return preset.policy;

        if (args == null) return ApprovalPolicy.SILENT;
        for (int i = 0; i < args.length; i++) {
            if ("--approval-policy".equals(args[i]) || "-a".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return parseApprovalPolicyValue(args[i + 1]);
                }
                return ApprovalPolicy.SILENT; // bare -a
            }
            if (args[i].startsWith("--approval-policy=")) {
                return parseApprovalPolicyValue(args[i].substring("--approval-policy=".length()));
            }
        }
        return ApprovalPolicy.SILENT;
    }

    /** Simple holder for -m preset in execute mode. */
    private static class ModePresetForExecute {
        final SandboxLevel level;
        final ApprovalPolicy policy;
        ModePresetForExecute(SandboxLevel level, ApprovalPolicy policy) {
            this.level = level;
            this.policy = policy;
        }
    }

    private static ModePresetForExecute parseModePresetForExecute(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if ("--mode".equals(args[i]) || "-m".equals(args[i])) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    return resolveModePreset(args[i + 1]);
                }
            }
            if (args[i].startsWith("--mode=")) {
                return resolveModePreset(args[i].substring("--mode=".length()));
            }
        }
        return null;
    }

    private static ModePresetForExecute resolveModePreset(String value) {
        if (value == null) return null;
        String lower = value.trim().toLowerCase();
        switch (lower) {
            case "normal":      return new ModePresetForExecute(SandboxLevel.FULL, ApprovalPolicy.SILENT);
            case "auto":        return new ModePresetForExecute(SandboxLevel.FULL, ApprovalPolicy.AUTO);
            case "silent":      return new ModePresetForExecute(SandboxLevel.FULL, ApprovalPolicy.SILENT);
            case "readonly":
            case "read-only":   return new ModePresetForExecute(SandboxLevel.READ_ONLY, ApprovalPolicy.SILENT);
            case "unrestricted": return new ModePresetForExecute(SandboxLevel.FULL, ApprovalPolicy.AUTO);
            default:
                logger.warn("Unknown mode preset for execute: {}, ignoring", value);
                return null;
        }
    }

    private static SandboxLevel parseSandboxLevelValue(String value) {
        if (value == null) return SandboxLevel.FULL;
        String lower = value.trim().toLowerCase();
        switch (lower) {
            case "read-only":
            case "readonly": return SandboxLevel.READ_ONLY;
            case "workspace": return SandboxLevel.WORKSPACE;
            case "full": return SandboxLevel.FULL;
            default:
                logger.warn("Unknown sandbox level: {}, defaulting to FULL", value);
                return SandboxLevel.FULL;
        }
    }

    private static ApprovalPolicy parseApprovalPolicyValue(String value) {
        if (value == null) return ApprovalPolicy.SILENT;
        String lower = value.trim().toLowerCase();
        switch (lower) {
            case "ask": return ApprovalPolicy.ASK;
            case "auto": return ApprovalPolicy.AUTO;
            case "silent": return ApprovalPolicy.SILENT;
            case "custom": return ApprovalPolicy.CUSTOM;
            default:
                logger.warn("Unknown approval policy: {}, defaulting to SILENT", value);
                return ApprovalPolicy.SILENT;
        }
    }

    /**
     * Resolve the prompt for execute mode:
     * 1. -e "prompt text" → use that value
     * 2. -e (bare, no value) → read stdin (pipe)
     * 3. -e (bare, no pipe) → error
     */
    private static String resolveExecutePrompt(String executeValue, String[] args) {
        // Case 1: -e "explicit prompt text"
        if (executeValue != null && !executeValue.isEmpty()) {
            return executeValue;
        }

        // Case 2: -e (bare) — read from stdin pipe
        if (System.console() == null) {
            try {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                java.io.InputStream in = System.in;
                while ((len = in.read(buf)) != -1) {
                    buffer.write(buf, 0, len);
                }
                String stdin = buffer.toString("UTF-8").trim();
                if (!stdin.isEmpty()) return stdin;
            } catch (java.io.IOException e) {
                logger.debug("No stdin data: {}", e.getMessage());
            }
        }

        // Case 3: -e (bare, no pipe data or terminal)
        throw new IllegalArgumentException(
            "No prompt provided. Usage: -e \"your prompt\" or echo \"prompt\" | java -jar diatom-cli.jar -e");
    }

    public static void main(String[] args) {
        // Install JUL-to-SLF4J bridge to route java.util.logging through Logback
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        // Clean up sources from previous self-upgrade if marker exists
        cleanupAfterUpgrade();

        // Show help and exit immediately if requested
        if (parseHelpArg(args)) {
            printHelp();
            return;
        }

        // Apply pending updates (core first, then custom)
        applyPendingCoreUpdate();
        applyPendingCustomUpdate();

        // Check and log custom version
        checkCustomVersion();

        // Parse active profile from --profile / -p CLI args
        String activeProfile = parseProfileArg(args);
        if (activeProfile != null) {
            logger.info("Active profile: {}", activeProfile);
        }

        // Parse gateway/worker CLI args and set instance isolation
        String role = parseRoleArg(args);
        String roleSource = "-r/--role CLI argument";
        // Fallback: DIATOM_ROLE environment variable
        if (role == null) {
            role = System.getenv("DIATOM_ROLE");
            roleSource = "DIATOM_ROLE env";
        }
        if ("gateway".equals(role) && parseDaemonizeArg(args)) {
            System.setProperty("diatom.role", "gateway-daemon");
        } else if (role != null) {
            System.setProperty("diatom.role", role);
        } else {
            System.setProperty("diatom.role", "cli");
            roleSource = "default";
        }
        String instanceId = parseInstanceIdArg(args);
        int port = parsePortArg(args, 8080);
        String gatewayUrl = parseGatewayUrlArg(args);
        String capabilityFile = parseCapabilityFileArg(args);
        String description = parseDescriptionArg(args);
        if (gatewayUrl != null) {
            System.setProperty("gateway.url", gatewayUrl);
        }
        if (instanceId != null && !instanceId.isEmpty()) {
            System.setProperty("diatom.instance.id", instanceId);
        }
        WorkspaceDirResolver.parseCliArg(args);

        // Parse execute mode args
        String executePrompt = parseExecuteArg(args);
        String outputFormat = parseFormatArg(args);
        String outputEncoding = parseEncodeArg(args);
        String outputFilePath = parseFilePathArg(args);
        String explicitTaskId = parseTaskIdArg(args);
        boolean resumeFlag = isResumeArg(args);

        // Initialize I18n and load SPI extensions
        AppConfig config = new AppConfig(activeProfile);
        CoreInitializer.initI18n(config);
        CoreInitializer.loadSpiExtensions(config);

        // Fallback: diatom.role from config file (lowest priority)
        if ("cli".equals(System.getProperty("diatom.role"))) {
            String cfgRole = config.getProperty("diatom.role", "");
            if (!cfgRole.isEmpty()) {
                role = cfgRole.trim().toLowerCase();
                System.setProperty("diatom.role", role);
                roleSource = "diatom.role config";
            }
        }

        System.out.println("  [Config] Role: " + role + " (from " + roleSource + ")");

        printBanner(role);

        // Initialize database
        DatabaseManager dbManager = null;
        HistoryManager historyManager = null;
        TaskCheckpointManager checkpointManager = null;
        CommandKnowledgeManager commandKnowledgeManager = null;
        CommandPermissionEngine commandPermissionEngine = null;
        ConfigManager configManager = null;
        ChangeLogDao changeLogDao = null;
        SnapshotDao snapshotDao = null;
        TaskDao taskDao = null;
        WorkspaceRegistry workspaceRegistry = null;
        Long workspaceId = null;
        Long defaultProjectId = null;
        try {
            dbManager = new HibernateDatabaseManager();
            dbManager.initialize();
            historyManager = new HistoryManager(dbManager, config.getHistoryMaxSize(), config.getWorkspaceDir());
            checkpointManager = new TaskCheckpointManager(dbManager);
            changeLogDao = new ChangeLogDao(dbManager);
            snapshotDao = new SnapshotDao(dbManager);
            taskDao = new TaskDao(dbManager);
            logger.info("Database initialized successfully");

            // Initialize config manager with properties and database
            configManager = new ConfigManager(dbManager);
            configManager.loadFromDatabase();

            // Initialize system config seed data (loads properties internally)
            DatabaseInitializer initializer = new DatabaseInitializer(dbManager);
            initializer.initialize();

            // Apply SystemConfigProvider extensions (register custom config items)
            CoreInitializer.registerSystemConfigExtensions(dbManager);

            // Initialize workspace registry for multi-project support
            workspaceRegistry = new WorkspaceRegistry(dbManager, config.getWorkspaceDir());
            logger.info("Workspace registry initialized: primary={}", config.getWorkspaceDir());

            // Register primary workspace into workspace_context and get workspace ID
            workspaceId = workspaceRegistry.ensurePrimaryWorkspace();
            logger.info("Primary workspace registered: id={}", workspaceId);

            // Scan workspace subdirectories for projects and register them
            defaultProjectId = registerWorkspaceProjects(dbManager, config.getWorkspaceDir(), workspaceId);
            logger.info("Default project id={}", defaultProjectId);

            // Initialize command knowledge base
            commandKnowledgeManager = new CommandKnowledgeManager(dbManager);
            commandKnowledgeManager.loadFromDatabase();
            commandKnowledgeManager.loadSeedData();
            commandPermissionEngine = new CommandPermissionEngine(commandKnowledgeManager);
            logger.info("Command knowledge base initialized with {} commands", commandKnowledgeManager.getStats().total);
            logger.info("Config manager initialized with {} system configs", configManager.getAll().size());
        } catch (Exception e) {
            logger.warn("Failed to initialize database, history and checkpoints will not be persisted: {}", e.getMessage());
            dbManager = null; // ensure downstream null checks work correctly
        }

        try {

            // Debug: print config loading path
            logger.info("Config loaded from: {}", config.getLoadedFromPath());
            logger.info("API Key configured: {}", config.getApiKey().isEmpty() ? "NO" : "YES (length=" + config.getApiKey().length() + ")");

            // Sync config to user directory on first run
            // Always sync if JAR directory has config file (including api.key)
            config.syncToUserDir();

            // Initialize authorized path manager
            AuthorizedPathManager authManager = new AuthorizedPathManager();

            // Auto-authorize install home for self-upgrade and user home for global config
            String installDir = InstallPaths.getInstallHome().toString();
            authManager.authorize(installDir);
            String userConfigDir = InstallPaths.getUserHome().toString();
            authManager.authorize(userConfigDir);
            logger.info("Auto-authorized install directory: {}", installDir);
            logger.info("Auto-authorized user config directory: {}", userConfigDir);

            // Initialize MCP filesystem server
            McpClientManager mcpManager = new McpClientManager();
            boolean allowExternal = config.isAllowExternalResources();
            mcpManager.startBuiltInServer("filesystem", config.getWorkspaceDir(), authManager, allowExternal);
            mcpManager.startBuiltInServer("system", config.getWorkspaceDir());
            mcpManager.startBuiltInServer("build", config.getWorkspaceDir());
            // Initialize checkpoint server with database for task resume functionality
            if (dbManager != null) {
                mcpManager.startCheckpointServer(dbManager);
                logger.info("MCP Checkpoint server started (task resume enabled)");
            }
            // Load and connect external MCP servers from ~/.diatom/mcpservers/ and project .diatom/mcpservers/
            // Project-level configs override global configs with same server name
            java.nio.file.Path projectPath = java.nio.file.Paths.get(config.getWorkspaceDir());
            mcpManager.loadAndConnectFromConfig(projectPath, config.getWorkspaceDir());
            logger.info("MCP Filesystem server started (allow_external={})", allowExternal);
            logger.info("MCP System server started (HIGH RISK - software detection)");

            AiHttpClient httpClient;
            ModelAdapter adapter;
            String apiUrl;

            // 自动检测 API 格式: openai / anthropic / responses / auto
            boolean isAnthropic = CoreInitializer.detectAnthropicFormat(config);
            boolean isResponses = !isAnthropic && CoreInitializer.detectResponsesFormat(config);

            if (isAnthropic) {
                apiUrl = CoreInitializer.resolveAnthropicEndpoint(config);
                httpClient = new AiHttpClient(config.getApiKey(), apiUrl, AiHttpClient.AuthStyle.ANTHROPIC);
                adapter = new com.github.obhen233.core.adapter.AnthropicAdapter(config.getModel(), config.getMaxTokens());
                logger.info("Using Anthropic format for model: {} at {}", config.getModel(), apiUrl);
            } else if (isResponses) {
                apiUrl = CoreInitializer.resolveResponsesEndpoint(config);
                httpClient = new AiHttpClient(config.getApiKey(), apiUrl);
                adapter = new com.github.obhen233.core.adapter.ResponsesAdapter(config.getModel(), config.getMaxTokens());
                logger.info("Using Responses API format for model: {} at {}", config.getModel(), apiUrl);
            } else {
                apiUrl = config.getApiUrl();
                httpClient = new AiHttpClient(config.getApiKey(), apiUrl);
                adapter = new OpenAIAdapter(config.getModel(), config.getMaxTokens());
                logger.info("Using OpenAI format for model: {} at {}", config.getModel(), apiUrl);
            }

            // Initialize LLM command classifier for self-learning
            if (commandKnowledgeManager != null && httpClient != null && adapter != null) {
                com.github.obhen233.core.ai.LlmCommandClassifier llmClassifier =
                    new com.github.obhen233.core.ai.LlmCommandClassifier(httpClient, adapter, apiUrl, commandKnowledgeManager);
                commandKnowledgeManager.setLlmClassifier(llmClassifier);
                logger.info("LLM command classifier initialized for self-learning");
            }

            SkillManager skillManager = new SkillManager();
            SystemPromptManager promptManager = new SystemPromptManager();
            ProjectIndexer projectIndexer = new ProjectIndexer(config.getWorkspaceDir());

            // Initialize ContextCacheManager and link to ProjectIndexer
            ContextCacheManager contextCacheManager = null;
            if (dbManager != null) {
                contextCacheManager = new ContextCacheManager(dbManager);
                projectIndexer.setContextCache(contextCacheManager);
                logger.info("Project context caching enabled");
            }

            // Initialize SystemInfo for system environment details
            SystemInfo systemInfo = new SystemInfo();
            logger.info("System: {} {}, CPUs={}, Memory={}",
                        systemInfo.getOsName(), systemInfo.getOsVersion(),
                        systemInfo.getAvailableProcessors(),
                        formatBytes(systemInfo.getMaxMemory()));

            // Initialize CommandTools with sandbox config
            // Include detected tool paths (Maven, Python, Node, Git) in safe PATH
            CommandTools.Config cmdConfig = new CommandTools.Config()
                .setAllowedCommands(config.getCommandWhitelist())
                .setTimeoutSeconds(config.getCommandTimeout())
                .setMaxOutputBytes(config.getCommandMaxOutputBytes())
                .setAllowAll(!config.isCommandWhitelistMode())
                .setWorkingDir(config.getWorkspaceDir())
                .setShellType(systemInfo.getShellType())
                .setShellPath(systemInfo.getDetectedShell())
                .setMavenPath(systemInfo.getDetectedMaven())
                .setPythonPath(systemInfo.getDetectedPython())
                .setNodePath(systemInfo.getDetectedNode())
                .setGitPath(systemInfo.getDetectedGitPath());

            // Worker mode: resolve workspace from Gateway first, then create agent
            if ("worker".equals(role)) {
                ServerModeLauncher.prefetchWorkspace(args, config);
                ReActAgent workerAgent = createAgent(config, authManager, cmdConfig, skillManager,
                    promptManager, configManager, dbManager, workspaceRegistry,
                    commandKnowledgeManager, httpClient, adapter, projectIndexer,
                    mcpManager, apiUrl, systemInfo);
                // Notify lifecycle hooks that initialization is complete
                for (AppLifecycleHook hook : SpiLoader.getAll(AppLifecycleHook.class)) {
                    try {
                        hook.onAfterInit();
                    } catch (Exception e) {
                        logger.warn("Lifecycle hook onAfterInit failed: {}", e.getMessage());
                    }
                }
                ServerModeLauncher.start(args, config, workerAgent, httpClient, adapter, apiUrl, capabilityFile, description, dbManager);
                return;
            }

            // Use unified tool registry center for consistent tool registration
            ReActAgent agent = createAgent(config, authManager, cmdConfig, skillManager,
                promptManager, configManager, dbManager, workspaceRegistry,
                commandKnowledgeManager, httpClient, adapter, projectIndexer,
                mcpManager, apiUrl, systemInfo);

            // Inject change log / snapshot DAOs into SessionTracker
            if (agent.getSessionTracker() != null) {
                agent.getSessionTracker().setDaos(changeLogDao, snapshotDao);
            }

            // Set checkpoint manager for task resume functionality
            if (checkpointManager != null) {
                agent.setCheckpointManager(checkpointManager);
            }

            // Set command permission engine for knowledge-based command checking
            if (commandPermissionEngine != null) {
                agent.setCommandPermissionEngine(commandPermissionEngine);
            }

            // Inject SourceCodeExtensionsDao for ToolResultSummarizer
            if (dbManager != null && agent.getToolExecutor() != null) {
                agent.getToolExecutor().setSourceCodeExtensionsDao(new SourceCodeExtensionsDao(dbManager));
            }

            // Apply UiCustomizer if available
            UiCustomizer uiCustomizer = SpiLoader.getFirst(UiCustomizer.class, null);

            com.github.obhen233.core.agent.TaskManager taskManager = new com.github.obhen233.core.agent.TaskManager();
            if (taskDao != null) {
                taskManager.setTaskDao(taskDao);
            }
            if (defaultProjectId != null) {
                taskManager.setDefaultProjectId(defaultProjectId);
            }

            TerminalUI ui = new TerminalUI(agent, authManager, historyManager, dbManager, checkpointManager, configManager,
                config.getWorkspaceDir(), taskManager, snapshotDao, taskDao, changeLogDao);

            // Now set the timeout callback on CommandTools via the registry
            CommandTools.TimeoutCallback timeoutCallback = ui.getTimeoutCallback();
            if (timeoutCallback != null && _appRegistryCenter != null) {
                _appRegistryCenter.setTimeoutCallback(timeoutCallback);
            }
            _appRegistryCenter = null; // clear reference, no longer needed

            // Notify lifecycle hooks that initialization is complete
            for (AppLifecycleHook hook : SpiLoader.getAll(AppLifecycleHook.class)) {
                try {
                    hook.onAfterInit();
                } catch (Exception e) {
                    logger.warn("Lifecycle hook onAfterInit failed: {}", e.getMessage());
                }
            }

            // Mode branching: gateway mode starts its own server and blocks
            if ("gateway".equals(role)) {
                GatewayModeLauncher.start(args, config, httpClient, adapter, configManager, dbManager);
                return;
            }

            // Execute mode: run single prompt and exit (only when -e is explicitly given)
            if (executePrompt != null) {
                if ("worker".equals(role)) {
                    System.err.println("--execute / -e is not supported in worker mode");
                    System.exit(1);
                }
                try {
                    String prompt = resolveExecutePrompt(executePrompt, args);
                    SandboxLevel level = parseSandboxLevelForExecute(args);
                    ApprovalPolicy policy = parseApprovalPolicyForExecute(args);
                    java.nio.charset.Charset encoding = outputEncoding != null
                        ? java.nio.charset.Charset.forName(outputEncoding)
                        : java.nio.charset.StandardCharsets.UTF_8;
                    java.nio.file.Path filePath = outputFilePath != null
                        ? java.nio.file.Paths.get(outputFilePath) : null;
                    OutputFormatter formatter = OutputFormatter.forFormat(outputFormat, encoding);

                    // Resolve taskId: explicit --task-id takes priority, --resume uses latest checkpoint
                    String resolvedTaskId = explicitTaskId;
                    if (resolvedTaskId == null && resumeFlag && checkpointManager != null) {
                        java.util.List<TaskCheckpointManager.TaskCheckpoint> checkpoints =
                            checkpointManager.listCheckpoints();
                        if (checkpoints != null && !checkpoints.isEmpty()) {
                            resolvedTaskId = checkpoints.get(0).getTaskId();
                            logger.info("--resume: latest checkpoint taskId={}", resolvedTaskId);
                        } else {
                            System.err.println("--resume: no checkpoints found");
                            System.exit(1);
                        }
                    }

                    new ExecuteModeRunner(formatter, filePath, level, policy, resolvedTaskId)
                        .execute(agent, prompt);
                } catch (Exception e) {
                    System.err.println("Execute failed: " + e.getMessage());
                    logger.error("Execute mode failed", e);
                    System.exit(1);
                }
                return;
            }

            ui.start();

            // restart command launched a new JVM process; force old JVM to exit
            // (non-daemon threads from Agent/MCP prevent natural JVM exit)
            if (TerminalUI.isRestartRequested()) {
                System.exit(0);
            }
        } catch (Exception e) {
            logger.error("Failed to start application", e);
            System.err.println(I18n.get("failed_to_start", e.getMessage()));
            System.exit(1);
        } finally {
            if (dbManager != null) {
                dbManager.close();
            }
        }
    }

    /**
     * Scan workspace subdirectories for projects and register them in the database.
     * <p>
     * Looks for known project marker files (pom.xml, package.json, etc.) in direct
     * subdirectories of the workspace. If no sub-project is found, registers the
     * workspace itself as the default project.
     *
     * @param db           the database manager
     * @param workspaceDir the workspace root path
     * @param wsId         the workspace ID from workspace_context
     * @return the default project ID (first discovered, or workspace itself)
     */
    private static Long registerWorkspaceProjects(DatabaseManager db, String workspaceDir, Long wsId) {
        if (db == null || wsId == null) return null;

        java.nio.file.Path wsPath = java.nio.file.Paths.get(workspaceDir);
        Long firstProjectId = null;

        // Scan direct subdirectories for project markers
        java.io.File[] subDirs = wsPath.toFile().listFiles(java.io.File::isDirectory);
        if (subDirs != null) {
            for (java.io.File subDir : subDirs) {
                String projectType = null;
                for (java.util.Map.Entry<String, String> marker : PROJECT_MARKERS.entrySet()) {
                    if (new java.io.File(subDir, marker.getKey()).exists()) {
                        projectType = marker.getValue();
                        break;
                    }
                }
                if (projectType == null) continue;

                String projectPath = subDir.toPath().toAbsolutePath().normalize().toString();
                String projectName = subDir.getName();
                Long projectId = insertProject(db, wsId, projectPath, projectName, projectType);
                if (projectId != null && firstProjectId == null) {
                    firstProjectId = projectId;
                }
            }
        }

        // If no sub-projects found, register workspace itself as the default project
        if (firstProjectId == null) {
            String wsName = wsPath.getFileName().toString();
            String wsPathStr = wsPath.toAbsolutePath().normalize().toString();
            String wsType = detectProjectType(wsPath);
            firstProjectId = insertProject(db, wsId, wsPathStr, wsName, wsType);
            if (firstProjectId != null) {
                logger.info("Default project (workspace root): {} (id={}, type={})", wsName, firstProjectId, wsType);
            }
        } else {
            logger.info("Found {} sub-project(s) under workspace", (subDirs != null ? subDirs.length : 0));
        }

        return firstProjectId;
    }

    /**
     * Insert a project record into project_context if it doesn't already exist.
     */
    private static Long insertProject(DatabaseManager db, Long wsId, String projectPath,
                                       String projectName, String projectType) {
        try (Session session = db.getSessionFactory().openSession()) {
            // Check if already exists
            ProjectContextEntity existing = session.createQuery(
                    "FROM ProjectContextEntity WHERE projectPath = :path", ProjectContextEntity.class)
                    .setParameter("path", projectPath)
                    .uniqueResult();
            if (existing != null) {
                return existing.getId();
            }

            // Insert new project
            long now = System.currentTimeMillis();
            session.beginTransaction();
            ProjectContextEntity entity = new ProjectContextEntity();
            entity.setWorkspaceId(wsId);
            entity.setProjectPath(projectPath);
            entity.setProjectName(projectName);
            entity.setProjectType(projectType != null ? projectType : "unknown");
            entity.setIndexedAt(now);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            session.persist(entity);
            session.getTransaction().commit();
            long id = entity.getId();
            logger.info("Registered project: {} (id={}, type={})", projectName, id, projectType);
            return id;
        } catch (Exception e) {
            logger.warn("Failed to insert project: {}", projectPath, e);
            return null;
        }
    }

    /**
     * Detect project type from workspace root using marker files.
     */
    private static String detectProjectType(java.nio.file.Path dir) {
        for (java.util.Map.Entry<String, String> marker : PROJECT_MARKERS.entrySet()) {
            if (java.nio.file.Files.exists(dir.resolve(marker.getKey()))) {
                return marker.getValue();
            }
        }
        return "unknown";
    }

    /**
     * Create ToolRegistryCenter and ReActAgent with the given configuration.
     * Extracted as a separate method so worker mode can call it after
     * {@link com.github.obhen233.core.gateway.ServerModeLauncher#prefetchWorkspace(String[], AppConfig)}.
     */
    private static ReActAgent createAgent(AppConfig config,
                                           AuthorizedPathManager authManager,
                                           CommandTools.Config cmdConfig,
                                           SkillManager skillManager,
                                           SystemPromptManager promptManager,
                                           ConfigManager configManager,
                                           DatabaseManager dbManager,
                                           WorkspaceRegistry workspaceRegistry,
                                           CommandKnowledgeManager commandKnowledgeManager,
                                           AiHttpClient httpClient,
                                           ModelAdapter adapter,
                                           ProjectIndexer projectIndexer,
                                           McpClientManager mcpManager,
                                           String apiUrl,
                                           SystemInfo systemInfo) {
        ToolRegistryCenter.Config registryConfig = new ToolRegistryCenter.Config()
            .setWorkspaceDir(config.getWorkspaceDir())
            .setAuthManager(authManager)
            .setCommandConfig(cmdConfig)
            .setStandaloneMode(isStandaloneJarMode())
            .setSkillManager(skillManager)
            .setPromptManager(promptManager)
            .setConfigManager(configManager)
            .setDbManager(dbManager)
            .setWorkspaceRegistry(workspaceRegistry)
            .setAllowExternalResources(config.isAllowExternalResources());

        ToolRegistryCenter registryCenter = ToolRegistryCenter.createStandard(registryConfig);
        _appRegistryCenter = registryCenter;
        ToolRegistry registry = registryCenter.getRegistry();

        // Set command knowledge manager on CommandTools for dynamic permission checking
        if (commandKnowledgeManager != null) {
            registryCenter.setKnowledgeManager(commandKnowledgeManager);
        }

        // Register custom tools from SPI ToolRegistrar extensions
        for (ToolRegistrar registrar : SpiLoader.getAll(ToolRegistrar.class)) {
            try {
                registrar.registerTools(registry);
                logger.info("Registered tools from: {}", registrar.getClass().getName());
            } catch (Exception e) {
                logger.warn("Failed to register tools from {}: {}", registrar.getClass().getName(), e.getMessage());
            }
        }

        logger.info("Command sandbox: whitelist mode={}, commands={}, timeout={}s, shell={}",
                    config.isCommandWhitelistMode(), config.getCommandWhitelist(), config.getCommandTimeout(), systemInfo.getShellType());

        ReActAgent agent = new ReActAgent(httpClient, adapter, registry, skillManager, promptManager,
            projectIndexer, mcpManager, config.getModel(), apiUrl, null, systemInfo,
            config.getContextWindow());
        return agent;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int unit = 0;
        double value = bytes;
        while (value >= 1024 && unit < 3) {
            value /= 1024;
            unit++;
        }
        String[] units = {"B", "KB", "MB", "GB"};
        return String.format("%.1f %s", value, units[unit]);
    }

}
