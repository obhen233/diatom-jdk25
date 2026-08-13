package com.github.obhen233.core.gateway.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * Gateway 侧协同工作空间管理器
 * 为每次协同会话分配独立的工作目录，用于存放中间文件
 */
public class WorkspaceManager {
    private static final Logger logger = LoggerFactory.getLogger(WorkspaceManager.class);

    private final Path baseDir;

    public WorkspaceManager() {
        String workspaceDir = System.getProperty("workspace.dir",
                System.getProperty("user.home") + "/.diatom/workspaces");
        this.baseDir = Paths.get(workspaceDir, "gateway-collab");
    }

    public WorkspaceManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * 为协同会话分配工作空间
     */
    public Path allocateWorkspace(String sessionId) throws IOException {
        Path workspace = baseDir.resolve(sanitize(sessionId));
        Files.createDirectories(workspace);
        logger.info("Allocated workspace: {}", workspace);
        return workspace;
    }

    /**
     * 释放协同会话工作空间（递归删除）
     */
    public void releaseWorkspace(String sessionId) throws IOException {
        Path workspace = baseDir.resolve(sanitize(sessionId));
        if (Files.exists(workspace)) {
            Files.walk(workspace)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            logger.warn("Failed to delete {}: {}", p, e.getMessage());
                        }
                    });
            logger.info("Released workspace: {}", workspace);
        }
    }

    /**
     * 获取协同会话工作空间路径
     */
    public Path getWorkspace(String sessionId) {
        return baseDir.resolve(sanitize(sessionId));
    }

    /**
     * 获取工作空间根目录（用于展示，不创建）
     */
    public Path getBaseDir() {
        return baseDir;
    }

    /**
     * 获取 group 对应的工作空间路径（不创建目录）
     */
    public Path getGroupPath(String groupName) {
        return baseDir.resolve("group-" + sanitize(groupName));
    }

    private static String sanitize(String name) {
        if (name == null) return "default";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
