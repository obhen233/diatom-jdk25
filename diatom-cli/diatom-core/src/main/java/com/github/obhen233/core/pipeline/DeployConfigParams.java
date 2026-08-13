package com.github.obhen233.core.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parameters for generating a deploy.yaml configuration.
 * Used by {@link DeployConfigService} to produce deployment configs
 * without interactive input (pool of CLI stdin prompts or IDE AI conversation).
 */
public class DeployConfigParams {

    private String projectName;
    private boolean clusterMode;
    private String clusterName = "production";
    private String strategy = "all";
    private HealthCheckConfig healthCheck;
    private List<ServerInfo> servers = new ArrayList<>();
    private Map<String, String> envVars = new LinkedHashMap<>();
    private Map<String, Map<String, String>> profiles;

    public DeployConfigParams() {}

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public boolean isClusterMode() { return clusterMode; }
    public void setClusterMode(boolean clusterMode) { this.clusterMode = clusterMode; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public HealthCheckConfig getHealthCheck() { return healthCheck; }
    public void setHealthCheck(HealthCheckConfig healthCheck) { this.healthCheck = healthCheck; }

    public List<ServerInfo> getServers() { return servers; }
    public void setServers(List<ServerInfo> servers) { this.servers = servers; }

    public Map<String, String> getEnvVars() { return envVars; }
    public void setEnvVars(Map<String, String> envVars) { this.envVars = envVars; }

    public Map<String, Map<String, String>> getProfiles() { return profiles; }
    public void setProfiles(Map<String, Map<String, String>> profiles) { this.profiles = profiles; }
}
