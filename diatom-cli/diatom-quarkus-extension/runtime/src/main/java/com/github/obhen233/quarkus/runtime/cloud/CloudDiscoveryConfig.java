package com.github.obhen233.quarkus.runtime.cloud;

import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;

/**
 * Cloud 注册中心发现配置（纯数据，从 {@code DiatomRuntimeConfig.Cloud} 构造）。
 *
 * <p>镜像 starter {@code DiatomCloudDiscoveryProperties} 的核心字段，服务名模型：
 * <ul>
 *   <li>{@code serviceName}（默认 {@code diatom}）— worker 自注册 + gateway 发现 worker 用的服务名</li>
 *   <li>{@code gatewayServiceFilter}（默认 {@code diatom-gateway}）— gateway 自注册 + 拓扑识别服务名</li>
 * </ul>
 */
public record CloudDiscoveryConfig(String type, String host, int port, String serviceName,
                                   String gatewayServiceFilter, String serviceFilter,
                                   long refreshIntervalMs, String workerGroup, String defaultModel) {

    /** Cloud 注册中心是否启用（none / nacos 视为关闭；nacos 非 Stork 内置，记为 TODO）。 */
    public boolean enabled() {
        return type != null && !type.isBlank()
                && !"none".equalsIgnoreCase(type) && !"nacos".equalsIgnoreCase(type);
    }

    /** 是否为 Stork 内置 provider 类型（consul / eureka）。 */
    public boolean isStorkType() {
        return "consul".equalsIgnoreCase(type) || "eureka".equalsIgnoreCase(type);
    }

    /** 从 {@link DiatomRuntimeConfig.Cloud} 构造（defaultModel 缺省用 api.model）。 */
    public static CloudDiscoveryConfig from(DiatomRuntimeConfig.Cloud cloud, String defaultModel) {
        return new CloudDiscoveryConfig(
                cloud.type(), cloud.host(), cloud.port(), cloud.serviceName(),
                cloud.gatewayServiceFilter(), cloud.serviceFilter().orElse(""), cloud.refreshIntervalMs(),
                cloud.workerGroup(), cloud.defaultModel().orElse(defaultModel));
    }
}
