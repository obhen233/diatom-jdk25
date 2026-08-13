package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cloud Discovery 自动配置
 *
 * 当 classpath 中存在 Spring Cloud {@link DiscoveryClient} 时自动启用。
 * 通过 {@link CloudDiscoveryWatcher} 将注册中心（Nacos/Eureka/Consul）中发现的
 * 服务实例同步为 diatom Worker。
 *
 * 启用条件：
 * 1. classpath 中有 {@link DiscoveryClient}
 * 2. {@code diatom.gateway.cloud-discovery.enabled=true}（默认 true）
 *
 * 使用示例（Nacos）：
 * <pre>
 * // pom.xml
 * &lt;dependency&gt;
 *     &lt;groupId&gt;com.alibaba.cloud&lt;/groupId&gt;
 *     &lt;artifactId&gt;spring-cloud-starter-alibaba-nacos-discovery&lt;/artifactId&gt;
 * &lt;/dependency&gt;
 *
 * // application.properties
 * spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848
 * diatom.gateway.cloud-discovery.service-filter=diatom-worker
 * diatom.gateway.cloud-discovery.instance-tag.model=ai-model
 * </pre>
 */
@Configuration
@ConditionalOnClass(DiscoveryClient.class)
@ConditionalOnProperty(prefix = "diatom.gateway.cloud-discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DiatomCloudDiscoveryProperties.class)
@AutoConfigureAfter(name = {
        "org.springframework.cloud.client.CommonsClientAutoConfiguration",
        "org.springframework.cloud.client.discovery.DiscoveryClientAutoConfiguration"
})
public class CloudDiscoveryAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(CloudDiscoveryAutoConfiguration.class);

    /**
     * 当 DiscoveryClient 可用时创建 CloudDiscoveryWatcher。
     * Spring Boot 会自动注入 DiscoveryClient（由 Spring Cloud 的自动配置提供）。
     */
    @Bean
    @ConditionalOnMissingBean
    public CloudDiscoveryWatcher cloudDiscoveryWatcher(
            DiscoveryClient discoveryClient,
            DiatomCloudDiscoveryProperties properties,
            WorkerRegistry workerRegistry) {
        logger.info("Cloud Discovery auto-configuration activated (DiscoveryClient: {})",
                discoveryClient.getClass().getName());
        return new CloudDiscoveryWatcher(discoveryClient, properties, workerRegistry);
    }
}
