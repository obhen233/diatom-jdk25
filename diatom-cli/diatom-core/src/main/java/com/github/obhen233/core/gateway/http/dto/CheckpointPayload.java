package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckpointPayload {
    public String taskId;
    public int stepCount;
    public int messageCount;
    public int tokenUsage;
    public String agentState;
    public String conversationHistory;
    public String toolResults;
}
