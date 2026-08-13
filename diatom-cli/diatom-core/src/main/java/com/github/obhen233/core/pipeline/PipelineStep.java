package com.github.obhen233.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Represents a single step in a deploy pipeline.
 * Deserialized from deploy.yaml.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PipelineStep {

    private String name;
    private String action;
    private String command;
    private String host;
    private String cluster;
    private String strategy;
    private HealthCheckConfig healthCheck;
    private List<String> commands;
    private List<ScpFileEntry> files;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public HealthCheckConfig getHealthCheck() { return healthCheck; }
    public void setHealthCheck(HealthCheckConfig healthCheck) { this.healthCheck = healthCheck; }

    public List<String> getCommands() { return commands; }
    public void setCommands(List<String> commands) { this.commands = commands; }

    public List<ScpFileEntry> getFiles() { return files; }
    public void setFiles(List<ScpFileEntry> files) { this.files = files; }
}
