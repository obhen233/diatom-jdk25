package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.RegistryEvent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Spring Cloud 服务发现适配器 - Nacos 版
 *
 * 使用 Nacos 作为注册中心，Worker 启动时注册到 Nacos，
 * Gateway 通过 Nacos 发现 Worker。
 *
 * 启用条件:
 * 1. classpath 中存在 nacos-client
 * 2. spring.cloud.nacos.discovery.server-addr 已配置
 *
 * P4 预留实现，当前为桩代码
 */
public class NacosRegistryAdapter implements WorkerRegistry {
    private static final Logger logger = LoggerFactory.getLogger(NacosRegistryAdapter.class);
    private final Map<String, WorkerInfo> localCache = new ConcurrentHashMap<>();

    @Override
    public void register(WorkerInfo worker) {
        localCache.put(worker.getWorkerId(), worker);
        logger.info("Nacos registry: worker {} registered (stub)", worker.getWorkerId());
    }

    @Override
    public void deregister(String workerId) {
        localCache.remove(workerId);
        logger.info("Nacos registry: worker {} deregistered (stub)", workerId);
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
        // Nacos 原生支持订阅，P4 预留
        logger.info("Nacos registry subscription not yet implemented (stub)");
    }

    @Override
    public void shutdown() {
        logger.info("Nacos registry shutdown (stub)");
    }
}
