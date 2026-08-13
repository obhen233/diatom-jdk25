package com.github.obhen233.starter.gateway.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Monitor 页面配置属性。
 *
 * <p>当 {@code diatam.mode=gateway} 时，Monitor 页面通过 {@code @RestController} 提供
 * 集群监控、拓扑管理等功能。
 *
 * <pre>
 * diatam.monitor.enabled=true
 * diatam.monitor.prefix=monitor
 * diatam.monitor.username=admin
 * diatam.monitor.password=changeme
 * </pre>
 */
@ConfigurationProperties(prefix = "diatom.monitor")
public class DiatomMonitorProperties {

    /** 是否启用 Monitor 页面（默认 true） */
    private boolean enabled = true;

    /** Monitor 路径前缀（默认 "monitor"） */
    private String prefix = "monitor";

    /** 登录用户名（不配置则不要求登录） */
    private String username = "";

    /** 登录密码（不配置则不要求登录） */
    private String password = "";

    /** Session Token 过期时间（秒，默认 86400 = 24 小时） */
    private int tokenExpireSeconds = 86400;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getTokenExpireSeconds() { return tokenExpireSeconds; }
    public void setTokenExpireSeconds(int tokenExpireSeconds) { this.tokenExpireSeconds = tokenExpireSeconds; }

    public boolean isAuthConfigured() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
