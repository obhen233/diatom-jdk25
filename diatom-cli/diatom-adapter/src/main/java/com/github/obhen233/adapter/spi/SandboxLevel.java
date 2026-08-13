package com.github.obhen233.adapter.spi;

/**
 * Sandbox level enum, aligned with diatom-core SandboxLevel.
 *
 * <p>Controls what file operations the adapter allows at the adapter layer,
 * before delegating to the underlying agent.</p>
 */
public enum SandboxLevel {
    /** Read-only: reject project push and write operations at adapter layer */
    READ_ONLY,
    /** Workspace-scoped: restrict file operations to workspacePath (default) */
    WORKSPACE,
    /** Full access: trust the underlying agent's own security mechanism */
    FULL
}
