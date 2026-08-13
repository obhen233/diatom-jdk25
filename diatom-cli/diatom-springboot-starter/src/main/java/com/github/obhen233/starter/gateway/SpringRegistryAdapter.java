package com.github.obhen233.starter.gateway;

import com.github.obhen233.core.gateway.registry.RegistryEvent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Spring 注册中心适配器
 * 适配 Spring Cloud 的注册发现机制
 */
public class SpringRegistryAdapter {
    private static final Logger logger = LoggerFactory.getLogger(SpringRegistryAdapter.class);

    private final WorkerRegistry registry;

    public SpringRegistryAdapter(WorkerRegistry registry) {
        this.registry = registry;
    }

    /**
     * 获取所有可用 Worker
     */
    public List<WorkerInfo> getAvailableWorkers() {
        return registry.availableWorkers();
    }

    /**
     * 订阅注册事件
     */
    public void subscribe(Consumer<RegistryEvent> listener) {
        registry.subscribe(listener);
    }

    /**
     * 注册一个新的 Worker（供 Spring Cloud 服务发现回调）
     */
    public void registerWorker(WorkerInfo worker) {
        registry.register(worker);
        logger.info("Spring-registered worker: {}", worker.getWorkerId());
    }

    /**
     * 注销 Worker
     */
    public void deregisterWorker(String workerId) {
        registry.deregister(workerId);
        logger.info("Spring-deregistered worker: {}", workerId);
    }
}
