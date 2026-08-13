package com.github.obhen233.config;

/**
 * 工作空间/项目上下文提供者接口
 *
 * 实现类负责提供当前项目上下文，实现多项目隔离。
 * Core 定义此接口，由 Spring Boot Starter 或 IDE 实现。
 *
 * 设计原则:
 * - Core 不依赖具体实现
 * - 通过依赖注入获取实现
 * - CLI 模式可使用 DefaultWorkspaceProvider
 */
public interface WorkspaceProvider {

    /**
     * 获取当前项目上下文
     * @return 上下文对象，无时返回 null
     */
    WorkspaceContext getCurrentWorkspace();

    /**
     * 切换当前项目
     * 会触发各组件重新初始化
     *
     * @param projectId 项目主键
     * @param projectPath 项目路径
     */
    void switchProject(Long projectId, String projectPath);

    /**
     * 是否启用了多项目模式
     * @return true 表示 IDE 集成模式，false 表示 CLI 模式
     */
    boolean isMultiProjectMode();

    /**
     * 当前工作空间上下文
     */
    class WorkspaceContext {
        public final Long workspaceId;
        public final String workspaceRoot;
        public final Long projectId;
        public final String projectPath;

        public WorkspaceContext(Long workspaceId, String workspaceRoot,
                                Long projectId, String projectPath) {
            this.workspaceId = workspaceId;
            this.workspaceRoot = workspaceRoot;
            this.projectId = projectId;
            this.projectPath = projectPath;
        }

        public boolean hasWorkspace() {
            return workspaceId != null;
        }

        public boolean hasProject() {
            return projectId != null;
        }

        @Override
        public String toString() {
            return "WorkspaceContext{" +
                    "workspaceId=" + workspaceId +
                    ", workspaceRoot='" + workspaceRoot + '\'' +
                    ", projectId=" + projectId +
                    ", projectPath='" + projectPath + '\'' +
                    '}';
        }
    }
}
