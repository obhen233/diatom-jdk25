package com.github.obhen233.quarkus.runtime.cloud;

import java.util.List;

/**
 * 注册中心服务 SPI（框架无关），镜像 starter {@code RegistryService} 的双职责：
 *
 * <ol>
 *   <li><b>自注册/注销</b> — 网关/worker 启动时将自身实例注册到注册中心</li>
 *   <li><b>实例发现</b> — 从注册中心发现指定服务的可用实例</li>
 * </ol>
 *
 * <p>默认实现 {@link StorkRegistryAdapter} 基于 SmallRye Stork（Quarkus 原生，consul/eureka）；
 * 测试可注入 in-memory 假实现验证 {@link StorkWorkerRegistry} 的发现/同步逻辑。</p>
 */
public interface CloudRegistryService extends AutoCloseable {

    /** 初始化（幂等；失败降级不抛异常）。 */
    void init();

    /** 注册单个实例（serviceName 下的 instanceId → host:port）。 */
    void registerInstance(String serviceName, String instanceId, String host, int port);

    /** 注销单个实例。 */
    void deregisterInstance(String serviceName, String instanceId, String host, int port);

    /** 发现指定服务的实例列表（失败返回空列表）。 */
    List<DiscoveredInstance> discover(String serviceName);

    /** 注册中心连接健康检查。 */
    boolean isHealthy();

    /** 释放资源。 */
    @Override
    void close();
}
