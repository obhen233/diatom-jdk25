package com.github.obhen233.core.gateway.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegistrationRequest {
    public String workerId;
    public String host;
    public int port;
    public String model;
    public List<String> traits;
    public Map<String, Double> capabilities;
    public String group;
    public String tier;
    public int maxConcurrency;
    public String workspace;
    public String gatewayProfile;
    public String publicKey;
    public String apiProvider;
    public double currentLoad;
    public int activeTasks;
    public long lastHeartbeat;
    public String agentVersion;
}
