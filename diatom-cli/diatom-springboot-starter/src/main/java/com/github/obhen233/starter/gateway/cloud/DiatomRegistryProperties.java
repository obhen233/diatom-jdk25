package com.github.obhen233.starter.gateway.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.Map;

/**
 * 注册中心配置属性。
 *
 * <p>用于 {@code diatam.mode=gateway:nacos} 等注册中心模式下的连接配置。
 *
 * <pre>
 * diatam.registry.type=nacos
 * diatam.registry.server-addr=127.0.0.1:8848
 * diatam.registry.namespace=public
 * diatam.registry.username=
 * diatam.registry.password=
 * diatam.registry.service-name=diatom-gateway
 * diatam.registry.options.key1=value1
 * diatam.registry.options.key2=value2
 * </pre>
 *
 * <p>自定义注册中心：
 * <pre>
 * diatam.registry.type=custom
 * diatam.registry.options.myKey=myValue
 * </pre>
 * 然后通过 {@code @Bean} 提供 {@link RegistryService} 实现。
 */
@ConfigurationProperties(prefix = "diatom.registry")
public class DiatomRegistryProperties {

    /** 注册中心类型：nacos / eureka / consul / custom */
    private String type = "";

    /** 注册中心地址（Nacos: server-addr, Eureka: defaultZone, Consul: host:port） */
    private String serverAddr = "";

    /** 注册中心命名空间（Nacos namespace, Eureka region） */
    private String namespace = "";

    /** 注册中心用户名 */
    private String username = "";

    /** 注册中心密码 */
    private String password = "";

    /** 注册的服务名（默认 diatom-gateway） */
    private String serviceName = "diatom-gateway";

    /** 自定义选项（用于 custom 类型或其他注册中心的扩展参数） */
    private Map<String, String> options = Collections.emptyMap();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getServerAddr() { return serverAddr; }
    public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public Map<String, String> getOptions() { return options; }
    public void setOptions(Map<String, String> options) { this.options = options; }
}
