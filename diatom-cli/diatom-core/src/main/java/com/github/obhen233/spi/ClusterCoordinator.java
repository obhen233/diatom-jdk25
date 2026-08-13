package com.github.obhen233.spi;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SPI for cross-Gateway cluster coordination.
 *
 * <p>Handles shared state that must be synchronized across multiple Gateway instances
 * behind a load balancer (e.g., Nginx). Covers three concerns:</p>
 * <ol>
 *   <li><b>Worker registry</b> — all Gateways see the same set of registered Workers,
 *       with TTL-based heartbeat expiration</li>
 *   <li><b>Distributed locks</b> — prevent duplicate task assignment across Gateways</li>
 *   <li><b>Event notifications</b> — Worker up/down events propagate to all Gateways</li>
 * </ol>
 *
 * <p><b>Default implementation:</b> {@code HazelcastClusterCoordinator} (embedded, auto-discovered).
 * Users can provide custom implementations via {@code META-INF/services/} in plugin JARs.</p>
 *
 * <p><b>Usage:</b></p>
 * <pre>{@code
 * ClusterCoordinator coord = ClusterCoordinatorLoader.load(config);
 * coord.putWorker("worker-1", workerInfo, 30);
 * WorkerInfo w = coord.getWorker("worker-1");
 * coord.tryLock("task-123", 5, TimeUnit.SECONDS);
 * }</pre>
 */
public interface ClusterCoordinator {

    /**
     * A human-readable name for this implementation (e.g., "hazelcast", "postgresql").
     */
    String getName();

    /**
     * Initialize the coordinator with configuration properties.
     * Called once at Gateway startup.
     *
     * @param config configuration properties (e.g., from application.properties)
     */
    void init(Map<String, String> config);

    /**
     * Shutdown the coordinator and release all resources.
     */
    void shutdown();

    // ========== Worker Registry ==========

    /**
     * Register (or update) a worker with a heartbeat TTL.
     * If the worker's heartbeat is not refreshed within {@code ttlSeconds},
     * it is automatically removed from the registry.
     */
    void putWorker(String key, WorkerEntry entry, int ttlSeconds);

    /**
     * Get a worker by its key. Returns null if not found or TTL expired.
     */
    WorkerEntry getWorker(String key);

    /**
     * Get all currently alive workers (those whose TTL has not expired).
     */
    Collection<WorkerEntry> getAllWorkers();

    /**
     * Remove a worker from the registry (e.g., on graceful shutdown).
     */
    void removeWorker(String key);

    // ========== Distributed Lock ==========

    /**
     * Acquire a distributed lock.
     *
     * @param key     the lock key (e.g., task ID)
     * @param timeout maximum time to wait for the lock
     * @param unit    time unit
     * @return true if the lock was acquired
     */
    boolean tryLock(String key, long timeout, TimeUnit unit);

    /**
     * Release a previously acquired distributed lock.
     */
    void unlock(String key);

    // ========== Event Listener ==========

    /**
     * Register a listener for cluster events (worker added/removed).
     */
    void addListener(ClusterEventListener listener);

    /**
     * Remove a previously registered listener.
     */
    void removeListener(ClusterEventListener listener);

    // ========== Generic KV Storage (Session, Task, etc.) ==========

    /**
     * Store a key-value pair with optional TTL.
     *
     * @param namespace  namespace (e.g., "session", "task") for data isolation
     * @param key        the key
     * @param value      JSON-serialized value
     * @param ttlSeconds TTL in seconds, &lt;=0 means no expiration
     */
    default void store(String namespace, String key, String value, int ttlSeconds) {
        throw new UnsupportedOperationException("store() not implemented by " + getName());
    }

    /**
     * Retrieve a value by namespace and key.
     *
     * @return the value, or null if not found or expired
     */
    default String retrieve(String namespace, String key) {
        throw new UnsupportedOperationException("retrieve() not implemented by " + getName());
    }

    /**
     * Remove a value by namespace and key.
     */
    default void remove(String namespace, String key) {
        throw new UnsupportedOperationException("remove() not implemented by " + getName());
    }

    /**
     * Scan keys in a namespace matching an optional prefix.
     *
     * @param namespace the namespace
     * @param prefix    key prefix filter (empty returns all keys)
     * @return collection of matching keys
     */
    default Collection<String> keys(String namespace, String prefix) {
        throw new UnsupportedOperationException("keys() not implemented by " + getName());
    }

    /**
     * Check whether the coordinator is connected and operational.
     */
    boolean isActive();

    // ========== WorkerEntry ==========

    /**
     * Lightweight worker data for cluster sharing.
     * Serialized/deserialized by the implementation.
     */
    class WorkerEntry {
        private String workerId;
        private String gatewayId;
        private String host;
        private int port;
        private String model;
        private String group;
        private double currentLoad;
        private int activeTasks;
        private long lastHeartbeat;
        private String infoJson;  // additional worker info as JSON

        public WorkerEntry() {}

        public WorkerEntry(String workerId, String gatewayId, String host, int port, String model,
                           String group, double currentLoad, int activeTasks) {
            this.workerId = workerId;
            this.gatewayId = gatewayId;
            this.host = host;
            this.port = port;
            this.model = model;
            this.group = group;
            this.currentLoad = currentLoad;
            this.activeTasks = activeTasks;
            this.lastHeartbeat = System.currentTimeMillis();
        }

        public String getWorkerId() { return workerId; }
        public void setWorkerId(String workerId) { this.workerId = workerId; }
        public String getGatewayId() { return gatewayId; }
        public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getGroup() { return group; }
        public void setGroup(String group) { this.group = group; }
        public double getCurrentLoad() { return currentLoad; }
        public void setCurrentLoad(double currentLoad) { this.currentLoad = currentLoad; }
        public int getActiveTasks() { return activeTasks; }
        public void setActiveTasks(int activeTasks) { this.activeTasks = activeTasks; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
        public String getInfoJson() { return infoJson; }
        public void setInfoJson(String infoJson) { this.infoJson = infoJson; }
    }
}
