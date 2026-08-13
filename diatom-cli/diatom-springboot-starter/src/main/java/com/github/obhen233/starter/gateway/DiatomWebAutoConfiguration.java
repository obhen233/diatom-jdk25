package com.github.obhen233.starter.gateway;

import com.github.obhen233.starter.gateway.monitor.DiatomServerProperties;
import com.github.obhen233.starter.gateway.monitor.MonitorController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import jakarta.annotation.PreDestroy;

/**
 * Diatom Web 独立端口自动配置。
 *
 * <p>当配置了 {@code diatam.server.port} 且与主 {@code server.port} 不同时，
 * 启动第二个嵌入式 Tomcat 容器，用于承载 Gateway API 和 Monitor 页面。
 *
 * <p>此配置确保 Diatom Web 服务与业务应用端口隔离，避免路径冲突和 Auth 拦截。
 */
@Configuration
@ConditionalOnProperty(name = "diatom.server.port")
@EnableConfigurationProperties(DiatomServerProperties.class)
public class DiatomWebAutoConfiguration implements DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(DiatomWebAutoConfiguration.class);

    private AnnotationConfigServletWebServerApplicationContext childContext;

    @Bean
    public WebServer diatomWebServer(
            DiatomServerProperties serverProperties,
            ApplicationContext parentContext) {

        int port = serverProperties.getPort();
        if (port <= 0) {
            logger.warn("diatom.server.port configured but invalid: {}, skipping isolated web server", port);
            return null;
        }

        logger.info("Starting isolated Diatom web server on port {} (parent context: {})",
                port, parentContext.getId());

        // Create a child ApplicationContext for the isolated web server
        childContext = new AnnotationConfigServletWebServerApplicationContext();
        childContext.setParent(parentContext);
        childContext.setId("diatom-web-server");
        childContext.register(DiatomWebChildConfig.class);
        childContext.refresh();

        WebServer webServer = childContext.getWebServer();
        logger.info("Isolated Diatom web server started on port {}", port);
        return webServer;
    }

    @Override
    @PreDestroy
    public void destroy() {
        if (childContext != null) {
            try {
                childContext.close();
                logger.info("Isolated Diatom web server stopped");
            } catch (Exception e) {
                logger.warn("Error stopping isolated Diatom web server: {}", e.getMessage());
            }
        }
    }

    /**
     * 子容器配置 — 仅扫描 Gateway 和 Monitor 的 Controller 包。
     * Service Bean 从父容器继承。
     */
    @Configuration
    @ComponentScan(basePackages = {
            "com.github.obhen233.starter.gateway.monitor",
            "com.github.obhen233.starter.gateway"
    })
    static class DiatomWebChildConfig {
        // Controllers are auto-discovered via @ComponentScan
        // All service beans are inherited from parent context
    }
}
