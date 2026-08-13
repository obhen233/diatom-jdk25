package com.github.obhen233.core.gateway.registry;

import com.github.obhen233.spi.ClusterCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * HA 集群模式的 Worker 注册表装饰器。
 *
 * <p>装饰 {@link FileSystemWorkerRegistry}，在本地文件系统注册表基础上，
 * 通过 {@link ClusterCoordinator} 将 Worker 注册信息同步到集群。</p>
 *
 * <p>行为模式：</p>
 * <ul>
 *   <li><b>HA=false</b>：所有方法直接委托给本地注册表</li>
 *   <li><b>HA=true</b>：register/heartbeat/deregister 同步到集群；
 *       availableWorkers 合并本地和远程 Worker</li>
 * </ul>
 */
public class ClusteredWorkerRegistry implements WorkerRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ClusteredWorkerRegistry.class);
    private static final int WORKER_TTL_SECONDS = 120;

    private final WorkerRegistry localRegistry;
    private final ClusterCoordinator cluster;
    private final String localGatewayId;
    private final boolean haEnabled;

    public ClusteredWorkerRegistry(WorkerRegistry localRegistry,
                                    ClusterCoordinator cluster,
                                    String localGatewayId,
                                    boolean haEnabled) {
        this.localRegistry = localRegistry;
        this.cluster = cluster;
        this.localGatewayId = localGatewayId;
        this.haEnabled = haEnabled;
    }

    @Override
    public void register(WorkerInfo worker) {
        localRegistry.register(worker);
        if (haEnabled && cluster != null && cluster.isActive()) {
            ClusterCoordinator.WorkerEntry entry = toWorkerEntry(worker);
            cluster.putWorker(worker.getWorkerId(), entry, WORKER_TTL_SECONDS);
            logger.debug("Cluster sync: registered worker {} (gatewayId={})",
                    worker.getWorkerId(), localGatewayId);
        }
    }

    @Override
    public void deregister(String workerId) {
        localRegistry.deregister(workerId);
        if (haEnabled && cluster != null && cluster.isActive()) {
            cluster.removeWorker(workerId);
            logger.debug("Cluster sync: deregistered worker {}", workerId);
        }
    }

    @Override
    public void heartbeat(String workerId, WorkerMetrics metrics) {
        localRegistry.heartbeat(workerId, metrics);
        if (haEnabled && cluster != null && cluster.isActive()) {
            // Refresh cluster TTL by re-putting the entry
            WorkerInfo worker = localRegistry.getWorker(workerId);
            if (worker != null) {
                ClusterCoordinator.WorkerEntry entry = toWorkerEntry(worker);
                cluster.putWorker(workerId, entry, WORKER_TTL_SECONDS);
            }
        }
    }

    @Override
    public List<WorkerInfo> availableWorkers() {
        List<WorkerInfo> localWorkers = localRegistry.availableWorkers();
        if (!haEnabled || cluster == null || !cluster.isActive()) {
            return localWorkers;
        }

        // Merge local + remote workers, excluding remote entries belonging to this gateway
        Collection<ClusterCoordinator.WorkerEntry> remoteEntries = cluster.getAllWorkers();
        if (remoteEntries == null || remoteEntries.isEmpty()) {
            return localWorkers;
        }

        // Build lookup of local worker IDs for dedup
        java.util.Set<String> localWorkerIds = localWorkers.stream()
                .map(WorkerInfo::getWorkerId)
                .collect(Collectors.toSet());

        List<WorkerInfo> merged = new ArrayList<>(localWorkers);
        for (ClusterCoordinator.WorkerEntry entry : remoteEntries) {
            if (entry == null) continue;
            // Skip entries from this gateway (already in local registry)
            if (localGatewayId != null && localGatewayId.equals(entry.getGatewayId())) continue;
            // Skip duplicates (should not happen, but safety)
            if (localWorkerIds.contains(entry.getWorkerId())) continue;

            WorkerInfo remoteWorker = fromWorkerEntry(entry);
            if (remoteWorker != null) {
                merged.add(remoteWorker);
            }
        }
        return merged;
    }

    @Override
    public List<WorkerInfo> localWorkers() {
        // Only return workers directly connected to THIS gateway instance
        return localRegistry.availableWorkers();
    }

    @Override
    public WorkerInfo getWorker(String workerId) {
        // Check local first
        WorkerInfo local = localRegistry.getWorker(workerId);
        if (local != null) return local;

        // Fallback to cluster
        if (haEnabled && cluster != null && cluster.isActive()) {
            ClusterCoordinator.WorkerEntry entry = cluster.getWorker(workerId);
            if (entry != null) {
                return fromWorkerEntry(entry);
            }
        }
        return null;
    }

    @Override
    public void markShuttingDown(String workerId) {
        localRegistry.markShuttingDown(workerId);
    }

    @Override
    public void subscribe(Consumer<RegistryEvent> listener) {
        localRegistry.subscribe(listener);
    }

    @Override
    public void shutdown() {
        localRegistry.shutdown();
    }

    // ========== Session Affinity ==========

    /**
     * Set session affinity: associate a session with a worker.
     * TTL is 1 hour (3600 seconds).
     */
    public void setSessionAffinity(String sessionId, String workerId) {
        if (haEnabled && cluster != null && cluster.isActive()) {
            cluster.store("session", sessionId, workerId, 3600);
        }
    }

    /**
     * Get the worker ID associated with a session, or null if none.
     */
    public String getSessionAffinity(String sessionId) {
        if (haEnabled && cluster != null && cluster.isActive()) {
            return cluster.retrieve("session", sessionId);
        }
        return null;
    }

    /**
     * Clear session affinity for a session.
     */
    public void clearSessionAffinity(String sessionId) {
        if (haEnabled && cluster != null && cluster.isActive()) {
            cluster.remove("session", sessionId);
        }
    }

    // ========== Task Metadata ==========

    /**
     * Store task metadata in the cluster (e.g., task status, worker assignment).
     */
    public void storeTaskMeta(String taskId, String jsonMeta) {
        if (haEnabled && cluster != null && cluster.isActive()) {
            cluster.store("task", taskId, jsonMeta, 86400); // 24h TTL
        }
    }

    /**
     * Retrieve task metadata from the cluster.
     */
    public String getTaskMeta(String taskId) {
        if (haEnabled && cluster != null && cluster.isActive()) {
            return cluster.retrieve("task", taskId);
        }
        return null;
    }

    // ========== Internal Helpers ==========

    /**
     * Convert WorkerInfo to a lightweight ClusterCoordinator.WorkerEntry.
     */
    private ClusterCoordinator.WorkerEntry toWorkerEntry(WorkerInfo w) {
        ClusterCoordinator.WorkerEntry entry = new ClusterCoordinator.WorkerEntry(
                w.getWorkerId(), localGatewayId, w.getHost(), w.getPort(),
                w.getModel(), w.getGroup(),
                w.getMetrics() != null ? w.getMetrics().getCurrentLoad() : 0.0,
                w.getMetrics() != null ? w.getMetrics().getActiveTasks() : 0
        );
        entry.setLastHeartbeat(System.currentTimeMillis());
        // Store extra fields as infoJson
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendJsonField(sb, "tier", w.getTier());
        sb.append(",");
        appendJsonField(sb, "useSsl", String.valueOf(w.isUseSsl()));
        sb.append(",");
        appendJsonField(sb, "maxConcurrency", String.valueOf(w.getMaxConcurrency()));
        if (w.getWorkspace() != null && !w.getWorkspace().isEmpty()) {
            sb.append(",");
            appendJsonField(sb, "workspace", w.getWorkspace());
        }
        sb.append("}");
        entry.setInfoJson(sb.toString());
        return entry;
    }

    /**
     * Reconstruct a WorkerInfo from a ClusterCoordinator.WorkerEntry.
     */
    private WorkerInfo fromWorkerEntry(ClusterCoordinator.WorkerEntry entry) {
        if (entry == null) return null;
        WorkerInfo w = new WorkerInfo(entry.getWorkerId(), entry.getHost(), entry.getPort());
        w.setModel(entry.getModel());
        w.setGroup(entry.getGroup());
        w.setGatewayId(entry.getGatewayId());
        if (w.getMetrics() != null) {
            w.getMetrics().setCurrentLoad(entry.getCurrentLoad());
            w.getMetrics().setActiveTasks(entry.getActiveTasks());
            w.getMetrics().setLastHeartbeat(entry.getLastHeartbeat());
        }

        // Parse extra fields from infoJson
        String infoJson = entry.getInfoJson();
        if (infoJson != null && !infoJson.isEmpty()) {
            String tier = extractJsonField(infoJson, "tier");
            if (tier != null) w.setTier(tier);
            String useSslStr = extractJsonField(infoJson, "useSsl");
            if (useSslStr != null) w.setUseSsl("true".equalsIgnoreCase(useSslStr));
            String maxConcStr = extractJsonField(infoJson, "maxConcurrency");
            if (maxConcStr != null) {
                try { w.setMaxConcurrency(Integer.parseInt(maxConcStr)); } catch (NumberFormatException ignored) {}
            }
            String workspace = extractJsonField(infoJson, "workspace");
            if (workspace != null) w.setWorkspace(workspace);
        }

        w.setStatus(WorkerInfo.WorkerStatus.ONLINE);
        return w;
    }

    /**
     * Append a JSON field key-value pair to a StringBuilder.
     */
    private static void appendJsonField(StringBuilder sb, String key, String value) {
        sb.append("\"").append(key).append("\":\"");
        if (value != null) {
            sb.append(value.replace("\\", "\\\\").replace("\"", "\\\""));
        }
        sb.append("\"");
    }

    /**
     * Extract a string field value from a simple JSON object (no nesting).
     */
    private static String extractJsonField(String json, String key) {
        if (json == null || key == null) return null;
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            // Try without quotes for boolean/number values
            search = "\"" + key + "\":";
            start = json.indexOf(search);
            if (start < 0) return null;
            start += search.length();
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            if (end > start) {
                return json.substring(start, end).trim();
            }
            return null;
        }
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end > start) {
            return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return null;
    }
}
