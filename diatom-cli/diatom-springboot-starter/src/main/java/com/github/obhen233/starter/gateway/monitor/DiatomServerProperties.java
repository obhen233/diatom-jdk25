package com.github.obhen233.starter.gateway.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Diatom 自定义服务器端口配置。
 *
 * <p>当配置此端口时，Diatom Web 服务（Gateway API、Monitor 页面）将在独立的
 * 嵌入式 Tomcat 容器上运行，与主业务应用的 {@code server.port} 隔离。
 *
 * <p>不配置时，共享主应用的 {@code server.port}，通过路径前缀区分。
 *
 * <pre>
 * diatam.server.port=8081
 * </pre>
 */
@ConfigurationProperties(prefix = "diatom.server")
public class DiatomServerProperties {

    /** Diatom Web 服务端口（可选，不配置则共享 server.port） */
    private int port = 0;

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}
