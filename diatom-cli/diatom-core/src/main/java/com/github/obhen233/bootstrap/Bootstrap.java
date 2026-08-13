package com.github.obhen233.bootstrap;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;




/**
 * AI代码助手启动器 - 零依赖版本
 * 职责：
 * 1. 确保custom JAR和lib存在于用户目录 (~/.diatom/custom/)
 * 2. 若不存在或版本更新，从内嵌资源释放
 * 3. 创建独立的 ClassLoader 加载核心并调用其主方法
 */
public class Bootstrap {

    // Simple logger that works before full logging system is initialized
    private static class SimpleLogger {
        private static Path logFile;
        private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        static void init(Path jarDir) {
            Path logDir = jarDir.resolve(".diatom").resolve("logs");
            try {
                Files.createDirectories(logDir);
                logFile = logDir.resolve("bootstrap.log");
            } catch (Exception e) {
                // Cannot create log directory
            }
        }

        static void info(String msg) {
            log("INFO", msg);
        }

        static void warn(String msg) {
            log("WARN", msg);
        }

        static void error(String msg) {
            log("ERROR", msg);
        }

        static void debug(String msg) {
            log("DEBUG", msg);
        }

        private static void log(String level, String msg) {
            String timestamp = dateFormat.format(new Date());
            String logLine = String.format("[%s] [%s] %s", timestamp, level, msg);
            // Only print ERROR to console; other levels go to file only
            if ("ERROR".equals(level)) {
                System.err.println(logLine);
            }
            // Always write to log file
            if (logFile != null) {
                try {
                    Files.write(logFile, (logLine + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception e) {
                    // Cannot write to log file
                }
            }
        }
    }

    private static final String APP_HOME_DIR = ".diatom";
    private static final String CUSTOM_DIR = "custom";
    private static final String CUSTOM_JAR_NAME = "custom-current.jar";
    private static final String CORE_VERSION_FILE = "core-version";
    private static final String CUSTOM_VERSION_FILE = "custom-version";
    // 内嵌资源路径
    private static final String INTERNAL_CUSTOM_SHADED = "custom/custom-shaded.jar";
    private static final String INTERNAL_LIB_DIR = "lib";
    // 旧版本兼容
    private static final String INTERNAL_CORE_RESOURCE_LEGACY = "core/core-initial.jar";
    private static final String INTERNAL_CORE_RESOURCE_LEGACY2 = "core/custom-shaded.jar";
    private static final String CORE_MAIN_CLASS = "com.github.obhen233.App";
    private static final String MAVEN_CENTRAL_METADATA_URL = "https://repo1.maven.org/maven2/com/github/obhen233/diatom-core/maven-metadata.xml";
    private static final String MAVEN_CENTRAL_REPO_URL = "https://repo1.maven.org/maven2/com/github/obhen233/diatom-core/";
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String PATH_SEP = IS_WINDOWS ? ";" : ":";

    private static Path appHome;
    private static Path customDir;
    private static Path customJarPath;
    private static Path customVersionFilePath;
    private static Path coreVersionFilePath;
    private static Path lockFilePath;
    private static Path libDir;
    private static Path execJarPath;  // Cached result of findExecJar()
    private static RandomAccessFile lockFile;
    private static FileLock lock;
    private static RandomAccessFile libLockFile;
    private static FileLock libLock;

    static {
        // Set UTF-8 encoding for proper character display
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
        System.setProperty("stdout.encoding", "UTF-8");
        System.setProperty("stderr.encoding", "UTF-8");
        System.setProperty("user.language", "en");
        System.setProperty("user.country", "US");
    }

    public static void main(String[] args) {
        // Mark that we're running in standalone JAR mode
        System.setProperty("diatom.standalone.jar", "true");
        // Initialize development mode to false (user must explicitly enable via 'dev' command)
        System.setProperty("diatom.development_mode", "false");
        try {
            initPaths();
            acquireLock();
            // 注册退出钩子以释放文件锁
            Runtime.getRuntime().addShutdownHook(new Thread(Bootstrap::releaseLock));
            ensureCustomReady();

            // Apply pending custom update BEFORE launching (custom-current.jar not yet locked)
            applyPendingCustomUpdate();

            launchCore(args);
        } catch (Exception e) {
            SimpleLogger.error("Boot failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void initPaths() {
        // Determine the JAR directory for sources and configuration
        // This is the directory containing diatom-cli.jar, NOT the current working directory
        Path jarDir = getJarDirectory();
        if (jarDir != null) {
            System.setProperty("diatom.jar.dir", jarDir.toString());
        } else {
            // Fallback to user.dir if not running from JAR
            jarDir = Paths.get(System.getProperty("user.dir"));
        }

        // Initialize SimpleLogger now that we know jarDir
        SimpleLogger.init(jarDir);
        SimpleLogger.info("JAR directory: " + jarDir);

        // Install home is {jarDir}/.diatom/ (self-update artifacts)
        appHome = jarDir.resolve(APP_HOME_DIR);
        customDir = appHome.resolve(CUSTOM_DIR);
        libDir = customDir.resolve("lib");

        customJarPath = customDir.resolve(CUSTOM_JAR_NAME);
        customVersionFilePath = customDir.resolve("custom-version.txt");
        coreVersionFilePath = customDir.resolve("core-version.txt");
        lockFilePath = appHome.resolve("bootstrap.lock");
        Path logsDir = appHome.resolve("logs");

        // Migration: if ~/.diatom/custom/ exists but {jarDir}/.diatom/ doesn't, migrate
        migrateFromUserHome();

        // Save the launcher JAR path so restart can find it even when running from custom-current.jar
        Path launcherJar = findExecJar();
        if (launcherJar != null) {
            System.setProperty("diatom.launcher.jar", launcherJar.toString());
            SimpleLogger.info("Launcher JAR: " + launcherJar);
        }

        Path appPluginsDir = appHome.resolve("plugins");
        Path globalPluginsDir = Paths.get(System.getProperty("user.home"), APP_HOME_DIR).resolve("plugins");

        try {
            Files.createDirectories(customDir);
            Files.createDirectories(appPluginsDir);
            Files.createDirectories(globalPluginsDir);
            Files.createDirectories(logsDir);
            SimpleLogger.info("Logs directory: " + logsDir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("无法创建应用目录: " + customDir, e);
        }
    }

    /**
     * Migrate self-update data from ~/.diatom/ to {jarDir}/.diatom/.
     * Only migrates self-update artifacts (custom/, backup/, versions/, versions-custom/,
     * bootstrap.lock, diatom.pid, core-version, .cleanup-pending, logs/,
     * application.properties, system-prompt.skill.md, self-update-section.skill.md,
     * authorized-paths.txt, history).
     * Does NOT migrate global configuration (skills/, mcpservers/).
     */
    private static void migrateFromUserHome() {
        Path oldAppHome = Paths.get(System.getProperty("user.home"), APP_HOME_DIR);
        Path newAppHome = appHome;

        // Only migrate if old location has data and new location doesn't exist
        if (!Files.exists(oldAppHome.resolve(CUSTOM_DIR)) || Files.exists(newAppHome)) {
            return;
        }

        SimpleLogger.info("Migrating self-update data from " + oldAppHome + " to " + newAppHome);

        // Directories to migrate (self-update artifacts only)
        String[] migrateDirs = {"custom", "backup", "versions", "versions-custom"};
        for (String dir : migrateDirs) {
            Path oldDir = oldAppHome.resolve(dir);
            Path newDir = newAppHome.resolve(dir);
            if (Files.exists(oldDir)) {
                try {
                    Files.move(oldDir, newDir, StandardCopyOption.ATOMIC_MOVE);
                    SimpleLogger.info("Migrated: " + dir);
                } catch (Exception e) {
                    try {
                        Files.move(oldDir, newDir, StandardCopyOption.REPLACE_EXISTING);
                        SimpleLogger.info("Migrated (non-atomic): " + dir);
                    } catch (Exception ex) {
                        SimpleLogger.warn("Failed to migrate " + dir + ": " + ex.getMessage());
                    }
                }
            }
        }

        // Files to migrate (application.properties follows install)
        String[] migrateFiles = {"bootstrap.lock", "diatom.pid", "core-version", ".cleanup-pending",
            "application.properties", "application.yml", "application.yaml",
            "system-prompt.skill.md", "self-update-section.skill.md", "authorized-paths.txt", "history"};
        for (String file : migrateFiles) {
            Path oldFile = oldAppHome.resolve(file);
            Path newFile = newAppHome.resolve(file);
            if (Files.exists(oldFile)) {
                try {
                    Files.move(oldFile, newFile, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    try {
                        Files.move(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ex) {
                        SimpleLogger.warn("Failed to migrate " + file + ": " + ex.getMessage());
                    }
                }
            }
        }

        // Also migrate logs directory
        Path oldLogs = oldAppHome.resolve("logs");
        Path newLogs = newAppHome.resolve("logs");
        if (Files.exists(oldLogs)) {
            try {
                Files.move(oldLogs, newLogs, StandardCopyOption.ATOMIC_MOVE);
                SimpleLogger.info("Migrated: logs");
            } catch (Exception e) {
                try {
                    Files.move(oldLogs, newLogs, StandardCopyOption.REPLACE_EXISTING);
                    SimpleLogger.info("Migrated (non-atomic): logs");
                } catch (Exception ex) {
                    SimpleLogger.warn("Failed to migrate logs: " + ex.getMessage());
                }
            }
        }

        SimpleLogger.info("Migration complete");
    }

    private static Path getInstanceDir() {
        String instanceId = System.getProperty("diatom.instance.id");
        if (instanceId != null && !instanceId.isEmpty()) {
            return appHome.resolve("instances").resolve(instanceId);
        }
        return null;
    }

    private static void acquireLock() throws IOException {
        // If instance isolation is enabled, use instance-specific lock and pid files
        Path instanceDir = getInstanceDir();
        Path effectiveLockFile;
        if (instanceDir != null) {
            Files.createDirectories(instanceDir);
            effectiveLockFile = instanceDir.resolve("bootstrap.lock");
        } else {
            effectiveLockFile = lockFilePath;
        }

        // Use PID file approach for reliable cross-platform locking
        Path pidFile = effectiveLockFile.resolveSibling("diatom.pid");
        String currentPid = System.getProperty("PID", "unknown");
        try {
            if (Files.exists(pidFile)) {
                // Read PID carefully - may be incomplete if another process is writing
                String oldPid = null;
                try {
                    oldPid = new String(Files.readAllBytes(pidFile)).trim();
                } catch (Exception e) {
                    SimpleLogger.warn("Could not read PID file, will overwrite: " + e.getMessage());
                }
                // Validate PID before checking (must be numeric)
                if (oldPid != null && !oldPid.isEmpty() && oldPid.matches("\\d+")) {
                    if (isProcessRunning(oldPid)) {
                        SimpleLogger.warn("Another instance is running (PID: " + oldPid + "), startup may conflict.");
                    }
                }
            }
            // Write current PID
            Files.write(pidFile, currentPid.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            SimpleLogger.warn("Could not manage PID file: " + e.getMessage());
        }

        // Also try advisory lock as secondary mechanism
        lockFile = new RandomAccessFile(effectiveLockFile.toFile(), "rw");
        try {
            lock = lockFile.getChannel().tryLock();
            if (lock == null) {
                SimpleLogger.warn("Could not acquire file lock, another instance may be running.");
            }
        } catch (Exception e) {
            // Lock acquisition failed but PID file check passed
            SimpleLogger.warn("File lock not available: " + e.getMessage());
        }
    }

    private static boolean isProcessRunning(String pid) {
        if (pid == null || pid.equals("unknown")) return false;
        try {
            // On Windows, use tasklist to check if process exists
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid);
            Process process = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(pid) && !line.contains("INFO")) {
                        return true;
                    }
                }
            }
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            // If we can't determine, assume it's not running to avoid blocking
            return false;
        }
    }

    private static void releaseLock() {
        SimpleLogger.debug("Bootstrap: releaseLock called");
        // 在应用退出时释放锁
        if (lock != null && lock.isValid()) {
            try {
                SimpleLogger.debug("Bootstrap: releasing lock");
                lock.release();
                SimpleLogger.debug("Bootstrap: lock released");
            } catch (Exception e) {
                // 忽略释放失败
            }
        }
        if (lockFile != null) {
            try {
                SimpleLogger.debug("Bootstrap: closing lock file");
                lockFile.close();
                SimpleLogger.debug("Bootstrap: lock file closed");
            } catch (Exception e) {
                // 忽略关闭失败
            }
        }
        releaseLibLock();
        SimpleLogger.debug("Bootstrap: releaseLock done");
    }

    /**
     * 获取 lib/ 目录的跨进程文件锁
     * 防止多个 diatom-cli 进程同时提取 lib/ 目录
     */
    private static void acquireLibLock() {
        try {
            Path libLockPath = appHome.resolve("lib-extract.lock");
            libLockFile = new RandomAccessFile(libLockPath.toFile(), "rw");
            libLock = libLockFile.getChannel().tryLock();
            if (libLock == null) {
                SimpleLogger.warn("Could not acquire lib/ extract lock, another instance may be extracting.");
                // 阻塞等待
                libLock = libLockFile.getChannel().lock();
            }
            SimpleLogger.debug("Bootstrap: lib lock acquired");
        } catch (Exception e) {
            SimpleLogger.warn("Bootstrap: could not acquire lib lock: " + e.getMessage());
        }
    }

    /**
     * 释放 lib/ 提取锁
     */
    private static void releaseLibLock() {
        if (libLock != null && libLock.isValid()) {
            try {
                libLock.release();
                SimpleLogger.debug("Bootstrap: lib lock released");
            } catch (Exception e) {
                // 忽略释放失败
            }
        }
        if (libLockFile != null) {
            try {
                libLockFile.close();
                SimpleLogger.debug("Bootstrap: lib lock file closed");
            } catch (Exception e) {
                // 忽略关闭失败
            }
        }
    }

    /**
     * 确保 custom JAR 和 lib 目录准备就绪
     */
    private static void ensureCustomReady() throws IOException {
        // 获取 lib/ 提取跨进程锁，防止多个进程同时写 lib/ 目录
        acquireLibLock();

        try {
            // 检查是否需要提取资源
            boolean needExtract = !Files.exists(customJarPath) || !isLibReady();
            boolean needLibUpdate = checkLibNeedsUpdate();
            // 检查旧格式嵌套 JAR（custom-shaded.jar 作为条目存在 → 旧格式），强制重建
            boolean hasOldNestedFormat = Files.exists(customJarPath) && hasNestedShadedJar();
            if (hasOldNestedFormat) {
                SimpleLogger.info("Detected old nested JAR format, rebuilding custom-current.jar and refreshing lib...");
                needExtract = true;
                needLibUpdate = true;
            }

            if (needExtract || needLibUpdate) {
                SimpleLogger.info("Initializing/updating custom components...");
                extractEmbeddedResources();
            }
        } finally {
            releaseLibLock();
        }

        SimpleLogger.info("Custom components ready.");
    }

    /**
     * 检查 custom-current.jar 中是否存在嵌套的 custom-shaded.jar（旧格式）
     * 标准 URLClassLoader 不支持嵌套 JAR，需要展开为独立条目
     */
    private static boolean hasNestedShadedJar() {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(customJarPath.toFile())) {
            return jar.getJarEntry("custom-shaded.jar") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 检查 lib 目录是否就绪（lib/diatom-core-*.jar 和 lib/*.jar 存在）
     */
    private static boolean isLibReady() {
        if (!Files.exists(libDir)) return false;
        try {
            long jarCount = Files.list(libDir)
                .filter(p -> p.toString().endsWith(".jar"))
                .count();
            return jarCount > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 检查 lib 是否需要更新（通过版本文件比较）
     */
    private static boolean checkLibNeedsUpdate() {
        // 如果 lib 目录不存在，需要更新
        if (!Files.exists(libDir)) return true;

        // 检查 embedded 版本和本地版本是否一致
        String embeddedCoreVersion = getEmbeddedCoreVersion();
        String localCoreVersion = getLocalCoreVersion();

        if (embeddedCoreVersion == null || !embeddedCoreVersion.equals(localCoreVersion)) {
            return true;
        }

        // 检查 core jar 是否存在
        Path coreJar = findCoreJar(libDir);
        if (coreJar == null || !Files.exists(coreJar) || !isJarValid(coreJar)) {
            return true;
        }

        return false;
    }

    /**
     * Find the diatom-core jar in the lib directory
     */
    private static Path findCoreJar(Path libDir) {
        if (libDir == null || !Files.exists(libDir) || !Files.isDirectory(libDir)) {
            return null;
        }
        try {
            return Files.list(libDir)
                .filter(p -> p.toString().endsWith(".jar"))
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("diatom-core-") && name.endsWith(".jar");
                })
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从内嵌的 diatom-cli.jar 中提取资源到 ~/.diatom/custom/
     * 从内嵌的 diatom-cli.jar 中提取资源到 ~/.diatom/custom/
     * 包括: lib/*.jar (diatom-core-*.jar 和其他依赖), 版本文件
     * custom-shaded.jar 不释放到文件系统，直接打包进 custom-current.jar
     */
    private static void extractEmbeddedResources() throws IOException {
        Path execJar = findExecJar();
        if (execJar == null || !Files.exists(execJar)) {
            throw new FileNotFoundException("diatom-cli.jar not found");
        }

        SimpleLogger.info("Extracting resources from: " + execJar);

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(execJar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // 提取 lib/ 目录下的所有文件
                if (entryName.startsWith("lib/") && !entry.isDirectory()) {
                    String relativePath = entryName.substring("lib/".length());
                    Path targetPath = libDir.resolve(relativePath);

                    // 确保父目录存在
                    Files.createDirectories(targetPath.getParent());

                    // 备份现有的 core jar（如果存在）
                    if (relativePath.startsWith("diatom-core-") && Files.exists(targetPath)) {
                        Path backup = targetPath.resolveSibling(targetPath.getFileName() + ".backup");
                        Files.move(targetPath, backup, StandardCopyOption.REPLACE_EXISTING);
                    }

                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    SimpleLogger.info("Extracted: lib/" + relativePath);
                }

                // 提取 core-version.txt 和 custom-version.txt
                if (entryName.equals("custom/core-version.txt")) {
                    Path targetPath = customDir.resolve("core-version.txt");
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    SimpleLogger.info("Extracted: custom/core-version.txt");
                }

                if (entryName.equals("custom/custom-version.txt")) {
                    Path targetPath = customDir.resolve("custom-version.txt");
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    SimpleLogger.info("Extracted: custom/custom-version.txt");
                }
            }
        }

        // 组装 custom-current.jar = Bootstrap + custom-shaded.jar (直接从 diatom-cli.jar 读取)
        assembleCustomCurrentJar(execJar);

        // Apply pending update if exists (before launching, so file is not locked yet)
        applyPendingCustomUpdate();
    }

    /**
     * Apply pending custom-current.jar update if marker exists.
     * Called before launching core, so custom-current.jar is not yet locked.
     */
    private static void applyPendingCustomUpdate() {
        try {
            Path updateMarker = customDir.resolve("custom-current.jar.update-pending.marker");
            if (!Files.exists(updateMarker)) {
                return;
            }

            Path pendingJar = customDir.resolve("custom-current.jar.update-pending");
            Path currentJar = customJarPath;

            if (!Files.exists(pendingJar)) {
                SimpleLogger.info("Pending custom-current.jar not found, skipping update");
                Files.deleteIfExists(updateMarker);
                return;
            }

            SimpleLogger.info("Applying pending custom-current.jar update...");

            // Try to delete current jar first (Windows allows deleting own locked file)
            if (Files.exists(currentJar)) {
                try {
                    Files.deleteIfExists(currentJar);
                    SimpleLogger.info("Deleted old custom-current.jar");
                } catch (Exception e) {
                    SimpleLogger.warn("Could not delete old jar: " + e.getMessage());
                }
            }

            // Move pending to current
            try {
                Files.move(pendingJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
                SimpleLogger.info("custom-current.jar updated successfully");
            } catch (Exception e) {
                // Fallback: copy then delete
                try {
                    Files.copy(pendingJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(pendingJar);
                    SimpleLogger.info("custom-current.jar updated via copy+delete");
                } catch (Exception ex) {
                    SimpleLogger.warn("Could not apply pending update: " + ex.getMessage());
                    return;
                }
            }

            // Clean up marker
            Files.deleteIfExists(updateMarker);
            SimpleLogger.info("Pending update marker removed");
        } catch (Exception e) {
            SimpleLogger.error("Failed to apply pending custom update: " + e.getMessage());
        }
    }

    /**
     * 组装 custom-current.jar
     * 包含 Bootstrap 类和 custom-shaded.jar (直接从 diatom-cli.jar 读取，不释放到文件系统)
     */
    private static void assembleCustomCurrentJar(Path execJar) throws IOException {
        Path tempJar = customJarPath.resolveSibling(CUSTOM_JAR_NAME + ".tmp");

        // 备份现有的
        if (Files.exists(customJarPath)) {
            Path backup = customJarPath.resolveSibling(CUSTOM_JAR_NAME + ".backup");
            Files.move(customJarPath, backup, StandardCopyOption.REPLACE_EXISTING);
        }

        // 创建 manifest
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "com.github.obhen233.bootstrap.Bootstrap");
        manifest.getMainAttributes().putValue("Implementation-Version", "1.0.0");

        // 创建新的 custom-current.jar
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tempJar)), manifest)) {

            // 写入 Bootstrap 目录下的所有类
            Path bootstrapDir = customDir.resolve("bootstrap");
            if (Files.exists(bootstrapDir)) {
                Files.walk(bootstrapDir)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        try {
                            String entryName = "com/github/obhen233/bootstrap/" + p.getFileName().toString();
                            jos.putNextEntry(new java.util.jar.JarEntry(entryName));
                            Files.copy(p, jos);
                            jos.closeEntry();
                        } catch (IOException e) {
                            SimpleLogger.error("Failed to add bootstrap class: " + p);
                        }
                    });
            }

            // 从 diatom-cli.jar 读取 custom/custom-shaded.jar 并将其内容展开为 custom-current.jar 的独立条目
            // 不能直接嵌套 JAR，因为标准 URLClassLoader 不支持嵌套 JAR，会导致 ServiceLoader 无法发现 SPI 服务
            try (java.util.jar.JarFile sourceJar = new java.util.jar.JarFile(execJar.toFile())) {
                java.util.jar.JarEntry shadedEntry = sourceJar.getJarEntry("custom/custom-shaded.jar");
                if (shadedEntry != null) {
                    // 提取到临时文件以便读取其条目
                    Path tempShaded = customDir.resolve("custom-shaded.tmp");
                    try (InputStream in = sourceJar.getInputStream(shadedEntry)) {
                        Files.copy(in, tempShaded, StandardCopyOption.REPLACE_EXISTING);
                    }
                    // 展开 custom-shaded.jar 的所有条目到 custom-current.jar
                    int entryCount = 0;
                    try (java.util.jar.JarFile shadedJar = new java.util.jar.JarFile(tempShaded.toFile())) {
                        java.util.Enumeration<java.util.jar.JarEntry> shadedEntries = shadedJar.entries();
                        while (shadedEntries.hasMoreElements()) {
                            java.util.jar.JarEntry shadedEntryInner = shadedEntries.nextElement();
                            String name = shadedEntryInner.getName();
                            // 跳过 META-INF/MANIFEST.MF（custom-current.jar 已有自己的 MANIFEST）
                            if (name.equals("META-INF/MANIFEST.MF") || shadedEntryInner.isDirectory()) {
                                continue;
                            }
                            jos.putNextEntry(new java.util.jar.JarEntry(name));
                            try (InputStream is = shadedJar.getInputStream(shadedEntryInner)) {
                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                while ((bytesRead = is.read(buffer)) != -1) {
                                    jos.write(buffer, 0, bytesRead);
                                }
                            }
                            jos.closeEntry();
                            entryCount++;
                        }
                    }
                    // 清理临时文件
                    try { Files.deleteIfExists(tempShaded); } catch (Exception ignored) {}
                    SimpleLogger.info("Extracted " + entryCount + " entries from custom-shaded.jar into custom-current.jar");
                }
            }
        }

        // 原子移动
        Files.move(tempJar, customJarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        SimpleLogger.info("Assembled: custom-current.jar");
    }

    /**
     * 查找 diatom-cli.jar 的路径 (结果会被缓存)
     */
    private static Path findExecJar() {
        if (execJarPath != null) return execJarPath;

        Path jarDir = getJarDirectory();
        if (jarDir != null) {
            Path execJar = jarDir.resolve("diatom-cli.jar");
            if (Files.exists(execJar)) {
                execJarPath = execJar;
                return execJarPath;
            }
        }

        // 尝试 user.dir (normalize to fix missing path separators)
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path execJar = userDir.resolve("diatom-cli.jar");
        if (Files.exists(execJar)) {
            execJarPath = execJar;
            return execJarPath;
        }

        return null;
    }

    /**
     * 获取嵌入的 core 版本
     */
    private static String getEmbeddedCoreVersion() {
        Path execJar = findExecJar();
        if (execJar == null) return null;

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(execJar.toFile())) {
            java.util.jar.JarEntry entry = jar.getJarEntry("custom/core-version.txt");
            if (entry != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                    return reader.readLine().trim();
                }
            }
        } catch (Exception e) {
            SimpleLogger.warn("Failed to read embedded core version: " + e.getMessage());
        }
        return null;
    }

