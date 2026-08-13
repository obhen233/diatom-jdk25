package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

@Entity
@Table(name = "command_rules", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"mode", "type", "pattern"})
}, indexes = {
    @Index(name = "idx_command_rules_mode", columnList = "mode"),
    @Index(name = "idx_command_rules_type", columnList = "type"),
    @Index(name = "idx_command_rules_source", columnList = "source")
})
public class CommandRuleEntity {
    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Long id;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String pattern;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
