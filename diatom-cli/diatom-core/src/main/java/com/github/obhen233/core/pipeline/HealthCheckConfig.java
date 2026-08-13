package com.github.obhen233.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Health check configuration for post-deployment verification.
 * Supports HTTP, TCP, command, and no-op check types.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HealthCheckConfig {

    private boolean enabled = false;
    private String type = "http";
    private int port;
    private String path = "/health";
    private int timeout = 30;
    private int retries = 3;
    private String command;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }

    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
}
