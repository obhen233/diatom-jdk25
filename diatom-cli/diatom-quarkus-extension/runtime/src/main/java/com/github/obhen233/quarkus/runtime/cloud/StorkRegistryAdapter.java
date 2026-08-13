package com.github.obhen233.quarkus.runtime.cloud;

import io.smallrye.stork.Stork;
import io.smallrye.stork.api.ServiceInstance;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 SmallRye Stork 的 {@link CloudRegistryService} 实现（Quarkus 原生注册中心）。
 *
 * <p>替换 Spring Cloud 的 {@code DiscoveryClient} / {@code RegistryService}：</p>
 * <ul>
 *   <li><b>自注册</b>：{@link Stork} 的 {@code ServiceRegistrar}（consul / eureka 内置 provider）</li>
 *   <li><b>发现</b>：{@link Stork} 的 {@code ServiceDiscovery}（consul / eureka 内置 provider）</li>
 * </ul>
 *
 * <p>{@link #init()} 按 {@code diatom.cloud.type} 写 {@code stork.<service>.*} 系统属性后初始化
 * Stork 单例；consul/eureka 时写入 service-discovery/registrar + host/port。provider jar 缺失或
 * 注册中心不可达时<b>优雅降级</b>（发现返回空、注册/注销打日志不抛异常），镜像 core 的降级哲学。</p>
 *
 * <p>Nacos 非 Stork 内置 → TODO：按 Stork SPI 自定义 provider 或 quarkiverse 社区扩展按需补充。</p>
 */
public class StorkRegistryAdapter implements CloudRegistryService {

    private static final Logger LOGGER = Logger.getLogger(StorkRegistryAdapter.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** JVM 级 Stork 单例初始化守卫（Stork.initialize 只应成功一次）。 */
    private static final AtomicBoolean STORK_INITIALIZED = new AtomicBoolean(false);

    private final CloudDiscoveryConfig config;
    private volatile boolean initialized;

    public StorkRegistryAdapter(CloudDiscoveryConfig config) {
        this.config = config;
    }

    @Override
    public synchronized void init() {
        if (initialized) {
            return;
        }
        if (!config.enabled()) {
            LOGGER.warnf("Stork registry disabled (cloud.type=%s)", config.type());
            initialized = true;
            return;
        }
        String type = config.type().toLowerCase();
        String service = config.serviceName();
        try {
            if ("consul".equals(type) || "eureka".equals(type)) {
                // stork.<service>.service-discovery / .service-registrar + <type>-host / <type>-port
                System.setProperty("stork." + service + ".service-discovery", type);
                System.setProperty("stork." + service + ".service-registrar", type);
                System.setProperty("stork." + service + "." + type + "-host", config.host());
                System.setProperty("stork." + service + "." + type + "-port", String.valueOf(config.port()));
            }
            if (STORK_INITIALIZED.compareAndSet(false, true)) {
                Stork.initialize();
            }
            initialized = true;
            LOGGER.infof("Stork registry initialized (type=%s, host=%s:%d, service=%s)",
                    type, config.host(), config.port(), service);
        } catch (Exception e) {
            LOGGER.warnf("Stork registry init failed: %s", e.getMessage());
            initialized = true; // 降级：后续调用返回空/失败
        }
    }

    @Override
    public void registerInstance(String serviceName, String instanceId, String host, int port) {
        if (!config.enabled() || instanceId == null || instanceId.isEmpty()) {
            return;
        }
        init();
        try {
            Stork.getInstance().getService(serviceName)
                    .registerInstance(instanceId, host, port)
                    .await().atMost(TIMEOUT);
            LOGGER.infof("Stork registered %s instance=%s at %s:%d", serviceName, instanceId, host, port);
        } catch (Exception e) {
            LOGGER.warnf("Stork register %s (%s) failed: %s", serviceName, instanceId, e.getMessage());
        }
    }

    @Override
    public void deregisterInstance(String serviceName, String instanceId, String host, int port) {
        if (!config.enabled() || !initialized || instanceId == null || instanceId.isEmpty()) {
            return;
        }
        try {
            Stork.getInstance().getService(serviceName)
                    .deregisterServiceInstance(instanceId, host, port)
                    .await().atMost(TIMEOUT);
            LOGGER.debugf("Stork deregistered %s instance=%s", serviceName, instanceId);
        } catch (Exception e) {
            LOGGER.warnf("Stork deregister %s (%s) failed: %s", serviceName, instanceId, e.getMessage());
        }
    }

    @Override
    public List<DiscoveredInstance> discover(String serviceName) {
        if (!config.enabled() || serviceName == null || serviceName.isEmpty()) {
            return Collections.emptyList();
        }
        init();
        try {
            List<ServiceInstance> instances = Stork.getInstance().getService(serviceName)
                    .getInstances().await().atMost(TIMEOUT);
            List<DiscoveredInstance> result = new ArrayList<>(instances.size());
            for (ServiceInstance si : instances) {
                result.add(new DiscoveredInstance(si.getHost(), si.getPort(), si.isSecure(), metadataMap(si)));
            }
            return result;
        } catch (Exception e) {
            LOGGER.warnf("Stork discover %s failed: %s", serviceName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            discover(config.serviceName());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        initialized = false;
    }

    /** 合并 labels + metadata（consul 的 service meta 可能走 labels 或 metadata）。 */
    private static Map<String, String> metadataMap(ServiceInstance si) {
        Map<String, String> merged = new HashMap<>();
        if (si.getLabels() != null) {
            merged.putAll(si.getLabels());
        }
        if (si.getMetadata() != null && si.getMetadata().asMap() != null) {
            merged.putAll(si.getMetadata().asMap());
        }
        return merged;
    }
}
