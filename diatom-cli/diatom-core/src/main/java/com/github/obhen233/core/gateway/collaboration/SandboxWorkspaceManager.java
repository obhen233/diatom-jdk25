package com.github.obhen233.core.gateway.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 沙箱工作空间管理器。
 *
 * 为每个子任务创建独立的沙箱目录，包含项目文件的符号链接（或副本）。
 * Worker 在沙箱中工作，修改不会影响原始项目文件。
 * 执行完成后，通过 collectChanges() 收集所有变更 diff。
 *
 * 沙箱目录结构：
 *   {sandboxBaseDir}/
 *     └── task-{taskId}/
 *         ├── subtask-a/         ← worker 的沙箱
 *         │   ├── src/main/java/X.java   ← 符号链接 → 项目/src/main/java/X.java
 *         │   ├── pom.xml                ← 符号链接 → 项目/pom.xml
 *         │   └── ...
 *         ├── subtask-b/         ← 另一个 worker 的沙箱
 *         │   └── ...
 *         └── manifest.json      ← 沙箱创建时的文件清单
 */
public class SandboxWorkspaceManager {
    private static final Logger logger = LoggerFactory.getLogger(SandboxWorkspaceManager.class);

    public enum SandboxStrategy {
        SYMLINK,
        COPY,
        AUTO
    }

    private final Path sandboxBaseDir;
    private final SandboxStrategy strategy;

    public SandboxWorkspaceManager(Path sandboxBaseDir) {
        this(sandboxBaseDir, SandboxStrategy.AUTO);
    }

    public SandboxWorkspaceManager(Path sandboxBaseDir, SandboxStrategy strategy) {
        this.sandboxBaseDir = sandboxBaseDir;
        this.strategy = strategy;
    }

    public Path getSandboxBaseDir() {
        return sandboxBaseDir;
    }

