package com.github.obhen233.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks MCP server connection states.
 * Used to determine which servers are new/delta for reload operations.
 */
public class McpConnectionTracker {
    private static final Logger logger = LoggerFactory.getLogger(McpConnectionTracker.class);

    // Server name -> ConnectedServer info
    private final Map<String, ConnectedServer> connectedServers = new ConcurrentHashMap<>();

    // Server name -> error message (for disconnected but configured servers)
    private final Map<String, String> serverErrors = new ConcurrentHashMap<>();

    public static class ConnectedServer {
        public final String name;
        public final McpServer server;
        public final long connectedAt;

        public ConnectedServer(String name, McpServer server) {
            this.name = name;
            this.server = server;
            this.connectedAt = System.currentTimeMillis();
        }
    }

    /**
     * Mark a server as connected
     */
    public void markConnected(String name, McpServer server) {
        connectedServers.put(name, new ConnectedServer(name, server));
        serverErrors.remove(name);
        logger.debug("MCP server '{}' marked as connected", name);
    }

    /**
     * Mark a server as disconnected
     */
    public void markDisconnected(String name) {
        connectedServers.remove(name);
        logger.debug("MCP server '{}' marked as disconnected", name);
    }

    /**
     * Mark a server as failed with error message
     */
    public void markFailed(String name, String error) {
        connectedServers.remove(name);
        serverErrors.put(name, error);
        logger.debug("MCP server '{}' marked as failed: {}", name, error);
    }

    /**
     * Get all connected server names
     */
    public Set<String> getConnectedNames() {
        return new HashSet<>(connectedServers.keySet());
    }

    /**
     * Get all servers that had errors (configured but failed to connect)
     */
    public Map<String, String> getServerErrors() {
        return new HashMap<>(serverErrors);
    }

    /**
     * Check if a server is currently connected
     */
    public boolean isConnected(String name) {
        return connectedServers.containsKey(name);
    }

    /**
     * Get the connected server instance
     */
    public McpServer getServer(String name) {
        ConnectedServer cs = connectedServers.get(name);
        return cs != null ? cs.server : null;
    }

    /**
     * Get error message for a failed server
     */
    public String getError(String name) {
        return serverErrors.get(name);
    }

    /**
     * Find servers that are in configured set but not in connected set
     */
    public Set<String> findNewServers(Set<String> configured) {
        Set<String> newServers = new HashSet<>(configured);
        newServers.removeAll(connectedServers.keySet());
        return newServers;
    }

    /**
     * Find servers that are connected but no longer in configured set
     */
    public Set<String> findRemovedServers(Set<String> configured) {
        Set<String> removed = new HashSet<>(connectedServers.keySet());
        removed.removeAll(configured);
        return removed;
    }

    /**
     * Clear all tracking state
     */
    public void clear() {
        connectedServers.clear();
        serverErrors.clear();
    }
}
