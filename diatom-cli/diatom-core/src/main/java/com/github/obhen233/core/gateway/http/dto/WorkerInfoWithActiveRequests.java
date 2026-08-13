package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkerInfoWithActiveRequests {
    public String workerId;
    public String host;
    public int port;
    public String model;
    public String group;
    public String tier;
    public String status;
    public double currentLoad;
    public int maxConcurrency;
    public int activeTasks;
    public int gatewayActiveRequests;
    public long heartbeatAge;
    public String workspace;
    public String gatewayProfile;
    public String gatewayId;
    public long lastHeartbeat;
}
