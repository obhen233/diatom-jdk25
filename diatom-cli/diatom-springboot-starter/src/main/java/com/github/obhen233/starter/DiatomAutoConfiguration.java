package com.github.obhen233.starter;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.mcp.McpClientManager;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.core.tool.ToolRegistry;
import com.github.obhen233.starter.gateway.remote.ChatService;
import com.github.obhen233.starter.gateway.remote.LocalChatService;
import com.github.obhen233.starter.mode.ModeUtils;
import com.github.obhen233.config.SystemInfo;
import com.github.obhen233.core.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Standard 模式自动配置。
 *
 * <p>当 {@code diatam.mode=standard}（默认）时激活。
 * 在 {@link SharedAutoConfiguration} 的共享 Bean 基础上，创建 ReActAgent 和 LocalChatService。
 * 不暴露网络端口，作为单机 Agent 运行。</p>
 */
@Configuration
@AutoConfigureAfter(SharedAutoConfiguration.class)
@ConditionalOnProperty(prefix = ModeUtils.MODE_PROPERTY_PREFIX, name = "mode",
        havingValue = "standard", matchIfMissing = true)
@EnableConfigurationProperties(DiatomProperties.class)
public class DiatomAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(DiatomAutoConfiguration.class);

    // ========== Agent ==========

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
            java.util.Optional<AiConfigProvider> configProvider) {
        String model = appConfig.getModel();
        String apiUrl = appConfig.getApiUrl();

        if (configProvider.isPresent()) {
            AiConfigProvider provider = configProvider.get();
            model = provider.getModel();
            String endpoint = provider.getEndpoint();
            if (endpoint != null && !endpoint.isEmpty()) {
                String baseUrl = httpClient.getBaseUrl();
                if (baseUrl != null && !baseUrl.isEmpty()) {
                    apiUrl = baseUrl.endsWith("/") || endpoint.startsWith("/") ? baseUrl + endpoint : baseUrl + "/" + endpoint;
                }
            }
            logger.info("Using AiConfigProvider: model={}, apiUrl={}", model, apiUrl);
        } else if (properties.getIde().isEnabled()) {
            model = properties.getIde().getModel();
            String ideApiUrl = properties.getIde().getApiUrl();
            if (ideApiUrl != null && !ideApiUrl.isEmpty()) {
                apiUrl = ideApiUrl;
            }
            logger.info("IDE mode (no provider): using model={}, apiUrl={}", model, apiUrl);
        }
        return new ReActAgent(httpClient, adapter, registry, skillManager,
                promptManager, projectIndexer, mcpManager,
                model, apiUrl, 3);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatService localChatService(ReActAgent reActAgent) {
        return new LocalChatService(reActAgent);
    }
}
