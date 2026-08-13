package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "topology_version", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"topology_id", "version"})
}, indexes = {
    @Index(name = "idx_topology_version_topo", columnList = "topology_id,version")
})
public class TopologyVersionEntity {

    @Id
    @GeneratedValue(generator = "diatom_id_gen")
    @org.hibernate.annotations.GenericGenerator(name = "diatom_id_gen", strategy = "com.github.obhen233.core.database.DiatomIdGenerator",
        parameters = {
            @org.hibernate.annotations.Parameter(name = "table_name", value = "hibernate_sequences")
        })
    private Long id;

    @Column(name = "topology_id", nullable = false)
    private Long topologyId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String definition;

    @Column(nullable = false)
    private String status;

    @Column(name = "published_at", nullable = false)
    private Long publishedAt;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopologyId() { return topologyId; }
    public void setTopologyId(Long topologyId) { this.topologyId = topologyId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Long publishedAt) { this.publishedAt = publishedAt; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
