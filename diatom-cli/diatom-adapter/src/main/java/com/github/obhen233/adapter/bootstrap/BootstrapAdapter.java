package com.github.obhen233.adapter.bootstrap;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Zero-dependency launcher for diatom-adapter (analogous to diatom-cli's Bootstrap).
 *
 * <p>Packaged inside {@code diatom-adapter-core.jar} along with {@code lib/} containing
 * all dependency JARs. On first run, extracts {@code lib/*.jar} to
 * {@code {jarDir}/.diatom/adapter/lib/}, then creates a URLClassLoader with those
 * JARs and launches {@link com.github.obhen233.adapter.AdapterBootstrap} via reflection.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar diatom-adapter-core.jar --port 8083 --gateway-url http://gateway:8080 --instance-id worker-1
 * </pre>
 */
public class BootstrapAdapter {

    private static final String ADAPTER_HOME = ".diatom/adapter";
    private static final String LIB_DIR = "lib";
    private static final String VERSION_FILE = "version.txt";
    private static final String MAIN_CLASS = "com.github.obhen233.adapter.AdapterBootstrap";
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String PATH_SEP = IS_WINDOWS ? ";" : ":";

    private static Path jarDir;
    private static Path adapterHome;
    private static Path libDir;
    private static Path lockFilePath;
    private static RandomAccessFile lockFile;
    private static FileLock lock;

    // SimpleLogger — zero external dependencies
    private static class SimpleLogger {
        private static Path logFile;
        private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        static void init(Path jarDir) {
            Path logsDir = jarDir.resolve(".diatom").resolve("adapter").resolve("logs");
            try {
                Files.createDirectories(logsDir);
                logFile = logsDir.resolve("bootstrap.log");
            } catch (Exception ignored) {}
        }

        static void info(String msg) { log("INFO", msg); }
        static void warn(String msg) { log("WARN", msg); }
        static void error(String msg) { log("ERROR", msg); }
        static void debug(String msg) { log("DEBUG", msg); }

        private static void log(String level, String msg) {
            String line = String.format("[%s] [%s] %s", FORMAT.format(new Date()), level, msg);
            if ("ERROR".equals(level)) {
                System.err.println(line);
            }
            if (logFile != null) {
                try {
                    Files.write(logFile, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception ignored) {}
            }
        }
    }

    public static void main(String[] args) {
        try {
            initPaths();
            acquireLock();
            ensureLibReady();
            launchAdapter(args);
        } catch (Exception e) {
            System.err.println("[BootstrapAdapter] Fatal: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void initPaths() {
        // Preserve the original user.dir BEFORE overriding with jarDir
        System.setProperty("diatom.original.user.dir", System.getProperty("user.dir", "."));

        // Determine the JAR directory (where diatom-adapter-core.jar lives)
        try {
            Path jarPath = Paths.get(
                    BootstrapAdapter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            jarDir = jarPath.getFileName().toString().endsWith(".jar")
                    ? jarPath.getParent().toAbsolutePath().normalize()
                    : Paths.get(System.getProperty("user.dir", "."));
        } catch (Exception e) {
            jarDir = Paths.get(System.getProperty("user.dir", "."));
        }

        System.setProperty("diatom.jar.dir", jarDir.toString());
        SimpleLogger.init(jarDir);
        SimpleLogger.info("JAR directory: " + jarDir);

        adapterHome = jarDir.resolve(".diatom").resolve("adapter");
        libDir = adapterHome.resolve(LIB_DIR);
        lockFilePath = adapterHome.resolve("bootstrap.lock");

        try {
            Files.createDirectories(libDir);
            Files.createDirectories(adapterHome.resolve("logs"));
        } catch (IOException e) {
            throw new RuntimeException("Cannot create adapter home: " + adapterHome, e);
        }
    }

    private static void acquireLock() {
        try {
            lockFile = new RandomAccessFile(lockFilePath.toFile(), "rw");
            lock = lockFile.getChannel().tryLock();
            if (lock == null) {
                SimpleLogger.warn("Could not acquire file lock, another instance may be running.");
            }
        } catch (Exception e) {
            SimpleLogger.warn("File lock not available: " + e.getMessage());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (lock != null && lock.isValid()) {
                try { lock.release(); } catch (Exception ignored) {}
            }
            if (lockFile != null) {
                try { lockFile.close(); } catch (Exception ignored) {}
            }
        }));
    }

    /**
     * Ensure lib/ directory has all required JARs extracted from the launcher JAR.
     * Skips extraction if version.txt matches (avoids redundant I/O on every startup).
     */
    private static void ensureLibReady() throws IOException {
        Path execJar = findExecJar();
        if (execJar == null || !Files.exists(execJar)) {
            throw new FileNotFoundException("Cannot find launcher JAR: " + execJar);
        }

        String embeddedVersion = readEmbeddedVersion(execJar);
        String localVersion = readLocalVersion();

        boolean needExtract = !isLibValid()
                || (embeddedVersion != null && !embeddedVersion.equals(localVersion));

        if (needExtract) {
            SimpleLogger.info("Extracting lib/ from launcher JAR...");
            extractLib(execJar);
            saveVersion(embeddedVersion != null ? embeddedVersion : "1.0.0");
            SimpleLogger.info("lib/ extracted to " + libDir);
        } else {
            SimpleLogger.debug("lib/ is up to date");
        }
    }

    private static boolean isLibValid() {
        if (!Files.exists(libDir)) return false;
        try {
            return Files.list(libDir).anyMatch(p -> p.toString().endsWith(".jar"));
        } catch (IOException e) {
            return false;
        }
    }

    private static void extractLib(Path execJar) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(execJar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("lib/") && !entry.isDirectory()) {
                    String relativePath = name.substring("lib/".length());
                    Path targetPath = libDir.resolve(relativePath);
                    Files.createDirectories(targetPath.getParent());
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    SimpleLogger.debug("Extracted: lib/" + relativePath);
                }
            }
        }
    }

    private static String readEmbeddedVersion(Path execJar) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(execJar.toFile())) {
            java.util.jar.JarEntry entry = jar.getJarEntry(VERSION_FILE);
            if (entry != null) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                    return r.readLine().trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String readLocalVersion() {
        Path versionFile = adapterHome.resolve(VERSION_FILE);
        if (!Files.exists(versionFile)) return "";
        try {
            return new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void saveVersion(String version) {
        try {
            Files.write(adapterHome.resolve(VERSION_FILE),
                    version.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            SimpleLogger.warn("Failed to save version: " + e.getMessage());
        }
    }

    /**
     * Launch the actual AdapterBootstrap via URLClassLoader with lib/*.jar on classpath.
     */
    private static void launchAdapter(String[] args) throws Exception {
        // Collect all JARs from lib/
        List<URL> urlList = new ArrayList<>();

        // Add the exec JAR itself (contains AdapterBootstrap and other adapter classes)
        Path execJar = findExecJar();
        if (execJar != null && Files.exists(execJar)) {
            urlList.add(execJar.toUri().toURL());
        }

        // Add all dependency JARs from lib/
        try {
            Files.list(libDir)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .sorted()
                    .forEach(p -> {
                        try { urlList.add(p.toUri().toURL()); } catch (Exception ignored) {}
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan lib/ directory", e);
        }

        if (urlList.isEmpty()) {
            throw new IllegalStateException("No JARs found in " + libDir
                    + ". Ensure diatom-adapter.jar was built correctly.");
        }

        SimpleLogger.info("Launching adapter with " + urlList.size() + " JARs");

        ClassLoader parent = ClassLoader.getSystemClassLoader().getParent(); // bootstrap
        URL[] urls = urlList.toArray(new URL[0]);
        try (URLClassLoader cl = new URLClassLoader(urls, parent)) {
            // Set TCCL for SLF4J/ServiceLoader discovery
            Thread.currentThread().setContextClassLoader(cl);

            // Load and invoke AdapterBootstrap.main()
            Class<?> mainClass = cl.loadClass(MAIN_CLASS);
            Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, new Object[]{args});
        }
    }

    private static Path findExecJar() {
        try {
            Path p = Paths.get(
                    BootstrapAdapter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (p.getFileName().toString().endsWith(".jar")) {
                return p.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
