package com.github.obhen233.core.security;

/**
 * Defines the operational scope of the agent.
 * Corresponds to the {@code --level / -l} CLI parameter.
 */
public enum SandboxLevel {
    /**
     * Read-only access. WRITE operations are rejected or require approval.
     * DANGER operations are always intercepted.
     */
    READ_ONLY,

    /**
     * Full read/write within workspace boundary.
     * Operations outside workspace are intercepted or require approval.
     * This is the default level.
     */
    WORKSPACE,

    /**
     * No restrictions. All operations are permitted within the
     * bounds of the approval policy.
     */
    FULL
}
