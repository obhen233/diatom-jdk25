package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.WorkerInfo;

import java.util.List;
import java.util.Map;

/**
 * 注册中心服务 SPI。
 *
 * <p>用于 Gateway 模式下的注册中心集成，承担两个职责：
 * <ol>
 *   <li><b>Gateway 自注册</b> — Gateway 启动时将自身注册到注册中心</li>
 *   <li><b>Worker 发现</b> — 从注册中心发现可用的 Worker 节点</li>
 * </ol>
 *
 * <p>由 {@code diatam.mode=gateway:nacos} 等模式子类型激活。
 * Spring Boot 模式无需额外配置，springboot-starter 根据 {@code mode} 子类型自动装配。
 *
 * <h3>扩展自定义注册中心</h3>
 *
 * <p>通过 Spring Bean 重写即可扩展新的注册中心：
 *
 * <pre>
 * &#64;Configuration
 * public class MyRegistryConfig {
 *
 *     &#64;Bean
 *     public RegistryService myRegistryService() {
 *         return new MyZookeeperRegistryService();
 *     }
 * }
 * </pre>
 *
 * 或使用 {@code diatam.registry.type=custom} + {@code @Component}：
 *
 * <pre>
 * &#64;Component
 * &#64;ConditionalOnProperty(prefix = "diatom.registry", name = "type", havingValue = "custom")
 * public class CustomRegistryService implements RegistryService {
 *     // ...
 * }
 * </pre>
 *
 * <p>内置实现：
 * <ul>
 *   <li>{@link NoopRegistryService} — 直连注册模式（默认）</li>
 *   <li>{@link NacosRegistryService} — Nacos</li>
 *   <li>{@link EurekaRegistryService} — Eureka（stub）</li>
 *   <li>{@link ConsulRegistryService} — Consul（stub）</li>
 * </ul>
 */
public interface RegistryService {

    /**
     * 将当前 Gateway 注册到注册中心。
     *
     * @param serviceId 服务名（如 "diatom-gateway"）
     * @param host 绑定主机
     * @param port 绑定端口
     * @param metadata 附加元数据（model、group、version 等）
     */
    void registerGateway(String serviceId, String host, int port, Map<String, String> metadata);

    /**
     * 从注册中心注销当前 Gateway。
     */
    void deregisterGateway();

    /**
     * 从注册中心发现 Worker 实例。
     *
     * @param serviceFilter 服务名过滤（为空则发现所有）
     * @return Worker 列表
     */
    List<WorkerInfo> discoverWorkers(String serviceFilter);

    /**
     * 检查注册中心连接是否健康。
     */
    boolean isHealthy();

    /**
     * 释放资源（关闭连接、停止心跳等）。
     */
    void destroy();
}
