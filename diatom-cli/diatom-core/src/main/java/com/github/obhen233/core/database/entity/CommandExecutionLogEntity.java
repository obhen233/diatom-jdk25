package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

@Entity
@Table(name = "command_execution_log", indexes = {
    @Index(name = "idx_command_execution_log_command", columnList = "command"),
    @Index(name = "idx_command_execution_log_timestamp", columnList = "timestamp")
})
public class CommandExecutionLogEntity {
    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Integer id;

    @Column(nullable = false)
    private String command;

    @Column(columnDefinition = "TEXT")
    private String args;

    @Column(name = "tool_type")
    private String toolType;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(name = "risk_assessed")
    private Integer riskAssessed;

    @Column(name = "user_feedback", columnDefinition = "TEXT")
    private String userFeedback;

    @Column
    private Long timestamp;

    @Column
    private String permission;

    @Column(name = "risk_level")
    private Integer riskLevel;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "classification_method")
    private String classificationMethod;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column
    private String status;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getArgs() { return args; }
    public void setArgs(String args) { this.args = args; }
    public String getToolType() { return toolType; }
    public void setToolType(String toolType) { this.toolType = toolType; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Integer getRiskAssessed() { return riskAssessed; }
    public void setRiskAssessed(Integer riskAssessed) { this.riskAssessed = riskAssessed; }
    public String getUserFeedback() { return userFeedback; }
    public void setUserFeedback(String userFeedback) { this.userFeedback = userFeedback; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public Integer getRiskLevel() { return riskLevel; }
    public void setRiskLevel(Integer riskLevel) { this.riskLevel = riskLevel; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public String getClassificationMethod() { return classificationMethod; }
    public void setClassificationMethod(String classificationMethod) { this.classificationMethod = classificationMethod; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
