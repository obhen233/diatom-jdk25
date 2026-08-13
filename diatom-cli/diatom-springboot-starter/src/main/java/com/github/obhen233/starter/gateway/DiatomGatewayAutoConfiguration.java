package com.github.obhen233.starter.gateway;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.cluster.ClusterCoordinatorLoader;
import com.github.obhen233.core.gateway.registry.ClusteredWorkerRegistry;
import com.github.obhen233.core.gateway.registry.FileSystemWorkerRegistry;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.spi.ClusterCoordinator;
import com.github.obhen233.spi.TaskQueueProvider;
import com.github.obhen233.util.NetworkUtils;
import com.github.obhen233.starter.DiatomProperties;
import com.github.obhen233.starter.SharedAutoConfiguration;
import com.github.obhen233.starter.gateway.cluster.NoopClusterCoordinator;
import com.github.obhen233.starter.gateway.cloud.DiatomCloudDiscoveryProperties;
import com.github.obhen233.starter.gateway.cloud.DiatomRegistryProperties;
import com.github.obhen233.starter.gateway.cloud.DiscoveryClientWorkerRegistry;
import com.github.obhen233.starter.gateway.cloud.NoopRegistryService;
import com.github.obhen233.starter.gateway.cloud.RegistryService;
import com.github.obhen233.starter.gateway.flow.AdmissionControlFilter;
import com.github.obhen233.starter.gateway.flow.DiatomGatewayFlowProperties;
import com.github.obhen233.starter.gateway.queue.DiatomGatewayQueueProperties;
import com.github.obhen233.starter.gateway.queue.InMemoryTaskQueueProvider;
import com.github.obhen233.starter.gateway.queue.TaskQueueProcessor;
import com.github.obhen233.starter.gateway.queue.TaskResultStore;
import com.github.obhen233.starter.gateway.remote.ChatService;
import com.github.obhen233.starter.gateway.remote.GatewayChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot 自动配置：Gateway 模式
 *
 * <p>启用条件: {@code diatam.mode=gateway} 或 {@code gateay:*}</p>
 *
 * <p>通过 Spring MVC {@code @RestController} 暴露 Gateway API，复用 Spring Boot 内嵌 Web 容器。
 * Gateway 端点绑定到 {@code server.port} 或 {@code management.server.port}，路径前缀为
 * {@code /gateway/v1/*}。
 *
 * <p>职责：
 * <ol>
 *   <li>注册 DiatomGatewayService、DiatomGatewayController 等 Bean</li>
 *   <li>通过 Spring MVC 提供 Gateway REST API</li>
 *   <li>创建 ResourceLockManager、CapabilityRouter 等核心组件</li>
 *   <li>Gateway 自注册（注册中心模式）</li>
 * </ol>
 */
@Configuration
@AutoConfigureAfter(SharedAutoConfiguration.class)
@Conditional(GatewayModeCondition.class)
@EnableConfigurationProperties({DiatomGatewayProperties.class, DiatomProperties.class,
        DiatomRegistryProperties.class, DiatomGatewayQueueProperties.class,
        DiatomGatewayFlowProperties.class, DiatomCloudDiscoveryProperties.class,
        DiatomGatewayHaProperties.class})
public class DiatomGatewayAutoConfiguration implements DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(DiatomGatewayAutoConfiguration.class);

    private CapabilityRouter capabilityRouter;

    @Autowired
    private RegistryService registryService;

    @Autowired
    private DiatomRegistryProperties registryProperties;

    @Bean
    @ConditionalOnMissingBean
    public TaskManager taskManager() {
        return new TaskManager();
    }

    /**
     * WorkerRegistry 装配点。
     *
     * <p>先构建基础注册表：当 Spring Cloud {@link DiscoveryClient} 可用且 cloud-discovery
     * 启用时返回 {@link DiscoveryClientWorkerRegistry}（注册中心直读，多 gateway 数据一致）；
     * 否则回退 {@link FileSystemWorkerRegistry}（本地文件注册表）。
     *
     * <p>当 {@code diatom.gateway.ha.enabled=true} 且存在 {@link ClusterCoordinator} Bean 时，
     * 额外包装 {@link ClusteredWorkerRegistry}（HA 集群模式：register/heartbeat/deregister
     * 同步到集群，availableWorkers 合并本地+远程 worker）。Hazelcast 初始化失败时协调器为
     * {@link NoopClusterCoordinator}（isActive=false），注册表自动降级纯本地。
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkerRegistry workerRegistry(
            ObjectProvider<DiscoveryClient> discoveryClientProvider,
            ObjectProvider<DiatomCloudDiscoveryProperties> cloudPropsProvider,
            ObjectProvider<DiatomGatewayHaProperties> haPropsProvider,
            ObjectProvider<ClusterCoordinator> clusterCoordinatorProvider,
            Environment environment) {
        WorkerRegistry base = buildBaseRegistry(discoveryClientProvider, cloudPropsProvider);

        DiatomGatewayHaProperties haProps = haPropsProvider.getIfAvailable();
        if (haProps != null && haProps.isEnabled()) {
            ClusterCoordinator clusterCoordinator = clusterCoordinatorProvider.getIfAvailable();
            if (clusterCoordinator != null) {
                String gatewayId = resolveGatewayId(haProps, environment);
                logger.info("Gateway HA enabled, wrapping registry with ClusteredWorkerRegistry (gatewayId={})",
                        gatewayId);
                return new ClusteredWorkerRegistry(base, clusterCoordinator, gatewayId, true);
            }
        }
        return base;
    }

    /**
     * Gateway HA 集群协调器 Bean（默认 Hazelcast）。
     *
     * <p>仅在 {@code diatom.gateway.ha.enabled=true} 时创建。使用 core 的
     * {@link ClusterCoordinatorLoader} 加载（插件 SPI → classpath ServiceLoader → Hazelcast 默认）。
     * Hazelcast 配置复用 standalone 核心的 {@code cluster.hazelcast.*} 键。
     * 加载失败时返回 {@link NoopClusterCoordinator}，注册表自动降级纯本地。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "diatom.gateway.ha", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ClusterCoordinator gatewayClusterCoordinator(Environment environment) {
        Map<String, String> clusterConfig = new HashMap<>();
        clusterConfig.put("cluster.enabled", "true");
        putIfPresent(clusterConfig, environment, "cluster.hazelcast.port", "5701");
        putIfPresent(clusterConfig, environment, "cluster.hazelcast.tcpip.enabled", "false");
        putIfPresent(clusterConfig, environment, "cluster.hazelcast.tcpip.members", "");
        putIfPresent(clusterConfig, environment, "cluster.hazelcast.multicast.enabled", "true");
        String instanceName = environment.getProperty("cluster.hazelcast.instance.name");
        if (instanceName != null && !instanceName.isEmpty()) {
            clusterConfig.put("cluster.hazelcast.instance.name", instanceName);
        }

        ClusterCoordinator coordinator;
        try {
            coordinator = ClusterCoordinatorLoader.load(clusterConfig);
        } catch (Exception e) {
            logger.warn("Failed to load ClusterCoordinator: {}", e.getMessage());
            coordinator = null;
        }
        if (coordinator == null) {
            logger.warn("Gateway HA enabled but no ClusterCoordinator available; "
                    + "using NoopClusterCoordinator (local-only registry)");
            return new NoopClusterCoordinator();
        }
        logger.info("Gateway HA cluster coordinator: {} ({})",
                coordinator.getName(), coordinator.getClass().getName());
        return coordinator;
    }

    private WorkerRegistry buildBaseRegistry(
            ObjectProvider<DiscoveryClient> discoveryClientProvider,
            ObjectProvider<DiatomCloudDiscoveryProperties> cloudPropsProvider) {
        DiatomCloudDiscoveryProperties cloudProps = cloudPropsProvider.getIfAvailable();
        if (cloudProps != null && cloudProps.isEnabled()) {
            try {
                DiscoveryClient discoveryClient = discoveryClientProvider.getIfAvailable();
                if (discoveryClient != null) {
                    logger.info("Gateway using DiscoveryClient-based WorkerRegistry (direct registry read): {}",
                            discoveryClient.getClass().getName());
                    return new DiscoveryClientWorkerRegistry(discoveryClient, cloudProps);
                }
            } catch (LinkageError | RuntimeException e) {
                // spring-cloud-commons 不在 classpath 或 DiscoveryClient 不可用时安全回退
                logger.debug("DiscoveryClient unavailable ({}), falling back to FileSystemWorkerRegistry",
                        e.getMessage());
            }
        }
        return new FileSystemWorkerRegistry();
    }

    private void putIfPresent(Map<String, String> config, Environment environment,
                              String key, String defaultValue) {
        String value = environment.getProperty(key);
        config.put(key, value != null && !value.isEmpty() ? value : defaultValue);
    }

    private String resolveGatewayId(DiatomGatewayHaProperties haProps, Environment environment) {
        if (haProps.getGatewayId() != null && !haProps.getGatewayId().isEmpty()) {
            return haProps.getGatewayId();
        }
        String port = environment.getProperty("server.port", "8080");
        // 使用 core 的 NetworkUtils 处理多网卡/VPN，避免 getLocalHost() 返回错误 IP
        return NetworkUtils.getRealLocalIP() + ":" + port;
    }

    @Bean
    @ConditionalOnMissingBean
    public DiatomGatewayService diatomGatewayService(
            TaskManager taskManager, WorkerRegistry registry) {
        return new DiatomGatewayService(taskManager, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringGatewayTransport springGatewayTransport(WorkerRegistry registry) {
        return new SpringGatewayTransport(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringRegistryAdapter springRegistryAdapter(WorkerRegistry registry) {
        return new SpringRegistryAdapter(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResourceLockManager resourceLockManager() {
        return new ResourceLockManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public CapabilityRouter capabilityRouter(WorkerRegistry registry) {
        this.capabilityRouter = new CapabilityRouter(registry);
        return this.capabilityRouter;
    }

    @Bean
    @ConditionalOnMissingBean
    public GatewayAgent gatewayAgent(AiHttpClient httpClient, ModelAdapter adapter,
                                     AppConfig appConfig, WorkerRegistry registry) {
        return new GatewayAgent(httpClient, adapter, appConfig.getModel(),
                appConfig.getApiUrl(), registry);
    }

    /**
     * ChatService for GATEWAY mode — routes IDE chat through the embedded Gateway
     * (LLM classify → route to worker → forward → return). Provides the IDE with
     * a uniform ChatService so it stays agnostic to the running mode.
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatService gatewayChatService(GatewayAgent gatewayAgent,
                                          CapabilityRouter capabilityRouter,
                                          SpringGatewayTransport transport) {
        return new GatewayChatService(gatewayAgent, capabilityRouter, transport);
    }

    // ========== Task Queue 支持 ==========

    @Bean
    @ConditionalOnMissingBean
    public TaskResultStore taskResultStore(DiatomGatewayQueueProperties queueProps) {
        return new TaskResultStore(queueProps.getResultTtlSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskQueueProvider taskQueueProvider() {
        return new InMemoryTaskQueueProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "diatom.gateway.queue", name = "enabled", havingValue = "true")
    public TaskQueueProcessor taskQueueProcessor(
            TaskQueueProvider taskQueueProvider,
            GatewayAgent gatewayAgent,
            CapabilityRouter capabilityRouter,
            SpringGatewayTransport transport,
            TaskManager taskManager,
            TaskResultStore taskResultStore,
            DiatomGatewayQueueProperties queueProps) {
        TaskQueueProcessor processor = new TaskQueueProcessor(
                taskQueueProvider, gatewayAgent, capabilityRouter,
                transport, taskManager, taskResultStore,
                queueProps.getWorkers());
        processor.start(); // 启动后台消费者线程
        return processor;
    }

    // ========== 接入层限流 ==========

    @Bean
    @ConditionalOnProperty(prefix = "diatom.gateway.flow", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<AdmissionControlFilter> admissionControlFilter(
            DiatomGatewayFlowProperties flowProps,
            WorkerRegistry workerRegistry) {
        AdmissionControlFilter filter = new AdmissionControlFilter(
                flowProps.getMaxConcurrent(), workerRegistry);
        FilterRegistrationBean<AdmissionControlFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(filter);
        reg.addUrlPatterns("/gateway/v1/*");
        reg.setOrder(0); // 最高优先级
        reg.setName("admissionControlFilter");
        logger.info("Admission control enabled: maxConcurrent={}, path=/gateway/v1/*",
                flowProps.getMaxConcurrent());
        return reg;
    }

    /**
     * Gateway 控制器：仅在 Spring Web 可用时加载。
     * 共享端口模式（diatom.server.port 未配置）下直接注册到主容器；
     * 端口隔离模式下由 DiatomWebChildConfig 的 @ComponentScan 接管。
     */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnProperty(name = "diatom.server.port", matchIfMissing = true)
    static class GatewayControllerConfig {

        @Autowired(required = false)
        private TaskQueueProvider taskQueueProvider;

        @Autowired(required = false)
        private TaskResultStore taskResultStore;

        @Autowired(required = false)
        private DiatomGatewayQueueProperties queueProps;

        @Bean
        @ConditionalOnMissingBean
        public DiatomGatewayController diatomGatewayController(
                DiatomGatewayService service,
                SpringGatewayTransport transport,
                ResourceLockManager lockManager,
                GatewayAgent gatewayAgent,
                CapabilityRouter capabilityRouter,
                RegistryService registryService,
                TaskManager taskManager) {
            // Queue dependencies: use defaults when not explicitly configured
            TaskQueueProvider queueProvider = taskQueueProvider != null ? taskQueueProvider : new InMemoryTaskQueueProvider();
            TaskResultStore resultStore = taskResultStore != null ? taskResultStore : new TaskResultStore(300);
            boolean queueEnabled = queueProps != null && queueProps.isEnabled();
            return new DiatomGatewayController(service, transport, lockManager,
                    gatewayAgent, capabilityRouter, registryService, taskManager,
                    queueProvider, resultStore, queueEnabled);
        }
    }

    /**
     * 初始化：设置网关属性并注册到注册中心。
     */
    @PostConstruct
    public void init() {
        registerGatewayWithRegistry();
    }

    /**
     * 将当前 Gateway 注册到注册中心（仅非 Noop 模式）。
     */
    private void registerGatewayWithRegistry() {
        if (registryService instanceof NoopRegistryService) {
            logger.debug("NoopRegistryService active, skipping registry registration");
            return;
        }
        try {
            String serviceName = registryProperties.getServiceName();
            if (serviceName == null || serviceName.isEmpty()) {
                serviceName = "diatom-gateway";
            }
            // 使用 core 的 NetworkUtils 处理多网卡/VPN，避免 getLocalHost() 返回错误 IP
            String host = NetworkUtils.getRealLocalIP();
            int port = 8080;
            if (registryProperties.getServerAddr() != null && !registryProperties.getServerAddr().isEmpty()) {
                String[] parts = registryProperties.getServerAddr().split(":");
                if (parts.length > 1) {
                    try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
                }
            }
            String scheme = "http";

            Map<String, String> metadata = new HashMap<>();
            metadata.put("scheme", scheme);
            metadata.put("type", "gateway");

            registryService.registerGateway(serviceName, host, port, metadata);
            logger.info("Gateway registered with registry: {} as {}:{}", serviceName, host, port);
        } catch (Exception e) {
            logger.warn("Failed to register gateway with registry: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        if (registryService != null) {
            try {
                registryService.deregisterGateway();
                registryService.destroy();
                logger.info("Registry service shut down");
            } catch (Exception e) {
                logger.warn("Error shutting down registry service: {}", e.getMessage());
            }
        }
        if (capabilityRouter != null) {
            capabilityRouter.shutdown();
            logger.info("CapabilityRouter shut down");
        }
    }
}
