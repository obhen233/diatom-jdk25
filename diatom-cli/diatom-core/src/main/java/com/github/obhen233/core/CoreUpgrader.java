package com.github.obhen233.core;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.core.spi.SpiMetadataReader;
import com.github.obhen233.spi.UpgradePolicy;
import com.github.obhen233.spi.impl.PromptUpgradePolicy;
import com.github.obhen233.util.InstallPaths;
import com.github.obhen233.util.JarUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Core version upgrader.
 * Checks Maven Central for newer core versions and manages the upgrade process.
 * Runs inside the custom application's JVM during startup.
 */
public class CoreUpgrader {

    private static final Logger logger = LoggerFactory.getLogger(CoreUpgrader.class);

    private static final String MAVEN_CENTRAL_METADATA_URL =
            "https://repo1.maven.org/maven2/com/github/obhen233/diatom-core/maven-metadata.xml";
    private static final String MAVEN_CENTRAL_REPO_URL =
            "https://repo1.maven.org/maven2/com/github/obhen233/diatom-core/";
    private static final String CORE_VERSION_FILE = "core-version";
    private static final String VERSIONS_BACKUP_DIR = "versions";

    private static Path getAppHome() {
        return InstallPaths.getInstallHome();
    }
    private static Path getCustomDir() {
        return InstallPaths.getCustomDir();
    }
    private static Path getLibDir() {
        return InstallPaths.getLibDir();
    }

    private static Path getCoreJar() {
        return JarUtils.findCoreJar(getLibDir());
    }

    private final AppConfig config;
    private final UpgradePolicy upgradePolicy;
    private final boolean compileCheckEnabled;
    private final String compileFailAction;

    public CoreUpgrader(AppConfig config) {
        this.config = config;
        String policyMode = config.getProperty("core.upgrade.policy", "prompt");
        this.upgradePolicy = SpiLoader.getFirst(UpgradePolicy.class, new PromptUpgradePolicy(policyMode));
        this.compileCheckEnabled = "true".equals(config.getProperty("core.upgrade.compile-check", "true"));
        this.compileFailAction = config.getProperty("core.upgrade.on-compile-fail", "rollback");
    }

    /**
     * Check for core upgrade and perform it if needed.
     * @return true if an upgrade was performed (caller should restart)
     */
    public boolean checkAndUpgrade() {
        try {
            String currentVersion = getCurrentCoreVersion();
            String latestVersion = getLatestCoreVersionFromMaven();

            if (latestVersion == null) {
                logger.info("Could not check Maven Central for core updates");
                return false;
            }

            if (currentVersion.equals(latestVersion)) {
                logger.info("Core is up-to-date: {}", currentVersion);
                return false;
            }

            logger.info("Core upgrade available: {} -> {}", currentVersion, latestVersion);

            // Ask policy whether to upgrade
            if (!upgradePolicy.shouldUpgrade(currentVersion, latestVersion)) {
                logger.info("Core upgrade skipped by policy");
                return false;
            }

            // Perform upgrade
            return doUpgrade(currentVersion, latestVersion);

        } catch (Exception e) {
            logger.error("Core upgrade check failed", e);
            return false;
        }
    }

