package com.github.obhen233.core.gateway.collaboration;

import java.util.Objects;

/**
 * 表示一个文件的变更 diff。
 * 用于沙箱策略中收集子任务对文件的修改。
 */
public class FileDiff {

    public enum ChangeType {
        MODIFIED,
        CREATED,
        DELETED
    }

    private final String relativePath;
    private final ChangeType changeType;
    private final String unifiedDiff;
    private final String oldContent;
    private final String newContent;
    private final String subTaskId;

    public FileDiff(String relativePath, ChangeType changeType, String unifiedDiff,
                    String oldContent, String newContent, String subTaskId) {
        this.relativePath = relativePath;
        this.changeType = changeType;
        this.unifiedDiff = unifiedDiff;
        this.oldContent = oldContent;
        this.newContent = newContent;
        this.subTaskId = subTaskId;
    }

    /**
     * 快速构造 MODIFIED 类型的 diff。
     */
    public static FileDiff modified(String relativePath, String unifiedDiff,
                                    String oldContent, String newContent, String subTaskId) {
        return new FileDiff(relativePath, ChangeType.MODIFIED, unifiedDiff,
                oldContent, newContent, subTaskId);
    }

    /**
     * 快速构造 CREATED 类型的 diff。
     */
    public static FileDiff created(String relativePath, String newContent, String subTaskId) {
        return new FileDiff(relativePath, ChangeType.CREATED, null,
                null, newContent, subTaskId);
    }

    /**
     * 快速构造 DELETED 类型的 diff。
     */
    public static FileDiff deleted(String relativePath, String oldContent, String subTaskId) {
        return new FileDiff(relativePath, ChangeType.DELETED, null,
                oldContent, null, subTaskId);
    }

    public String getRelativePath() {
        return relativePath;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getUnifiedDiff() {
        return unifiedDiff;
    }

    public String getOldContent() {
        return oldContent;
    }

    public String getNewContent() {
        return newContent;
    }

    public String getSubTaskId() {
        return subTaskId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileDiff)) return false;
        FileDiff fileDiff = (FileDiff) o;
        return Objects.equals(relativePath, fileDiff.relativePath)
                && changeType == fileDiff.changeType
                && Objects.equals(subTaskId, fileDiff.subTaskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relativePath, changeType, subTaskId);
    }

    @Override
    public String toString() {
        return changeType + " " + relativePath + " (subTask: " + subTaskId + ")";
    }
}
