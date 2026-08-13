package com.github.obhen233.core.gateway.checkpoint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Worker 上报的 checkpoint 数据模型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CheckpointReport {
    private String taskId;
    private int stepCount;
    private int messageCount;
    private int tokenUsage;
    private String agentState;
    private List<String> conversationHistory;
    private List<String> toolResults;
    private String llmSummary;
    private String fileChangeSummary;
    private int progress;
    private String workspacePath;
    private String status = "running"; // "running", "completed", "failed"

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public int getStepCount() { return stepCount; }
    public void setStepCount(int stepCount) { this.stepCount = stepCount; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public int getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(int tokenUsage) { this.tokenUsage = tokenUsage; }
    public String getAgentState() { return agentState; }
    public void setAgentState(String agentState) { this.agentState = agentState; }
    public List<String> getConversationHistory() { return conversationHistory; }
    public void setConversationHistory(List<String> conversationHistory) { this.conversationHistory = conversationHistory; }
    public List<String> getToolResults() { return toolResults; }
    public void setToolResults(List<String> toolResults) { this.toolResults = toolResults; }
    public String getLlmSummary() { return llmSummary; }
    public void setLlmSummary(String llmSummary) { this.llmSummary = llmSummary; }
    public String getFileChangeSummary() { return fileChangeSummary; }
    public void setFileChangeSummary(String fileChangeSummary) { this.fileChangeSummary = fileChangeSummary; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
