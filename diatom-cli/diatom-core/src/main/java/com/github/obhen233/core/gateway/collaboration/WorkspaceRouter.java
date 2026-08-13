package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作空间路由器
 * 根据 Worker 的 group 属性，将子任务路由到对应的工作空间目录。
 * 同 group 的 worker 共享工作空间，不同 group 的工作空间隔离。
 */
public class WorkspaceRouter {
    private static final Logger logger = LoggerFactory.getLogger(WorkspaceRouter.class);

    private final WorkspaceManager workspaceManager;
    private final Map<String, Path> groupWorkspaces = new ConcurrentHashMap<>();

    public WorkspaceRouter(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /**
     * 获取或创建指定 group 的工作空间
     */
    public Path getOrAllocateGroup(String groupName) throws IOException {
        return groupWorkspaces.computeIfAbsent(groupName, name -> {
            try {
                Path ws = workspaceManager.allocateWorkspace("group-" + name);
                logger.info("Allocated group workspace: {} -> {}", name, ws);
                return ws;
            } catch (IOException e) {
                logger.warn("Failed to allocate workspace for group {}: {}", name, e.getMessage());
                return null;
            }
        });
    }

    /**
     * 为指定 Worker 解析工作空间路径
     * 如果 worker 有 group 属性，返回该 group 对应的工作空间；
     * 否则返回 null（保持按 session 分配的 fallback 行为）
     */
    public String resolveWorkspace(WorkerInfo worker) {
        if (worker == null) return null;
        String group = worker.getGroup();
        if (group != null && !group.isEmpty()) {
            try {
                Path ws = getOrAllocateGroup(group);
                return ws != null ? ws.toString() : null;
            } catch (IOException e) {
                logger.warn("Failed to resolve workspace for worker {} group {}: {}",
                        worker.getWorkerId(), group, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * 释放指定 group 的工作空间
     */
    public void releaseGroup(String groupName) {
        Path ws = groupWorkspaces.remove(groupName);
        if (ws != null) {
            try {
                workspaceManager.releaseWorkspace("group-" + groupName);
                logger.info("Released group workspace: {} -> {}", groupName, ws);
            } catch (IOException e) {
                logger.warn("Failed to release workspace for group {}: {}", groupName, e.getMessage());
            }
        }
    }

    /**
     * 释放所有工作空间
     */
    public void releaseAll() {
        for (String group : groupWorkspaces.keySet()) {
            releaseGroup(group);
        }
    }
}
