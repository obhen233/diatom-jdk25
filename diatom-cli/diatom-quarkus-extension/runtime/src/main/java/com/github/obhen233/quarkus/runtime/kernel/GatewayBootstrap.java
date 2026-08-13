package com.github.obhen233.quarkus.runtime.kernel;

import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.registry.FileSystemWorkerRegistry;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.quarkus.runtime.cloud.CloudDiscoveryConfig;
import com.github.obhen233.quarkus.runtime.cloud.StorkRegistryAdapter;
import com.github.obhen233.quarkus.runtime.cloud.StorkWorkerRegistry;
import com.github.obhen233.quarkus.runtime.queue.TaskQueueProcessor;
import com.github.obhen233.quarkus.runtime.queue.TaskResultStore;
import com.github.obhen233.quarkus.runtime.rest.QuarkusGatewayTransport;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.TaskQueueProvider;
import org.jboss.logging.Logger;

import java.util.Properties;

/**
 * Gateway 模式装配。
 *
 * <p>装配 WorkerRegistry / GatewayAgent / CapabilityRouter / TaskManager / ResourceLockManager，
 * 复用 core 逻辑层。HTTP 端点注册到 Quarkus 原生 web 容器（{@code /gateway/v1/*}，
 * 见 Phase 2 {@code GatewayResource}）；可选独立端口启动 core {@code GatewayHttpServer}
 * （{@code diatom.gateway.standalone-port} &gt; 0，双端口应用）。
 */
public class GatewayBootstrap extends ModeBootstrap {

    private static final Logger LOGGER = Logger.getLogger(GatewayBootstrap.class);

    private WorkerRegistry workerRegistry;
    private GatewayAgent gatewayAgent;
    private CapabilityRouter capabilityRouter;
    private TaskManager taskManager;
    private ResourceLockManager lockManager;

    /** Cloud 注册中心适配器（diatom.cloud.type=consul|eureka|stork 时装配，Phase 5）。 */
    private StorkRegistryAdapter cloudAdapter;

    private TaskQueueProvider taskQueueProvider;
    private TaskResultStore taskResultStore;
    private TaskQueueProcessor taskQueueProcessor;

    public GatewayBootstrap(DiatomKernel kernel) {
        super(kernel);
    }

    @Override
    public void start() {
        SharedComponents shared = kernel.getShared();
        try {
            CloudDiscoveryConfig cloudConfig = CloudDiscoveryConfig.from(
                    kernel.config().cloud(), shared.appConfig.getModel());
            if (cloudConfig.enabled()) {
                // Cloud 注册中心直读模式（Phase 5）：Stork 发现为 worker 唯一数据源
                this.cloudAdapter = new StorkRegistryAdapter(cloudConfig);
                this.cloudAdapter.init();
                this.workerRegistry = new StorkWorkerRegistry(cloudAdapter, cloudConfig);
                registerGatewaySelf(cloudConfig);
                LOGGER.infof("Gateway mode: Stork-backed WorkerRegistry (cloud.type=%s)", cloudConfig.type());
            } else {
                this.workerRegistry = new FileSystemWorkerRegistry();
            }
            this.gatewayAgent = new GatewayAgent(shared.aiHttpClient, shared.modelAdapter,
                    shared.appConfig.getModel(), shared.appConfig.getApiUrl(), workerRegistry);
            this.capabilityRouter = new CapabilityRouter(workerRegistry);
            this.taskManager = new TaskManager();
            this.lockManager = new ResourceLockManager();
            if (shared.databaseManager != null) {
                taskManager.setDatabase(shared.databaseManager);
                taskManager.loadFromDatabase();
            }
            LOGGER.info("Gateway mode: WorkerRegistry/GatewayAgent/CapabilityRouter/"
                    + "TaskManager/ResourceLockManager assembled");

            // 队列模式（diatom.gateway.queue-enabled=true）：后台消费者随内核启动
            if (kernel.config().gateway().queueEnabled()) {
                initGatewayQueue();
            }
        } catch (Exception e) {
            LOGGER.errorf("Failed to assemble gateway components: %s", e.getMessage());
        }
    }