    /**
     * 获取本地的 core 版本
     */
    private static String getLocalCoreVersion() {
        Path versionFile = customDir.resolve("core-version.txt");
        if (!Files.exists(versionFile)) return "";
        try {
            return new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isJarValid(Path jarPath) {
        if (jarPath == null || !Files.exists(jarPath)) return false;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            jar.getManifest();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getCustomVersion() {
        if (!Files.exists(customVersionFilePath)) return "";
        try {
            return new String(Files.readAllBytes(customVersionFilePath), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    public static void saveCustomVersion(String version) {
        try {
            Files.write(customVersionFilePath, version.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            SimpleLogger.warn("Failed to save custom version: " + e.getMessage());
        }
    }

    /**
     * Get the directory containing the diatom-cli.jar file.
     * Priority: 1) diatom.jar.dir system property, 2) protection domain code source.
     * Returns null if not running from a JAR (e.g., in IDE).
     */
    private static Path getJarDirectory() {
        // Priority 1: system property set by Bootstrap.initPaths() or restart command
        String jarDirProp = System.getProperty("diatom.jar.dir");
        if (jarDirProp != null && !jarDirProp.isEmpty()) {
            return Paths.get(jarDirProp).toAbsolutePath().normalize();
        }

        // Priority 2: protection domain (works when running from diatom-cli.jar)
        try {
            Path jarPath = Paths.get(
                Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jarPath.getFileName().toString().endsWith(".jar")) {
                return jarPath.getParent().toAbsolutePath().normalize();
            }
        } catch (Exception e) {
            // Not running from JAR
        }
        return null;
    }

    /**
     * Scan a plugins directory and append all JARs to the classpath.
     * Skips non-existent directories silently.
     */
    private static void addPluginJars(StringBuilder classpath, Path pluginsDir, String label) {
        if (!Files.exists(pluginsDir)) return;
        try {
            List<Path> jars = Files.list(pluginsDir)
                .filter(p -> p.toString().endsWith(".jar"))
                .sorted()
                .collect(Collectors.toList());
            if (jars.isEmpty()) return;
            SimpleLogger.info("[" + label + "] plugins/: " + jars.size() + " plugin(s)");
            for (Path jar : jars) {
                classpath.append(PATH_SEP).append(jar.toString());
                SimpleLogger.info("  + " + jar.getFileName());
            }
        } catch (IOException e) {
            SimpleLogger.warn("Could not scan [" + label + "] plugins/: " + e.getMessage());
        }
    }

    private static void launchCore(String[] args) throws Exception {
        Path jarDir = getJarDirectory();
        if (jarDir == null) {
            jarDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }

        // 构建 classpath: custom-current.jar:lib/*
        StringBuilder classpath = new StringBuilder();
        classpath.append(customJarPath.toString());

        // 添加 lib 目录下的所有 jar
        List<Path> libJars = new ArrayList<>();
        if (Files.exists(libDir)) {
            try {
                libJars = Files.list(libDir)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .collect(Collectors.toList());
                if (!libJars.isEmpty()) {
                    SimpleLogger.info("Found " + libJars.size() + " libraries in lib/");
                }
            } catch (IOException e) {
                SimpleLogger.warn("Could not scan lib directory: " + e.getMessage());
            }
        }

        for (Path libJar : libJars) {
            classpath.append(PATH_SEP).append(libJar.toString());
        }

        // 添加 plugins 目录下的所有 SPI 扩展 JAR（两级：应用级优先于全局级）
        Path appPluginsDir = appHome.resolve("plugins");
        Path globalPluginsDir = Paths.get(System.getProperty("user.home"), APP_HOME_DIR).resolve("plugins");
        addPluginJars(classpath, appPluginsDir, "app");
        addPluginJars(classpath, globalPluginsDir, "global");

        // Find Java executable
        String javaHome = System.getProperty("java.home");
        Path javaBin = Paths.get(javaHome, "bin", "java" + (IS_WINDOWS ? ".exe" : ""));
        if (!Files.exists(javaBin)) {
            javaBin = Paths.get(javaHome, "bin", "javaw" + (IS_WINDOWS ? ".exe" : ""));
        }

        // Build command with UTF-8 encoding settings
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin.toString());
        // Set UTF-8 encoding for the subprocess
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dsun.stdout.encoding=UTF-8");
        cmd.add("-Dsun.stderr.encoding=UTF-8");
        // Pass development mode setting from current process to subprocess
        String devMode = System.getProperty("diatom.development_mode", "false");
        cmd.add("-Ddiatom.development_mode=" + devMode);
        cmd.add("-Ddiatom.standalone.jar=true");
        cmd.add("-Ddiatom.jar.dir=" + jarDir.toString());
        // Pass original working directory (user.dir before Bootstrap overrides it)
        cmd.add("-Ddiatom.original.user.dir=" + System.getProperty("user.dir"));
        cmd.add("-cp");
        cmd.add(classpath.toString());
        cmd.add(CORE_MAIN_CLASS);
        // Add user arguments
        for (String arg : args) {
            cmd.add(arg);
        }

        // Use ProcessBuilder to launch with correct encoding
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LANG", "en_US.UTF-8");
        pb.environment().put("LC_ALL", "en_US.UTF-8");

        // Set working directory to jar directory
        pb.directory(jarDir.toFile());

        pb.inheritIO();

        try {
            Process process = pb.start();
            int exitCode = process.waitFor();
            System.exit(exitCode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to launch core: " + e.getMessage(), e);
        }
    }
}
