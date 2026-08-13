package com.github.obhen233.quarkus.runtime.kernel;

import com.github.obhen233.adapter.spi.AgentAdapter;
import com.github.obhen233.spi.PluginClassLoader;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter 模式装配。
 *
 * <p>通过 {@code ServiceLoader<AgentAdapter>}（插件目录优先，其次 classpath）发现驱动，
 * 并调用 {@link AgentAdapter#init} / {@link AgentAdapter#setWorkspace}。找不到驱动时
 * 不阻断启动，{@code /worker/v1/chat} 返回 503 + 告警（Phase 2 {@code AdapterResource}）。
 */
public class AdapterBootstrap extends ModeBootstrap {

    private static final Logger LOGGER = Logger.getLogger(AdapterBootstrap.class);

    private AgentAdapter agentAdapter;
    private WorkerLoadState loadState;

    public AdapterBootstrap(DiatomKernel kernel) {
        super(kernel);
    }

    @Override
    public void start() {
        SharedComponents shared = kernel.getShared();
        try {
            this.loadState = new WorkerLoadState(1);
            this.agentAdapter = discoverAdapter();
            if (agentAdapter != null) {
                String workspace = kernel.config().adapter().workspaceDir()
                        .filter(s -> !s.isEmpty())
                        .orElse(shared.workspacePath);
                agentAdapter.setWorkspace(workspace);
                Map<String, String> adapterConfig = new HashMap<>();
                if (shared.appConfig.getApiKey() != null && !shared.appConfig.getApiKey().isEmpty()) {
                    adapterConfig.put("api.key", shared.appConfig.getApiKey());
                }
                agentAdapter.init(adapterConfig);
                LOGGER.infof("Adapter mode: discovered AgentAdapter type=%s",
                        agentAdapter.getAgentType());
            } else {
                LOGGER.warn("Adapter mode: no AgentAdapter found on classpath/plugins; "
                        + "/worker/v1/chat will return 503");
            }

            // Gateway 直连注册（tier=null，普通 worker）；gateway-url 未配置时跳过
            String workerId = kernel.config().worker().instanceId()
                    .filter(s -> !s.isEmpty())
                    .orElseGet(() -> defaultWorkerId("adapter-worker"));
            String externalHost = kernel.config().worker().externalHost()
                    .filter(s -> !s.isEmpty())
                    .orElseGet(this::defaultExternalHost);
            int externalPort = kernel.config().worker().externalPort()
                    .orElse(kernel.config().worker().port());
            String gatewayUrl = kernel.config().worker().gatewayUrl().orElse("");
            startWorkerRegistration(workerId, externalHost, externalPort, shared.appConfig.getModel(),
                    kernel.config().worker().group().orElse("default"), null, 1, gatewayUrl);
        } catch (Exception e) {
            LOGGER.errorf("Failed to assemble adapter: %s", e.getMessage());
        }
    }

    @Override
    public void stop() {
        stopRegistration();
        if (agentAdapter != null) {
            try {
                agentAdapter.shutdown();
            } catch (Exception e) {
                LOGGER.warnf("AgentAdapter shutdown failed: %s", e.getMessage());
            }
            agentAdapter = null;
        }
    }

    @Override
    public AgentAdapter agentAdapter() {
        return agentAdapter;
    }

    @Override
    public WorkerLoadState loadState() {
        return loadState;
    }

    private AgentAdapter discoverAdapter() {
        try {
            PluginClassLoader loader = PluginClassLoader.getInstance();
            if (loader != null && loader.hasPlugins()) {
                List<AgentAdapter> fromPlugins = loader.loadAll(AgentAdapter.class);
                if (!fromPlugins.isEmpty()) {
                    return fromPlugins.get(0);
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Plugin AgentAdapter discovery failed: %s", e.getMessage());
        }
        java.util.ServiceLoader<AgentAdapter> sl = java.util.ServiceLoader.load(AgentAdapter.class);
        for (AgentAdapter adapter : sl) {
            return adapter;
        }
        return null;
    }
}
