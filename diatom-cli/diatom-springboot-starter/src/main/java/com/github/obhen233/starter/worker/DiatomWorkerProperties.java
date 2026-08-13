package com.github.obhen233.starter.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker/Adapter 模式配置属性。
 *
 * <p>用于 {@code diatam.mode=worker} 和 {@code diatam.mode=adapter} 模式下，
 * 配置与 Gateway 的通信参数。
 *
 * <p>{@code gateway-url} 为可选配置：留空时跳过直连 Gateway 注册（适用于仅通过
 * Spring Cloud 注册中心被发现，由 Gateway 侧 CloudDiscoveryWatcher 拉取）。
 * 配置后则启动时直连注册并定时心跳。
 *
 * <pre>
 * diatam.worker.gateway-url=http://gateway:8080
 * diatam.worker.name=my-worker-1
 * diatam.worker.group=default
 * diatam.worker.external-host=10.0.0.5
 * diatam.worker.external-port=8080
 * </pre>
 */
@ConfigurationProperties(prefix = "diatom.worker")
public class DiatomWorkerProperties {

    /** Gateway 地址（可选，为空则不直连注册） */
    private String gatewayUrl = "";

    /** Worker 实例名（为空则自动生成） */
    private String name = "";

    /** Worker 分组 */
    private String group = "default";

    /** 外部可访问的主机地址（为空则自动检测） */
    private String externalHost = "";

    /** 外部可访问的端口（为空则使用 server.port） */
    private String externalPort = "";

    /** Worker 描述的 AI 模型名称 */
    private String model = "";

    /** 最大并发任务数（默认 1 表示串行执行；本地 ReActAgent 非线程安全，超出部分返回 503） */
    private int maxConcurrency = 1;

    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }

    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getExternalHost() { return externalHost; }
    public void setExternalHost(String externalHost) { this.externalHost = externalHost; }

    public String getExternalPort() { return externalPort; }
    public void setExternalPort(String externalPort) { this.externalPort = externalPort; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
