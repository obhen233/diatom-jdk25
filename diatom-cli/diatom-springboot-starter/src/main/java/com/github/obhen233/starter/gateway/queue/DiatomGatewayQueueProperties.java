package com.github.obhen233.starter.gateway.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Task Queue 配置属性。
 *
 * <p>前缀: {@code diatom.gateway.queue}</p>
 */
@ConfigurationProperties(prefix = "diatom.gateway.queue")
public class DiatomGatewayQueueProperties {

    /** 是否启用异步队列模式（默认 false，保持现有同步行为） */
    private boolean enabled = false;

    /** 后台消费者线程数（默认 4） */
    private int workers = 4;

    /** 已完成任务结果的 TTL（秒），过期自动清理（默认 300 = 5 分钟） */
    private long resultTtlSeconds = 300;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }
    public long getResultTtlSeconds() { return resultTtlSeconds; }
    public void setResultTtlSeconds(long resultTtlSeconds) { this.resultTtlSeconds = resultTtlSeconds; }
}
