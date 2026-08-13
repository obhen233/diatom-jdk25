package com.github.obhen233.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Represents a single host in a cluster deployment configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterHost {

    private String host;
    private int port = 22;
    private String user;
    private String key;
    private String password;
    private Map<String, String> variables;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }
}
