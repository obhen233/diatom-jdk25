package com.github.obhen233.core.gateway.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker 注册信息
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkerInfo {
    private String workerId;
    private String host;
    private int port;
    private String model;
    private java.util.List<String> traits;
    private Map<String, Double> capabilities = new ConcurrentHashMap<>();
    private String tier;
    private double costPer1kTokens;
    private int maxConcurrency;
    private String authToken;
    private String group;
    private long pid;
    private long registeredAt;
    private volatile WorkerStatus status = WorkerStatus.ONLINE;
    private WorkerMetrics metrics = new WorkerMetrics();
    private boolean useSsl;
    private String workspace;
    private String gatewayProfile;
    private List<String> boundaries = new ArrayList<>();
    private int maxTokens;
    private boolean supportsToolCalls;
    private String apiProvider;
    private String gatewayId;

    public enum WorkerStatus {
        ONLINE, SUSPECT, SHUTTING_DOWN, OFFLINE
    }

    public WorkerInfo() {}

    public WorkerInfo(String workerId, String host, int port) {
        this.workerId = workerId;
        this.host = host;
        this.port = port;
        this.registeredAt = System.currentTimeMillis();
    }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public java.util.List<String> getTraits() { return traits; }
    public void setTraits(java.util.List<String> traits) { this.traits = traits; }
    public Map<String, Double> getCapabilities() { return capabilities; }
    public void setCapabilities(Map<String, Double> capabilities) { this.capabilities = capabilities; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public double getCostPer1kTokens() { return costPer1kTokens; }
    public void setCostPer1kTokens(double costPer1kTokens) { this.costPer1kTokens = costPer1kTokens; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public long getPid() { return pid; }
    public void setPid(long pid) { this.pid = pid; }
    public long getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(long registeredAt) { this.registeredAt = registeredAt; }
    public WorkerStatus getStatus() { return status; }
    public void setStatus(WorkerStatus status) { this.status = status; }
    public WorkerMetrics getMetrics() { return metrics; }
    public void setMetrics(WorkerMetrics metrics) { this.metrics = metrics; }

    public boolean isAvailable() {
        return status == WorkerStatus.ONLINE;
    }

    public boolean isUseSsl() { return useSsl; }
    public void setUseSsl(boolean useSsl) { this.useSsl = useSsl; }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }

    public String getGatewayProfile() { return gatewayProfile; }
    public void setGatewayProfile(String gatewayProfile) { this.gatewayProfile = gatewayProfile; }

    public List<String> getBoundaries() { return boundaries; }
    public void setBoundaries(List<String> boundaries) { this.boundaries = boundaries; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public boolean isSupportsToolCalls() { return supportsToolCalls; }
    public void setSupportsToolCalls(boolean supportsToolCalls) { this.supportsToolCalls = supportsToolCalls; }
    public String getApiProvider() { return apiProvider; }
    public void setApiProvider(String apiProvider) { this.apiProvider = apiProvider; }

    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }

    public String getBaseUrl() {
        return (useSsl ? "https://" : "http://") + host + ":" + port;
    }

    public void mergeCapabilities(Map<String, Double> additional) {
        if (additional != null) {
            capabilities.putAll(additional);
        }
    }
}
