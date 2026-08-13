package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eureka 注册中心服务。
 *
 * <p>当 {@code diatom.mode=gateway:eureka} 时激活。
 * 通过 Spring Cloud Netflix Eureka 进行 Gateway 自注册和 Worker 发现。
 *
 * <p>需要引入：
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;org.springframework.cloud&lt;/groupId&gt;
 *     &lt;artifactId&gt;spring-cloud-starter-netflix-eureka-client&lt;/artifactId&gt;
 * &lt;/dependency&gt;
 * </pre>
 *
 * 配置：
 * <pre>
 * diatom.mode=gateway:eureka
 * eureka.client.serviceUrl.defaultZone=http://localhost:8761/eureka/
 * </pre>
 *
 * 当前为 Stub 实现，预留真实 Eureka API 调用。
 */
public class EurekaRegistryService implements RegistryService {
    private static final Logger logger = LoggerFactory.getLogger(EurekaRegistryService.class);

    private final Map<String, WorkerInfo> localCache = new ConcurrentHashMap<>();
    private volatile boolean registered = false;

    public EurekaRegistryService() {
        logger.info("EurekaRegistryService created (stub)");
    }

    @Override
    public void registerGateway(String serviceId, String host, int port, Map<String, String> metadata) {
        if (registered) {
            logger.debug("Gateway already registered with Eureka: {}", serviceId);
            return;
        }
        // TODO: 通过 Eureka API 注册
        // ApplicationInfoManager.getInstance().registerAppMetadata(metadata);
        // InstanceInfo instance = InstanceInfo.Builder.newBuilder()
        //         .setAppName(serviceId)
        //         .setHostName(host)
        //         .setPort(port)
        //         .build();
        // DiscoveryManager.getInstance().getEurekaClient().register(instance);
        registered = true;
        logger.info("Gateway registered with Eureka: {} at {}:{} (stub)", serviceId, host, port);
    }

    @Override
    public void deregisterGateway() {
        if (!registered) return;
        registered = false;
        logger.info("Gateway deregistered from Eureka (stub)");
    }

    @Override
    public List<WorkerInfo> discoverWorkers(String serviceFilter) {
        List<WorkerInfo> result = new ArrayList<>();
        for (WorkerInfo w : localCache.values()) {
            if (serviceFilter == null || serviceFilter.isEmpty()
                    || w.getWorkerId().contains(serviceFilter)) {
                result.add(w);
            }
        }
        return result;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public void destroy() {
        deregisterGateway();
        localCache.clear();
        logger.info("EurekaRegistryService destroyed");
    }
}
