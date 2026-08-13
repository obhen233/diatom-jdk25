package com.github.obhen233.core.gateway.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * 从用户请求中提取的任务需求，用于路由匹配
 */
public class TaskRequirement {
    private String taskType;
    private List<String> requiredCapabilities = new ArrayList<>();
    private List<String> preferredModelTraits = new ArrayList<>();
    private int complexity;
    private int sensitivity;
    private int expectedTokens;
    private String budgetPriority = "quality";
    private boolean fallbackAllowed = true;
    private boolean pipelineRecommended;
    private String suggestedWorkerId;
    private String reasoning;
    private String workspaceHint;
    private String syncStrategy;   // "full_sync" | "skip"
    private String syncReasoning;  // LLM 决策理由

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public List<String> getRequiredCapabilities() { return requiredCapabilities; }
    public void setRequiredCapabilities(List<String> requiredCapabilities) { this.requiredCapabilities = requiredCapabilities; }
    public List<String> getPreferredModelTraits() { return preferredModelTraits; }
    public void setPreferredModelTraits(List<String> preferredModelTraits) { this.preferredModelTraits = preferredModelTraits; }
    public int getComplexity() { return complexity; }
    public void setComplexity(int complexity) { this.complexity = complexity; }
    public int getSensitivity() { return sensitivity; }
    public void setSensitivity(int sensitivity) { this.sensitivity = sensitivity; }
    public int getExpectedTokens() { return expectedTokens; }
    public void setExpectedTokens(int expectedTokens) { this.expectedTokens = expectedTokens; }
    public String getBudgetPriority() { return budgetPriority; }
    public void setBudgetPriority(String budgetPriority) { this.budgetPriority = budgetPriority; }
    public boolean isFallbackAllowed() { return fallbackAllowed; }
    public void setFallbackAllowed(boolean fallbackAllowed) { this.fallbackAllowed = fallbackAllowed; }
    public boolean isPipelineRecommended() { return pipelineRecommended; }
    public void setPipelineRecommended(boolean pipelineRecommended) { this.pipelineRecommended = pipelineRecommended; }
    public String getSuggestedWorkerId() { return suggestedWorkerId; }
    public void setSuggestedWorkerId(String suggestedWorkerId) { this.suggestedWorkerId = suggestedWorkerId; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public String getWorkspaceHint() { return workspaceHint; }
    public void setWorkspaceHint(String workspaceHint) { this.workspaceHint = workspaceHint; }
    public String getSyncStrategy() { return syncStrategy; }
    public void setSyncStrategy(String syncStrategy) { this.syncStrategy = syncStrategy; }
    public String getSyncReasoning() { return syncReasoning; }
    public void setSyncReasoning(String syncReasoning) { this.syncReasoning = syncReasoning; }
}
