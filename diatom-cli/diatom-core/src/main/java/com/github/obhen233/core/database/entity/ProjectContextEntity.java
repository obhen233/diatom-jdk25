package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

@Entity
@Table(name = "project_context", indexes = {
    @Index(name = "idx_project_workspace", columnList = "workspace_id"),
    @Index(name = "idx_context_project", columnList = "project_path")
})
public class ProjectContextEntity {
    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "project_path", nullable = false, unique = true)
    private String projectPath;

    @Column(name = "project_name")
    private String projectName;

    @Column(name = "project_type")
    private String projectType;

    @Column(name = "indexed_at")
    private Long indexedAt;

    @Column(name = "context_data", columnDefinition = "TEXT")
    private String contextData;

    @Column(name = "file_index", columnDefinition = "TEXT")
    private String fileIndex;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }
    public Long getIndexedAt() { return indexedAt; }
    public void setIndexedAt(Long indexedAt) { this.indexedAt = indexedAt; }
    public String getContextData() { return contextData; }
    public void setContextData(String contextData) { this.contextData = contextData; }
    public String getFileIndex() { return fileIndex; }
    public void setFileIndex(String fileIndex) { this.fileIndex = fileIndex; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
