package com.github.obhen233.starter.gateway.child;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.starter.gateway.DiatomGatewayAutoConfiguration;
import com.github.obhen233.starter.gateway.SpringGatewayTransport;
import com.github.obhen233.starter.mode.ChildGatewayModeCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 子 Gateway（Gateway 级联）模式自动配置。
 *
 * <p>当 {@code diatom.mode=gateway:child} 时激活，且依赖 {@link DiatomGatewayAutoConfiguration}
 * 已创建 Gateway 核心 Bean（GatewayAgent、CapabilityRouter、SpringGatewayTransport 等）。</p>
 *
 * <p>职责：
 * <ol>
 *   <li>创建 {@link ChildGatewayRegistrationService}：启动时向父 Gateway 自注册并定时心跳，
 *       关闭时注销（仅配置了 {@code diatom.gateway.child.upstream-url} 时生效）。</li>
 *   <li>创建 {@link ChildGatewayWorkerChatController}：暴露 {@code POST /worker/v1/chat}，
 *       承接父 Gateway 下发的任务并转发到下挂 worker。</li>
 * </ol>
 */
@Configuration
@AutoConfigureAfter(DiatomGatewayAutoConfiguration.class)
@Conditional(ChildGatewayModeCondition.class)
@EnableConfigurationProperties(ChildGatewayProperties.class)
public class ChildGatewayAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(ChildGatewayAutoConfiguration.class);

    /**
     * 子 Gateway 上行 HTTP 自注册服务（父 Gateway 未启动时优雅降级，不阻断启动）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ChildGatewayRegistrationService childGatewayRegistrationService(
            ChildGatewayProperties properties, Environment environment, AppConfig appConfig) {
        logger.info("Child Gateway mode enabled (diatom.mode=gateway:child), upstream={}",
                properties.getUpstreamUrl());
        return new ChildGatewayRegistrationService(properties, environment, appConfig);
    }

    /**
     * 子 Gateway 的 Worker 代理 Web 端点：仅在 Spring Web 可用时加载。
     */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    static class ChildGatewayWebConfig {

        @Bean
        @ConditionalOnMissingBean
        public ChildGatewayWorkerChatController childGatewayWorkerChatController(
                GatewayAgent gatewayAgent,
                CapabilityRouter capabilityRouter,
                SpringGatewayTransport transport,
                ChildGatewayProperties properties,
                Environment environment) {
            logger.info("Child Gateway worker proxy enabled at /worker/v1/chat");
            return new ChildGatewayWorkerChatController(gatewayAgent, capabilityRouter,
                    transport, properties, environment);
        }
    }
}
