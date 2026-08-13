package com.github.obhen233.config;

/**
 * 默认的 WorkspaceProvider 实现
 * 用于 CLI 模式，不支持多项目
 */
public class DefaultWorkspaceProvider implements WorkspaceProvider {

    @Override
    public WorkspaceContext getCurrentWorkspace() {
        return null;
    }

    @Override
    public void switchProject(Long projectId, String projectPath) {
        // CLI 模式不做任何事
    }

    @Override
    public boolean isMultiProjectMode() {
        return false;
    }
}
