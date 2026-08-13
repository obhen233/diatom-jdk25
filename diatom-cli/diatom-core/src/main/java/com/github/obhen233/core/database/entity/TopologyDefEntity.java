package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "topology_def")
public class TopologyDefEntity {

    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @org.hibernate.annotations.GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @org.hibernate.annotations.Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "draft_definition", columnDefinition = "TEXT")
    private String draftDefinition;

    @Column(name = "published_at")
    private Long publishedAt;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getDraftDefinition() { return draftDefinition; }
    public void setDraftDefinition(String draftDefinition) { this.draftDefinition = draftDefinition; }
    public Long getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Long publishedAt) { this.publishedAt = publishedAt; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
