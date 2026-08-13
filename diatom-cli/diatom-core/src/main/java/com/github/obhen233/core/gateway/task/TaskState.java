package com.github.obhen233.core.gateway.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务状态数据
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskState {
    private final String taskId;
    private volatile TaskStatus status;
    private String sessionId;
    private String workerId;
    private String originalRequest;
    private int currentStep;
    private int totalTokens;
    private int messageCount;
    private long createdAt;
    private long updatedAt;
    private long assignedAt;
    private String llmSummary;
    private String fileChangeSummary;
    private int checkpointStep;
    private Map<String, Object> attributes = new ConcurrentHashMap<>();

    public TaskState(String taskId) {
        this.taskId = taskId;
        this.status = TaskStatus.PENDING;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getTaskId() { return taskId; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) {
        this.status = status;
        this.updatedAt = System.currentTimeMillis();
    }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getOriginalRequest() { return originalRequest; }
    public void setOriginalRequest(String originalRequest) { this.originalRequest = originalRequest; }
    public int getCurrentStep() { return currentStep; }
    public void setCurrentStep(int currentStep) { this.currentStep = currentStep; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public long getAssignedAt() { return assignedAt; }
    public void setAssignedAt(long assignedAt) { this.assignedAt = assignedAt; }
    public String getLlmSummary() { return llmSummary; }
    public void setLlmSummary(String llmSummary) { this.llmSummary = llmSummary; }
    public String getFileChangeSummary() { return fileChangeSummary; }
    public void setFileChangeSummary(String fileChangeSummary) { this.fileChangeSummary = fileChangeSummary; }
    public int getCheckpointStep() { return checkpointStep; }
    public void setCheckpointStep(int checkpointStep) { this.checkpointStep = checkpointStep; }
    public Map<String, Object> getAttributes() { return attributes; }

    public void addAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public long getIdleTimeMs() {
        return System.currentTimeMillis() - updatedAt;
    }
}
