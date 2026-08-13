package com.github.obhen233.core.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Gateway → Worker 的任务迁移请求数据结构
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrateRequest {
    private String taskId;
    private String originalRequest;
    private int checkpointStep;
    private String gatewayUrl;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getOriginalRequest() { return originalRequest; }
    public void setOriginalRequest(String originalRequest) { this.originalRequest = originalRequest; }
    public int getCheckpointStep() { return checkpointStep; }
    public void setCheckpointStep(int checkpointStep) { this.checkpointStep = checkpointStep; }
    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
}
