package com.github.obhen233.starter.gateway.child;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 子 Gateway（Gateway 级联）模式配置属性。
 *
 * <p>当 {@code diatom.mode=gateway:child} 时激活。当前 Gateway 作为父 Gateway 的
 * 子节点（类似 worker）运行：
 * <ul>
 *   <li>上接父 Gateway：通过 {@code upstream-url} HTTP 直连自注册（可选），或仅走
 *       Spring Cloud 注册中心被父 Gateway 发现。</li>
 *   <li>下接 Spring Boot worker：worker 直接注册到本 Gateway。</li>
 * </ul>
 *
 * <pre>
 * diatom.mode=gateway:child
 * diatom.gateway.child.upstream-url=http://parent-gateway:8080
 * diatom.gateway.child.name=child-gw-1
 * diatom.gateway.child.group=default
 * </pre>
 */
@ConfigurationProperties(prefix = "diatom.gateway.child")
public class ChildGatewayProperties {

    /** 父 Gateway 地址（可选，为空则仅走注册中心发现） */
    private String upstreamUrl = "";

    /** 实例名（为空则自动生成 &lt;本机IP&gt;:&lt;server.port&gt;） */
    private String name = "";

    /** 分组 */
    private String group = "default";

    /** 描述的 AI 模型名称（为空则用 AppConfig.getModel()） */
    private String model = "";

    /** 外部可访问的主机地址（为空则用 NetworkUtils.getRealLocalIP()） */
    private String externalHost = "";

    /** 外部可访问的端口（为空则用 server.port） */
    private String externalPort = "";

    /** 最大并发任务数 */
    private int maxConcurrency = 5;

    /** 心跳间隔（秒） */
    private int heartbeatIntervalSeconds = 10;

    /** 注册到父 Gateway 的 tier */
    private String tier = "gateway-proxy";

    public String getUpstreamUrl() { return upstreamUrl; }
    public void setUpstreamUrl(String upstreamUrl) { this.upstreamUrl = upstreamUrl; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getExternalHost() { return externalHost; }
    public void setExternalHost(String externalHost) { this.externalHost = externalHost; }

    public String getExternalPort() { return externalPort; }
    public void setExternalPort(String externalPort) { this.externalPort = externalPort; }

    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }

    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) { this.heartbeatIntervalSeconds = heartbeatIntervalSeconds; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
}
