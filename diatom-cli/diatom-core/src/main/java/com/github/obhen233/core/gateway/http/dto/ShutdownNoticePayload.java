package com.github.obhen233.core.gateway.http.dto;

public class ShutdownNoticePayload {
    public String taskId;
    public int stepCount;
    public int tokenUsage;
    public String agentState;
    public String llmSummary;
    public String fileChangeSummary;
}
