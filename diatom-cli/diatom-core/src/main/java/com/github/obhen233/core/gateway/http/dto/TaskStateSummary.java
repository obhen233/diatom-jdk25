package com.github.obhen233.core.gateway.http.dto;

public class TaskStateSummary {
    public String taskId;
    public String status;
    public String workerId;
    public int currentStep;
    public int totalTokens;
    public long createdAt;
    public long updatedAt;
}
