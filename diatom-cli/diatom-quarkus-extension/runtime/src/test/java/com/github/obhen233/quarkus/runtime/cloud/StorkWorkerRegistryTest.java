package com.github.obhen233.quarkus.runtime.cloud;

import com.github.obhen233.core.gateway.registry.RegistryEvent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link StorkWorkerRegistry} 测试（纯 JUnit：in-memory 假 {@link CloudRegistryService}）。
 *
 * <p>验证注册中心直读 WorkerRegistry 的发现/并集/宽限期注销/diff 事件逻辑（镜像 starter
 * {@code DiscoveryClientWorkerRegistry} 的 Stork 版）。</p>
 */
public class StorkWorkerRegistryTest {

    /** 可编程假注册中心：discover 返回可替换的实例列表。 */
    private static final class FakeCloud implements CloudRegistryService {
        final Map<String, List<DiscoveredInstance>> services = new ConcurrentHashMap<>();
        final List<String> registered = new CopyOnWriteArrayList<>();

        FakeCloud instances(String service, DiscoveredInstance... instances) {
            services.put(service, List.of(instances));
            return this;
        }

        @Override
        public void init() {
        }

        @Override
        public void registerInstance(String serviceName, String instanceId, String host, int port) {
            registered.add(serviceName + ":" + instanceId);
        }

        @Override
        public void deregisterInstance(String serviceName, String instanceId, String host, int port) {
            registered.remove(serviceName + ":" + instanceId);
        }

