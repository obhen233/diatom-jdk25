package com.github.obhen233.starter.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway HA（高可用集群）配置属性。
 *
 * <p>当 {@code diatom.gateway.ha.enabled=true} 时，{@link DiatomGatewayAutoConfiguration}
 * 创建 {@code ClusterCoordinator} Bean（默认 Hazelcast）并将 {@code WorkerRegistry}
 * 包装为 {@code ClusteredWorkerRegistry}：register/heartbeat/deregister 同步到集群，
 * availableWorkers 合并本地 + 远程 worker，实现多 gateway 数据一致。
 *
 * <pre>
 * diatom.gateway.ha.enabled=true
 * diatom.gateway.ha.gateway-id=gateway-1
 *
 * # Hazelcast 集群配置（与 standalone 核心共享同一套 cluster.hazelcast.* 键）
 * cluster.hazelcast.port=5701
 * cluster.hazelcast.tcpip.enabled=true
 * cluster.hazelcast.tcpip.members=10.0.0.1:5701,10.0.0.2:5701
 * cluster.hazelcast.multicast.enabled=false
 * </pre>
 *
 * <p>Hazelcast 依赖随 diatom-core 传递提供，无需额外引入。
 */
@ConfigurationProperties(prefix = "diatom.gateway.ha")
public class DiatomGatewayHaProperties {

    /** 是否启用 Gateway HA 集群模式。启用后 WorkerRegistry 包装为 ClusteredWorkerRegistry。 */
    private boolean enabled = false;

    /**
     * 本 Gateway 在集群中的稳定标识（用于集群 worker 去重与归属判断）。
     * 为空时自动生成 {@code <本机IP>:<server.port>}。
     */
    private String gatewayId = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }
}
