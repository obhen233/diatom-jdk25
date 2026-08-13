package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consul 注册中心服务。
 *
 * <p>当 {@code diatom.mode=gateway:consul} 时激活。
 * 通过 Spring Cloud Consul 进行 Gateway 自注册和 Worker 发现。
 *
 * <p>需要引入：
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;org.springframework.cloud&lt;/groupId&gt;
 *     &lt;artifactId&gt;spring-cloud-starter-consul-discovery&lt;/artifactId&gt;
 * &lt;/dependency&gt;
 * </pre>
 *
 * 配置：
 * <pre>
 * diatom.mode=gateway:consul
 * spring.cloud.consul.host=localhost
 * spring.cloud.consul.port=8500
 * </pre>
 *
 * 当前为 Stub 实现，预留真实 Consul API 调用。
 */
public class ConsulRegistryService implements RegistryService {
    private static final Logger logger = LoggerFactory.getLogger(ConsulRegistryService.class);

    private final Map<String, WorkerInfo> localCache = new ConcurrentHashMap<>();
    private volatile boolean registered = false;

    public ConsulRegistryService() {
        logger.info("ConsulRegistryService created (stub)");
    }

    @Override
    public void registerGateway(String serviceId, String host, int port, Map<String, String> metadata) {
        if (registered) {
            logger.debug("Gateway already registered with Consul: {}", serviceId);
            return;
        }
        // TODO: 通过 Consul API 注册
        // ConsulClient client = new ConsulClient(consulHost, consulPort);
        // Registration registration = ImmutableRegistration.builder()
        //         .id(serviceId + "-" + host + ":" + port)
        //         .name(serviceId)
        //         .address(host)
        //         .port(port)
        //         .build();
        // client.agentServiceRegister(registration);
        registered = true;
        logger.info("Gateway registered with Consul: {} at {}:{} (stub)", serviceId, host, port);
    }

    @Override
    public void deregisterGateway() {
        if (!registered) return;
        registered = false;
        logger.info("Gateway deregistered from Consul (stub)");
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
        logger.info("ConsulRegistryService destroyed");
    }
}
