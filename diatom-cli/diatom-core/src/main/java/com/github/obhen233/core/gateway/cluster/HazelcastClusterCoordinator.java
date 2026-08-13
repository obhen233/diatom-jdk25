package com.github.obhen233.core.gateway.cluster;

import com.github.obhen233.spi.ClusterCoordinator;
import com.github.obhen233.spi.ClusterEventListener;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.MapEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link ClusterCoordinator} implementation using embedded Hazelcast.
 *
 * <p>Each Gateway runs an embedded Hazelcast instance. They auto-discover each other
 * (via multicast, TCP/IP, or Kubernetes) and form a P2P cluster. Worker registry data
 * is stored in a distributed {@link IMap} with TTL-based heartbeat expiration.</p>
 *
 * <p>Configuration (via {@code cluster.hazelcast.*} properties):</p>
 * <ul>
 *   <li>{@code cluster.hazelcast.port} — Hazelcast port (default: 5701)</li>
 *   <li>{@code cluster.hazelcast.multicast.enabled} — enable multicast discovery (default: true)</li>
 *   <li>{@code cluster.hazelcast.tcpip.enabled} — enable TCP/IP discovery (default: false)</li>
 *   <li>{@code cluster.hazelcast.tcpip.members} — comma-separated host:port list</li>
 *   <li>{@code cluster.hazelcast.instance.name} — Hazelcast instance name</li>
 * </ul>
 */
