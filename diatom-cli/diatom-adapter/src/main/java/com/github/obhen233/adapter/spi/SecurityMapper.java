package com.github.obhen233.adapter.spi;

import java.util.Map;

/**
 * SPI interface for mapping diatom security policies to agent-native configuration.
 *
 * <p>Each agent has its own security model (e.g., Claude Code's --mode/--yes/pre-approve,
 * Codex's auto-approve rules, etc.). This SPI allows each driver to define
 * the mapping from diatom's generic {@link SandboxLevel}/{@link ApprovalPolicy}
 * to the agent's native configuration keys.</p>
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} and matched
 * by {@link #getAgentType()} against {@link AgentAdapter#getAgentType()}.</p>
 */
public interface SecurityMapper {

    /**
     * Map diatom security level and policy to agent-native config.
     *
     * @param level  the diatom SandboxLevel (READ_ONLY / WORKSPACE / FULL)
     * @param policy the diatom ApprovalPolicy (ASK / AUTO / SILENT)
     * @return agent-native configuration key-value pairs
     */
    Map<String, String> mapSecurity(SandboxLevel level, ApprovalPolicy policy);

    /**
     * Return the agent type this mapper applies to, or null for a fallback/default mapper.
     *
     * <p>When the adapter looks up a mapper for a specific driver, it prefers mappers
     * whose {@code getAgentType()} matches the driver's {@link AgentAdapter#getAgentType()}.
     * If no match is found, the null-returning (default) mapper is used.</p>
     */
    default String getAgentType() { return null; }
}
