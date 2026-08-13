package com.github.obhen233.core.gateway.topology;

/**
 * POJO for topology_version table — immutable published snapshot.
 */
public class TopologyVersion {
    private long id;
    private long topologyId;
    private int version;
    private String definition;   // full JSON snapshot
    private String status;       // "active" | "superseded"
    private long publishedAt;
    private long createdAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getTopologyId() { return topologyId; }
    public void setTopologyId(long topologyId) { this.topologyId = topologyId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getPublishedAt() { return publishedAt; }
    public void setPublishedAt(long publishedAt) { this.publishedAt = publishedAt; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
