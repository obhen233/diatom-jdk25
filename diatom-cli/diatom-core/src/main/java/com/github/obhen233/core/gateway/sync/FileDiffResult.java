package com.github.obhen233.core.gateway.sync;

/**
 * Worker 执行完成后，对比文件快照产生的变更记录。
 * 用于 Worker → Gateway 的 JSON 传输。
 */
public class FileDiffResult {

    /** 文件相对路径（相对项目根目录） */
    private String relativePath;

    /** 变更类型：MODIFIED / CREATED / DELETED */
    private String changeType;

    /** 新文件内容（MODIFIED / CREATED 时有效） */
    private String newContent;

    /** 旧文件内容（MODIFIED / DELETED 时有效） */
    private String oldContent;

    public FileDiffResult() {
    }

    public FileDiffResult(String relativePath, String changeType, String newContent, String oldContent) {
        this.relativePath = relativePath;
        this.changeType = changeType;
        this.newContent = newContent;
        this.oldContent = oldContent;
    }

    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getNewContent() { return newContent; }
    public void setNewContent(String newContent) { this.newContent = newContent; }
    public String getOldContent() { return oldContent; }
    public void setOldContent(String oldContent) { this.oldContent = oldContent; }
}
