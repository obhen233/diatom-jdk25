package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

@Entity
@Table(name = "gateway_checkpoints")
public class GatewayCheckpointEntity {
    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Long id;

    @Column(name = "checkpoint_id", nullable = false, unique = true)
    private String checkpointId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "agent_state", columnDefinition = "TEXT")
    private String agentState;

    @Column(name = "step_count")
    private Integer stepCount;

    @Column(name = "conversation_history", columnDefinition = "TEXT")
    private String conversationHistory;

    @Column(name = "tool_results", columnDefinition = "TEXT")
    private String toolResults;

    @Column(name = "llm_summary", columnDefinition = "TEXT")
    private String llmSummary;

    @Column(name = "file_change_summary", columnDefinition = "TEXT")
    private String fileChangeSummary;

    @Column(name = "message_count")
    private Integer messageCount;

    @Column(name = "token_usage")
    private Integer tokenUsage;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCheckpointId() { return checkpointId; }
    public void setCheckpointId(String checkpointId) { this.checkpointId = checkpointId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getAgentState() { return agentState; }
    public void setAgentState(String agentState) { this.agentState = agentState; }
    public Integer getStepCount() { return stepCount; }
    public void setStepCount(Integer stepCount) { this.stepCount = stepCount; }
    public String getConversationHistory() { return conversationHistory; }
    public void setConversationHistory(String conversationHistory) { this.conversationHistory = conversationHistory; }
    public String getToolResults() { return toolResults; }
    public void setToolResults(String toolResults) { this.toolResults = toolResults; }
    public String getLlmSummary() { return llmSummary; }
    public void setLlmSummary(String llmSummary) { this.llmSummary = llmSummary; }
    public String getFileChangeSummary() { return fileChangeSummary; }
    public void setFileChangeSummary(String fileChangeSummary) { this.fileChangeSummary = fileChangeSummary; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public Integer getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(Integer tokenUsage) { this.tokenUsage = tokenUsage; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
