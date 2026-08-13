package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Response for the topology API — used by workspace UI for Vis.js graph rendering.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TopologyData {
    public GatewayInfo gateway;
    public List<WorkerTopologyInfo> workers;
    public List<ConnectionInfo> connections;
    public List<TaskStateSummary> tasks;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GatewayInfo {
        public String id;
        public String host;
        public int port;
        public String version;
        public int workerCount;
        public long uptime;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkerTopologyInfo {
        public String workerId;
        public String host;
        public int port;
        public String model;
        public String group;
        public String tier;
        public String status;
        public double currentLoad;
        public int activeTasks;
        public int maxConcurrency;
        public long heartbeatAge;
        public long lastHeartbeat;
        public String workspace;
        public List<String> boundaries;
        public int maxTokens;
        public double successRate;
        public double avgLatencyMs;
        public List<String> traits;
        public String gatewayProfile;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConnectionInfo {
        public String from;
        public String to;
        public String type;
        public int activeRequests;
        public String status;
    }
}
