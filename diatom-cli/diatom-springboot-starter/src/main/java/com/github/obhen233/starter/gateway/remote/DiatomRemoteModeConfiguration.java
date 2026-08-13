package com.github.obhen233.starter.gateway.remote;

import com.github.obhen233.starter.DiatomProperties;
import com.github.obhen233.starter.SharedAutoConfiguration;
import com.github.obhen233.starter.mode.ModeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 客户端模式自动配置。
 *
 * <p>当 {@code diatam.mode=api} 时激活。
 * 当前 Spring Boot 应用作为一个 API 客户端运行，通过 HTTP 调用远程 Diatom Gateway 的 API。
 * 不启动本地 ReActAgent，不暴露网络端口。
 *
 * <p>需要配置：
 * <pre>
 * diatam.mode=api
 * diatam.gateway.gateway-url=http://gateway:8080
 * </pre>
 *
 * <p>向后兼容：也支持 {@code diatam.gateway.remote-enable=true}（已弃用）。
 */
@Configuration
@AutoConfigureAfter(SharedAutoConfiguration.class)
@EnableConfigurationProperties({DiatomProperties.class, com.github.obhen233.starter.gateway.DiatomGatewayProperties.class})
public class DiatomRemoteModeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DiatomRemoteModeConfiguration.class);

    /**
     * 主条件：diatom.mode=api
     */
    @Configuration
    @ConditionalOnProperty(prefix = ModeUtils.MODE_PROPERTY_PREFIX, name = "mode",
            havingValue = "api", matchIfMissing = false)
    static class ApiModeConfig {

        @Bean
        @ConditionalOnMissingBean
        public GatewayChatClient gatewayChatClient(
                com.github.obhen233.starter.gateway.DiatomGatewayProperties properties) {
            String gatewayUrl = properties.getGatewayUrl();
            if (gatewayUrl == null || gatewayUrl.isEmpty()) {
                throw new IllegalStateException(
                        "diatom.gateway.gateway-url must be set when mode=api");
            }
            log.info("Diatom API mode enabled, connecting to Gateway at: {}", gatewayUrl);
            return new GatewayChatClient(gatewayUrl);
        }

        @Bean
        @ConditionalOnMissingBean
        public ChatService remoteChatService(GatewayChatClient client) {
            log.info("Creating RemoteChatService (API mode)");
            return new RemoteChatService(client);
        }
    }

    /**
     * 向后兼容：diatom.gateway.remote-enable=true（已弃用）
     */
    @Configuration
    @ConditionalOnProperty(prefix = "diatom.gateway", name = "remote-enable", havingValue = "true")
    static class LegacyRemoteConfig {

        @Bean
        @ConditionalOnMissingBean
        public GatewayChatClient legacyGatewayChatClient(
                com.github.obhen233.starter.gateway.DiatomGatewayProperties properties) {
            log.warn("'diatom.gateway.remote-enable=true' is deprecated, use 'diatom.mode=api' instead");
            String gatewayUrl = properties.getGatewayUrl();
            if (gatewayUrl == null || gatewayUrl.isEmpty()) {
                throw new IllegalStateException(
                        "diatom.gateway.gateway-url must be set when remote-enable=true");
            }
            return new GatewayChatClient(gatewayUrl);
        }

        @Bean
        @ConditionalOnMissingBean
        public ChatService legacyRemoteChatService(GatewayChatClient client) {
            return new RemoteChatService(client);
        }
    }
}
