package com.github.obhen233.config;

import java.util.List;

/**
 * 项目上下文数据访问接口
 *
 * 封装项目表的操作，供 WorkspaceProvider 实现使用。
 * Core 定义此接口，由 Spring Boot Starter 或 IDE 实现。
 */
public interface ProjectRepository {

    /**
     * 根据项目路径查找项目
     * @param projectPath 项目路径
     * @return 项目上下文，不存在返回 null
     */
    ProjectContext findByPath(String projectPath);

    /**
     * 根据项目 ID 查找项目
     * @param projectId 项目主键
     * @return 项目上下文，不存在返回 null
     */
    ProjectContext findById(Long projectId);

    /**
     * 列出工作区下的所有项目
     * @param workspaceId 工作区主键
     * @return 项目列表
     */
    List<ProjectContext> listByWorkspace(Long workspaceId);

    /**
     * 列出所有项目
     * @return 所有项目列表
     */
    List<ProjectContext> listAll();

    /**
     * 创建项目
     * 如果工作区不存在，自动创建
     *
     * @param workspaceRoot 工作区根目录
     * @param workspaceName 工作区名称
     * @param projectPath 项目路径
     * @param projectName 项目名称
     * @param projectType 项目类型
     * @return 创建的项目上下文
     */
    ProjectContext createProject(String workspaceRoot, String workspaceName,
                                 String projectPath, String projectName, String projectType);

    /**
     * 删除项目 (级联删除关联数据)
     * @param projectId 项目主键
     */
    void deleteProject(Long projectId);

    /**
     * 删除工作区 (级联删除所有关联项目)
     * @param workspaceId 工作区主键
     */
    void deleteWorkspace(Long workspaceId);

    /**
     * 获取或创建工作区
     * @param workspaceRoot 工作区根目录
     * @param workspaceName 工作区名称
     * @return 工作区 ID
     */
    Long getOrCreateWorkspace(String workspaceRoot, String workspaceName);

    /**
     * 项目上下文数据
     */
    class ProjectContext {
        public Long id;
        public Long workspaceId;
        public String projectPath;
        public String projectName;
        public String projectType;
        public Long indexedAt;
        public String contextData;
        public Long createdAt;
        public Long updatedAt;

        public ProjectContext() {}

        public ProjectContext(Long id, Long workspaceId, String projectPath,
                             String projectName, String projectType) {
            this.id = id;
            this.workspaceId = workspaceId;
            this.projectPath = projectPath;
            this.projectName = projectName;
            this.projectType = projectType;
        }

        @Override
        public String toString() {
            return "ProjectContext{" +
                    "id=" + id +
                    ", workspaceId=" + workspaceId +
                    ", projectPath='" + projectPath + '\'' +
                    ", projectName='" + projectName + '\'' +
                    ", projectType='" + projectType + '\'' +
                    '}';
        }
    }
}
