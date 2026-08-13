package com.github.obhen233.core.gateway.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Worker 实时指标
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkerMetrics {
    private double currentLoad;
    private double avgLatencyMs;
    private double successRate;
    private volatile long lastHeartbeat;
    private int activeTasks;

    public WorkerMetrics() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public double getCurrentLoad() { return currentLoad; }
    public void setCurrentLoad(double currentLoad) { this.currentLoad = currentLoad; }
    public double getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double successRate) { this.successRate = successRate; }
    public long getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public int getActiveTasks() { return activeTasks; }
    public void setActiveTasks(int activeTasks) { this.activeTasks = activeTasks; }

    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public long getHeartbeatAgeMs() {
        return System.currentTimeMillis() - lastHeartbeat;
    }
}
