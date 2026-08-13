package com.github.obhen233.core.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Gateway checkpoint 确认响应
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckpointAck {
    private String status;
    private String taskStatus;
    private String message;

    public CheckpointAck() {}

    public CheckpointAck(String status) {
        this.status = status;
    }

    public CheckpointAck(String status, String taskStatus, String message) {
        this.status = status;
        this.taskStatus = taskStatus;
        this.message = message;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTaskStatus() { return taskStatus; }
    public void setTaskStatus(String taskStatus) { this.taskStatus = taskStatus; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
