package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.RegistryEvent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 注册中心直读的 {@link WorkerRegistry} 实现（Path B）。
 *
 * <p>与 {@link com.github.obhen233.core.gateway.registry.FileSystemWorkerRegistry}
 * 不同，本实现不维护本地 worker 快照，而是直接读取 Spring Cloud {@link DiscoveryClient}
 * （注册中心是唯一数据源）。多台 gateway 直读同一注册中心，因此永远看到同一份 worker 数据，
 * 无需 Hazelcast 等额外基础设施。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@link #availableWorkers()} / {@link #getWorker(String)} 在数据过期时触发
 *       {@link #refresh()}，返回 发现实例(cloud) + 直连 override 的并集（按 workerId 去重）。</li>
 *   <li>{@code register/heartbeat/deregister/markShuttingDown} 仅作用于 overrideWorkers
 *       （直连注册的非 cloud worker）；{@code gatewayProfile == "cloud-discovery"} 的 register 为空操作，
 *       避免与直读重复。</li>
 *   <li>后台调度线程按 {@code refreshIntervalMs} 周期刷新；diff 事件：新增发 REGISTERED、
 *       连续 2 次缺失才发 DEREGISTERED（容忍 Nacos 瞬时空）、状态变化发 STATUS_CHANGED。</li>
 *   <li>刷新异常时保留上一快照，不批量注销。</li>
 *   <li>{@link #gatewayNodes()} 查询匹配 {@code gatewayServiceFilter} 的实例，供拓扑 API 渲染多 gateway。</li>
 * </ul>
 */
public class DiscoveryClientWorkerRegistry implements WorkerRegistry {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryClientWorkerRegistry.class);

    private static final String CLOUD_DISCOVERY_PROFILE = "cloud-discovery";
    private static final String GATEWAY_ROLE = "gateway";

    private final DiscoveryClient discoveryClient;
    private final DiatomCloudDiscoveryProperties properties;

    /** 直连注册（非 cloud）的 worker */
    private final ConcurrentHashMap<String, WorkerInfo> overrideWorkers = new ConcurrentHashMap<>();

    /** 发现的 cloud worker 缓存快照（含缺失 1 次的宽限期 worker） */
    private final ConcurrentHashMap<String, WorkerInfo> snapshot = new ConcurrentHashMap<>();

    /** 连续缺失计数：workerId → 已连续缺失次数（>=2 才发 DEREGISTERED） */
    private final ConcurrentHashMap<String, Integer> missingCounts = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<Consumer<RegistryEvent>> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "discovery-worker-registry");
        t.setDaemon(true);
        return t;
    });

    private final Object refreshLock = new Object();
    private volatile long lastRefreshMs = 0L;
    private volatile boolean running = true;

    public DiscoveryClientWorkerRegistry(DiscoveryClient discoveryClient,
                                         DiatomCloudDiscoveryProperties properties) {
        this.discoveryClient = discoveryClient;
        this.properties = properties;
        refresh();
        long interval = properties.getRefreshIntervalMs();
        if (interval > 0) {
            scheduler.scheduleAtFixedRate(this::refresh, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void register(WorkerInfo worker) {
        if (worker == null) return;
        if (CLOUD_DISCOVERY_PROFILE.equals(worker.getGatewayProfile())) {
            logger.debug("Ignoring register for cloud-discovery worker {} (handled by direct discovery read)",
                    worker.getWorkerId());
            return;
        }
        overrideWorkers.put(worker.getWorkerId(), worker);
        notifyListeners(new RegistryEvent(worker.getWorkerId(), RegistryEvent.EventType.REGISTERED, worker));
        logger.info("Override worker registered: {} at {}:{}", worker.getWorkerId(), worker.getHost(), worker.getPort());
    }

    @Override
    public void deregister(String workerId) {
        WorkerInfo removed = overrideWorkers.remove(workerId);
        if (removed != null) {
            notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.DEREGISTERED, removed));
            logger.info("Override worker deregistered: {}", workerId);
        }
    }

    @Override
    public void heartbeat(String workerId, WorkerMetrics metrics) {
        WorkerInfo worker = overrideWorkers.get(workerId);
        if (worker != null) {
            worker.setMetrics(metrics != null ? metrics : new WorkerMetrics());
            worker.getMetrics().updateHeartbeat();
            if (worker.getStatus() != WorkerInfo.WorkerStatus.ONLINE) {
                worker.setStatus(WorkerInfo.WorkerStatus.ONLINE);
                notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.STATUS_CHANGED, worker));
            }
        }
    }

    @Override
    public List<WorkerInfo> availableWorkers() {
        refreshIfStale();
        Map<String, WorkerInfo> union = new LinkedHashMap<>();
        for (WorkerInfo w : snapshot.values()) {
            if (w.isAvailable()) union.put(w.getWorkerId(), w);
        }
        for (WorkerInfo w : overrideWorkers.values()) {
            if (w.isAvailable()) union.put(w.getWorkerId(), w);
        }
        return new ArrayList<>(union.values());
    }

    @Override
    public WorkerInfo getWorker(String workerId) {
        refreshIfStale();
        WorkerInfo w = snapshot.get(workerId);
        if (w == null) w = overrideWorkers.get(workerId);
        return w;
    }

    @Override
    public void markShuttingDown(String workerId) {
        WorkerInfo worker = overrideWorkers.get(workerId);
        if (worker != null) {
            worker.setStatus(WorkerInfo.WorkerStatus.SHUTTING_DOWN);
            notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.STATUS_CHANGED, worker));
        }
    }

    @Override
    public void subscribe(Consumer<RegistryEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void shutdown() {
        running = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 注册中心 gateway 节点列表（拓扑 API 使用）。匹配 {@code gatewayServiceFilter} 的服务实例；
     * 未配置过滤或查询失败时返回空列表，前端回退旧单 gateway 显示。
     */
    public List<GatewayNode> gatewayNodes() {
        List<GatewayNode> result = new ArrayList<>();
        String gwFilter = properties.getGatewayServiceFilter();
        if (gwFilter == null || gwFilter.isEmpty()) {
            return result;
        }
        try {
            List<String> services = discoveryClient.getServices();
            if (services == null) return result;
            for (String serviceId : services) {
                if (!gwFilter.equals(serviceId)) continue;
                List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
                if (instances == null) continue;
                for (ServiceInstance instance : instances) {
                    Map<String, String> md = instance.getMetadata();
                    GatewayNode node = new GatewayNode();
                    String id = md != null ? md.get("diatom.worker-id") : null;
                    node.setId(id != null && !id.isEmpty() ? id
                            : serviceId + ":" + instance.getHost() + ":" + instance.getPort());
                    node.setHost(instance.getHost());
                    node.setPort(instance.getPort());
                    String version = md != null ? md.get("diatom.version") : null;
                    node.setVersion(version != null && !version.isEmpty() ? version : "1.0.0");
                    // 直读模式下所有 gateway 共享同一 worker 集合
                    node.setWorkerCount(snapshot.size());
                    node.setUptime(System.currentTimeMillis());
                    result.add(node);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to list gateway nodes from discovery: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 数据过期（超过 refreshIntervalMs）时触发一次刷新。
     * interval <= 0 视为每次读取都刷新（始终最新）。
     */
    void refreshIfStale() {
        long interval = properties.getRefreshIntervalMs();
        if (interval <= 0 || System.currentTimeMillis() - lastRefreshMs >= interval) {
            refresh();
        }
    }

    /**
     * 从 DiscoveryClient 拉取实例并重建 cloud 快照，同时触发 diff 事件。
     * 包可见以便测试直接调用。异常时保留上一快照不清空。
     */
    void refresh() {
        if (!running) return;
        synchronized (refreshLock) {
            try {
                List<String> services = discoveryClient.getServices();
                if (services == null) return;

                String filter = properties.getServiceFilter();
                String gwFilter = properties.getGatewayServiceFilter();

                Map<String, WorkerInfo> discovered = new HashMap<>();
                Set<String> instanceKeys = new HashSet<>();
                for (String serviceId : services) {
                    if (filter != null && !filter.isEmpty() && !serviceId.contains(filter)) continue;
                    // 跳过 gateway 服务自身（直读模式 gateway 与 worker 无直连关系）
                    if (gwFilter != null && !gwFilter.isEmpty() && gwFilter.equals(serviceId)) continue;
                    List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
                    if (instances == null) continue;
                    for (ServiceInstance instance : instances) {
                        Map<String, String> md = instance.getMetadata();
                        if (md != null && GATEWAY_ROLE.equalsIgnoreCase(md.get("diatom.role"))) continue;
                        WorkerInfo wi = toWorkerInfo(serviceId, instance);
                        discovered.put(wi.getWorkerId(), wi);
                        instanceKeys.add(instance.getHost() + ":" + instance.getPort());
                    }
                }

                // diff：新增 / 更新 / 状态变化
                for (Map.Entry<String, WorkerInfo> e : discovered.entrySet()) {
                    String workerId = e.getKey();
                    WorkerInfo old = snapshot.get(workerId);
                    if (old == null) {
                        snapshot.put(workerId, e.getValue());
                        notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.REGISTERED, e.getValue()));
                    } else {
                        snapshot.put(workerId, e.getValue());
                        if (old.getStatus() != e.getValue().getStatus()) {
                            notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.STATUS_CHANGED, e.getValue()));
                        }
                    }
                    missingCounts.remove(workerId);
                }

                // 缺失：连续 2 次刷新都未发现才发 DEREGISTERED（容忍 Nacos 瞬时空）
                for (String workerId : snapshot.keySet()) {
                    if (discovered.containsKey(workerId)) continue;
                    if (overrideWorkers.containsKey(workerId)) continue;
                    Integer next = missingCounts.get(workerId);
                    int count = (next == null ? 0 : next) + 1;
                    if (count >= 2) {
                        WorkerInfo removed = snapshot.remove(workerId);
                        missingCounts.remove(workerId);
                        if (removed != null) {
                            notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.DEREGISTERED, removed));
                            logger.info("Discovered worker deregistered (missing for 2 refreshes): {}", workerId);
                        }
                    } else {
                        missingCounts.put(workerId, count);
                        logger.debug("Discovered worker {} missing (count={}), keeping in grace period", workerId, count);
                    }
                }

                // 直连 override 被同名 (host,port) 的发现实例取代时移除
                for (String workerId : overrideWorkers.keySet()) {
                    WorkerInfo ow = overrideWorkers.get(workerId);
                    if (ow != null && instanceKeys.contains(ow.getHost() + ":" + ow.getPort())) {
                        WorkerInfo removed = overrideWorkers.remove(workerId);
                        if (removed != null) {
                            notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.DEREGISTERED, removed));
                            logger.info("Override worker {} superseded by discovered instance {}:{}",
                                    workerId, ow.getHost(), ow.getPort());
                        }
                    }
                }

                lastRefreshMs = System.currentTimeMillis();
            } catch (Exception e) {
                logger.warn("Discovery refresh failed, keeping previous snapshot: {}", e.getMessage());
            }
        }
    }

    /**
     * 将单个 ServiceInstance 映射为 WorkerInfo。
     * workerId 优先取 metadata {@code diatom.worker-id}（稳定身份），否则 {@code serviceId:host:port}。
     */
    private WorkerInfo toWorkerInfo(String serviceId, ServiceInstance instance) {
        Map<String, String> metadata = instance.getMetadata();
        String workerId = metadataValue(metadata, "diatom.worker-id", null);
        if (workerId == null || workerId.isEmpty()) {
            workerId = serviceId + ":" + instance.getHost() + ":" + instance.getPort();
        }

        WorkerInfo worker = new WorkerInfo(workerId, instance.getHost(), instance.getPort());
        worker.setUseSsl(instance.isSecure());
        worker.setModel(readMetadata(metadata, "diatom.model", "model", properties.getDefaultModel()));
        worker.setGroup(readMetadata(metadata, "diatom.group", "group", properties.getWorkerGroup()));
        worker.setTier(readMetadata(metadata, "diatom.tier", "tier", "worker"));
        worker.setMaxConcurrency(readIntMetadata(metadata, "diatom.max-concurrency", null, 5));
        worker.setWorkspace(readMetadata(metadata, "diatom.workspace", null, null));
        worker.setGatewayProfile(CLOUD_DISCOVERY_PROFILE);
        worker.setStatus(WorkerInfo.WorkerStatus.ONLINE);

        WorkerMetrics metrics = new WorkerMetrics();
        metrics.setCurrentLoad(readDoubleMetadata(metadata, "diatom.current-load", null, 0.0));
        metrics.setActiveTasks(readIntMetadata(metadata, "diatom.active-tasks", null, 0));
        metrics.updateHeartbeat();
        worker.setMetrics(metrics);
        return worker;
    }

    /**
     * 读取 metadata：优先标准键 {@code diatom.xxx}，其次 {@code instanceTag} 映射键，最后默认值。
     */
    private String readMetadata(Map<String, String> metadata, String standardKey, String tagKey,
                                String defaultValue) {
        if (metadata != null) {
            String v = metadata.get(standardKey);
            if (v != null && !v.isEmpty()) return v;
            if (tagKey != null) {
                Map<String, String> tagMapping = properties.getInstanceTag();
                if (tagMapping != null) {
                    String tag = tagMapping.get(tagKey);
                    if (tag != null && !tag.isEmpty()) {
                        v = metadata.get(tag);
                        if (v != null && !v.isEmpty()) return v;
                    }
                }
            }
        }
        return defaultValue;
    }

    private int readIntMetadata(Map<String, String> metadata, String standardKey, String tagKey,
                                int defaultValue) {
        String v = readMetadata(metadata, standardKey, tagKey, null);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double readDoubleMetadata(Map<String, String> metadata, String standardKey, String tagKey,
                                      double defaultValue) {
        String v = readMetadata(metadata, standardKey, tagKey, null);
        if (v == null) return defaultValue;
        try {
            double d = Double.parseDouble(v.trim());
            return Math.max(0.0, Math.min(1.0, d));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String metadataValue(Map<String, String> metadata, String key, String defaultValue) {
        if (metadata == null) return defaultValue;
        String v = metadata.get(key);
        return v != null && !v.isEmpty() ? v : defaultValue;
    }

    private void notifyListeners(RegistryEvent event) {
        for (Consumer<RegistryEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Registry listener error: {}", e.getMessage());
            }
        }
    }
}
