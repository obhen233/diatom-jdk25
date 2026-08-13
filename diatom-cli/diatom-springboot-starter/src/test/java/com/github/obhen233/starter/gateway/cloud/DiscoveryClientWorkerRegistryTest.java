package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.RegistryEvent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DiscoveryClientWorkerRegistry} 直读注册表测试。
 *
 * <p>手写 {@link FakeDiscoveryClient} / {@link FakeServiceInstance}（无需 Mockito），
 * 覆盖 plan 第 9 步的 6 类场景：
 * serviceFilter + gateway 排除、metadata 映射与默认值、override 与发现实例并存、
 * diff 事件（REGISTERED / 连续 2 次缺失才 DEREGISTERED）、刷新异常保留旧快照、
 * getWorker 解析与 (host,port) 去重。
 */
public class DiscoveryClientWorkerRegistryTest {

    private FakeDiscoveryClient discovery;
    private DiatomCloudDiscoveryProperties props;

    @Before
    public void setUp() {
        discovery = new FakeDiscoveryClient();
        props = new DiatomCloudDiscoveryProperties();
        props.setServiceFilter("diatom-worker");
        props.setGatewayServiceFilter("diatom-gateway");
        // 极大刷新间隔：由测试显式调用 refresh()，避免调度线程干扰
        props.setRefreshIntervalMs(1_000_000L);
        props.setWorkerGroup("cloud");
        props.setDefaultModel("gpt-4");
    }

    private DiscoveryClientWorkerRegistry newRegistry() {
        return new DiscoveryClientWorkerRegistry(discovery, props);
    }

    /** 1. availableWorkers() 按 serviceFilter 过滤；gateway 服务与 diatom.role=gateway 被排除 */
    @Test
    public void availableWorkersFiltersByServiceAndExcludesGateways() {
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.1", 8081, false, workerMetadata("w1", "gpt-4o")));
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.2", 8082, true, workerMetadata("w2", "deepseek")));
        // 不匹配 serviceFilter 的服务
        discovery.addInstance("other-service",
                new FakeServiceInstance("other-service", "10.0.0.9", 9000, false, Collections.<String, String>emptyMap()));
        // gateway 服务（匹配 gatewayServiceFilter）
        discovery.addInstance("diatom-gateway",
                new FakeServiceInstance("diatom-gateway", "10.0.0.100", 9090, false, Collections.<String, String>emptyMap()));
        // 匹配 serviceFilter 但 diatom.role=gateway 的实例
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.3", 8083, false, gatewayRoleMetadata()));

        DiscoveryClientWorkerRegistry registry = newRegistry();
        List<WorkerInfo> workers = registry.availableWorkers();

        Set<String> ids = new HashSet<>();
        for (WorkerInfo w : workers) {
            ids.add(w.getWorkerId());
        }
        assertEquals(2, workers.size());
        assertTrue(ids.contains("w1"));
        assertTrue(ids.contains("w2"));
        assertFalse(ids.contains("diatom-worker:10.0.0.3:8083"));

        WorkerInfo w2 = registry.getWorker("w2");
        assertNotNull(w2);
        assertTrue(w2.isUseSsl());
    }

    /** 2. metadata 映射（标准键 + instanceTag 映射 + 默认值） */
    @Test
    public void metadataMappingAndDefaults() {
        Map<String, String> full = new LinkedHashMap<>();
        full.put("diatom.model", "deepseek-v3");
        full.put("diatom.group", "gpu");
        full.put("diatom.tier", "gpu");
        full.put("diatom.max-concurrency", "8");
        full.put("diatom.current-load", "0.5");
        full.put("diatom.active-tasks", "3");
        full.put("diatom.workspace", "/data/proj");
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.1", 8081, false, full));

        // instanceTag 映射：model ← ai-model
        props.getInstanceTag().put("model", "ai-model");
        Map<String, String> tagged = new LinkedHashMap<>();
        tagged.put("ai-model", "claude-sonnet");
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.2", 8082, false, tagged));

        // 空 metadata → 全部默认值
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.3", 8083, false, Collections.<String, String>emptyMap()));

        DiscoveryClientWorkerRegistry registry = newRegistry();

        WorkerInfo w1 = registry.getWorker("diatom-worker:10.0.0.1:8081");
        assertNotNull(w1);
        assertEquals("deepseek-v3", w1.getModel());
        assertEquals("gpu", w1.getGroup());
        assertEquals("gpu", w1.getTier());
        assertEquals(8, w1.getMaxConcurrency());
        assertEquals(0.5, w1.getMetrics().getCurrentLoad(), 0.0001);
        assertEquals(3, w1.getMetrics().getActiveTasks());
        assertEquals("/data/proj", w1.getWorkspace());
        assertEquals("cloud-discovery", w1.getGatewayProfile());
        assertEquals(WorkerInfo.WorkerStatus.ONLINE, w1.getStatus());

        WorkerInfo w2 = registry.getWorker("diatom-worker:10.0.0.2:8082");
        assertNotNull(w2);
        assertEquals("claude-sonnet", w2.getModel());

        WorkerInfo w3 = registry.getWorker("diatom-worker:10.0.0.3:8083");
        assertNotNull(w3);
        assertEquals("gpt-4", w3.getModel());
        assertEquals("cloud", w3.getGroup());
        assertEquals("worker", w3.getTier());
        assertEquals(5, w3.getMaxConcurrency());
        assertEquals(0.0, w3.getMetrics().getCurrentLoad(), 0.0001);
        assertEquals(0, w3.getMetrics().getActiveTasks());
    }

