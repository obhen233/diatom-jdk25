package com.github.obhen233.starter.gateway.cloud;

/**
 * 注册中心中的 Gateway 节点信息（拓扑 API {@code gateways} 数组元素）。
 *
 * <p>直读模式下由 {@link DiscoveryClientWorkerRegistry#gatewayNodes()} 从
 * Spring Cloud {@code DiscoveryClient} 中查询匹配 {@code gatewayServiceFilter}
 * 的实例生成，用于拓扑编辑器渲染多 gateway 节点。
 */
public class GatewayNode {

    private String id;
    private String host;
    private int port;
    private String version;
    private int workerCount;
    private long uptime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public int getWorkerCount() { return workerCount; }
    public void setWorkerCount(int workerCount) { this.workerCount = workerCount; }

    public long getUptime() { return uptime; }
    public void setUptime(long uptime) { this.uptime = uptime; }
}
