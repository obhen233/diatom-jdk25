package com.github.obhen233.quarkus.runtime.internal;

import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 多源插件扫描器（移植自 Spring Boot starter 的 {@code PluginResourceScanner}）。
 *
 * <p>从以下来源发现插件 JAR 目录：
 * <ol>
 *   <li><b>Bundled</b> — classpath 上所有 JAR 中 {@code META-INF/diatom-plugins/*.jar} 的内置插件</li>
 *   <li><b>Classpath-adjacent</b> — {@code java.class.path} 每个条目的同级 {@code plugins/} 目录</li>
 *   <li><b>Fat JAR</b> — fat JAR 同级的 {@code plugins/} 目录</li>
 *   <li><b>jarDir 同级</b> — {@code {jarDir}/plugins/}</li>
 * </ol>
 *
 * <p>扫描结果在 {@code PluginClassLoader.init()} 之前合并到插件搜索路径中。
 */
public class PluginResourceScanner {
    private static final Logger logger = Logger.getLogger(PluginResourceScanner.class);

    static final String BUNDLED_PLUGINS_RESOURCE = "META-INF/diatom-plugins/";

    private final List<Path> tempDirs = new ArrayList<>();

    /**
     * 从所有来源发现插件目录。
     *
     * @param jarDir 当前 {@code diatom.jar.dir} 对应的 Path
     * @return 包含插件 JAR 的目录列表（可能为空）
     */
    public List<Path> discoverPluginDirs(Path jarDir) {
        List<Path> dirs = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        Path bundledDir = extractBundledPlugins();
        if (bundledDir != null && seen.add(bundledDir)) {
            dirs.add(bundledDir);
            logger.infof("Discovered bundled plugins directory: %s", bundledDir);
        }

        List<Path> cpDirs = findClasspathPluginDirs();
        for (Path d : cpDirs) {
            if (seen.add(d)) {
                dirs.add(d);
                logger.debugf("Discovered classpath-adjacent plugins directory: %s", d);
            }
        }

        Path fatJarDir = findFatJarPluginDir();
        if (fatJarDir != null && seen.add(fatJarDir)) {
            dirs.add(fatJarDir);
            logger.infof("Discovered fat JAR adjacent plugins directory: %s", fatJarDir);
        }

        Path jarPeerPlugins = jarDir.resolve("plugins");
        if (Files.isDirectory(jarPeerPlugins) && seen.add(jarPeerPlugins)) {
            dirs.add(jarPeerPlugins);
            logger.debugf("Discovered jarDir peer plugins directory: %s", jarPeerPlugins);
        }

        return dirs;
    }

    // ========== Bundled plugins ==========

    private Path extractBundledPlugins() {
        try {
            ClassLoader cl = getClass().getClassLoader();
            Enumeration<URL> resources = cl.getResources(BUNDLED_PLUGINS_RESOURCE);
            if (!resources.hasMoreElements()) {
                return null;
            }

            String home = System.getProperty("user.home", "~");
            Path outputDir = Paths.get(home, ".diatom", "cache", "plugins",
                    "bundled-" + System.currentTimeMillis());
            Files.createDirectories(outputDir);
            tempDirs.add(outputDir);

            boolean found = false;
            Set<String> seenNames = new HashSet<>();

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                found |= extractFromUrl(url, outputDir, seenNames);
            }

            if (found) {
                logger.infof("Extracted %d bundled plugin(s) to: %s", seenNames.size(), outputDir);
                return outputDir;
            }

            deleteDir(outputDir);
            tempDirs.remove(outputDir);
            return null;
        } catch (Exception e) {
            logger.warnf("Failed to extract bundled plugins: %s", e.getMessage());
            return null;
        }
    }

    private boolean extractFromUrl(URL url, Path outputDir, Set<String> seenNames) {
        String protocol = url.getProtocol();
        try {
            if ("jar".equals(protocol)) {
                String path = url.getPath();
                int sep = path.indexOf("!/");
                if (sep > 0) {
                    String jarPath = path.substring(0, sep);
                    if (jarPath.startsWith("file:")) {
                        jarPath = jarPath.substring(5);
                    }
                    if (jarPath.startsWith("/") && jarPath.length() > 2
                            && Character.isLetter(jarPath.charAt(1))
                            && jarPath.charAt(2) == ':') {
                        jarPath = jarPath.substring(1);
                    }
                    return extractFromJarPath(new File(new URI(jarPath)), outputDir, seenNames);
                }
            } else if ("file".equals(protocol)) {
                File dir = new File(url.toURI());
                File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    boolean found = false;
                    for (File jar : jars) {
                        if (seenNames.add(jar.getName())) {
                            Files.copy(jar.toPath(), outputDir.resolve(jar.getName()),
                                    StandardCopyOption.REPLACE_EXISTING);
                            found = true;
                        }
                    }
                    return found;
                }
            }
        } catch (Exception e) {
            logger.warnf("Failed to extract from URL %s: %s", url, e.getMessage());
        }
        return false;
    }

    private boolean extractFromJarPath(File jarFile, Path outputDir, Set<String> seenNames) {
        boolean found = false;
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jf.entries();
            byte[] buf = new byte[8192];

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.startsWith(BUNDLED_PLUGINS_RESOURCE)
                        && name.endsWith(".jar") && !entry.isDirectory()) {
                    String fileName = name.substring(BUNDLED_PLUGINS_RESOURCE.length());
                    if (!seenNames.add(fileName)) {
                        continue;
                    }
                    Path target = outputDir.resolve(fileName);
                    try (InputStream is = jf.getInputStream(entry);
                         OutputStream os = Files.newOutputStream(target)) {
                        int n;
                        while ((n = is.read(buf)) >= 0) {
                            os.write(buf, 0, n);
                        }
                    }
                    found = true;
                }
            }
        } catch (Exception e) {
            logger.warnf("Failed to read JAR %s: %s", jarFile, e.getMessage());
        }
        return found;
    }

    // ========== Classpath-adjacent plugins/ ==========

    static List<Path> findClasspathPluginDirs() {
        List<Path> dirs = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        String classpath = System.getProperty("java.class.path", "");
        String separator = File.pathSeparator;

        for (String entry : classpath.split(separator)) {
            if (entry.isEmpty()) continue;
            try {
                Path path = Paths.get(entry).toAbsolutePath().normalize();
                Path parent = path.getParent();
                if (parent == null) continue;

                Path pluginDir = parent.resolve("plugins");
                if (Files.isDirectory(pluginDir) && seen.add(pluginDir)) {
                    dirs.add(pluginDir);
                }

                Path dotDiatomPluginDir = parent.resolve(".diatom").resolve("plugins");
                if (Files.isDirectory(dotDiatomPluginDir) && seen.add(dotDiatomPluginDir)) {
                    dirs.add(dotDiatomPluginDir);
                }
            } catch (Exception e) {
                logger.tracef("Failed to scan classpath entry '%s': %s", entry, e.getMessage());
            }
        }
        return dirs;
    }

    // ========== Fat JAR adjacent plugins/ ==========

    static Path findFatJarPluginDir() {
        String classpath = System.getProperty("java.class.path", "");
        String separator = File.pathSeparator;

        Set<Path> jarParents = new LinkedHashSet<>();
        for (String entry : classpath.split(separator)) {
            if (entry.isEmpty()) continue;
            try {
                Path path = Paths.get(entry).toAbsolutePath().normalize();
                if (path.toString().endsWith(".jar") && Files.isRegularFile(path)) {
                    Path parent = path.getParent();
                    if (parent != null) {
                        jarParents.add(parent);
                    }
                }
            } catch (Exception e) {
                logger.tracef("Failed to check classpath entry '%s': %s", entry, e.getMessage());
            }
        }

        for (Path parent : jarParents) {
            Path pluginDir = parent.resolve("plugins");
            if (Files.isDirectory(pluginDir)) {
                return pluginDir;
            }
        }

        return null;
    }

    // ========== Cleanup ==========

    /**
     * 清理提取 bundled 插件时创建的临时目录。
     */
    public void cleanup() {
        for (Path dir : tempDirs) {
            deleteDir(dir);
        }
        tempDirs.clear();
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.warnf("Failed to delete temporary directory: %s", e.getMessage());
        }
    }
}
