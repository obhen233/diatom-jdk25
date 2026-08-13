package com.github.obhen233.starter.gateway;

import com.github.obhen233.core.gateway.registry.ClusteredWorkerRegistry;
import com.github.obhen233.core.gateway.registry.RegistryEvent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.spi.ClusterCoordinator;
import com.github.obhen233.spi.ClusterEventListener;
import com.github.obhen233.starter.gateway.cluster.NoopClusterCoordinator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Gateway HA 装配契约测试：验证 starter 的 {@code ClusteredWorkerRegistry} 包装依赖的
 * 集群行为（register 同步到集群、远程 worker 合并、localWorkers 隔离、Noop 降级）。
 * 手写 {@link FakeClusterCoordinator} / {@link StubLocalRegistry}，无需 Spring 上下文。
 */
public class GatewayHaWiringTest {

    /** HA 开启时 register 同步到集群，且携带本 gateway 的 gatewayId */
    @Test
    public void registerSyncsToClusterWithGatewayId() {
        FakeClusterCoordinator cluster = new FakeClusterCoordinator();
        StubLocalRegistry local = new StubLocalRegistry();
        ClusteredWorkerRegistry registry = new ClusteredWorkerRegistry(local, cluster, "gw-1", true);

        registry.register(worker("w-1", "10.0.0.1", 8081));

        ClusterCoordinator.WorkerEntry entry = cluster.getWorker("w-1");
        assertNotNull(entry);
        assertEquals("gw-1", entry.getGatewayId());
        assertEquals("10.0.0.1", entry.getHost());
        assertEquals(8081, entry.getPort());
        assertEquals("gpt-4o", entry.getModel());
        // 本地侧同步注册
        assertNotNull(local.getWorker("w-1"));
    }

    /** availableWorkers 合并本地 + 远程（其他 gateway）；localWorkers 仅本 gateway 直连 */
    @Test
    public void availableWorkersMergesLocalAndRemote() {
        FakeClusterCoordinator cluster = new FakeClusterCoordinator();
        StubLocalRegistry local = new StubLocalRegistry();
        ClusteredWorkerRegistry registry = new ClusteredWorkerRegistry(local, cluster, "gw-1", true);

        local.register(worker("local-1", "10.0.0.1", 8081));
        cluster.putWorker("remote-1", new ClusterCoordinator.WorkerEntry(
                "remote-1", "gw-2", "10.0.0.2", 8082, "llama3", "cloud", 0.3, 2), 120);

        List<WorkerInfo> merged = registry.availableWorkers();
        assertEquals(2, merged.size());
        assertTrue(hasWorker(merged, "local-1"));
        assertTrue(hasWorker(merged, "remote-1"));

        List<WorkerInfo> localOnly = registry.localWorkers();
        assertEquals(1, localOnly.size());
        assertTrue(hasWorker(localOnly, "local-1"));
        assertFalse(hasWorker(localOnly, "remote-1"));
    }

    /** getWorker 本地未命中时回退集群；deregister 同步移除集群条目 */
    @Test
    public void getWorkerFallsBackToClusterAndDeregisterRemovesClusterEntry() {
        FakeClusterCoordinator cluster = new FakeClusterCoordinator();
        StubLocalRegistry local = new StubLocalRegistry();
        ClusteredWorkerRegistry registry = new ClusteredWorkerRegistry(local, cluster, "gw-1", true);

        local.register(worker("local-1", "10.0.0.1", 8081));
        assertNotNull(registry.getWorker("local-1"));

        // 仅存在于集群的远程 worker
        cluster.putWorker("remote-1", new ClusterCoordinator.WorkerEntry(
                "remote-1", "gw-2", "10.0.0.2", 8082, "llama3", "cloud", 0.0, 0), 120);
        assertNotNull(registry.getWorker("remote-1"));

        // deregister 本地 worker → 集群条目同步移除
        registry.deregister("local-1");
        assertNull(cluster.getWorker("local-1"));
        assertNull(local.getWorker("local-1"));
    }

