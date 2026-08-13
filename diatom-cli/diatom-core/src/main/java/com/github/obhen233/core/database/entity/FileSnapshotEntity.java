package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

@Entity
@Table(name = "file_snapshots", indexes = {
    @Index(name = "idx_file_snapshots_task", columnList = "task_id, created_at"),
    @Index(name = "idx_file_snapshots_hash", columnList = "content_hash")
})
public class FileSnapshotEntity {
    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Integer id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(nullable = false)
    private String operation;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "content_type")
    private String contentType;

    @Column(columnDefinition = "BLOB")
    private byte[] content;

    @Column(name = "base_snapshot_id")
    private Integer baseSnapshotId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public Integer getBaseSnapshotId() { return baseSnapshotId; }
    public void setBaseSnapshotId(Integer baseSnapshotId) { this.baseSnapshotId = baseSnapshotId; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
