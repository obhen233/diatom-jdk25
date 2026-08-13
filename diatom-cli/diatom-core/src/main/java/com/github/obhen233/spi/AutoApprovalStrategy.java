package com.github.obhen233.spi;

import com.github.obhen233.core.security.ApprovalContext;

/**
 * SPI interface for custom approval strategies.
 * <p>
 * Implement this interface to define custom logic for deciding
 * whether tool operations should be auto-approved, rejected, or
 * forwarded for user confirmation.
 * <p>
 * Register implementations in:
 * {@code META-INF/services/com.github.obhen233.spi.AutoApprovalStrategy}
 */
public interface AutoApprovalStrategy {

    /**
     * Decide the approval action for a tool invocation.
     *
     * @param context the full context of the tool invocation
     * @return the approval decision
     */
    ApprovalDecision decide(ApprovalContext context);

    /**
     * Priority of this strategy. Higher priority strategies are consulted first.
     * If a strategy returns a non-ASK decision, that decision is final.
     * If it returns ASK, the next strategy in priority order is consulted.
     * If all strategies return ASK, the fallback behavior applies.
     */
    default int getPriority() {
        return 0;
    }

    enum ApprovalDecision {
        /** Automatically approve the operation. */
        APPROVE,
        /** Automatically reject the operation. */
        REJECT,
        /** Ask the user for confirmation. */
        ASK
    }
}
