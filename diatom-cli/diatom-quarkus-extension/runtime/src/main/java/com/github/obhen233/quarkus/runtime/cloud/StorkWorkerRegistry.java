package com.github.obhen233.quarkus.runtime.cloud;

import com.github.obhen233.core.gateway.registry.RegistryEvent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 注册中心直读的 {@link WorkerRegistry} 实现（镜像 starter {@code DiscoveryClientWorkerRegistry} 的
 * SmallRye Stork 版）。
 *
 * <p>与 {@code FileSystemWorkerRegistry}（本地 worker 快照）不同，本实现不维护本地 worker 快照，
 * 而是直接读取注册中心（{@link CloudRegistryService}）——注册中心是唯一数据源。多台 gateway 直读
 * 同一注册中心，因此永远看到同一份 worker 数据，无需 Hazelcast 等额外基础设施。</p>
 *
 * <ul>
 *   <li>{@link #availableWorkers()} / {@link #getWorker(String)} 在数据过期时触发 {@link #refresh()}，
 *       返回 发现实例(cloud) + 直连 override 的并集（按 workerId 去重）。</li>
 *   <li>{@code register/heartbeat/deregister/markShuttingDown} 仅作用于 overrideWorkers
 *       （直连注册的非 cloud worker）；{@code gatewayProfile == "cloud-discovery"} 的 register 为空操作。</li>
 *   <li>后台调度线程按 {@code refreshIntervalMs} 周期刷新；diff 事件：新增发 REGISTERED、
 *       连续 2 次缺失才发 DEREGISTERED（容忍注册中心瞬时空）、状态变化发 STATUS_CHANGED。</li>
 *   <li>刷新异常时保留上一快照，不批量注销。</li>
 * </ul>
 */
public class StorkWorkerRegistry implements WorkerRegistry {

    private static final Logger LOGGER = Logger.getLogger(StorkWorkerRegistry.class);
    private static final String CLOUD_DISCOVERY_PROFILE = "cloud-discovery";

    private final CloudRegistryService cloud;
    private final CloudDiscoveryConfig config;

    /** 直连注册（非 cloud）的 worker。 */
    private final ConcurrentHashMap<String, WorkerInfo> overrideWorkers = new ConcurrentHashMap<>();

    /** 发现的 cloud worker 缓存快照（含缺失宽限期的 worker）。 */
    private final ConcurrentHashMap<String, WorkerInfo> snapshot = new ConcurrentHashMap<>();

    /** 连续缺失计数：workerId → 已连续缺失次数（>=2 才发 DEREGISTERED）。 */
    private final ConcurrentHashMap<String, Integer> missingCounts = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<Consumer<RegistryEvent>> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService scheduler;
    private final Object refreshLock = new Object();
    private volatile long lastRefreshMs = 0L;
    private volatile boolean running = true;

    public StorkWorkerRegistry(CloudRegistryService cloud, CloudDiscoveryConfig config) {
        this.cloud = cloud;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stork-worker-registry");
            t.setDaemon(true);
            return t;
        });
        refresh();
        long interval = config.refreshIntervalMs();
        if (interval > 0) {
            scheduler.scheduleAtFixedRate(this::refresh, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void register(WorkerInfo worker) {
        if (worker == null) {
            return;
        }
        if (CLOUD_DISCOVERY_PROFILE.equals(worker.getGatewayProfile())) {
            LOGGER.debugf("Ignoring register for cloud-discovery worker %s (handled by direct discovery read)",
                    worker.getWorkerId());
            return;
        }
        overrideWorkers.put(worker.getWorkerId(), worker);
        notifyListeners(new RegistryEvent(worker.getWorkerId(), RegistryEvent.EventType.REGISTERED, worker));
        LOGGER.debugf("Override worker registered: %s", worker.getWorkerId());
    }

    @Override
    public void deregister(String workerId) {
        WorkerInfo removed = overrideWorkers.remove(workerId);
        if (removed != null) {
            notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.DEREGISTERED, removed));
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
            if (w.isAvailable()) {
                union.put(w.getWorkerId(), w);
            }
        }
        for (WorkerInfo w : overrideWorkers.values()) {
            if (w.isAvailable()) {
                union.put(w.getWorkerId(), w);
            }
        }
        return new ArrayList<>(union.values());
    }

    @Override
    public WorkerInfo getWorker(String workerId) {
        refreshIfStale();
        WorkerInfo w = snapshot.get(workerId);
        if (w == null) {
            w = overrideWorkers.get(workerId);
        }
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

    /** 数据过期（超过 refreshIntervalMs）时触发一次刷新；interval <= 0 每次读取都刷新。 */
    void refreshIfStale() {
        long interval = config.refreshIntervalMs();
        if (interval <= 0 || System.currentTimeMillis() - lastRefreshMs >= interval) {
            refresh();
        }
    }

    /**
     * 从注册中心拉取实例并重建 cloud 快照，同时触发 diff 事件。
     * 包可见以便测试直接调用。异常时保留上一快照不清空。
     */
    void refresh() {
        if (!running) {
            return;
        }
        synchronized (refreshLock) {
            try {
                Map<String, WorkerInfo> discovered = new HashMap<>();
                for (DiscoveredInstance instance : cloud.discover(config.serviceName())) {
                    // 跳过 gateway 角色实例（直读模式下 gateway 与 worker 无直连关系）
                    String role = instance.metadata("diatom.role");
                    if (role != null && "gateway".equalsIgnoreCase(role)) {
                        continue;
                    }
                    WorkerInfo wi = toWorkerInfo(config.serviceName(), instance);
                    discovered.put(wi.getWorkerId(), wi);
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

                // 缺失：连续 2 次刷新都未发现才发 DEREGISTERED（容忍注册中心瞬时空）
                for (String workerId : snapshot.keySet()) {
                    if (discovered.containsKey(workerId)) {
                        continue;
                    }
                    if (overrideWorkers.containsKey(workerId)) {
                        continue;
                    }
                    Integer next = missingCounts.get(workerId);
                    int count = (next == null ? 0 : next) + 1;
                    if (count >= 2) {
                        WorkerInfo removed = snapshot.remove(workerId);
                        missingCounts.remove(workerId);
                        if (removed != null) {
                            notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.DEREGISTERED, removed));
                            LOGGER.infof("Stork-discovered worker deregistered (missing for 2 refreshes): %s", workerId);
                        }
                    } else {
                        missingCounts.put(workerId, count);
                        LOGGER.debugf("Stork-discovered worker %s missing (count=%d), keeping in grace period", workerId, count);
                    }
                }

                lastRefreshMs = System.currentTimeMillis();
            } catch (Exception e) {
                LOGGER.warnf("Stork refresh failed, keeping previous snapshot: %s", e.getMessage());
            }
        }
    }

    /**
     * 将单个 {@link DiscoveredInstance} 映射为 WorkerInfo。
     * workerId 优先取 metadata {@code diatom.worker-id}（稳定身份），否则 {@code serviceId:host:port}。
     */
    private WorkerInfo toWorkerInfo(String serviceId, DiscoveredInstance instance) {
        String workerId = instance.metadata("diatom.worker-id");
        if (workerId == null || workerId.isEmpty()) {
            workerId = serviceId + ":" + instance.host() + ":" + instance.port();
        }

        WorkerInfo worker = new WorkerInfo(workerId, instance.host(), instance.port());
        worker.setUseSsl(instance.secure());
        worker.setModel(instance.metadata("diatom.model", config.defaultModel()));
        worker.setGroup(instance.metadata("diatom.group", config.workerGroup()));
        worker.setTier(instance.metadata("diatom.tier", "worker"));
        worker.setMaxConcurrency(readInt(instance.metadata("diatom.max-concurrency"), 5));
        worker.setWorkspace(instance.metadata("diatom.workspace", null));
        worker.setGatewayProfile(CLOUD_DISCOVERY_PROFILE);
        worker.setStatus(WorkerInfo.WorkerStatus.ONLINE);

        WorkerMetrics metrics = new WorkerMetrics();
        metrics.setCurrentLoad(readDouble(instance.metadata("diatom.current-load"), 0.0));
        metrics.setActiveTasks(readInt(instance.metadata("diatom.active-tasks"), 0));
        metrics.updateHeartbeat();
        worker.setMetrics(metrics);
        return worker;
    }

    private static int readInt(String v, int defaultValue) {
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double readDouble(String v, double defaultValue) {
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Math.max(0.0, Math.min(1.0, Double.parseDouble(v.trim())));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void notifyListeners(RegistryEvent event) {
        for (Consumer<RegistryEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOGGER.warnf("Registry listener error: %s", e.getMessage());
            }
        }
    }
}
