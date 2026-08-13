package com.github.obhen233.starter.mode;

import com.github.obhen233.starter.SharedAutoConfiguration;
import com.github.obhen233.starter.adapter.AdapterChatService;
import com.github.obhen233.starter.adapter.AdapterDriverPlugin;
import com.github.obhen233.starter.adapter.AdapterRestController;
import com.github.obhen233.starter.gateway.remote.ChatService;
import com.github.obhen233.starter.worker.DiatomWorkerProperties;
import com.github.obhen233.starter.worker.GatewayRegistrationService;
import com.github.obhen233.starter.worker.WorkerLoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;
import java.util.ServiceLoader;

/**
 * Adapter 模式自动配置。
 *
 * <p>当 {@code diatam.mode=adapter} 时激活。
 * 当前 Spring Boot 应用作为一个适配器节点运行，桥接非 diatom AI Agent（如 Claude Code、Cursor 等）。
 *
 * <p>通过 Spring MVC {@code @RestController} 暴露 {@code /worker/v1/chat} 端点接收 Gateway 下发的任务，
 * 经由 {@link AdapterDriverPlugin} SPI 转发到外部 AI Agent 执行。
 *
 * <p>需要配置：
 * <pre>
 * diatam.mode=adapter
 * </pre>
 * {@code diatam.worker.gateway-url} 可选：配置后直连 Gateway 注册；留空则仅通过
 * Spring Cloud 注册中心被 Gateway 发现。
 * 同时需要将 AdapterDriverPlugin 驱动 JAR 放入 plugins/ 目录或配置到 diatam.plugin.paths。
 */
@Configuration
@AutoConfigureAfter(SharedAutoConfiguration.class)
@ConditionalOnProperty(prefix = ModeUtils.MODE_PROPERTY_PREFIX, name = "mode",
        havingValue = "adapter", matchIfMissing = false)
@EnableConfigurationProperties(DiatomWorkerProperties.class)
public class AdapterModeConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AdapterModeConfiguration.class);

    private AdapterDriverPlugin driver;

    @PostConstruct
    public void validateDriver() {
        ServiceLoader<AdapterDriverPlugin> loader = ServiceLoader.load(AdapterDriverPlugin.class);
        for (AdapterDriverPlugin plugin : loader) {
            driver = plugin;
            break;
        }
        if (driver == null) {
            throw new IllegalStateException(
                    "Adapter mode requires an AdapterDriverPlugin SPI implementation. " +
                    "Please add a driver plugin JAR containing META-INF/services/" +
                    AdapterDriverPlugin.class.getName() + " to the plugins/ directory " +
                    "or configure 'diatom.plugin.paths' to include the plugin location.");
        }
        logger.info("Adapter driver loaded: type={}, name={}",
                driver.getDriverType(), driver.getDriverName());
    }

    /**
     * 将 SPI 加载的 AdapterDriverPlugin 暴露为 Spring Bean，供其他组件注入。
     */
    @Bean
    @ConditionalOnMissingBean
    public AdapterDriverPlugin adapterDriverPlugin() {
        if (driver == null) {
            throw new IllegalStateException("AdapterDriverPlugin not initialized. Check plugin configuration.");
        }
        return driver;
    }

    /**
     * ChatService for ADAPTER mode — proxies IDE chat to the external AI agent
     * via the {@link AdapterDriverPlugin} SPI. Provides the IDE with a uniform
     * ChatService so it stays agnostic to the running mode.
     */
    @Bean
    @ConditionalOnMissingBean
    @DependsOn("adapterDriverPlugin")
    public ChatService adapterChatService(AdapterDriverPlugin driver) {
        return new AdapterChatService(driver);
    }

    /**
     * Adapter 并发与负载状态：准入控制 + 真实负载上报。
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
     * Adapter Web 端点：仅在 Spring Web 可用时加载。
     * 通过 {@code @RestController} 暴露 {@code /worker/v1/chat}。
     */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    static class AdapterWebConfig {

        @Bean
        @ConditionalOnMissingBean
        @DependsOn("adapterDriverPlugin")
        public AdapterRestController adapterRestController(
                AdapterDriverPlugin driver, GatewayRegistrationService registrationService,
                WorkerLoadState loadState) {
            logger.info("Adapter REST controller enabled at /worker/v1/chat (driver: {})",
                    driver.getDriverType());
            return new AdapterRestController(driver, registrationService, loadState);
        }
    }
}
