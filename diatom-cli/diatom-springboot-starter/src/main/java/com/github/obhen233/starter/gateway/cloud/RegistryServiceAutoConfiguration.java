package com.github.obhen233.starter.gateway.cloud;

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
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 注册中心服务自动配置。
 *
 * <p>根据 {@code diatom.mode} 的子类型创建对应的 {@link RegistryService} 实现：
 * <ul>
 *   <li>{@code gateway:nacos} → {@link NacosRegistryService}</li>
 *   <li>{@code gateway:eureka} → {@link EurekaRegistryService}</li>
 *   <li>{@code gateway:consul} → {@link ConsulRegistryService}</li>
 *   <li>{@code gateway}（无子类型）→ {@link NoopRegistryService}</li>
 * </ul>
 *
 * <p>通过 {@link ConditionalOnMissingBean} 确保用户可以替换为自定义实现。
 *
 * <h3>自定义注册中心</h3>
 *
 * 只需提供 {@code @Bean} 即可覆盖：
 * <pre>
 * &#64;Bean
 * public RegistryService myRegistryService() {
 *     return new MyZookeeperService();
 * }
 * </pre>
 *
 * 或配合配置 {@code diatam.registry.type=custom} 激活：
 * <pre>
 * &#64;Configuration
 * &#64;ConditionalOnProperty(prefix = "diatom.registry", name = "type", havingValue = "custom")
 * public class CustomRegistryConfig {
 *     &#64;Bean
 *     public RegistryService customRegistry(DiatomRegistryProperties props) {
 *         return new CustomRegistryService(props);
 *     }
 * }
 * </pre>
 */
@Configuration
@AutoConfigureAfter(SharedAutoConfiguration.class)
@EnableConfigurationProperties({DiatomProperties.class, DiatomRegistryProperties.class})
public class RegistryServiceAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(RegistryServiceAutoConfiguration.class);

    /**
     * 根据 {@code diatom.mode} 的子类型创建对应的 RegistryService。
     * 通过 mode 值精确匹配子类型。
     */
    @Configuration
    @ConditionalOnProperty(prefix = "diatom", name = "mode", havingValue = "gateway:nacos")
    static class NacosRegistryServiceConfig {
        @Bean
        @ConditionalOnMissingBean(RegistryService.class)
        public RegistryService nacosRegistryService(DiatomRegistryProperties registryProps) {
            String serverAddr = registryProps.getServerAddr();
            if (serverAddr == null || serverAddr.isEmpty()) {
                serverAddr = "127.0.0.1:8848";
                logger.warn("diatom.registry.server-addr not configured, using default: {}", serverAddr);
            }
            logger.info("Creating NacosRegistryService (server: {})", serverAddr);
            return new NacosRegistryService(serverAddr, registryProps.getNamespace());
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "diatom", name = "mode", havingValue = "gateway:eureka")
    static class EurekaRegistryServiceConfig {
        @Bean
        @ConditionalOnMissingBean(RegistryService.class)
        public RegistryService eurekaRegistryService() {
            logger.info("Creating EurekaRegistryService");
            return new EurekaRegistryService();
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "diatom", name = "mode", havingValue = "gateway:consul")
    static class ConsulRegistryServiceConfig {
        @Bean
        @ConditionalOnMissingBean(RegistryService.class)
        public RegistryService consulRegistryService() {
            logger.info("Creating ConsulRegistryService");
            return new ConsulRegistryService();
        }
    }

    /**
     * 默认：gateway 无子类型时使用 NoopRegistryService。
     * 同时也作为兜底（当 {@code diatom.mode} 配置为 {@code gateway} 但未匹配上述子类型时）。
     */
    @Configuration
    @ConditionalOnProperty(prefix = "diatom", name = "mode", havingValue = "gateway", matchIfMissing = false)
    static class DefaultRegistryServiceConfig {
        @Bean
        @ConditionalOnMissingBean(RegistryService.class)
        public RegistryService noopRegistryService() {
            logger.debug("Creating NoopRegistryService (direct HTTP registration mode)");
            return new NoopRegistryService();
        }
    }
}
