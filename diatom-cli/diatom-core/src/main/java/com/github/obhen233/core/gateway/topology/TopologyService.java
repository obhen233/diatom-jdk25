package com.github.obhen233.core.gateway.topology;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.gateway.topology.model.TopologyDefinition;
import com.github.obhen233.spi.ClusterCoordinator;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for topology management.
 * Handles CRUD, publish, rollback, and HA cluster sync.
 */
public class TopologyService {
    private static final Logger logger = LoggerFactory.getLogger(TopologyService.class);

    private final TopologyDao dao;
    private volatile ClusterCoordinator clusterCoordinator;
    private volatile TopologyDefinition activeDefinition;

    public TopologyService(DatabaseManager db) {
        this.dao = new TopologyDao(db);
        // Load active definition on startup
        reloadActiveDefinition();
    }

    public void setClusterCoordinator(ClusterCoordinator clusterCoordinator) {
        this.clusterCoordinator = clusterCoordinator;
    }

    /**
     * Reload the active published topology from the database.
     */
    public void reloadActiveDefinition() {
        try {
            TopologyVersion active = dao.findActiveVersion();
            if (active != null && active.getDefinition() != null && !active.getDefinition().isEmpty()) {
                this.activeDefinition = JsonUtils.fromJson(active.getDefinition(), TopologyDefinition.class);
                logger.info("Loaded active topology definition v{} (topology_id={})",
                        active.getVersion(), active.getTopologyId());
            } else {
                this.activeDefinition = null;
                logger.info("No active topology definition found");
            }
        } catch (Exception e) {
            logger.warn("Failed to load active topology definition: {}", e.getMessage());
            this.activeDefinition = null;
        }
    }

    /**
     * Get the currently active topology definition (for routing).
     * Returns null if no published topology exists.
     */
    public TopologyDefinition getActiveDefinition() {
        return activeDefinition;
    }

    // ========== CRUD ==========

    public List<TopologyDef> listDefinitions() {
        return dao.findAllDefs();
    }

    public TopologyDef getDefinition(long id) {
        return dao.findDefById(id);
    }

    public long createDefinition(String name, String description) {
        long now = System.currentTimeMillis();
        int maxVersion = dao.findMaxVersion();
        TopologyDef def = new TopologyDef();
        def.setName(name);
        def.setDescription(description);
        def.setStatus("draft");
        def.setVersion(maxVersion);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        return dao.insertDef(def);
    }

    public boolean updateDefinition(long id, String name, String description, String draftDefinition) {
        return dao.updateDef(id, name, description, draftDefinition, System.currentTimeMillis());
    }

    public boolean deleteDefinition(long id) {
        TopologyDef def = dao.findDefById(id);
        if (def == null) return false;
        if ("published".equals(def.getStatus())) {
            return false; // Cannot delete published topology
        }
        return dao.deleteDef(id);
    }

    // ========== Publish ==========

    public boolean publishDefinition(long id, String definitionJson) {
        boolean ok = dao.publishDef(id, definitionJson, System.currentTimeMillis());
        if (ok) {
            reloadActiveDefinition();
            // Sync to cluster if HA mode
            syncActiveToCluster();
        }
        return ok;
    }

    // ========== Versions ==========

    public List<TopologyVersion> listVersions(long topologyId) {
        return dao.findVersionsByTopologyId(topologyId);
    }

    public TopologyVersion getVersion(long topologyId, int version) {
        return dao.findVersion(topologyId, version);
    }

    /**
     * List all history entries: up to 50 most recent version snapshots across all defs,
     * plus all draft defs with non-null draftDefinition.
     * Each entry is a Map with keys: type, defId, defName, version, status, definition, publishedAt.
     */
    public List<Map<String, Object>> listAllHistory() {
        List<Map<String, Object>> result = new ArrayList<>();

        // Build def name lookup map
        List<TopologyDef> allDefs = dao.findAllDefs();
        java.util.Map<Long, String> defNames = new java.util.HashMap<>();
        for (TopologyDef def : allDefs) {
            defNames.put(def.getId(), def.getName());
        }

        // Versions: up to 50 most recent across all defs
        List<TopologyVersion> versions = dao.findAllVersions(50);
        for (TopologyVersion v : versions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "version");
            entry.put("defId", v.getTopologyId());
            entry.put("defName", defNames.getOrDefault(v.getTopologyId(), "Unknown"));
            entry.put("version", v.getVersion());
            entry.put("status", v.getStatus());
            entry.put("definition", v.getDefinition());
            entry.put("publishedAt", v.getPublishedAt());
            result.add(entry);
        }

        // Draft defs with non-null draftDefinition
        for (TopologyDef def : allDefs) {
            if ("draft".equals(def.getStatus()) && def.getDraftDefinition() != null && !def.getDraftDefinition().isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "draft");
                entry.put("defId", def.getId());
                entry.put("defName", def.getName());
                entry.put("version", def.getVersion());
                entry.put("status", "draft");
                entry.put("definition", def.getDraftDefinition());
                entry.put("publishedAt", null);
                result.add(entry);
            }
        }

        return result;
    }

    // ========== Rollback ==========

    public boolean rollback(long topologyId, int version) {
        boolean ok = dao.rollbackToVersion(topologyId, version, System.currentTimeMillis());
        if (ok) {
            logger.info("Rolled back topology {} to version {}, now in draft status", topologyId, version);
        }
        return ok;
    }

    // ========== Version Delete ==========

    /**
     * Delete an unpublished version. Active (published) versions cannot be deleted.
     */
    public boolean deleteVersion(long topologyId, int version) {
        TopologyVersion tv = dao.findVersion(topologyId, version);
        if (tv == null) return false;
        if ("active".equals(tv.getStatus())) return false; // Cannot delete active version
        return dao.deleteVersion(topologyId, version);
    }

    // ========== HA Cluster Sync ==========

    /**
     * Sync the active topology definition to the cluster (Hazelcast).
     * All Gateways in the HA cluster can then read it.
     */
    public void syncActiveToCluster() {
        if (clusterCoordinator == null || !clusterCoordinator.isActive()) return;
        try {
            TopologyVersion active = dao.findActiveVersion();
            if (active != null && active.getDefinition() != null) {
                clusterCoordinator.store("topology", "active", active.getDefinition(), 0);
                logger.info("Synced active topology v{} to cluster", active.getVersion());
            } else {
                clusterCoordinator.remove("topology", "active");
                logger.info("Removed topology from cluster (no active definition)");
            }
        } catch (Exception e) {
            logger.warn("Failed to sync topology to cluster: {}", e.getMessage());
        }
    }

    /**
     * Load active topology from cluster. Used by HA Gateway instances
     * that didn't publish the topology themselves.
     */
    public void loadActiveFromCluster() {
        if (clusterCoordinator == null || !clusterCoordinator.isActive()) return;
        try {
            String json = clusterCoordinator.retrieve("topology", "active");
            if (json != null && !json.isEmpty()) {
                this.activeDefinition = JsonUtils.fromJson(json, TopologyDefinition.class);
                logger.info("Loaded active topology from cluster");
            }
        } catch (Exception e) {
            logger.warn("Failed to load topology from cluster: {}", e.getMessage());
        }
    }
}
