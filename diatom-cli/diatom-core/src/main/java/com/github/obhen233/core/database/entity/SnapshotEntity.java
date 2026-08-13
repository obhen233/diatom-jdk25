package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

@Entity
@Table(name = "snapshots", indexes = {
    @Index(name = "idx_snapshots_task", columnList = "task_id, created_at")
})
public class SnapshotEntity {
    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Integer id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "snapshot_type", nullable = false)
    private String snapshotType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "parent_snapshot_id")
    private Integer parentSnapshotId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getParentSnapshotId() { return parentSnapshotId; }
    public void setParentSnapshotId(Integer parentSnapshotId) { this.parentSnapshotId = parentSnapshotId; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