    /**
     * 将当前 Gateway 自注册到注册中心（服务名 = {@code cloud.gatewayServiceFilter}）。
     * 失败仅告警（降级），不影响网关启动。
     */
    private void registerGatewaySelf(CloudDiscoveryConfig cloudConfig) {
        try {
            String instanceId = kernel.config().gateway().instanceId()
                    .filter(s -> !s.isEmpty())
                    .orElse("diatom-gateway");
            String host = defaultExternalHost();
            int port = kernel.config().gateway().port();
            cloudAdapter.registerInstance(cloudConfig.gatewayServiceFilter(), instanceId, host, port);
        } catch (Exception e) {
            LOGGER.warnf("Gateway cloud self-registration failed: %s", e.getMessage());
        }
    }

    /**
     * 初始化 Gateway 任务队列（202 异步模式）：SPI 优先，缺省用 core InMemoryTaskQueue。
     * 幂等 —— 已初始化时直接返回。
     */
    public synchronized void initGatewayQueue() {
        if (taskQueueProvider != null) {
            return;
        }
        try {
            TaskQueueProvider provider = SpiLoader.getFirst(TaskQueueProvider.class,
                    new com.github.obhen233.core.gateway.queue.InMemoryTaskQueue());
            provider.init(new Properties());
            TaskResultStore store = new TaskResultStore(300);
            TaskQueueProcessor processor = new TaskQueueProcessor(provider, gatewayAgent,
                    capabilityRouter, new QuarkusGatewayTransport(workerRegistry),
                    taskManager, store, 1);
            processor.start();
            this.taskQueueProvider = provider;
            this.taskResultStore = store;
            this.taskQueueProcessor = processor;
            LOGGER.infof("Gateway queue mode enabled with provider=%s", provider.getName());
        } catch (Exception e) {
            LOGGER.errorf("Failed to init gateway queue: %s", e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (taskQueueProcessor != null) {
            try {
                taskQueueProcessor.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("TaskQueueProcessor shutdown failed: %s", e.getMessage());
            }
            taskQueueProcessor = null;
        }
        if (taskResultStore != null) {
            try {
                taskResultStore.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("TaskResultStore shutdown failed: %s", e.getMessage());
            }
            taskResultStore = null;
        }
        if (taskQueueProvider != null) {
            try {
                taskQueueProvider.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("TaskQueueProvider shutdown failed: %s", e.getMessage());
            }
            taskQueueProvider = null;
        }
        if (capabilityRouter != null) {
            try {
                capabilityRouter.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("CapabilityRouter shutdown failed: %s", e.getMessage());
            }
        }
        if (lockManager != null) {
            try {
                lockManager.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("ResourceLockManager shutdown failed: %s", e.getMessage());
            }
        }
        if (workerRegistry != null) {
            try {
                workerRegistry.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("WorkerRegistry shutdown failed: %s", e.getMessage());
            }
        }
        if (cloudAdapter != null) {
            try {
                cloudAdapter.close();
            } catch (Exception e) {
                LOGGER.warnf("Cloud registry adapter close failed: %s", e.getMessage());
            }
            cloudAdapter = null;
        }
    }

    @Override
    public WorkerRegistry workerRegistry() {
        return workerRegistry;
    }

    @Override
    public GatewayAgent gatewayAgent() {
        return gatewayAgent;
    }

    @Override
    public CapabilityRouter capabilityRouter() {
        return capabilityRouter;
    }

    @Override
    public TaskManager taskManager() {
        return taskManager;
    }

    @Override
    public ResourceLockManager lockManager() {
        return lockManager;
    }

    @Override
    public TaskQueueProvider taskQueueProvider() {
        return taskQueueProvider;
    }

    @Override
    public TaskResultStore taskResultStore() {
        return taskResultStore;
    }

    @Override
    public TaskQueueProcessor taskQueueProcessor() {
        return taskQueueProcessor;
    }
}
