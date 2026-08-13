package com.github.obhen233.quarkus.runtime.kernel;

import com.github.obhen233.adapter.spi.AgentAdapter;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.http.GatewayHttpServer;
import com.github.obhen233.core.gateway.http.WorkerHttpServer;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.quarkus.runtime.queue.TaskQueueProcessor;
import com.github.obhen233.quarkus.runtime.queue.TaskResultStore;
import com.github.obhen233.quarkus.runtime.rest.QuarkusRegistrationService;
import com.github.obhen233.spi.TaskQueueProvider;
import com.github.obhen233.util.NetworkUtils;

/**
 * 各运行模式 Bootstrap 的抽象基类。
 *
 * <p>每个模式负责按需装配 core 组件并管理其生命周期（start/stop）。
 * 模式特有组件通过默认返回 null 的 getter 暴露，kernel 对外统一访问。
 */
public abstract class ModeBootstrap {

    protected static final org.jboss.logging.Logger LOGGER =
            org.jboss.logging.Logger.getLogger(ModeBootstrap.class);

    protected final DiatomKernel kernel;

    /** Gateway 直连注册服务（worker / adapter / child 模式装配）。 */
    protected QuarkusRegistrationService registrationService;

    protected ModeBootstrap(DiatomKernel kernel) {
        this.kernel = kernel;
    }

    /** 启动模式装配。 */
    public abstract void start();

    /** 逆序关停模式组件。 */
    public abstract void stop();

    // ===== 模式特有组件 getter（未装配时返回 null）=====

    public ReActAgent reActAgent() {
        return null;
    }

    public WorkerRegistry workerRegistry() {
        return null;
    }

    public GatewayAgent gatewayAgent() {
        return null;
    }

    public CapabilityRouter capabilityRouter() {
        return null;
    }

    public TaskManager taskManager() {
        return null;
    }

    public ResourceLockManager lockManager() {
        return null;
    }

    public GatewayHttpServer gatewayHttpServer() {
        return null;
    }

    public WorkerHttpServer workerHttpServer() {
        return null;
    }

    public WorkerLoadState loadState() {
        return null;
    }

    public AgentAdapter agentAdapter() {
        return null;
    }

    public QuarkusRegistrationService registrationService() {
        return registrationService;
    }

    /** 独立端口模式下的实际监听端口（供测试）。 */
    public int serverPort() {
        return 0;
    }

    // ===== Gateway 队列模式组件（仅 gateway/child 装配）=====

    public TaskQueueProvider taskQueueProvider() {
        return null;
    }

    public TaskResultStore taskResultStore() {
        return null;
    }

    public TaskQueueProcessor taskQueueProcessor() {
        return null;
    }

    // ===== 共享装配 helper =====

    /**
     * 构建 ReActAgent（mirror starter {@code DiatomAutoConfiguration} 的构造，maxRetry=3）。
     * 任一组件失败不阻断启动 —— 由调用方 catch 降级。
     */
    protected ReActAgent buildReActAgent(String model, String apiUrl) {
        SharedComponents shared = kernel.getShared();
        ReActAgent agent = new ReActAgent(shared.aiHttpClient, shared.modelAdapter,
                shared.toolRegistry, shared.skillManager, shared.systemPromptManager,
                shared.projectIndexer, shared.mcpClientManager, model, apiUrl, 3);
        if (shared.taskCheckpointManager != null) {
            agent.setCheckpointManager(shared.taskCheckpointManager);
        }
        if (shared.commandPermissionEngine != null) {
            agent.setCommandPermissionEngine(shared.commandPermissionEngine);
        }
        shared.initCoreCommandAgent(agent);
        return agent;
    }

    /**
     * 启动 Gateway 直连注册（worker / adapter / child 模式共用）。
     * {@code gatewayUrl} 为空时跳过直连注册。
     */
    protected void startWorkerRegistration(String workerId, String externalHost, int externalPort,
                                           String model, String group, String tier,
                                           int maxConcurrency, String gatewayUrl) {
        this.registrationService = new QuarkusRegistrationService(workerId, externalHost, externalPort,
                model, group, tier, maxConcurrency, gatewayUrl, loadState());
        this.registrationService.start();
    }

    /** 关停并注销注册服务。 */
    protected void stopRegistration() {
        if (registrationService != null) {
            try {
                registrationService.stop();
            } catch (Exception e) {
                LOGGER.warnf("Registration service stop failed: %s", e.getMessage());
            }
            registrationService = null;
        }
    }

    /** 生成默认 workerId（未显式配置 instance-id 时）。 */
    protected String defaultWorkerId(String prefix) {
        return prefix + "-" + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
    }

    /** 本机外部可达 IP（未显式配置 external-host 时）。 */
    protected String defaultExternalHost() {
        try {
            return NetworkUtils.getRealLocalIP();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
