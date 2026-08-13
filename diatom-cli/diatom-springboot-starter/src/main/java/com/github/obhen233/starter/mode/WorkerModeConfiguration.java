package com.github.obhen233.starter.mode;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.config.SystemInfo;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.mcp.McpClientManager;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.tool.ToolRegistry;
import com.github.obhen233.starter.DiatomProperties;
import com.github.obhen233.starter.SharedAutoConfiguration;
import com.github.obhen233.starter.gateway.remote.ChatService;
import com.github.obhen233.starter.gateway.remote.LocalChatService;
import com.github.obhen233.starter.worker.DiatomWorkerProperties;
import com.github.obhen233.starter.worker.GatewayRegistrationService;
import com.github.obhen233.starter.worker.WorkerLoadReporter;
import com.github.obhen233.starter.worker.WorkerLoadReporterBinder;
import com.github.obhen233.starter.worker.WorkerLoadState;
import com.github.obhen233.starter.worker.WorkerRestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Worker 模式自动配置。
 *
 * <p>当 {@code diatam.mode=worker} 时激活。
 * 当前 Spring Boot 应用作为一个 Worker 节点运行，注册到远程 Gateway 并执行下发的任务。
 *
 * <p>通过 Spring MVC {@code @RestController} 暴露 {@code /worker/v1/chat} 端点接收任务，
 * 无需启动额外的 HTTP 服务器。
 *
 * <p>需要配置：
 * <pre>
 * diatam.mode=worker
 * diatam.worker.name=my-worker-1
 * </pre>
 * {@code diatam.worker.gateway-url} 可选：配置后直连 Gateway 注册；留空则仅通过
 * Spring Cloud 注册中心被 Gateway 发现。
 */
@Configuration
@AutoConfigureAfter(SharedAutoConfiguration.class)
@ConditionalOnProperty(prefix = ModeUtils.MODE_PROPERTY_PREFIX, name = "mode",
        havingValue = "worker", matchIfMissing = false)
@EnableConfigurationProperties(DiatomWorkerProperties.class)
public class WorkerModeConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(WorkerModeConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ReActAgent reActAgent(
            AiHttpClient httpClient,
            ModelAdapter adapter,
            ToolRegistry registry,
            SkillManager skillManager,
            SystemPromptManager promptManager,
            ProjectIndexer projectIndexer,
            McpClientManager mcpManager,
            AppConfig appConfig,
            SystemInfo systemInfo,
            DiatomProperties properties,
            java.util.Optional<com.github.obhen233.starter.AiConfigProvider> configProvider) {
        String model = appConfig.getModel();
        String apiUrl = appConfig.getApiUrl();

        if (configProvider.isPresent()) {
            com.github.obhen233.starter.AiConfigProvider provider = configProvider.get();
            model = provider.getModel();
            String endpoint = provider.getEndpoint();
            if (endpoint != null && !endpoint.isEmpty()) {
                String baseUrl = httpClient.getBaseUrl();
                if (baseUrl != null && !baseUrl.isEmpty()) {
                    apiUrl = baseUrl.endsWith("/") || endpoint.startsWith("/") ? baseUrl + endpoint : baseUrl + "/" + endpoint;
                }
            }
            logger.info("Worker using AiConfigProvider: model={}, apiUrl={}", model, apiUrl);
        }
        return new ReActAgent(httpClient, adapter, registry, skillManager,
                promptManager, projectIndexer, mcpManager,
                model, apiUrl, 3);
    }

    /**
     * ChatService for WORKER mode — runs the in-process {@link ReActAgent}.
     * Provides the IDE with a uniform ChatService so it stays agnostic to the
     * running mode.
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatService workerChatService(ReActAgent agent) {
        return new LocalChatService(agent);
    }

    /**
     * Worker 并发与负载状态：准入控制 + 真实负载上报 + Agent 串行锁。
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkerLoadState workerLoadState(DiatomWorkerProperties properties) {
        return new WorkerLoadState(properties.getMaxConcurrency());
    }

    /**
     * Gateway 注册服务：启动时注册、定时心跳、关闭时注销。
     */
    @Bean
    @ConditionalOnMissingBean
    public GatewayRegistrationService gatewayRegistrationService(
            DiatomWorkerProperties properties, Environment environment,
            WorkerLoadState loadState) {
        return new GatewayRegistrationService(properties, environment, loadState);
    }

    /**
     * {@link WorkerLoadReporter} SPI 生命周期管理（纯 SPI，不内置任何实现）。
     *
     * <p>由用户按注册中心实现 {@code WorkerLoadReporter} 并注册 Spring Bean，
     * starter 自动调用 {@code start(loadState)} / {@code stop()}。未注册实现则无动态负载上报。
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkerLoadReporterBinder workerLoadReporterBinder(
            ObjectProvider<WorkerLoadReporter> reporterProvider, WorkerLoadState loadState) {
        return new WorkerLoadReporterBinder(reporterProvider, loadState);
    }

    /**
     * Worker Web 端点：仅在 Spring Web 可用时加载。
     * 通过 {@code @RestController} 暴露 {@code /worker/v1/chat}。
     */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    static class WorkerWebConfig {

        @Bean
        @ConditionalOnMissingBean
        public WorkerRestController workerRestController(
                ReActAgent agent, GatewayRegistrationService registrationService,
                WorkerLoadState loadState) {
            logger.info("Worker REST controller enabled at /worker/v1/chat");
            return new WorkerRestController(agent, registrationService, loadState);
        }
    }
}
