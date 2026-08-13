package com.github.obhen233.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Defines a cluster of hosts for multi-machine deployment.
 * Supports "all", "rolling", and "canary" deployment strategies.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterConfig {

    private String strategy = "all";
    private List<ClusterHost> hosts;
    private HealthCheckConfig healthCheck;
    private Map<String, String> variables;

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public List<ClusterHost> getHosts() { return hosts; }
    public void setHosts(List<ClusterHost> hosts) { this.hosts = hosts; }

    public HealthCheckConfig getHealthCheck() { return healthCheck; }
    public void setHealthCheck(HealthCheckConfig healthCheck) { this.healthCheck = healthCheck; }

    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }
}
