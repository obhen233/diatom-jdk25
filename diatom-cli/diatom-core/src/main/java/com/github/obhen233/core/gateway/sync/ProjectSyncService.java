package com.github.obhen233.core.gateway.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static java.util.stream.Collectors.toList;

/**
 * 项目文件同步核心服务。
 * <p>
 * 负责项目文件的打包/解压、快照计算、变更收集和差异合并。
 * 忽略规则复用 {@code isIgnoredPath()} 逻辑。
 */
public class ProjectSyncService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectSyncService.class);

    /**
     * 项目大小的估算结果。
     */
    public static class ProjectSize {
        public int fileCount;
        public long totalBytes;

        public ProjectSize() {
        }

        public ProjectSize(int fileCount, long totalBytes) {
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }
    }

    /**
     * 将项目根目录下的所有非忽略文件打包为 zip 字节数组。
     *
     * @param projectRoot 项目根目录
     * @return zip 文件的字节数组
     */
    public byte[] packProject(Path projectRoot) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * 1024);
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            Files.walk(projectRoot)
                    .filter(Files::isRegularFile)
                    .filter(file -> !isIgnoredPath(file, projectRoot))
                    .forEach(file -> {
                        String relativePath = projectRoot.relativize(file).toString()
                                .replace('\\', '/');
                        try {
                            zos.putNextEntry(new ZipEntry(relativePath));
                            Files.copy(file, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            logger.warn("Failed to add file to zip: {} - {}", relativePath, e.getMessage());
                        }
                    });
        }
        return baos.toByteArray();
    }

    /**
     * 将 zip 字节数组解压到目标目录。
     *
     * @param zipData  zip 文件的字节数组
     * @param targetDir 目标目录
     */
    public void unpackProject(byte[] zipData, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path targetFile = targetDir.resolve(entry.getName().replace('/', java.io.File.separatorChar));
                Files.createDirectories(targetFile.getParent());
                ByteArrayOutputStream baos = new ByteArrayOutputStream((int) entry.getSize());
                byte[] buf = new byte[8192];
                int len;
                while ((len = zis.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                Files.write(targetFile, baos.toByteArray());
                zis.closeEntry();
            }
        }
    }

    /**
     * 估算项目大小（文件数 + 总字节数）。
     * 递归遍历项目目录，排除忽略路径。
     *
     * @param projectRoot 项目根目录
     * @return 项目大小估算结果
     */
    public ProjectSize estimateProjectSize(Path projectRoot) throws IOException {
        ProjectSize size = new ProjectSize();
        Files.walk(projectRoot)
                .filter(Files::isRegularFile)
                .filter(file -> !isIgnoredPath(file, projectRoot))
                .forEach(file -> {
                    size.fileCount++;
                    try {
                        size.totalBytes += Files.size(file);
                    } catch (IOException e) {
                        // skip file size on error
                    }
                });
        return size;
    }

    /**
     * 计算项目目录下所有非忽略文件的 MD5 快照。
     * Key: 相对路径, Value: Base64(MD5)
     *
     * @param root 项目根目录
     * @return 文件路径 → MD5 的映射
     */
    public Map<String, String> snapshotProject(Path root) throws IOException {
        Map<String, String> snapshot = new HashMap<>();
        Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(file -> !isIgnoredPath(file, root))
                .forEach(file -> {
                    String relativePath = root.relativize(file).toString().replace('\\', '/');
                    try {
                        // Use DigestInputStream to compute MD5 without loading entire file into memory
                        MessageDigest md = MessageDigest.getInstance("MD5");
                        try (InputStream is = Files.newInputStream(file);
                             DigestInputStream dis = new DigestInputStream(is, md)) {
                            byte[] buffer = new byte[8192];
                            while (dis.read(buffer) != -1) { /* digest updated automatically */ }
                        }
                        String md5 = Base64.getEncoder().encodeToString(md.digest());
                        snapshot.put(relativePath, md5);
                    } catch (Exception e) {
                        logger.warn("Failed to snapshot file: {} - {}", relativePath, e.getMessage());
                    }
                });
        return snapshot;
    }

    /**
     * 对比当前项目目录与之前快照，收集变更差异。
     *
     * @param root        项目根目录
     * @param preSnapshot 之前的快照（路径 → MD5）
     * @return 变更差异列表
     */
    public List<FileDiffResult> collectDiffs(Path root, Map<String, String> preSnapshot) throws IOException {
        List<FileDiffResult> diffs = new ArrayList<>();
        Map<String, String> currentSnapshot = snapshotProject(root);

        // 检查新增和修改的文件
        for (Map.Entry<String, String> entry : currentSnapshot.entrySet()) {
            String path = entry.getKey();
            String currentMd5 = entry.getValue();
            String preMd5 = preSnapshot.get(path);

            if (preMd5 == null) {
                // 新增文件
                String content = readFileContent(root.resolve(path.replace('/', java.io.File.separatorChar)));
                diffs.add(new FileDiffResult(path, "CREATED", content, null));
            } else if (!currentMd5.equals(preMd5)) {
                // 修改文件
                String content = readFileContent(root.resolve(path.replace('/', java.io.File.separatorChar)));
                diffs.add(new FileDiffResult(path, "MODIFIED", content, null));
            }
        }

        // 检查删除的文件
        for (String path : preSnapshot.keySet()) {
            if (!currentSnapshot.containsKey(path)) {
                diffs.add(new FileDiffResult(path, "DELETED", null, null));
            }
        }

        return diffs;
    }

    /**
     * 将差异列表合并到本地项目目录。
     *
     * @param diffs       差异列表
     * @param projectRoot 项目根目录
     */
    public void applyDiffs(List<FileDiffResult> diffs, Path projectRoot) throws IOException {
        for (FileDiffResult diff : diffs) {
            Path targetFile = projectRoot.resolve(diff.getRelativePath().replace('/', java.io.File.separatorChar));
            switch (diff.getChangeType()) {
                case "CREATED":
                case "MODIFIED":
                    if (diff.getNewContent() != null) {
                        Files.createDirectories(targetFile.getParent());
                        Files.write(targetFile, diff.getNewContent().getBytes(StandardCharsets.UTF_8));
                        logger.debug("Synced file ({}): {}", diff.getChangeType(), diff.getRelativePath());
                    }
                    break;
                case "DELETED":
                    if (Files.exists(targetFile)) {
                        Files.delete(targetFile);
                        logger.debug("Synced file (DELETED): {}", diff.getRelativePath());
                    }
                    break;
                default:
                    logger.warn("Unknown change type: {} for file {}", diff.getChangeType(), diff.getRelativePath());
            }
        }
    }

    /**
     * 只打包有变更的文件（MODIFIED / CREATED），附带 manifest。
     * 增量 zip 格式：
     * <pre>
     * .zip
     * ├── .diatom-sync-manifest.json  ← { "type":"incremental", "files":[...] }
     * ├── src/main/java/X.java         ← 仅 MODIFIED/CREATED 的文件
     * └── src/main/java/Y.java
     * </pre>
     *
     * @param projectRoot 项目根目录
     * @param diffs       变更列表（来自 collectDiffs）
     * @return zip 字节数组
     */
    public byte[] packChangedFiles(Path projectRoot, List<FileDiffResult> diffs) throws IOException {
        // 筛选出需要包含实际文件内容的变更（MODIFIED / CREATED）
        List<FileDiffResult> contentChanges = diffs.stream()
                .filter(d -> "MODIFIED".equals(d.getChangeType()) || "CREATED".equals(d.getChangeType()))
                .collect(toList());

        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {

            // 1. 写入 manifest.json
            String manifest = buildIncrementalManifest(diffs);
            zos.putNextEntry(new ZipEntry(".diatom-sync-manifest.json"));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 2. 写入变更文件内容
            for (FileDiffResult diff : contentChanges) {
                String relativePath = diff.getRelativePath().replace('\\', '/');
                Path sourceFile = projectRoot.resolve(relativePath.replace('/', java.io.File.separatorChar));
                if (!Files.exists(sourceFile)) {
                    logger.warn("Changed file no longer exists, skipping: {}", sourceFile);
                    continue;
                }
                try {
                    zos.putNextEntry(new ZipEntry(relativePath));
                    Files.copy(sourceFile, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    logger.warn("Failed to add changed file to incremental zip: {} - {}",
                            relativePath, e.getMessage());
                }
            }
        }
        return baos.toByteArray();
    }

    /**
     * 构建增量推送的 manifest JSON 字符串。
     * <pre>
     * {
     *   "type": "incremental",
     *   "files": [
     *     {"path":"src/main/java/X.java","operation":"MODIFIED"},
     *     {"path":"src/main/java/Y.java","operation":"CREATED"},
     *     {"path":"src/main/java/Z.java","operation":"DELETED"}
     *   ]
     * }
     * </pre>
     */
    private static String buildIncrementalManifest(List<FileDiffResult> diffs) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"type\":\"incremental\",\"files\":[");
        boolean first = true;
        for (FileDiffResult diff : diffs) {
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append("{\"path\":\"")
                    .append(escapeJsonString(diff.getRelativePath().replace('\\', '/')))
                    .append("\",\"operation\":\"")
                    .append(diff.getChangeType())
                    .append("\"}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String readFileContent(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to read file content: {} - {}", file, e.getMessage());
            return "";
        }
    }

    /**
     * 检查路径是否应被忽略（.git, node_modules, target 等）。
     * 逻辑与 GatewayHttpServer.isIgnoredPath() 保持一致。
     */
    private static boolean isIgnoredPath(java.nio.file.Path file, java.nio.file.Path projectRoot) {
        java.nio.file.Path relative = projectRoot.relativize(file);
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
