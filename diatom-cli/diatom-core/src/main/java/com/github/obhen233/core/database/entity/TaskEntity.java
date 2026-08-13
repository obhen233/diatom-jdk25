package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "tasks", indexes = {
    @Index(name = "idx_tasks_status", columnList = "status"),
    @Index(name = "idx_tasks_updated", columnList = "updated_at")
})
public class TaskEntity {
    @Id
    @Column(length = 255)
    private String id;

    @Column(nullable = false)
    private String status;

    @Column(name = "original_request", nullable = false, columnDefinition = "TEXT")
    private String originalRequest;

    @Column(name = "current_step")
    private Integer currentStep;

    @Column(name = "total_steps")
    private Integer totalSteps;

    @Column(name = "workspace_path", nullable = false)
    private String workspacePath;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "context_checkpoint_id")
    private Integer contextCheckpointId;

    @Column(name = "latest_snapshot_id")
    private Integer latestSnapshotId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOriginalRequest() { return originalRequest; }
    public void setOriginalRequest(String originalRequest) { this.originalRequest = originalRequest; }
    public Integer getCurrentStep() { return currentStep; }
    public void setCurrentStep(Integer currentStep) { this.currentStep = currentStep; }
    public Integer getTotalSteps() { return totalSteps; }
    public void setTotalSteps(Integer totalSteps) { this.totalSteps = totalSteps; }
    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Integer getContextCheckpointId() { return contextCheckpointId; }
    public void setContextCheckpointId(Integer contextCheckpointId) { this.contextCheckpointId = contextCheckpointId; }
    public Integer getLatestSnapshotId() { return latestSnapshotId; }
    public void setLatestSnapshotId(Integer latestSnapshotId) { this.latestSnapshotId = latestSnapshotId; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
