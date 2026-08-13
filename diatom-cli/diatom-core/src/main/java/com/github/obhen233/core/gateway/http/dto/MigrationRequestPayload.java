package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationRequestPayload {
    public String taskId;
    public String originalRequest;
    public int checkpointStep;
    public String gatewayUrl;
    public String workspacePath;
}
