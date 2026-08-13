package com.github.obhen233.starter.gateway.monitor;

import com.github.obhen233.starter.DiatomProperties;
import com.github.obhen233.starter.SharedAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Monitor 页面自动配置。
 *
 * <p>当 {@code diatam.monitor.enabled=true}（默认）时激活。
 * 创建 {@link MonitorController} 提供仪表盘、拓扑管理等功能。
 *
 * <p>当配置了 {@code diatam.server.port} 且与 {@code server.port} 不同时，
 * 本配置被禁用，由 {@link DiatomWebAutoConfiguration} 的独立容器接管。
 */
@Configuration
@AutoConfigureAfter(SharedAutoConfiguration.class)
@ConditionalOnProperty(prefix = "diatom.monitor", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DiatomMonitorProperties.class)
public class MonitorAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(MonitorAutoConfiguration.class);

    /**
     * 共享端口模式下创建 MonitorController。
     * 端口隔离模式下由 DiatomWebChildConfig 的 @ComponentScan 扫描创建。
     */
    @Configuration
    @ConditionalOnProperty(name = "diatom.server.port", matchIfMissing = true)
    static class SharedMonitorConfig {

        @Bean
        @ConditionalOnMissingBean
        public MonitorController monitorController(DiatomMonitorProperties props) {
            logger.info("Monitor page enabled (shared port mode)");
            return new MonitorController(props);
        }
    }
}
