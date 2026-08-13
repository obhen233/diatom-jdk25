package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResumeRequestPayload {
    public String taskId;
    public int checkpointStep;
    public String originalRequest;
    public String conversationHistory;
    public String agentState;
}