    /**
     * Perform the actual upgrade: download, backup, replace, compile-check, restart.
     */
    private boolean doUpgrade(String oldVersion, String newVersion) {
        try {
            // 1. Backup current JAR
            backupCore(oldVersion);

            // 2. Download new JAR
            Path downloadedJar = downloadCore(newVersion);
            if (downloadedJar == null) {
                upgradePolicy.onUpgradeFailed(oldVersion, newVersion, "Download failed");
                restoreBackup(oldVersion);
                return false;
            }

            // 3. Check SPI compatibility
            SpiMetadataReader.SpiDiff spiDiff = checkSpiCompatibility(downloadedJar);
            if (spiDiff != null && spiDiff.hasChanges()) {
                String report = spiDiff.toMarkdown();
                logger.info("SPI changes detected: {}", report);
                // Log the SPI changes for the user to review
                System.out.println("\n" + report);
            }

            // 4. Replace current JAR
            Files.createDirectories(getCustomDir());
            Path tempTarget = getCustomDir().resolve("core.jar.tmp");
            Files.move(downloadedJar, tempTarget, StandardCopyOption.REPLACE_EXISTING);
            Path coreJar = getCoreJar();
            if (coreJar != null) {
                Files.move(tempTarget, coreJar, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // No existing core jar found, just move to lib dir
                Path newCoreJar = getLibDir().resolve("diatom-core-" + newVersion + ".jar");
                Files.move(tempTarget, newCoreJar, StandardCopyOption.REPLACE_EXISTING);
            }

            // 5. Compile check (optional)
            if (compileCheckEnabled) {
                boolean compileOk = runCompileCheck();
                if (!compileOk) {
                    String msg = "Compile check failed after core upgrade";
                    if ("rollback".equals(compileFailAction)) {
                        upgradePolicy.onUpgradeFailed(oldVersion, newVersion, msg);
                        restoreBackup(oldVersion);
                        return false;
                    } else {
                        logger.warn("{} - continuing despite failure (policy: warn)", msg);
                    }
                }
            }

            // 6. Save version and notify
            saveCoreVersion(newVersion);
            upgradePolicy.onUpgradeSuccess(oldVersion, newVersion);

            // 7. Schedule restart
            scheduleRestart();
            return true;

        } catch (Exception e) {
            logger.error("Core upgrade failed", e);
            upgradePolicy.onUpgradeFailed(oldVersion, newVersion, e.getMessage());
            return false;
        }
    }

    /**
     * Read the current core version from ~/.diatom/core-version (tracked version).
     * This is different from ~/.diatom/custom/core-version.txt which is extracted from the JAR.
     */
    private String getCurrentCoreVersion() {
        Path versionFile = getAppHome().resolve(CORE_VERSION_FILE);
        if (!Files.exists(versionFile)) return "";
        try {
            return new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Save the core version to ~/.diatom/core-version (tracked version).
     */
    private void saveCoreVersion(String version) throws IOException {
        Path versionFile = getAppHome().resolve(CORE_VERSION_FILE);
        Files.createDirectories(getAppHome());
        Files.write(versionFile, version.getBytes(StandardCharsets.UTF_8));
        logger.info("Core version saved: {}", version);
    }

    /**
     * Check Maven Central for the latest diatom-core version.
     */
    private String getLatestCoreVersionFromMaven() {
        try {
            URL url = new URL(MAVEN_CENTRAL_METADATA_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Diatom-CLI/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    String releaseVersion = null;
                    String latestVersion = null;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("<release>")) {
                            releaseVersion = line.substring("<release>".length(),
                                    line.indexOf("</release>")).trim();
                        }
                        if (line.startsWith("<latest>")) {
                            latestVersion = line.substring("<latest>".length(),
                                    line.indexOf("</latest>")).trim();
                        }
                    }
                    String version = (releaseVersion != null && !releaseVersion.isEmpty())
                            ? releaseVersion : latestVersion;
                    if (version != null && !version.isEmpty()) {
                        logger.info("Maven Central diatom-core version: {}", version);
                        return version;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Maven Central check failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check SPI compatibility between current and new core versions.
     * @return SPI compatibility report, or null if check failed
     */
    private SpiMetadataReader.SpiDiff checkSpiCompatibility(Path newCoreJar) {
        try {
            Path currentCoreJar = getCoreJar();
            if (currentCoreJar == null || !Files.exists(currentCoreJar)) {
                logger.info("No current core JAR found, skipping SPI compatibility check");
                return null;
            }

            SpiMetadataReader oldReader = SpiMetadataReader.loadFromJar(currentCoreJar);
            SpiMetadataReader newReader = SpiMetadataReader.loadFromJar(newCoreJar);

            SpiMetadataReader.SpiDiff diff = SpiMetadataReader.compare(oldReader, newReader);
            return diff;
        } catch (Exception e) {
            logger.warn("Failed to check SPI compatibility: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Download core JAR from Maven Central to a temp file.
     * @return path to downloaded JAR, or null on failure
     */
    private Path downloadCore(String version) {
        String jarUrl = MAVEN_CENTRAL_REPO_URL + version + "/diatom-core-" + version + ".jar";
        logger.info("Downloading core from: {}", jarUrl);

        try {
            URL url = new URL(jarUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Diatom-CLI/1.0");

            if (conn.getResponseCode() == 200) {
                Path tempFile = Files.createTempFile("diatom-core-", ".jar");
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }

                // Validate JAR
                if (isJarValid(tempFile)) {
                    logger.info("Core JAR downloaded and validated: {}", version);
                    return tempFile;
                } else {
                    logger.warn("Downloaded JAR is invalid");
                    Files.deleteIfExists(tempFile);
                }
            } else {
                logger.warn("Download failed with HTTP code: {}", conn.getResponseCode());
            }
        } catch (Exception e) {
            logger.error("Download failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Backup the current core JAR.
     */
    private void backupCore(String version) throws IOException {
        Path coreJar = getCoreJar();
        if (coreJar == null || !Files.exists(coreJar)) return;

        Path backupDir = getAppHome().resolve(VERSIONS_BACKUP_DIR);
        Files.createDirectories(backupDir);

        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                .format(new java.util.Date());
        Path backupPath = backupDir.resolve("core-" + version + "-" + timestamp + ".jar");
        Files.copy(coreJar, backupPath, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Core backed up: {}", backupPath);
    }

    /**
     * Restore core from the most recent backup for a given version.
     */
    private void restoreBackup(String version) {
        try {
            Path backupDir = getAppHome().resolve(VERSIONS_BACKUP_DIR);
            if (!Files.exists(backupDir)) return;

            // Find the most recent backup for this version
            Path latestBackup;
            try (Stream<Path> files = Files.list(backupDir)) {
                latestBackup = files
                        .filter(p -> p.getFileName().toString().startsWith("core-" + version))
                        .max(Comparator.comparing(p -> p.toFile().lastModified()))
                        .orElse(null);
            }

            if (latestBackup != null && Files.exists(latestBackup)) {
                Path coreJar = getCoreJar();
                if (coreJar != null) {
                    Files.copy(latestBackup, coreJar, StandardCopyOption.REPLACE_EXISTING);
                }
                logger.info("Core restored from backup: {}", latestBackup);
            }
        } catch (IOException e) {
            logger.error("Failed to restore core backup", e);
        }
    }

    /**
     * Run Maven compile check on the diatom-custom module.
     * @return true if compilation was successful
     */
    private boolean runCompileCheck() {
        try {
            logger.info("Running compile check after core upgrade...");

            // Detect Maven
            String mvn = detectMaven();
            if (mvn == null) {
                logger.warn("Maven not found, skipping compile check");
                return true; // Skip check if Maven is not available
            }

            // Find diatom-custom POM
            Path customPom = findCustomPom();
            if (customPom == null) {
                logger.warn("diatom-custom/pom.xml not found, skipping compile check");
                return true;
            }

            // Run mvn compile
            ProcessBuilder pb = new ProcessBuilder(mvn, "compile", "-f", customPom.toString());
            pb.directory(customPom.getParent().toFile());
            pb.inheritIO();

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logger.info("Compile check passed");
                return true;
            } else {
                logger.error("Compile check failed with exit code: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            logger.error("Compile check failed", e);
            return false;
        }
    }

    /**
     * Detect Maven executable.
     */
    private String detectMaven() {
        // Check MAVEN_HOME / M2_HOME environment variable
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome == null) mavenHome = System.getenv("M2_HOME");
        if (mavenHome != null) {
            Path mvnPath = Paths.get(mavenHome, "bin", "mvn" + (System.getProperty("os.name").toLowerCase().contains("win") ? ".cmd" : ""));
            if (Files.exists(mvnPath)) return mvnPath.toString();
        }

        // Check PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] dirs = pathEnv.split(File.pathSeparator);
            String mvnName = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
            for (String dir : dirs) {
                Path p = Paths.get(dir, mvnName);
                if (Files.exists(p)) return p.toString();
            }
        }

        return null;
    }

    /**
     * Find the diatom-custom POM file.
     * Uses a robust multi策略查找：
     * 1. diatom.jar.dir 系统属性（由 Bootstrap 设置，最可靠）
     * 2. user.dir（子进程的工作目录，由 Bootstrap 设置为 JAR 目录）
     * 3. 向上查找：尝试从 diatom.jar.dir 的父目录查找 diatom-custom
     */
    private Path findCustomPom() {
        // Strategy 1: Use diatom.jar.dir (set by Bootstrap, most reliable in production)
        String jarDir = System.getProperty("diatom.jar.dir");
        if (jarDir != null) {
            Path customPom = Paths.get(jarDir, "diatom-custom", "pom.xml");
            if (Files.exists(customPom)) return customPom;

            // Also check if jar dir IS the diatom-custom dir itself (dev mode)
            Path pomInJarDir = Paths.get(jarDir, "pom.xml");
            if (Files.exists(pomInJarDir)) return pomInJarDir;
        }

        // Strategy 2: Use user.dir (set by Bootstrap to jar directory in production)
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path customPom = userDir.resolve("diatom-custom/pom.xml");
        if (Files.exists(customPom)) return customPom;

        // Also check if user.dir itself contains pom.xml
        if (Files.exists(userDir.resolve("pom.xml"))) return userDir.resolve("pom.xml");

        // Strategy 3: Walk up from jar dir to find diatom-custom project
        if (jarDir != null) {
            Path parent = Paths.get(jarDir).toAbsolutePath().normalize();
            for (int i = 0; i < 3; i++) {
                Path candidate = parent.resolve("diatom-custom/pom.xml");
                if (Files.exists(candidate)) return candidate;
                parent = parent.getParent();
                if (parent == null) break;
            }
        }

        return null;
    }

    /**
     * Schedule a restart by writing a restart marker and notifying the TerminalUI.
     */
    private void scheduleRestart() {
        try {
            // Write restart marker
            Path restartFlag = getAppHome().resolve(".restart-required");
            Files.createDirectories(getAppHome());
            Files.write(restartFlag, "restart".getBytes(StandardCharsets.UTF_8));
            logger.info("Restart scheduled. Restart flag written to: {}", restartFlag);
        } catch (IOException e) {
            logger.error("Failed to write restart flag", e);
        }
    }

    /**
     * Validate that a JAR file has a manifest.
     */
    private boolean isJarValid(Path jarPath) {
        if (!Files.exists(jarPath)) return false;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            jar.getManifest();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
