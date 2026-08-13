package com.github.obhen233.adapter.spi;

import java.util.Map;

/**
 * SPI interface for adapting external AI agents into diatom Workers.
 *
 * <p>Each implementation wraps a specific agent (Claude Code, Cursor, custom agent, etc.)
 * and exposes it via the standard AgentAdapter contract. The adapter module creates a
 * Worker HTTP server that delegates to this interface.</p>
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}.</p>
 *
 * <p>Analogous to JDBC's {@code java.sql.Driver} — the adapter module provides the
 * framework, and concrete JARs (drivers) provide implementations.</p>
 */
public interface AgentAdapter {

    /**
     * Adapter type identifier, e.g. "claude-code", "cursor", "custom".
     * Used for logging, routing hints, and matching with SecurityMapper.
     */
    String getAgentType();

    /**
     * Adapter metadata including model info, capabilities, and constraints.
     * Sent to Gateway during worker registration.
     */
    AgentInfo getAgentInfo();

    /**
     * Core execution method: process a request and return a response.
     *
     * @param request the incoming request with task details and security-mapped config
     * @return the agent's response, including file diffs if any
     */
    AgentResponse execute(AgentRequest request);

    /**
     * Streaming execution (optional).
     *
     * <p>Default implementation falls back to non-streaming: calls {@link #execute(AgentRequest)}
     * once and emits the full response as a single token.</p>
     *
     * @param request  the incoming request
     * @param consumer callback for receiving tokens
     */
    default void executeStream(AgentRequest request, StreamConsumer consumer) {
        AgentResponse resp = execute(request);
        consumer.onToken(resp.response());
        consumer.onComplete();
    }

    /** Cancel the current task if supported. */
    default void cancel() {}

    /**
     * Initialize the adapter with configuration.
     *
     * @param config configuration properties (api.key, agent-specific settings, and
     *               security-mapped config from SecurityMapper)
     */
    default void init(Map<String, String> config) {}

    /**
     * Set the adapter-level workspace root directory.
     *
     * <p>This is the persistent workspace assigned to this adapter instance
     * (set via {@code --workspace-dir} CLI option), distinct from the
     * per-request transient workspace path in {@link AgentRequest#getWorkspacePath()}.
     * Adapter drivers should use this path to set the working directory for
     * the underlying agent process (e.g., via a {@code --working-directory} CLI flag).</p>
     *
     * @param workspacePath absolute path to the workspace root directory
     */
    default void setWorkspace(String workspacePath) {}

    /** Shut down the adapter and release resources. */
    default void shutdown() {}
}
