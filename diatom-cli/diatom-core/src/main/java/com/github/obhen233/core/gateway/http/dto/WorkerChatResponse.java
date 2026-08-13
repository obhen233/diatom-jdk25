package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkerChatResponse {
    public String status;
    public String taskId;
    public String response;
    public WorkerMeta workerMeta;
    public Object fileDiffs;

    public WorkerChatResponse() {}

    public WorkerChatResponse(String status, String taskId, String response) {
        this.status = status;
        this.taskId = taskId;
        this.response = response;
    }
}
