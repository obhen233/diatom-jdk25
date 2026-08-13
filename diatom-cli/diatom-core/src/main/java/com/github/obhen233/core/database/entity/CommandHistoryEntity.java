package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

@Entity
@Table(name = "command_history", indexes = {
    @Index(name = "idx_history_timestamp", columnList = "timestamp"),
    @Index(name = "idx_history_session", columnList = "session_id"),
    @Index(name = "idx_history_task_id", columnList = "task_id")
})
public class CommandHistoryEntity {
    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Long id;

    @Column(name = "input_text", nullable = false, columnDefinition = "TEXT")
    private String inputText;

    @Column(nullable = false)
    private Long timestamp;

    @Column(name = "session_id")
    private String sessionId;

    @Column
    private String workspace;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "response_token_count")
    private Integer responseTokenCount;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "tool_calls", columnDefinition = "TEXT")
    private String toolCalls;

    @Column(name = "project_name")
    private String projectName;

    @Column(name = "workspace_id")
    private Long workspaceId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public Integer getResponseTokenCount() { return responseTokenCount; }
    public void setResponseTokenCount(Integer responseTokenCount) { this.responseTokenCount = responseTokenCount; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getToolCalls() { return toolCalls; }
    public void setToolCalls(String toolCalls) { this.toolCalls = toolCalls; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
}
