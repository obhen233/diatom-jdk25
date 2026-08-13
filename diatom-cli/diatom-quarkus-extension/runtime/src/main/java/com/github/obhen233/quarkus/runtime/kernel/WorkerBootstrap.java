package com.github.obhen233.quarkus.runtime.kernel;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.quarkus.runtime.cloud.CloudDiscoveryConfig;
import com.github.obhen233.quarkus.runtime.cloud.StorkRegistryAdapter;
import org.jboss.logging.Logger;

/**
 * Worker 模式装配。
 *
 * <p>装配 ReActAgent + {@link WorkerLoadState}（准入控制）。HTTP 端点注册到 Quarkus
 * 原生 web 容器（{@code /worker/v1/*}，见 Phase 2 {@code WorkerResource}）；
 * 可选独立端口启动 core {@code WorkerHttpServer}（{@code diatom.worker.standalone-port} &gt; 0）
 * 与 Gateway 注册/心跳（Phase 2）在本类完成。
 */
public class WorkerBootstrap extends ModeBootstrap {

    private static final Logger LOGGER = Logger.getLogger(WorkerBootstrap.class);

    private ReActAgent reActAgent;
    private WorkerLoadState loadState;

    /** Cloud 注册中心自注册（diatom.cloud.type=consul|eureka|stork 时，Phase 5）。 */
    private StorkRegistryAdapter cloudAdapter;
    private String cloudServiceName;
    private String cloudInstanceId;
    private String cloudHost;
    private int cloudPort;

    public WorkerBootstrap(DiatomKernel kernel) {
        super(kernel);
    }

    @Override
    public void start() {
        SharedComponents shared = kernel.getShared();
        try {
            String model = kernel.config().worker().model().orElse(shared.appConfig.getModel());
            this.reActAgent = buildReActAgent(model, shared.appConfig.getApiUrl());
            this.loadState = new WorkerLoadState(kernel.config().worker().maxConcurrency());
            LOGGER.infof("Worker mode: ReActAgent assembled (model=%s, maxConcurrency=%d)",
                    model, kernel.config().worker().maxConcurrency());

            // Gateway 直连注册（tier=null，普通 worker）；gateway-url 未配置时跳过
            String workerId = kernel.config().worker().instanceId()
                    .filter(s -> !s.isEmpty())
                    .orElseGet(() -> defaultWorkerId("quarkus-worker"));
            String externalHost = kernel.config().worker().externalHost()
                    .filter(s -> !s.isEmpty())
                    .orElseGet(this::defaultExternalHost);
            int externalPort = kernel.config().worker().externalPort()
                    .orElse(kernel.config().worker().port());
            String gatewayUrl = kernel.config().worker().gatewayUrl().orElse("");
            startWorkerRegistration(workerId, externalHost, externalPort, model,
                    kernel.config().worker().group().orElse("default"), null,
                    kernel.config().worker().maxConcurrency(), gatewayUrl);

            // Cloud 注册中心自注册（worker 经 Stork 上报，gateway 可从注册中心发现）
            startCloudRegistration(cloudConfig(model), workerId, externalHost, externalPort);
        } catch (Exception e) {
            LOGGER.errorf("Failed to assemble worker ReActAgent: %s", e.getMessage());
        }
    }

    @Override
    public void stop() {
        stopCloudRegistration();
        stopRegistration();
        if (reActAgent != null) {
            try {
                reActAgent.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("ReActAgent shutdown failed: %s", e.getMessage());
            }
            reActAgent = null;
        }
    }

    @Override
    public ReActAgent reActAgent() {
        return reActAgent;
    }

    @Override
    public WorkerLoadState loadState() {
        return loadState;
    }

    // ===== Cloud 注册中心自注册（Phase 5）=====

    private CloudDiscoveryConfig cloudConfig(String defaultModel) {
        return CloudDiscoveryConfig.from(kernel.config().cloud(), defaultModel);
    }

    /** Cloud 启用时经 Stork 自注册 worker 实例；失败仅告警（降级，不影响 worker 启动）。 */
    private void startCloudRegistration(CloudDiscoveryConfig cloudConfig, String workerId,
                                        String externalHost, int externalPort) {
        if (!cloudConfig.enabled()) {
            return;
        }
        try {
            this.cloudAdapter = new StorkRegistryAdapter(cloudConfig);
            this.cloudAdapter.init();
            this.cloudServiceName = cloudConfig.serviceName();
            this.cloudInstanceId = workerId;
            this.cloudHost = externalHost;
            this.cloudPort = externalPort;
            this.cloudAdapter.registerInstance(cloudConfig.serviceName(), workerId, externalHost, externalPort);
        } catch (Exception e) {
            LOGGER.warnf("Worker cloud self-registration failed: %s", e.getMessage());
            this.cloudAdapter = null;
        }
    }

    /** 关停：注销 Stork 实例并释放适配器。 */
    private void stopCloudRegistration() {
        if (cloudAdapter != null) {
            try {
                cloudAdapter.deregisterInstance(cloudServiceName, cloudInstanceId, cloudHost, cloudPort);
            } catch (Exception e) {
                LOGGER.warnf("Worker cloud deregister failed: %s", e.getMessage());
            }
            try {
                cloudAdapter.close();
            } catch (Exception e) {
                LOGGER.warnf("Worker cloud adapter close failed: %s", e.getMessage());
            }
            cloudAdapter = null;
        }
    }
}
