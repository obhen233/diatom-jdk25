package com.github.obhen233.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 多源插件扫描器。
 *
 * 从以下来源发现插件 JAR 目录：
 * <ol>
 *   <li><b>Bundled</b> — classpath 上所有 JAR 中 {@code META-INF/diatom-plugins/*.jar} 的内置插件</li>
 *   <li><b>Classpath-adjacent</b> — {@code java.class.path} 每个条目的同级 {@code plugins/} 目录</li>
 *   <li><b>Fat JAR</b> — fat JAR 同级的 {@code plugins/} 目录</li>
 * </ol>
 *
 * 扫描结果用于 {@link PluginPathConfiguration}，在 {@code PluginClassLoader.init()}
 * 调用之前合并到插件搜索路径中。
 */
public class PluginResourceScanner {
    private static final Logger logger = LoggerFactory.getLogger(PluginResourceScanner.class);

    static final String BUNDLED_PLUGINS_RESOURCE = "META-INF/diatom-plugins/";

    private final List<Path> tempDirs = new ArrayList<>();

    /**
     * 从所有来源发现插件目录。
     *
     * @param jarDir 当前 {@code diatam.jar.dir} 对应的 Path
     * @return 包含插件 JAR 的目录列表（可能为空）
     */
    public List<Path> discoverPluginDirs(Path jarDir) {
        List<Path> dirs = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        // 1. 提取 bundled 插件（从 classpath 中 META-INF/diatom-plugins/）
        Path bundledDir = extractBundledPlugins();
        if (bundledDir != null && seen.add(bundledDir)) {
            dirs.add(bundledDir);
            logger.info("Discovered bundled plugins directory: {}", bundledDir);
        }

        // 2. 扫描 classpath 条目同级 plugins/ 目录
        List<Path> cpDirs = findClasspathPluginDirs();
        for (Path d : cpDirs) {
            if (seen.add(d)) {
                dirs.add(d);
                logger.debug("Discovered classpath-adjacent plugins directory: {}", d);
            }
        }

        // 3. fat JAR 同级 plugins/ 目录
        Path fatJarDir = findFatJarPluginDir();
        if (fatJarDir != null && seen.add(fatJarDir)) {
            dirs.add(fatJarDir);
            logger.info("Discovered fat JAR adjacent plugins directory: {}", fatJarDir);
        }

        // 4. jarDir 同级的 plugins/（diatom.jar.dir 对等目录）
        Path jarPeerPlugins = jarDir.resolve("plugins");
        if (Files.isDirectory(jarPeerPlugins) && seen.add(jarPeerPlugins)) {
            dirs.add(jarPeerPlugins);
            logger.debug("Discovered jarDir peer plugins directory: {}", jarPeerPlugins);
        }

        return dirs;
    }

    // ========== Bundled plugins ==========

    /**
     * 从 classpath 中所有 {@code META-INF/diatom-plugins/} 资源里提取 .jar 文件
     * 到 {@code ~/.diatom/cache/plugins/bundled-{timestamp}/}。
     */
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
                logger.info("Extracted {} bundled plugin(s) to: {}", seenNames.size(), outputDir);
                return outputDir;
            }

            // 没有找到任何插件，清理空目录
            deleteDir(outputDir);
            tempDirs.remove(outputDir);
            return null;
        } catch (Exception e) {
            logger.warn("Failed to extract bundled plugins: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从单个资源 URL 中提取 .jar 文件。
     * 支持 jar: 协议（JAR 内部）和 file: 协议（开发模式目录）。
     */
    private boolean extractFromUrl(URL url, Path outputDir, Set<String> seenNames) {
        String protocol = url.getProtocol();
        try {
            if ("jar".equals(protocol)) {
                // jar:file:///path/to/jar!/META-INF/diatom-plugins/
                String path = url.getPath();
                int sep = path.indexOf("!/");
                if (sep > 0) {
                    String jarPath = path.substring(0, sep);
                    if (jarPath.startsWith("file:")) {
                        jarPath = jarPath.substring(5);
                    }
                    // 处理 Windows 路径（file:/C:/... → C:/...）
                    if (jarPath.startsWith("/") && jarPath.length() > 2
                            && Character.isLetter(jarPath.charAt(1))
                            && jarPath.charAt(2) == ':') {
                        jarPath = jarPath.substring(1);
                    }
                    return extractFromJarPath(new File(new URI(jarPath)), outputDir, seenNames);
                }
            } else if ("file".equals(protocol)) {
                // 开发模式：目录形式的 classpath 条目
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
            logger.warn("Failed to extract from URL {}: {}", url, e.getMessage());
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
                        logger.debug("Skipping duplicate bundled plugin: {}", fileName);
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
                    logger.debug("Extracted bundled plugin: {} from {}", fileName, jarFile.getName());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to read JAR {}: {}", jarFile, e.getMessage());
        }
        return found;
    }

    // ========== Classpath-adjacent plugins/ ==========

    /**
     * 扫描 {@code java.class.path} 中每个条目，查找其上级目录下的 {@code plugins/} 目录。
     *
     * 例如：
     * <ul>
     *   <li>{@code /app/myapp.jar} → {@code /app/plugins/}</li>
     *   <li>{@code /app/target/classes/} → {@code /app/target/classes/plugins/}</li>
     * </ul>
     */
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

                // 查找 plugins/ 目录
                Path pluginDir = parent.resolve("plugins");
                if (Files.isDirectory(pluginDir) && seen.add(pluginDir)) {
                    dirs.add(pluginDir);
                }

                // 也查找 .diatom/plugins/ 目录（兼容已有习惯）
                Path dotDiatomPluginDir = parent.resolve(".diatom").resolve("plugins");
                if (Files.isDirectory(dotDiatomPluginDir) && seen.add(dotDiatomPluginDir)) {
                    dirs.add(dotDiatomPluginDir);
                }
            } catch (Exception e) {
                logger.trace("Failed to scan classpath entry '{}': {}", entry, e.getMessage());
            }
        }
        return dirs;
    }

    // ========== Fat JAR adjacent plugins/ ==========

    /**
     * 检测 fat JAR 路径并查找其同级的 {@code plugins/} 目录。
     *
     * 检测策略：
     * <ol>
     *   <li>检查 {@code java.class.path} 中的 .jar 条目</li>
     *   <li>如果有且只有一个 main JAR，其同级 plugins/ 即为目标</li>
     * </ol>
     */
    static Path findFatJarPluginDir() {
        String classpath = System.getProperty("java.class.path", "");
        String separator = File.pathSeparator;

        // 收集所有 JAR 条目的父目录
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
                logger.trace("Failed to check classpath entry '{}': {}", entry, e.getMessage());
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
            logger.warn("Failed to delete temporary directory: {}", e.getMessage());
        }
    }
}
