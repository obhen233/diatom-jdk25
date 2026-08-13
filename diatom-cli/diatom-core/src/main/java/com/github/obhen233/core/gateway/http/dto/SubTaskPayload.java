package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubTaskPayload {
    public String subTaskId;
    public String parentTaskId;
    public String description;
    public int order;
    public String workspacePath;
    public String fileManifest;
}
