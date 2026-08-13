package com.github.obhen233.core.gateway.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 工作空间路径解析工具。
 * 将沙箱/锁策略解析的 workspace 路径注入到 worker 请求中。
 *
 * 沙箱策略 → 沙箱路径（worker 在该目录中操作）
 * 锁策略 → 原始项目路径（不变）
 *
 * 同时支持远程 worker 的文件清单注入（文件不在本地时，Worker
 * 可通过清单从 Gateway 按需拉取文件）。
 */
public class WorkerWorkspaceResolver {
    private static final Logger logger = LoggerFactory.getLogger(WorkerWorkspaceResolver.class);

    /**
     * 为请求体中的 workspacePath 字段设置值。
     * 此字段在 worker 端的 WorkerHttpServer.handleChat() 中被解析，
     * worker 会将 user.dir 临时切换到此路径。
     */
    public static String injectWorkspacePath(String requestBody, String workspacePath) {
        if (workspacePath == null || workspacePath.isEmpty()) {
            return requestBody;
        }
        if (requestBody.endsWith("}")) {
            String insert = ",\"workspacePath\":\"" + escapeJson(workspacePath) + "\"}";
            return requestBody.substring(0, requestBody.length() - 1) + insert;
        }
        return requestBody;
    }

    /**
     * 注入 workspacePath 和 fileManifest。
     *
     * fileManifest 是项目文件的清单（相对路径数组），当 Worker 本地没有对应
     * 目录时，可根据清单从 Gateway 的 /gateway/v1/file/batch 按需拉取文件。
     *
     * @param requestBody    原始请求体
     * @param workspacePath  worker 应使用的工作目录路径
     * @param fileManifest   项目文件清单（相对路径列表），null 则不注入
     * @return 注入后的请求体
     */
    public static String injectWorkspacePathWithManifest(String requestBody,
                                                          String workspacePath,
                                                          List<String> fileManifest) {
        if (workspacePath == null && (fileManifest == null || fileManifest.isEmpty())) {
            return requestBody;
        }
        if (!requestBody.endsWith("}")) {
            return requestBody;
        }

        StringBuilder sb = new StringBuilder(requestBody);
        sb.setLength(sb.length() - 1); // remove trailing }

        if (workspacePath != null && !workspacePath.isEmpty()) {
            sb.append(",\"workspacePath\":\"").append(escapeJson(workspacePath)).append("\"");
        }

        if (fileManifest != null && !fileManifest.isEmpty()) {
            sb.append(",\"fileManifest\":[");
            for (int i = 0; i < fileManifest.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(fileManifest.get(i))).append("\"");
            }
            sb.append("]");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * 扫描项目根目录，生成文件清单。
     * 仅包含普通文件，排除 .git、node_modules、target 等目录。
     *
     * @param projectRoot 项目根目录
     * @return 文件相对路径清单
     */
    public static List<String> buildFileManifest(Path projectRoot) {
        List<String> files = new ArrayList<>();
        if (projectRoot == null || !Files.exists(projectRoot)) {
            return files;
        }

        try (Stream<Path> stream = Files.walk(projectRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isIgnoredPath(p, projectRoot))
                    .forEach(p -> {
                        String relativePath = projectRoot.relativize(p).toString()
                                .replace('\\', '/');
                        files.add(relativePath);
                    });
        } catch (IOException e) {
            logger.warn("Failed to build file manifest from {}: {}", projectRoot, e.getMessage());
        }

        return files;
    }

    /**
     * 构建文件清单的 JSON 数组字符串。
     */
    public static String buildFileManifestJson(Path projectRoot) {
        List<String> manifest = buildFileManifest(projectRoot);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < manifest.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(manifest.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 解析 Gateway 地址（从请求上下文中获取）。
     * Worker 可以通过此地址访问 Gateway 的文件服务端点。
     */
    public static String resolveGatewayUrl() {
        String gatewayUrl = System.getProperty("diatom.gateway.url");
        if (gatewayUrl == null || gatewayUrl.isEmpty()) {
            // 尝试从之前的请求中推断 Gateway 地址
            String lastGateway = System.getProperty("diatom.last.gateway");
            if (lastGateway != null && !lastGateway.isEmpty()) {
                return lastGateway;
            }
        }
        return gatewayUrl;
    }

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

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