    /** 3. 直连 override worker 与发现 worker 并存；cloud-discovery 的 register 为空操作 */
    @Test
    public void overrideWorkersCoexistAndCloudProfileRegisterIsNoop() {
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.1", 8081, false, workerMetadata("w1", "gpt-4o")));

        DiscoveryClientWorkerRegistry registry = newRegistry();

        // 直连注册（非 cloud）worker
        WorkerInfo direct = new WorkerInfo("direct-1", "10.0.0.5", 8085);
        direct.setModel("llama3");
        registry.register(direct);
        assertNotNull(registry.getWorker("direct-1"));
        assertTrue(registry.availableWorkers().stream().anyMatch(w -> "direct-1".equals(w.getWorkerId())));

        // gatewayProfile=cloud-discovery 的 register 为空操作（由直读处理）
        WorkerInfo cloud = new WorkerInfo("cloud-dup", "10.0.0.1", 8081);
        cloud.setGatewayProfile("cloud-discovery");
        registry.register(cloud);
        assertNull(registry.getWorker("cloud-dup"));

        // heartbeat 更新直连 worker 的 metrics
        WorkerMetrics metrics = new WorkerMetrics();
        metrics.setActiveTasks(2);
        metrics.setCurrentLoad(0.25);
        registry.heartbeat("direct-1", metrics);
        WorkerInfo after = registry.getWorker("direct-1");
        assertEquals(2, after.getMetrics().getActiveTasks());
        assertEquals(0.25, after.getMetrics().getCurrentLoad(), 0.0001);

        // deregister 移除直连 worker
        registry.deregister("direct-1");
        assertNull(registry.getWorker("direct-1"));
    }

    /** 4. diff 事件：新增发 REGISTERED；连续 2 次缺失才发 DEREGISTERED（容忍瞬时空） */
    @Test
    public void diffEventsRegisterAndDeregisterWithGrace() {
        List<RegistryEvent> events = new CopyOnWriteArrayList<>();
        DiscoveryClientWorkerRegistry registry = newRegistry();
        registry.subscribe(events::add);

        // 初始 refresh（构造器内）为空，无事件
        assertTrue(events.isEmpty());

        // 新增 worker → refresh → REGISTERED
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.1", 8081, false, workerMetadata("w1", "gpt-4o")));
        registry.refresh();
        assertEquals(1, events.size());
        assertEquals(RegistryEvent.EventType.REGISTERED, events.get(0).getType());
        assertEquals("w1", events.get(0).getWorkerId());

        // 移除 worker → 第 1 次缺失仍宽限，不发事件、不移除
        events.clear();
        discovery.removeInstances("diatom-worker");
        registry.refresh();
        assertTrue(events.isEmpty());
        assertNotNull(registry.getWorker("w1"));

        // 第 2 次缺失 → DEREGISTERED
        registry.refresh();
        assertEquals(1, events.size());
        assertEquals(RegistryEvent.EventType.DEREGISTERED, events.get(0).getType());
        assertEquals("w1", events.get(0).getWorkerId());
        assertNull(registry.getWorker("w1"));
    }

    /** 5. DiscoveryClient 抛异常时保留上一快照，不批量注销 */
    @Test
    public void refreshFailureKeepsPreviousSnapshot() {
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.1", 8081, false, workerMetadata("w1", "gpt-4o")));
        DiscoveryClientWorkerRegistry registry = newRegistry();
        assertNotNull(registry.getWorker("w1"));

        discovery.failNext(new RuntimeException("registry down"));
        registry.refresh();
        assertNotNull(registry.getWorker("w1"));

        // 恢复后仍正常
        registry.refresh();
        assertNotNull(registry.getWorker("w1"));
    }

