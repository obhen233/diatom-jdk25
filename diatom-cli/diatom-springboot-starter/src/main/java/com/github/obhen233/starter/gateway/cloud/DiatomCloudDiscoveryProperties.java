package com.github.obhen233.starter.gateway.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Cloud Discovery 配置属性
 *
 * <pre>
 * diatom.gateway.cloud-discovery.enabled=true
 * diatom.gateway.cloud-discovery.service-filter=diatom-worker
 * diatom.gateway.cloud-discovery.gateway-service-filter=diatom-gateway
 * diatom.gateway.cloud-discovery.refresh-interval-ms=30000
 * diatom.gateway.cloud-discovery.worker-group=cloud
 * diatom.gateway.cloud-discovery.default-model=gpt-4
 * diatom.gateway.cloud-discovery.instance-tag.model=ai-model
 * diatom.gateway.cloud-discovery.instance-tag.group=ai-group
 * </pre>
 */
@ConfigurationProperties(prefix = "diatom.gateway.cloud-discovery")
public class DiatomCloudDiscoveryProperties {

    /** 是否启用 Cloud Discovery */
    private boolean enabled = true;

    /** 服务名过滤，为空则发现所有服务 */
    private String serviceFilter = "";

    /**
     * Gateway 服务名过滤，用于从注册中心识别 gateway 节点。
     * 直读模式下 worker 读取会跳过该服务；拓扑 API 的 {@code gateways} 数组按此过滤匹配。
     */
    private String gatewayServiceFilter = "diatom-gateway";

    /** 刷新间隔（毫秒） */
    private long refreshIntervalMs = 30000;

    /** Cloud 发现 Worker 的分组名 */
    private String workerGroup = "cloud";

    /** 默认 model 名称（metadata 中未指定时使用） */
    private String defaultModel = "gpt-4";

    /**
     * 实例 metadata tag 到 WorkerInfo 字段的映射。
     * 例如：instance-tag.model=ai-model 表示从 metadata["ai-model"] 读取 model 名称
     */
    private Map<String, String> instanceTag = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getServiceFilter() { return serviceFilter; }
    public void setServiceFilter(String serviceFilter) { this.serviceFilter = serviceFilter; }

    public String getGatewayServiceFilter() { return gatewayServiceFilter; }
    public void setGatewayServiceFilter(String gatewayServiceFilter) { this.gatewayServiceFilter = gatewayServiceFilter; }

    public long getRefreshIntervalMs() { return refreshIntervalMs; }
    public void setRefreshIntervalMs(long refreshIntervalMs) { this.refreshIntervalMs = refreshIntervalMs; }

    public String getWorkerGroup() { return workerGroup; }
    public void setWorkerGroup(String workerGroup) { this.workerGroup = workerGroup; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public Map<String, String> getInstanceTag() { return instanceTag; }
    public void setInstanceTag(Map<String, String> instanceTag) { this.instanceTag = instanceTag; }
}
