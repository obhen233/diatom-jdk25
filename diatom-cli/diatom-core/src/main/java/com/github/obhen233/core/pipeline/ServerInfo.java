package com.github.obhen233.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents server connection information for deployment.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerInfo {
    private String host;
    private String user = "root";
    private int port = 22;
    private String key = "";
    private String password = "";

    public ServerInfo() {}

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
