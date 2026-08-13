package com.github.obhen233.starter.gateway.flow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 接入层流量控制属性。
 *
 * <p>前缀: {@code diatom.gateway.flow}</p>
 *
 * <p>通过 Servlet Filter + Semaphore 实现，超过 {@code max-concurrent} 的请求返回 429。</p>
 */
@ConfigurationProperties(prefix = "diatom.gateway.flow")
public class DiatomGatewayFlowProperties {

    /** 是否启用接入层限流（默认 false） */
    private boolean enabled = false;

    /** 最大并发请求数（默认 200） */
    private int maxConcurrent = 200;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
}
