package com.github.obhen233.starter.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 配置属性。
 *
 * <p>当 {@code diatam.mode=gateway}（或 {@code gateay:nacos} 等子类型）时，
 * Gateway 通过 Spring MVC {@code @RestController} 暴露 API，复用 Spring Boot 内嵌 Web 容器。
 *
 * <p>与 standalone {@code java -jar diatom.jar -r gateway} 功能对等，但传输层不同。
 *
 * <pre>
 * diatam.mode=gateway
 * diatam.gateway.bind-address=0.0.0.0
 * diatam.gateway.management-port=8081
 *
 * # API 模式使用：
 * diatam.gateway.gateway-url=http://gateway:8080
 * </pre>
 *
 * @see com.github.obhen233.starter.mode.DiatomMode#GATEWAY
 */
@ConfigurationProperties(prefix = "diatom.gateway")
public class DiatomGatewayProperties {

    /** @deprecated 由 {@code diatam.mode=gateway} 替代 */
    @Deprecated
    private boolean enabled = false;

    private int port = 8080;
    private String url = "";
    private String bindAddress = "0.0.0.0";

    /** @deprecated 由 {@code diatam.mode=gateway:nacos} 的 RegistryService SPI 替代 */
    @Deprecated
    private String registry = "filesystem";

    /** @deprecated 不再使用 */
    @Deprecated
    private String registryPath = "";

    private int managementPort = 0;

    // Remote client mode
    private boolean remoteEnable = false;
    private String gatewayUrl = "";

    // SSL — 由 server.ssl.* 替代，保留仅用于向后兼容
    /** @deprecated 由 {@code server.ssl.*} 替代 */
    @Deprecated
    private boolean sslEnabled = false;
    /** @deprecated 由 {@code server.ssl.key-store} 替代 */
    @Deprecated
    private String sslKeystorePath = "";
    /** @deprecated 由 {@code server.ssl.key-store-password} 替代 */
    @Deprecated
    private String sslKeystorePassword = "";
    /** @deprecated 不再使用 */
    @Deprecated
    private String sslKeystorePasswordFile = "";
    /** @deprecated 由 {@code server.ssl.key-password} 替代 */
    @Deprecated
    private String sslKeyPassword = "";
    /** @deprecated 不再使用 */
    @Deprecated
    private String sslKeyPasswordFile = "";
    /** @deprecated 不再使用 */
    @Deprecated
    private String sslKeystoreKey = "";

    @Deprecated
    public boolean isEnabled() { return enabled; }
    @Deprecated
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }
    @Deprecated
    public String getRegistry() { return registry; }
    @Deprecated
    public void setRegistry(String registry) { this.registry = registry; }
    @Deprecated
    public String getRegistryPath() { return registryPath; }
    @Deprecated
    public void setRegistryPath(String registryPath) { this.registryPath = registryPath; }
    public int getManagementPort() { return managementPort; }
    public void setManagementPort(int managementPort) { this.managementPort = managementPort; }

    public boolean isRemoteEnable() { return remoteEnable; }
    public void setRemoteEnable(boolean remoteEnable) { this.remoteEnable = remoteEnable; }
    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }

    @Deprecated
    public boolean isSslEnabled() { return sslEnabled; }
    @Deprecated
    public void setSslEnabled(boolean sslEnabled) { this.sslEnabled = sslEnabled; }
    @Deprecated
    public String getSslKeystorePath() { return sslKeystorePath; }
    @Deprecated
    public void setSslKeystorePath(String sslKeystorePath) { this.sslKeystorePath = sslKeystorePath; }
    @Deprecated
    public String getSslKeystorePassword() { return sslKeystorePassword; }
    @Deprecated
    public void setSslKeystorePassword(String sslKeystorePassword) { this.sslKeystorePassword = sslKeystorePassword; }
    @Deprecated
    public String getSslKeystorePasswordFile() { return sslKeystorePasswordFile; }
    @Deprecated
    public void setSslKeystorePasswordFile(String sslKeystorePasswordFile) { this.sslKeystorePasswordFile = sslKeystorePasswordFile; }
    @Deprecated
    public String getSslKeyPassword() { return sslKeyPassword; }
    @Deprecated
    public void setSslKeyPassword(String sslKeyPassword) { this.sslKeyPassword = sslKeyPassword; }
    @Deprecated
    public String getSslKeyPasswordFile() { return sslKeyPasswordFile; }
    @Deprecated
    public void setSslKeyPasswordFile(String sslKeyPasswordFile) { this.sslKeyPasswordFile = sslKeyPasswordFile; }
    @Deprecated
    public String getSslKeystoreKey() { return sslKeystoreKey; }
    @Deprecated
    public void setSslKeystoreKey(String sslKeystoreKey) { this.sslKeystoreKey = sslKeystoreKey; }
}
