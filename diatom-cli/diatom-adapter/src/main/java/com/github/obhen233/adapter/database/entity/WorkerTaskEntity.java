package com.github.obhen233.adapter.database.entity;

import jakarta.persistence.*;

/**
 * Entity for the worker_tasks table, tracking tasks assigned to this adapter worker.
 * Hibernate hbm2ddl.auto=update will auto-create this table.
 */
@Entity
@Table(name = "worker_tasks")
public class WorkerTaskEntity {

    @Id
    @Column(name = "task_id", length = 255, nullable = false)
    private String taskId;

    @Column(nullable = false)
    private String status;

    @Column(name = "gateway_url")
    private String gatewayUrl;

    @Column(name = "assigned_at")
    private Long assignedAt;

    @Column(name = "completed_at")
    private Long completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
    public Long getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Long assignedAt) { this.assignedAt = assignedAt; }
    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
