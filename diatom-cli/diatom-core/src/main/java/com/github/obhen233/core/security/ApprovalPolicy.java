package com.github.obhen233.core.security;

/**
 * Defines when user approval is required for tool execution.
 * Corresponds to the {@code --approval-policy / -a} CLI parameter.
 */
public enum ApprovalPolicy {
    /**
     * Always ask the user for WRITE and DANGER operations.
     * This is the default policy when no {@code -a} flag is given.
     */
    ASK,

    /**
     * Automatically approve operations within sandbox scope.
     * Operations that exceed the sandbox scope will prompt the user.
     */
    AUTO,

    /**
     * Automatically approve operations within sandbox scope.
     * Operations that exceed the sandbox scope are silently rejected.
     * No prompts are shown.
     */
    SILENT,

    /**
     * Use a custom AutoApprovalStrategy implementation loaded via SPI.
     */
    CUSTOM
}