    /**
     * 为子任务创建沙箱目录。
     *
     * @param projectRoot 项目根目录
     * @param taskId      父任务 ID
     * @param subTaskId   子任务 ID
     * @return 沙箱目录路径
     */
    public Path createSandbox(Path projectRoot, String taskId, String subTaskId) throws IOException {
        Path sandboxDir = sandboxBaseDir.resolve(taskId).resolve(subTaskId);
        Files.createDirectories(sandboxDir);

        // 扫描项目根目录中的所有文件
        List<Path> projectFiles;
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            projectFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isIgnoredPath(p, projectRoot))
                    .collect(Collectors.toList());
        }

        // 为每个文件创建符号链接或复制
        for (Path file : projectFiles) {
            Path relativePath = projectRoot.relativize(file);
            Path sandboxFile = sandboxDir.resolve(relativePath);
            Files.createDirectories(sandboxFile.getParent());

            boolean linked = false;
            if (strategy == SandboxStrategy.AUTO || strategy == SandboxStrategy.SYMLINK) {
                try {
                    Files.createSymbolicLink(sandboxFile, file.toAbsolutePath());
                    linked = true;
                } catch (IOException | UnsupportedOperationException e) {
                    if (strategy == SandboxStrategy.SYMLINK) {
                        logger.warn("Failed to create symlink for {}, falling back to copy: {}",
                                relativePath, e.getMessage());
                    }
                }
            }

            if (!linked) {
                Files.copy(file, sandboxFile, StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // 写入 manifest.json
        writeManifest(sandboxDir, projectFiles, projectRoot);

        logger.debug("Created sandbox for {} at {}", subTaskId, sandboxDir);
        return sandboxDir;
    }

    /**
     * 收集沙箱中的变更 diff。
     *
     * @param sandboxDir  沙箱目录
     * @param projectRoot 原始项目根目录
     * @param subTaskId   子任务 ID
     * @return 该子任务的变更列表
     */
    public List<FileDiff> collectChanges(Path sandboxDir, Path projectRoot, String subTaskId) throws IOException {
        List<FileDiff> diffs = new ArrayList<>();

        // 读取 manifest
        Map<String, String> manifest = readManifest(sandboxDir);

        // 遍历沙箱中所有文件
        List<Path> sandboxFiles;
        try (Stream<Path> stream = Files.walk(sandboxDir)) {
            sandboxFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("manifest.json"))
                    .collect(Collectors.toList());
        }

        Set<String> manifestPaths = new HashSet<>(manifest.keySet());
        Set<String> processedPaths = new HashSet<>();

        for (Path sandboxFile : sandboxFiles) {
            Path relativePath = sandboxDir.relativize(sandboxFile);
            String relativePathStr = relativePath.toString().replace('\\', '/');

            // 检查是否是符号链接
            boolean isSymlink = Files.isSymbolicLink(sandboxFile);

            if (isSymlink) {
                // 符号链接 → 检查是否仍指向原文件
                Path symlinkTarget = Files.readSymbolicLink(sandboxFile);
                Path expectedTarget = projectRoot.resolve(relativePath).normalize();
                if (!symlinkTarget.normalize().equals(expectedTarget)) {
                    // 符号链接被修改过 → MODIFIED
                    diffs.add(detectChangeType(sandboxFile, relativePathStr, projectRoot, subTaskId));
                }
                // 否则未修改，跳过
            } else {
                // 不是符号链接 → 被 worker 修改或创建
                if (manifestPaths.contains(relativePathStr)) {
                    // 在 manifest 中 → MODIFIED
                    diffs.add(detectChangeType(sandboxFile, relativePathStr, projectRoot, subTaskId));
                } else {
                    // 不在 manifest 中 → CREATED
                    String newContent = new String(Files.readAllBytes(sandboxFile), StandardCharsets.UTF_8);
                    diffs.add(FileDiff.created(relativePathStr, newContent, subTaskId));
                }
            }
            processedPaths.add(relativePathStr);
        }

        // 检查 DELETED：在 manifest 中但沙箱中不存在的文件被删除
        for (String manifestPath : manifestPaths) {
            if (!processedPaths.contains(manifestPath)) {
                diffs.add(FileDiff.deleted(manifestPath, manifest.get(manifestPath), subTaskId));
            }
        }

        return diffs;
    }

    /**
     * 释放沙箱（递归删除）。
     */
    public void releaseSandbox(Path sandboxDir) {
        if (!Files.exists(sandboxDir)) return;
        try {
            Files.walkFileTree(sandboxDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            logger.debug("Released sandbox at {}", sandboxDir);
        } catch (IOException e) {
            logger.warn("Failed to fully release sandbox {}: {}", sandboxDir, e.getMessage());
        }
    }

    /**
     * 释放整个 task 下的所有沙箱。
     */
    public void releaseTaskSandboxes(String taskId) {
        Path taskDir = sandboxBaseDir.resolve(taskId);
        releaseSandbox(taskDir);
    }

    // ===== 内部方法 =====

    private FileDiff detectChangeType(Path sandboxFile, String relativePath,
                                      Path projectRoot, String subTaskId) throws IOException {
        String newContent = new String(Files.readAllBytes(sandboxFile), StandardCharsets.UTF_8);
        Path originalFile = projectRoot.resolve(relativePath.replace('/', File.separatorChar));

        if (!Files.exists(originalFile)) {
            return FileDiff.created(relativePath, newContent, subTaskId);
        }

        String oldContent = new String(Files.readAllBytes(originalFile), StandardCharsets.UTF_8);

        // 如果内容相同 → 无变更（跳过）
        if (oldContent.equals(newContent)) {
            return null;
        }

        // 生成 unified diff
        String unifiedDiff = generateUnifiedDiff(relativePath, oldContent, newContent);
        return FileDiff.modified(relativePath, unifiedDiff, oldContent, newContent, subTaskId);
    }

    /**
     * 简单的 unified diff 生成器（无外部依赖）。
     */
    static String generateUnifiedDiff(String filePath, String oldContent, String newContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append("\n");
        sb.append("+++ b/").append(filePath).append("\n");

        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        int maxLen = Math.max(oldLines.length, newLines.length);
        int chunkStart = -1;
        boolean inChunk = false;

        for (int i = 0; i < maxLen; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : "";
            String newLine = i < newLines.length ? newLines[i] : "";
            boolean same = oldLine.equals(newLine);

            if (same) {
                if (inChunk) {
                    // 输出 chunk
                    int chunkEnd = i - 1;
                    sb.append(String.format("@@ -%d,%d +%d,%d @@\n",
                            chunkStart + 1, chunkEnd - chunkStart + 1,
                            chunkStart + 1, chunkEnd - chunkStart + 1));
                    for (int j = chunkStart; j <= chunkEnd; j++) {
                        String oj = j < oldLines.length ? oldLines[j] : "";
                        String nj = j < newLines.length ? newLines[j] : "";
                        if (!oj.equals(nj)) {
                            sb.append("-").append(oj).append("\n");
                            sb.append("+").append(nj).append("\n");
                        } else {
                            sb.append(" ").append(oj).append("\n");
                        }
                    }
                    inChunk = false;
                }
                chunkStart = -1;
            } else {
                if (!inChunk) {
                    chunkStart = i;
                    inChunk = true;
                }
            }
        }

        // 处理最后一个 chunk
        if (inChunk && chunkStart >= 0) {
            sb.append(String.format("@@ -%d,%d +%d,%d @@\n",
                    chunkStart + 1, maxLen - chunkStart,
                    chunkStart + 1, maxLen - chunkStart));
            for (int j = chunkStart; j < maxLen; j++) {
                String oj = j < oldLines.length ? oldLines[j] : "";
                String nj = j < newLines.length ? newLines[j] : "";
                if (!oj.equals(nj)) {
                    sb.append("-").append(oj).append("\n");
                    sb.append("+").append(nj).append("\n");
                } else {
                    sb.append(" ").append(oj).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private void writeManifest(Path sandboxDir, List<Path> projectFiles, Path projectRoot) throws IOException {
        StringBuilder manifest = new StringBuilder();
        manifest.append("{\n");

        // 计算所有文件的 checksum
        for (int i = 0; i < projectFiles.size(); i++) {
            Path file = projectFiles.get(i);
            Path relativePath = projectRoot.relativize(file);
            String relativePathStr = relativePath.toString().replace('\\', '/');
            String checksum = computeChecksum(file);

            manifest.append("  \"").append(escapeJsonStr(relativePathStr)).append("\": \"")
                    .append(checksum).append("\"");
            if (i < projectFiles.size() - 1) {
                manifest.append(",");
            }
            manifest.append("\n");
        }

        manifest.append("}\n");
        Files.write(sandboxDir.resolve("manifest.json"), manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> readManifest(Path sandboxDir) throws IOException {
        Path manifestFile = sandboxDir.resolve("manifest.json");
        if (!Files.exists(manifestFile)) {
            return Collections.emptyMap();
        }
        String content = new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8);
        return parseManifest(content);
    }

    private Map<String, String> parseManifest(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        // Simple JSON parser for {"key":"value",...} format
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return result;
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) return result;

        // Split by comma not inside quotes
        boolean inQuote = false;
        int start = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                addManifestEntry(result, json.substring(start, i));
                start = i + 1;
            }
        }
        if (start < json.length()) {
            addManifestEntry(result, json.substring(start));
        }
        return result;
    }

    private void addManifestEntry(Map<String, String> map, String entry) {
        entry = entry.trim();
        int colon = entry.indexOf(':');
        if (colon < 0) return;
        String key = entry.substring(0, colon).trim();
        String value = entry.substring(colon + 1).trim();
        key = unescapeJsonStr(stripQuotes(key));
        value = unescapeJsonStr(stripQuotes(value));
        if (!key.isEmpty()) {
            map.put(key, value);
        }
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String escapeJsonStr(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJsonStr(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String computeChecksum(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] content = Files.readAllBytes(file);
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            return "";
        }
    }

    /**
     * 检查路径是否应该被忽略（.git, node_modules, target 等）。
     */
    private static boolean isIgnoredPath(Path file, Path projectRoot) {
        Path relative = projectRoot.relativize(file);
        for (int i = 0; i < relative.getNameCount(); i++) {
            String name = relative.getName(i).toString();
            if (name.startsWith(".") || "node_modules".equals(name)
                    || "target".equals(name) || "build".equals(name)
                    || "dist".equals(name) || "__pycache__".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
