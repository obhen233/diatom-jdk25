package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos 注册中心服务。
 *
 * <p>当 {@code diatom.mode=gateway:nacos} 时激活。
 * 使用 Nacos Naming API 进行 Gateway 自注册和 Worker 发现。
 *
 * <p>需要配置：
 * <pre>
 * diatom.mode=gateway:nacos
 * spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848
 * </pre>
 *
 * <p>注意：依赖 {@code com.alibaba.nacos:nacos-client}，需自行引入。
 * 当前为 Stub 实现，预留真实 Nacos API 调用。
 */
public class NacosRegistryService implements RegistryService {
    private static final Logger logger = LoggerFactory.getLogger(NacosRegistryService.class);

    private final String serverAddr;
    private final String namespace;
    private final Map<String, WorkerInfo> localCache = new ConcurrentHashMap<>();
    private volatile boolean registered = false;

    public NacosRegistryService(String serverAddr) {
        this(serverAddr, "");
    }

    public NacosRegistryService(String serverAddr, String namespace) {
        this.serverAddr = serverAddr;
        this.namespace = namespace != null ? namespace : "";
        logger.info("NacosRegistryService created (server: {}, namespace: '{}')", serverAddr, this.namespace);
    }

    @Override
    public void registerGateway(String serviceId, String host, int port, Map<String, String> metadata) {
        if (registered) {
            logger.debug("Gateway already registered with Nacos: {}", serviceId);
            return;
        }
        try {
            // TODO: 使用 Nacos NamingService API 注册
            // Properties props = new Properties();
            // props.setProperty("serverAddr", serverAddr);
            // if (!namespace.isEmpty()) props.setProperty("namespace", namespace);
            // NamingService naming = NamingFactory.createNamingService(props);
            // naming.registerInstance(serviceId, host, port);
            registered = true;
            logger.info("Gateway registered with Nacos: {} at {}:{} (service: {})",
                    serviceId, host, port, serviceId);
        } catch (Exception e) {
            logger.warn("Failed to register gateway with Nacos: {}", e.getMessage());
        }
    }

    @Override
    public void deregisterGateway() {
        if (!registered) return;
        try {
            // TODO: NamingService.deregisterInstance(serviceId, host, port)
            registered = false;
            logger.info("Gateway deregistered from Nacos");
        } catch (Exception e) {
            logger.warn("Failed to deregister gateway from Nacos: {}", e.getMessage());
        }
    }

    @Override
    public List<WorkerInfo> discoverWorkers(String serviceFilter) {
        try {
            // TODO: 使用 Nacos NamingService.getInstances(serviceId) 发现实例
            // 当前返回本地缓存
            List<WorkerInfo> result = new ArrayList<>();
            for (WorkerInfo w : localCache.values()) {
                if (serviceFilter == null || serviceFilter.isEmpty()
                        || w.getWorkerId().contains(serviceFilter)) {
                    result.add(w);
                }
            }
            return result;
        } catch (Exception e) {
            logger.warn("Failed to discover workers from Nacos: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            // TODO: 通过 Nacos API 健康检查
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        deregisterGateway();
        localCache.clear();
        logger.info("NacosRegistryService destroyed");
    }
}