    /** haEnabled=false 时纯本地行为：不合并远程、不写集群 */
    @Test
    public void haDisabledRegistryDelegatesLocalOnly() {
        FakeClusterCoordinator cluster = new FakeClusterCoordinator();
        StubLocalRegistry local = new StubLocalRegistry();
        ClusteredWorkerRegistry registry = new ClusteredWorkerRegistry(local, cluster, "gw-1", false);

        local.register(worker("local-1", "10.0.0.1", 8081));
        cluster.putWorker("remote-1", new ClusterCoordinator.WorkerEntry(
                "remote-1", "gw-2", "10.0.0.2", 8082, "llama3", "cloud", 0.0, 0), 120);

        List<WorkerInfo> workers = registry.availableWorkers();
        assertEquals(1, workers.size());
        assertTrue(hasWorker(workers, "local-1"));
        assertFalse(hasWorker(workers, "remote-1"));

        registry.register(worker("local-2", "10.0.0.3", 8083));
        assertNull(cluster.getWorker("local-2"));
    }

    /** Hazelcast 初始化失败时 Noop 协调器降级：可用 worker 仅本地 */
    @Test
    public void noopCoordinatorIsInactiveAndDegrades() {
        NoopClusterCoordinator noop = new NoopClusterCoordinator();
        assertFalse(noop.isActive());
        assertTrue(noop.getAllWorkers().isEmpty());
        assertEquals("noop", noop.getName());

        StubLocalRegistry local = new StubLocalRegistry();
        ClusteredWorkerRegistry registry = new ClusteredWorkerRegistry(local, noop, "gw-1", true);
        local.register(worker("local-1", "10.0.0.1", 8081));

        List<WorkerInfo> workers = registry.availableWorkers();
        assertEquals(1, workers.size());
        assertTrue(hasWorker(workers, "local-1"));
    }

    // ============ helpers ============

    private static WorkerInfo worker(String id, String host, int port) {
        WorkerInfo w = new WorkerInfo(id, host, port);
        w.setModel("gpt-4o");
        w.setGroup("cloud");
        return w;
    }

    private static boolean hasWorker(List<WorkerInfo> workers, String id) {
        for (WorkerInfo w : workers) {
            if (id.equals(w.getWorkerId())) return true;
        }
        return false;
    }

    /** 内存 ClusterCoordinator fake：记录 worker 条目，isActive=true */
    private static class FakeClusterCoordinator implements ClusterCoordinator {
        private final Map<String, WorkerEntry> workers = new ConcurrentHashMap<>();

        @Override public String getName() { return "fake"; }
        @Override public void init(Map<String, String> config) { }
        @Override public void shutdown() { }
        @Override public void putWorker(String key, WorkerEntry entry, int ttlSeconds) {
            workers.put(key, entry);
        }
        @Override public WorkerEntry getWorker(String key) { return workers.get(key); }
        @Override public Collection<WorkerEntry> getAllWorkers() { return new ArrayList<>(workers.values()); }
        @Override public void removeWorker(String key) { workers.remove(key); }
        @Override public boolean tryLock(String key, long timeout, TimeUnit unit) { return true; }
        @Override public void unlock(String key) { }
        @Override public void addListener(ClusterEventListener listener) { }
        @Override public void removeListener(ClusterEventListener listener) { }
        @Override public boolean isActive() { return true; }
    }

    /** 内存 WorkerRegistry fake：模拟 FileSystemWorkerRegistry 的本地行为 */
    private static class StubLocalRegistry implements WorkerRegistry {
        private final Map<String, WorkerInfo> workers = new LinkedHashMap<>();

        @Override public void register(WorkerInfo worker) { workers.put(worker.getWorkerId(), worker); }
        @Override public void deregister(String workerId) { workers.remove(workerId); }
        @Override public void heartbeat(String workerId, WorkerMetrics metrics) {
            WorkerInfo w = workers.get(workerId);
            if (w != null) w.setMetrics(metrics);
        }
        @Override public List<WorkerInfo> availableWorkers() { return new ArrayList<>(workers.values()); }
        @Override public WorkerInfo getWorker(String workerId) { return workers.get(workerId); }
        @Override public void markShuttingDown(String workerId) {
            WorkerInfo w = workers.get(workerId);
            if (w != null) w.setStatus(WorkerInfo.WorkerStatus.SHUTTING_DOWN);
        }
        @Override public void subscribe(Consumer<RegistryEvent> listener) { }
        @Override public void shutdown() { }
    }
}