    /** 6. getWorker 解析发现 worker；(host,port) 相同的直连 override 被发现实例取代 */
    @Test
    public void getWorkerAndOverrideDedupByHostPort() {
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.1", 8081, false, workerMetadata("w1", "gpt-4o")));
        DiscoveryClientWorkerRegistry registry = newRegistry();

        // 直连 override 使用相同 (host,port)：refresh 前两者并存
        WorkerInfo direct = new WorkerInfo("direct-1", "10.0.0.1", 8081);
        registry.register(direct);
        assertNotNull(registry.getWorker("direct-1"));
        assertNotNull(registry.getWorker("w1"));

        // refresh 后 override 被发现实例取代
        registry.refresh();
        assertNull(registry.getWorker("direct-1"));
        assertNotNull(registry.getWorker("w1"));
    }

    /** gatewayNodes() 返回匹配 gatewayServiceFilter 的实例 */
    @Test
    public void gatewayNodesListsMatchingInstances() {
        discovery.addInstance("diatom-gateway",
                new FakeServiceInstance("diatom-gateway", "10.0.0.100", 9090, false, gatewayMetadata("gw-1", "1.2.3")));
        discovery.addInstance("diatom-gateway",
                new FakeServiceInstance("diatom-gateway", "10.0.0.101", 9091, false, gatewayMetadata("gw-2", null)));
        discovery.addInstance("diatom-worker",
                new FakeServiceInstance("diatom-worker", "10.0.0.1", 8081, false, workerMetadata("w1", "gpt-4o")));

        DiscoveryClientWorkerRegistry registry = newRegistry();
        List<GatewayNode> nodes = registry.gatewayNodes();

        assertEquals(2, nodes.size());
        boolean hasGw1 = nodes.stream().anyMatch(n -> "gw-1".equals(n.getId()));
        boolean hasGw2 = nodes.stream().anyMatch(n -> "gw-2".equals(n.getId()));
        assertTrue(hasGw1);
        assertTrue(hasGw2);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Map<String, String> workerMetadata(String workerId, String model) {
        Map<String, String> md = new LinkedHashMap<>();
        md.put("diatom.worker-id", workerId);
        md.put("diatom.model", model);
        md.put("diatom.role", "worker");
        return md;
    }

    private static Map<String, String> gatewayRoleMetadata() {
        Map<String, String> md = new LinkedHashMap<>();
        md.put("diatom.role", "gateway");
        return md;
    }

    private static Map<String, String> gatewayMetadata(String id, String version) {
        Map<String, String> md = new LinkedHashMap<>();
        md.put("diatom.worker-id", id);
        if (version != null) {
            md.put("diatom.version", version);
        }
        return md;
    }

    /** 最小 DiscoveryClient fake（spring-cloud-commons 3.1.x） */
    private static class FakeDiscoveryClient implements DiscoveryClient {
        private final Map<String, List<ServiceInstance>> services = new LinkedHashMap<>();
        private volatile RuntimeException failure;

        void addInstance(String serviceId, ServiceInstance instance) {
            services.computeIfAbsent(serviceId, k -> new ArrayList<>()).add(instance);
        }

        void removeInstances(String serviceId) {
            services.remove(serviceId);
        }

        /** 下一次 getServices()/getInstances() 抛异常（一次性） */
        void failNext(RuntimeException ex) {
            this.failure = ex;
        }

        @Override
        public String description() {
            return "fake-discovery";
        }

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            maybeThrow();
            List<ServiceInstance> list = services.get(serviceId);
            return list == null ? Collections.<ServiceInstance>emptyList() : new ArrayList<>(list);
        }

        @Override
        public List<String> getServices() {
            maybeThrow();
            return new ArrayList<>(services.keySet());
        }

        private void maybeThrow() {
            RuntimeException ex = failure;
            if (ex != null) {
                failure = null;
                throw ex;
            }
        }
    }

    /** 最小 ServiceInstance fake（spring-cloud-commons 3.1.x） */
    private static class FakeServiceInstance implements ServiceInstance {
        private final String serviceId;
        private final String host;
        private final int port;
        private final boolean secure;
        private final Map<String, String> metadata;

        FakeServiceInstance(String serviceId, String host, int port, boolean secure, Map<String, String> metadata) {
            this.serviceId = serviceId;
            this.host = host;
            this.port = port;
            this.secure = secure;
            this.metadata = metadata != null ? metadata : Collections.<String, String>emptyMap();
        }

        @Override
        public String getServiceId() { return serviceId; }

        @Override
        public String getHost() { return host; }

        @Override
        public int getPort() { return port; }

        @Override
        public boolean isSecure() { return secure; }

        @Override
        public URI getUri() {
            return URI.create((secure ? "https" : "http") + "://" + host + ":" + port);
        }

        @Override
        public Map<String, String> getMetadata() { return metadata; }

        @Override
        public String getInstanceId() { return serviceId + ":" + host + ":" + port; }

        @Override
        public String getScheme() { return secure ? "https" : "http"; }
    }
}