        @Override
        public List<DiscoveredInstance> discover(String serviceName) {
            return services.getOrDefault(serviceName, List.of());
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static CloudDiscoveryConfig config(String serviceName) {
        // refreshIntervalMs > 0：关闭"每次读取都刷新"，避免 availableWorkers() 触发额外 refresh
        // 干扰宽限期计数；60s 远大于测试时长，后台调度在测试内不会触发。
        return new CloudDiscoveryConfig("consul", "localhost", 8500, serviceName,
                "diatom-gateway", "", 60_000, "cloud", "gpt-4");
    }

    private static DiscoveredInstance inst(String host, int port, Map<String, String> metadata) {
        return new DiscoveredInstance(host, port, false, metadata);
    }

    @Test
    public void discoverRegistersWorkers() {
        FakeCloud cloud = new FakeCloud().instances("diatom",
                inst("10.0.0.1", 8081, Map.of()),
                inst("10.0.0.2", 8082, Map.of()));
        StorkWorkerRegistry registry = new StorkWorkerRegistry(cloud, config("diatom"));
        try {
            List<WorkerInfo> workers = registry.availableWorkers();
            assertEquals(2, workers.size());

            WorkerInfo w = registry.getWorker("diatom:10.0.0.1:8081");
            assertNotNull(w);
            assertEquals("10.0.0.1", w.getHost());
            assertEquals(8081, w.getPort());
            assertEquals("gpt-4", w.getModel());      // 默认 model 来自 config
            assertEquals("cloud", w.getGroup());      // 默认分组来自 config
            assertEquals("worker", w.getTier());
            assertEquals(WorkerInfo.WorkerStatus.ONLINE, w.getStatus());
            assertEquals("cloud-discovery", w.getGatewayProfile());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    public void discoveredWorkerSkipsGatewayRole() {
        FakeCloud cloud = new FakeCloud().instances("diatom",
                inst("10.0.0.1", 8081, Map.of()),
                inst("10.0.0.2", 8082, Map.of("diatom.role", "gateway")));
        StorkWorkerRegistry registry = new StorkWorkerRegistry(cloud, config("diatom"));
        try {
            assertEquals(1, registry.availableWorkers().size());
            assertNull(registry.getWorker("diatom:10.0.0.2:8082"));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    public void metadataWorkerIdAndFieldsApplied() {
        FakeCloud cloud = new FakeCloud().instances("diatom",
                inst("10.0.0.9", 9090, Map.of(
                        "diatom.worker-id", "w-custom",
                        "diatom.model", "claude-3",
                        "diatom.group", "gpu",
                        "diatom.tier", "gateway-proxy",
                        "diatom.max-concurrency", "8")));
        StorkWorkerRegistry registry = new StorkWorkerRegistry(cloud, config("diatom"));
        try {
            WorkerInfo w = registry.getWorker("w-custom");
            assertNotNull(w);
            assertEquals("claude-3", w.getModel());
            assertEquals("gpu", w.getGroup());
            assertEquals("gateway-proxy", w.getTier());
            assertEquals(8, w.getMaxConcurrency());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    public void unionWithOverrideWorkers() {
        FakeCloud cloud = new FakeCloud().instances("diatom", inst("10.0.0.1", 8081, Map.of()));
        StorkWorkerRegistry registry = new StorkWorkerRegistry(cloud, config("diatom"));
        try {
            WorkerInfo override = new WorkerInfo("direct-1", "127.0.0.1", 8085);
            registry.register(override);

            List<WorkerInfo> workers = registry.availableWorkers();
            assertEquals(2, workers.size());
            assertEquals("direct-1", registry.getWorker("direct-1").getWorkerId());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    public void overrideHeartbeatAndDeregister() {
        FakeCloud cloud = new FakeCloud().instances("diatom", inst("10.0.0.1", 8081, Map.of()));
        StorkWorkerRegistry registry = new StorkWorkerRegistry(cloud, config("diatom"));
        try {
            WorkerInfo override = new WorkerInfo("direct-1", "127.0.0.1", 8085);
            override.setStatus(WorkerInfo.WorkerStatus.OFFLINE);
            registry.register(override);

            List<String> events = new CopyOnWriteArrayList<>();
            registry.subscribe(ev -> events.add(ev.getType() + ":" + ev.getWorkerId()));

            registry.heartbeat("direct-1", new WorkerMetrics());
            assertEquals(WorkerInfo.WorkerStatus.ONLINE, override.getStatus());
            assertTrue("OFFLINE→ONLINE 应发 STATUS_CHANGED",
                    events.contains("STATUS_CHANGED:direct-1"));

            registry.deregister("direct-1");
            assertNull(registry.getWorker("direct-1"));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    public void missingTwiceDeregistersWithGracePeriod() {
        FakeCloud cloud = new FakeCloud().instances("diatom", inst("10.0.0.1", 8081, Map.of()));
        StorkWorkerRegistry registry = new StorkWorkerRegistry(cloud, config("diatom"));
        List<String> events = new CopyOnWriteArrayList<>();
        registry.subscribe(ev -> events.add(ev.getType() + ":" + ev.getWorkerId()));

        // 实例消失：第 1 次刷新 → 宽限期保留
        cloud.services.put("diatom", List.of());
        registry.refresh();
        assertEquals(1, registry.availableWorkers().size());

        // 第 2 次刷新 → 注销 + DEREGISTERED 事件
        registry.refresh();
        assertEquals(0, registry.availableWorkers().size());
        assertTrue(events.contains("DEREGISTERED:diatom:10.0.0.1:8081"));
        registry.shutdown();
    }

    @Test
    public void registerIgnoresCloudProfileWorker() {
        FakeCloud cloud = new FakeCloud().instances("diatom", inst("10.0.0.1", 8081, Map.of()));
        StorkWorkerRegistry registry = new StorkWorkerRegistry(cloud, config("diatom"));
        try {
            WorkerInfo cloudWorker = new WorkerInfo("c-1", "10.0.0.1", 8081);
            cloudWorker.setGatewayProfile("cloud-discovery");
            registry.register(cloudWorker);
            // cloud-profile worker 由直读管理，不应进 overrideWorkers → getWorker 仍为 null
            assertNull(registry.getWorker("c-1"));
            // 直读发现的 worker 正常可见
            assertNotNull(registry.getWorker("diatom:10.0.0.1:8081"));
        } finally {
            registry.shutdown();
        }
    }
}
