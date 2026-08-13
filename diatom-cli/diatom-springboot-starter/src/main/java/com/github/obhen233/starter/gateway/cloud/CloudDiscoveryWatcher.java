package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cloud Discovery 监听器
 *
 * 通过 Spring Cloud 的 {@link DiscoveryClient} 发现注册的服务实例，
 * 自动同步为 diatom Worker 注册到 {@link com.github.obhen233.core.gateway.registry.WorkerRegistry}。
 *
 * 支持 Nacos、Eureka、Consul 等所有实现 {@link DiscoveryClient} 的注册中心。
 * {@code @ConditionalOnClass(DiscoveryClient.class)} 确保无 Spring Cloud 依赖时不加载。
 */
public class CloudDiscoveryWatcher {
    private static final Logger logger = LoggerFactory.getLogger(CloudDiscoveryWatcher.class);

    private final DiscoveryClient discoveryClient;
    private final DiatomCloudDiscoveryProperties properties;
    private final com.github.obhen233.core.gateway.registry.WorkerRegistry workerRegistry;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 已知的云发现 Worker ID 集合。
     * key = "cloud:{serviceId}:{host}:{port}"，用于跟踪哪些 worker 是通过 cloud discovery 注册的。
     */
    private final Set<String> knownCloudWorkers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public CloudDiscoveryWatcher(DiscoveryClient discoveryClient,
                                  DiatomCloudDiscoveryProperties properties,
                                  com.github.obhen233.core.gateway.registry.WorkerRegistry workerRegistry) {
        this.discoveryClient = discoveryClient;
        this.properties = properties;
        this.workerRegistry = workerRegistry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                new CustomizableThreadFactory("diatom-cloud-discovery-"));
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            logger.info("Cloud Discovery watcher is disabled by configuration");
            return;
        }
        if (workerRegistry instanceof DiscoveryClientWorkerRegistry) {
            logger.info("Cloud Discovery watcher disabled: WorkerRegistry is {} (direct registry read mode)",
                    DiscoveryClientWorkerRegistry.class.getSimpleName());
            return;
        }
        running.set(true);
        // 初始同步
        syncDiscoveredWorkers();
        // 定期刷新
        scheduler.scheduleAtFixedRate(this::syncDiscoveredWorkers,
                properties.getRefreshIntervalMs(),
                properties.getRefreshIntervalMs(),
                TimeUnit.MILLISECONDS);
        logger.info("Cloud Discovery watcher started (refresh interval: {}ms, filter: '{}')",
                properties.getRefreshIntervalMs(), properties.getServiceFilter());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 清理所有通过 Cloud Discovery 注册的 Worker
        for (String cloudKey : knownCloudWorkers) {
            String workerId = cloudKey.substring("cloud:".length());
            workerRegistry.deregister(workerId);
            logger.debug("Deregistered cloud-discovered worker on shutdown: {}", workerId);
        }
        knownCloudWorkers.clear();
        logger.info("Cloud Discovery watcher stopped");
    }

    /**
     * 同步 DiscoveryClient 中发现的实例到 WorkerRegistry。
     */
    void syncDiscoveredWorkers() {
        if (!running.get()) return;
        try {
            List<String> services = getServices();
            Set<String> currentInstances = new HashSet<>();

            for (String serviceId : services) {
                String filter = properties.getServiceFilter();
                if (filter != null && !filter.isEmpty() && !serviceId.contains(filter)) {
                    continue;
                }

                List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
                for (ServiceInstance instance : instances) {
                    String cloudKey = registerCloudWorker(serviceId, instance);
                    if (cloudKey != null) {
                        currentInstances.add(cloudKey);
                    }
                }
            }

            // 注销已不在 DiscoveryClient 中的 Worker
            deregisterRemovedWorkers(currentInstances);
        } catch (Exception e) {
            logger.warn("Cloud Discovery sync failed: {}", e.getMessage());
        }
    }

    /**
     * 从 DiscoveryClient 获取服务列表。
     * 某些实现可能返回 null（如 Consul 在没有服务时），做防御处理。
     */
    private List<String> getServices() {
        try {
            List<String> services = discoveryClient.getServices();
            return services != null ? services : Collections.emptyList();
        } catch (Exception e) {
            logger.debug("Failed to get services from DiscoveryClient: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将单个 ServiceInstance 注册为 Worker。
     *
     * @return cloudKey，用于后续跟踪；null 表示注册失败
     */
    private String registerCloudWorker(String serviceId, ServiceInstance instance) {
        try {
            String workerId = serviceId + ":" + instance.getHost() + ":" + instance.getPort();
            String cloudKey = "cloud:" + workerId;

            // 检查是否已注册
            if (knownCloudWorkers.contains(cloudKey)) {
                // 已注册，更新心跳
                workerRegistry.heartbeat(workerId, createHeartbeatMetrics(instance));
                return cloudKey;
            }

            // 构造 WorkerInfo
            WorkerInfo worker = new WorkerInfo(workerId, instance.getHost(), instance.getPort());
            worker.setUseSsl(instance.isSecure());

            // 从 metadata 读取额外属性
            Map<String, String> metadata = instance.getMetadata();
            if (metadata != null) {
                applyMetadata(worker, metadata);
            }

            // 设置分组
            worker.setGroup(properties.getWorkerGroup());

            // 默认 model
            if (worker.getModel() == null || worker.getModel().isEmpty()) {
                worker.setModel(properties.getDefaultModel());
            }

            // 标记为 Cloud Discovered Worker
            worker.setGatewayProfile("cloud-discovery");
            worker.setStatus(WorkerInfo.WorkerStatus.ONLINE);

            workerRegistry.register(worker);
            knownCloudWorkers.add(cloudKey);
            logger.info("Cloud-discovered worker registered: {} at {}:{} (service: {})",
                    workerId, instance.getHost(), instance.getPort(), serviceId);
            return cloudKey;
        } catch (Exception e) {
            logger.warn("Failed to register cloud-discovered worker ({}:{}): {}",
                    instance.getHost(), instance.getPort(), e.getMessage());
            return null;
        }
    }

    /**
     * 将 ServiceInstance metadata 映射到 WorkerInfo 字段。
     */
    private void applyMetadata(WorkerInfo worker, Map<String, String> metadata) {
        Map<String, String> tagMapping = properties.getInstanceTag();
        if (tagMapping == null || tagMapping.isEmpty()) {
            return;
        }

        // instance-tag.model=ai-model → 从 metadata["ai-model"] 读取 model
        String modelTag = tagMapping.get("model");
        if (modelTag != null && metadata.containsKey(modelTag)) {
            worker.setModel(metadata.get(modelTag));
        }

        String groupTag = tagMapping.get("group");
        if (groupTag != null && metadata.containsKey(groupTag)) {
            worker.setGroup(metadata.get(groupTag));
        }

        String tierTag = tagMapping.get("tier");
        if (tierTag != null && metadata.containsKey(tierTag)) {
            worker.setTier(metadata.get(tierTag));
        }
    }

    /**
     * 注销已消失的服务实例。
     */
    private void deregisterRemovedWorkers(Set<String> currentInstances) {
        for (String cloudKey : knownCloudWorkers) {
            if (!currentInstances.contains(cloudKey)) {
                String workerId = cloudKey.substring("cloud:".length());
                workerRegistry.deregister(workerId);
                knownCloudWorkers.remove(cloudKey);
                logger.info("Cloud-discovered worker deregistered (no longer in registry): {}", workerId);
            }
        }
    }

    /**
     * 为 Cloud Worker 创建心跳指标。
     */
    private static WorkerMetrics createHeartbeatMetrics(ServiceInstance instance) {
        WorkerMetrics metrics = new WorkerMetrics();
        metrics.updateHeartbeat();
        metrics.setCurrentLoad(0);      // Cloud Worker 负载由注册中心管理
        metrics.setActiveTasks(0);
        return metrics;
    }

    /**
     * 获取当前已知的 Cloud Worker 数量（测试用）。
     */
    int getKnownCloudWorkerCount() {
        return knownCloudWorkers.size();
    }
}
