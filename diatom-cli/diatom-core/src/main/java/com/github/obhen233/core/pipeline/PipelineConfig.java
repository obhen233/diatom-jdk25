package com.github.obhen233.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Represents the full deploy pipeline configuration, deserialized from deploy.yaml.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PipelineConfig {

    private String version;
    private String name;
    private Map<String, String> variables;
    private Map<String, ClusterConfig> clusters;
    private List<PipelineStep> steps;
    private List<ClusterHost> servers;
    private HealthCheckConfig healthCheck;
    private Map<String, Map<String, String>> profiles;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }

    public Map<String, ClusterConfig> getClusters() { return clusters; }
    public void setClusters(Map<String, ClusterConfig> clusters) { this.clusters = clusters; }

    public List<PipelineStep> getSteps() { return steps; }
    public void setSteps(List<PipelineStep> steps) { this.steps = steps; }

    public List<ClusterHost> getServers() { return servers; }
    public void setServers(List<ClusterHost> servers) { this.servers = servers; }

    @JsonProperty("health_check")
    public HealthCheckConfig getHealthCheck() { return healthCheck; }
    @JsonProperty("health_check")
    public void setHealthCheck(HealthCheckConfig healthCheck) { this.healthCheck = healthCheck; }

    public Map<String, Map<String, String>> getProfiles() { return profiles; }
    public void setProfiles(Map<String, Map<String, String>> profiles) { this.profiles = profiles; }
}
