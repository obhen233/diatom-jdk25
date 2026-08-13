package com.github.obhen233.core.gateway.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Etcd 注册中心适配器
 *
 * 使用 Etcd 作为共享注册中心，适用于 K8s/大规模多可用区部署。
 * 通过 Etcd 的 lease 机制实现自动过期，Watch API 实现实时事件通知。
 *
 * 启用条件:
 * 1. classpath 中存在 etcd4j 或 jetcd
 * 2. etcd.endpoints 已配置
 *
 * P6 预留实现，当前为桩代码
 */
public class EtcdWorkerRegistry implements WorkerRegistry {
    private static final Logger logger = LoggerFactory.getLogger(EtcdWorkerRegistry.class);
    private final Map<String, WorkerInfo> localCache = new ConcurrentHashMap<>();

    @Override
    public void register(WorkerInfo worker) {
        localCache.put(worker.getWorkerId(), worker);
        logger.info("Etcd registry: worker {} registered (stub)", worker.getWorkerId());
    }

    @Override
    public void deregister(String workerId) {
        localCache.remove(workerId);
        logger.info("Etcd registry: worker {} deregistered (stub)", workerId);
    }

    @Override
    public void heartbeat(String workerId, WorkerMetrics metrics) {
        WorkerInfo worker = localCache.get(workerId);
        if (worker != null) {
            worker.setMetrics(metrics);
            worker.getMetrics().setLastHeartbeat(System.currentTimeMillis());
        }
    }

    @Override
    public List<WorkerInfo> availableWorkers() {
        List<WorkerInfo> result = new ArrayList<>();
        for (WorkerInfo w : localCache.values()) {
            if (w.isAvailable()) result.add(w);
        }
        return result;
    }

    @Override
    public WorkerInfo getWorker(String workerId) {
        return localCache.get(workerId);
    }

    @Override
    public void markShuttingDown(String workerId) {
        WorkerInfo w = localCache.get(workerId);
        if (w != null) w.setStatus(WorkerInfo.WorkerStatus.SHUTTING_DOWN);
    }

    @Override
    public void subscribe(Consumer<RegistryEvent> listener) {
        logger.info("Etcd registry subscription not yet implemented (stub)");
    }

    @Override
    public void shutdown() {
        logger.info("Etcd registry shutdown (stub)");
    }
}
