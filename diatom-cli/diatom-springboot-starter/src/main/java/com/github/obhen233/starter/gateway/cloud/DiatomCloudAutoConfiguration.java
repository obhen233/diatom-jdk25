package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.FileSystemWorkerRegistry;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.starter.gateway.DiatomGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cloud Gateway 自动配置
 *
 * 当 classpath 中存在 spring-cloud-starter-gateway 时自动生效。
 * 提供:
 * 1. DiatomRoutingFilter — 将请求路由到 Worker 节点
 * 2. 可选的 Gateway 基础组件 (TaskManager, WorkerRegistry)
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.cloud.gateway.filter.GlobalFilter")
@ConditionalOnProperty(prefix = "diatom.gateway.cloud", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DiatomGatewayProperties.class)
public class DiatomCloudAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(DiatomCloudAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TaskManager cloudTaskManager() {
        return new TaskManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerRegistry cloudWorkerRegistry() {
        return new FileSystemWorkerRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalFilter diatomRoutingFilter(WorkerRegistry registry) {
        logger.info("Diatom Cloud Gateway routing filter initialized");
        return new DiatomRoutingFilter(registry, -1);
    }
}
