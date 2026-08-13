package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

@Entity
@Table(name = "command_knowledge", indexes = {
    @Index(name = "idx_command_knowledge_command", columnList = "command"),
    @Index(name = "idx_command_knowledge_tool_type", columnList = "tool_type"),
    @Index(name = "idx_command_knowledge_permission", columnList = "permission")
})
public class CommandKnowledgeEntity {
    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Integer id;

    @Column(nullable = false, unique = true)
    private String command;

    @Column(name = "tool_type")
    private String toolType;

    @Column
    private String permission;

    @Column(name = "risk_level")
    private Integer riskLevel;

    @Column
    private Integer confidence;

    @Column
    private String source;

    @Column(name = "last_verified")
    private Long lastVerified;

    @Column(name = "verified_count")
    private Integer verifiedCount;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getToolType() { return toolType; }
    public void setToolType(String toolType) { this.toolType = toolType; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public Integer getRiskLevel() { return riskLevel; }
    public void setRiskLevel(Integer riskLevel) { this.riskLevel = riskLevel; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getLastVerified() { return lastVerified; }
    public void setLastVerified(Long lastVerified) { this.lastVerified = lastVerified; }
    public Integer getVerifiedCount() { return verifiedCount; }
    public void setVerifiedCount(Integer verifiedCount) { this.verifiedCount = verifiedCount; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
