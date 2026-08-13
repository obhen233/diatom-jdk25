package com.github.obhen233.quarkus.runtime.components;

import com.github.obhen233.adapter.spi.AgentAdapter;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.database.ContextCacheManager;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.HistoryManager;
import com.github.obhen233.core.database.TaskCheckpointManager;
import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.http.GatewayHttpServer;
import com.github.obhen233.core.gateway.http.WorkerHttpServer;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.quarkus.runtime.kernel.DiatomKernel;
import com.github.obhen233.quarkus.runtime.kernel.WorkerLoadState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 运行时门面。
 *
 * <p>可空/模式服务不单独 {@code @Produces}（normal-scoped 生产者返回 null 不安全），
 * 统一经此门面获取：null 表示该模式未装配或数据库降级。
 */
@ApplicationScoped
public class DiatomRuntimeContext {

    private final DiatomComponents components;

    @Inject
    public DiatomRuntimeContext(DiatomComponents components) {
        this.components = components;
    }

    public DiatomKernel kernel() {
        return components.kernel();
    }

    // ===== 可空共享组件 =====

    public DatabaseManager databaseManager() {
        return kernel().getShared().databaseManager;
    }

    public ConfigManager configManager() {
        return kernel().getShared().configManager;
    }

    public HistoryManager historyManager() {
        return kernel().getShared().historyManager;
    }

    public TaskCheckpointManager taskCheckpointManager() {
        return kernel().getShared().taskCheckpointManager;
    }

    public ContextCacheManager contextCacheManager() {
        return kernel().getShared().contextCacheManager;
    }

    // ===== 模式服务 =====

    public ReActAgent reActAgent() {
        return kernel().reActAgent();
    }

    /** Core 命令注册中心（所有模式构建；供 {@code /worker/v1/command} 执行入口使用）。 */
    public com.github.obhen233.spi.CoreCommandRegistry coreCommandRegistry() {
        return kernel().getShared().coreCommandRegistry;
    }

    public AgentAdapter agentAdapter() {
        return kernel().agentAdapter();
    }

    public WorkerLoadState loadState() {
        return kernel().loadState();
    }

    public WorkerRegistry workerRegistry() {
        return kernel().workerRegistry();
    }

    public GatewayAgent gatewayAgent() {
        return kernel().gatewayAgent();
    }

    public CapabilityRouter capabilityRouter() {
        return kernel().capabilityRouter();
    }

    public TaskManager taskManager() {
        return kernel().taskManager();
    }

    public ResourceLockManager lockManager() {
        return kernel().lockManager();
    }

    public GatewayHttpServer gatewayHttpServer() {
        return kernel().gatewayHttpServer();
    }

    public WorkerHttpServer workerHttpServer() {
        return kernel().workerHttpServer();
    }

    /** Gateway 直连注册服务（worker / adapter / child 模式；health 端点读取 workerId/外部地址）。 */
    public com.github.obhen233.quarkus.runtime.rest.QuarkusRegistrationService registrationService() {
        return kernel().registrationService();
    }
}
