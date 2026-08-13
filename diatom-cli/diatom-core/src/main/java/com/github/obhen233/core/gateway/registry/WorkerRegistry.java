package com.github.obhen233.core.gateway.registry;

import java.util.List;
import java.util.function.Consumer;

/**
 * Worker 注册中心 SPI
 */
public interface WorkerRegistry {
    void register(WorkerInfo worker);
    void deregister(String workerId);
    void heartbeat(String workerId, WorkerMetrics metrics);
    List<WorkerInfo> availableWorkers();

    /**
     * Return only workers directly connected to this local gateway instance.
     * In HA cluster mode, this excludes workers registered to other gateways.
     * Default implementation delegates to {@link #availableWorkers()}.
     */
    default List<WorkerInfo> localWorkers() {
        return availableWorkers();
    }

    WorkerInfo getWorker(String workerId);
    void markShuttingDown(String workerId);
    void subscribe(Consumer<RegistryEvent> listener);
    void shutdown();
}
