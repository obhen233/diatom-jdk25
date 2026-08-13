package com.github.obhen233.core.gateway.topology;

/**
 * POJO for topology_def table — topology definition entity.
 */
public class TopologyDef {
    private long id;
    private String name;
    private String description;
    private String status;         // "draft" | "published"
    private int version;
    private String draftDefinition; // JSON draft (null when published and no draft in progress)
    private long publishedAt;
    private long createdAt;
    private long updatedAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getDraftDefinition() { return draftDefinition; }
    public void setDraftDefinition(String draftDefinition) { this.draftDefinition = draftDefinition; }
    public long getPublishedAt() { return publishedAt; }
    public void setPublishedAt(long publishedAt) { this.publishedAt = publishedAt; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