public class HazelcastClusterCoordinator implements ClusterCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(HazelcastClusterCoordinator.class);

    private static final String DEFAULT_MAP_NAME = "diatom-worker-registry";
    private static final String KV_MAP_PREFIX = "diatom-";

    private HazelcastInstance hzInstance;
    private IMap<String, WorkerEntry> registry;
    private final ConcurrentHashMap<String, IMap<String, String>> kvStores = new ConcurrentHashMap<>();
    private volatile boolean active = false;

    @Override
    public String getName() {
        return "hazelcast";
    }

    @Override
    public void init(Map<String, String> config) {
        if (active) return;

        Config cfg = new Config();

        // Instance name
        String instanceName = config.getOrDefault("cluster.hazelcast.instance.name",
                "diatom-gateway-" + UUID.randomUUID().toString().substring(0, 8));
        cfg.setInstanceName(instanceName);

        // Network config
        NetworkConfig network = cfg.getNetworkConfig();
        int port = parseInt(config.get("cluster.hazelcast.port"), 5701);
        network.setPort(port);
        network.setPortAutoIncrement(true);

        // Discovery: multicast (default) or TCP/IP
        JoinConfig join = network.getJoin();
        boolean multicastEnabled = Boolean.parseBoolean(
                config.getOrDefault("cluster.hazelcast.multicast.enabled", "true"));
        join.getMulticastConfig().setEnabled(multicastEnabled);

        boolean tcpipEnabled = Boolean.parseBoolean(
                config.getOrDefault("cluster.hazelcast.tcpip.enabled", "false"));
        join.getTcpIpConfig().setEnabled(tcpipEnabled);
        String tcpipMembers = config.get("cluster.hazelcast.tcpip.members");
        if (tcpipEnabled && tcpipMembers != null && !tcpipMembers.isEmpty()) {
            for (String member : tcpipMembers.split(",")) {
                join.getTcpIpConfig().addMember(member.trim());
            }
        }

        // Disable Kubernetes discovery by default (avoids spurious lookups)
        join.getKubernetesConfig().setEnabled(false);

        // Set Hazelcast logging to SLF4J
        System.setProperty("hazelcast.logging.type", "slf4j");

        try {
            hzInstance = Hazelcast.newHazelcastInstance(cfg);
            registry = hzInstance.getMap(DEFAULT_MAP_NAME);
            active = true;
            logger.info("HazelcastClusterCoordinator started: instance={}, port={}, members={}",
                    instanceName, port, hzInstance.getCluster().getMembers().size());
        } catch (Exception e) {
            logger.error("Failed to start HazelcastClusterCoordinator", e);
            throw new RuntimeException("Hazelcast initialization failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void shutdown() {
        if (hzInstance != null) {
            try {
                hzInstance.shutdown();
                logger.info("HazelcastClusterCoordinator shut down");
            } catch (Exception e) {
                logger.warn("Error shutting down Hazelcast: {}", e.getMessage());
            }
        }
        active = false;
    }

    // ========== Worker Registry ==========

    @Override
    public void putWorker(String key, WorkerEntry entry, int ttlSeconds) {
        checkActive();
        registry.set(key, entry, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public WorkerEntry getWorker(String key) {
        checkActive();
        return registry.get(key);
    }

    @Override
    public Collection<WorkerEntry> getAllWorkers() {
        checkActive();
        return registry.values();
    }

    @Override
    public void removeWorker(String key) {
        checkActive();
        registry.delete(key);
    }

    // ========== Distributed Lock ==========

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        checkActive();
        return hzInstance.getCPSubsystem().getLock(key).tryLock(timeout, unit);
    }

    @Override
    public void unlock(String key) {
        checkActive();
        hzInstance.getCPSubsystem().getLock(key).unlock();
    }

    // ========== Event Listener ==========

    @Override
    public void addListener(ClusterEventListener listener) {
        checkActive();
        registry.addEntryListener(new EntryListenerAdapter(listener), true);
    }

    @Override
    public void removeListener(ClusterEventListener listener) {
        // Hazelcast doesn't support removing by listener instance directly
        // in a simple way; this is a best-effort operation
        logger.debug("removeListener: Hazelcast entry listeners managed per-map");
    }

    @Override
    public boolean isActive() {
        return active && hzInstance != null && hzInstance.getLifecycleService().isRunning();
    }

    // ========== Generic KV Storage ==========

    @Override
    public void store(String namespace, String key, String value, int ttlSeconds) {
        checkActive();
        IMap<String, String> map = kvStores.computeIfAbsent(namespace,
                n -> hzInstance.getMap(KV_MAP_PREFIX + n));
        if (ttlSeconds > 0) {
            map.set(key, value, ttlSeconds, TimeUnit.SECONDS);
        } else {
            map.set(key, value);
        }
    }

    @Override
    public String retrieve(String namespace, String key) {
        checkActive();
        IMap<String, String> map = kvStores.computeIfAbsent(namespace,
                n -> hzInstance.getMap(KV_MAP_PREFIX + n));
        return map.get(key);
    }

    @Override
    public void remove(String namespace, String key) {
        checkActive();
        IMap<String, String> map = kvStores.computeIfAbsent(namespace,
                n -> hzInstance.getMap(KV_MAP_PREFIX + n));
        map.delete(key);
    }

    @Override
    public Collection<String> keys(String namespace, String prefix) {
        checkActive();
        IMap<String, String> map = kvStores.computeIfAbsent(namespace,
                n -> hzInstance.getMap(KV_MAP_PREFIX + n));
        if (prefix == null || prefix.isEmpty()) {
            return map.keySet();
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String k : map.keySet()) {
            if (k.startsWith(prefix)) {
                result.add(k);
            }
        }
        return result;
    }

    // ========== Internal ==========

    private void checkActive() {
        if (!isActive()) {
            throw new IllegalStateException("HazelcastClusterCoordinator is not active. Call init() first.");
        }
    }

    private static int parseInt(String s, int defaultValue) {
        if (s == null) return defaultValue;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Adapts Hazelcast {@link EntryListener} to {@link ClusterEventListener}.
     */
    private static class EntryListenerAdapter implements EntryListener<String, WorkerEntry> {
        private final ClusterEventListener listener;

        EntryListenerAdapter(ClusterEventListener listener) {
            this.listener = listener;
        }

        @Override
        public void entryAdded(EntryEvent<String, WorkerEntry> event) {
            listener.onEvent(new ClusterEventListener.ClusterEvent(
                    ClusterEventListener.EventType.WORKER_ADDED,
                    event.getKey(), event.getValue()));
        }

        @Override
        public void entryRemoved(EntryEvent<String, WorkerEntry> event) {
            listener.onEvent(new ClusterEventListener.ClusterEvent(
                    ClusterEventListener.EventType.WORKER_REMOVED,
                    event.getKey(), event.getValue()));
        }

        @Override
        public void entryUpdated(EntryEvent<String, WorkerEntry> event) {
            listener.onEvent(new ClusterEventListener.ClusterEvent(
                    ClusterEventListener.EventType.WORKER_UPDATED,
                    event.getKey(), event.getValue()));
        }

        @Override
        public void entryEvicted(EntryEvent<String, WorkerEntry> event) {
            // TTL expiration triggers evict — treat as removal
            listener.onEvent(new ClusterEventListener.ClusterEvent(
                    ClusterEventListener.EventType.WORKER_REMOVED,
                    event.getKey(), event.getValue()));
        }

        @Override public void entryExpired(EntryEvent<String, WorkerEntry> event) {
            // TTL expiration — treat as removal
            listener.onEvent(new ClusterEventListener.ClusterEvent(
                    ClusterEventListener.EventType.WORKER_REMOVED,
                    event.getKey(), event.getValue()));
        }

        @Override public void mapEvicted(MapEvent event) {}
        @Override public void mapCleared(MapEvent event) {}
    }
}
