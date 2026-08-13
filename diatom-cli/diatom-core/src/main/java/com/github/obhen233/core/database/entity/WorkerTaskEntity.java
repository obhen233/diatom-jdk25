package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

/**
 * Entity for the worker_tasks table, used by ServerModeLauncher.
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

    @Column(name = "assigned_at", nullable = false)
    private Long assignedAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
    public Long getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Long assignedAt) { this.assignedAt = assignedAt; }
}
