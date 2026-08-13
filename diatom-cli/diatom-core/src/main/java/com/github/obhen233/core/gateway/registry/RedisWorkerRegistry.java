package com.github.obhen233.core.gateway.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Redis 注册中心适配器
 *
 * 使用 Redis 作为共享注册中心，支持跨进程 Worker 发现。
 * 通过 Redis 的 Pub/Sub 实现实时事件通知。
 *
 * 启用条件:
 * 1. classpath 中存在 jedis 或 lettuce-core
 * 2. redis.host / redis.port 已配置
 *
 * P5 预留实现，当前为桩代码
 */
public class RedisWorkerRegistry implements WorkerRegistry {
    private static final Logger logger = LoggerFactory.getLogger(RedisWorkerRegistry.class);
    private final Map<String, WorkerInfo> localCache = new ConcurrentHashMap<>();

    @Override
    public void register(WorkerInfo worker) {
        localCache.put(worker.getWorkerId(), worker);
        logger.info("Redis registry: worker {} registered (stub)", worker.getWorkerId());
    }

    @Override
    public void deregister(String workerId) {
        localCache.remove(workerId);
        logger.info("Redis registry: worker {} deregistered (stub)", workerId);
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
        logger.info("Redis registry subscription not yet implemented (stub)");
    }

    @Override
    public void shutdown() {
        logger.info("Redis registry shutdown (stub)");
    }
}
