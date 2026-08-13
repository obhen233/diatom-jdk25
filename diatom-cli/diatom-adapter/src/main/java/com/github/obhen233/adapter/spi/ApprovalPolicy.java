package com.github.obhen233.adapter.spi;

/**
 * Approval policy enum, aligned with diatom-core ApprovalPolicy.
 *
 * <p>Controls whether the adapter sends confirmation callbacks back to Gateway.</p>
 */
public enum ApprovalPolicy {
    /** Send confirmation callback to Gateway for all operations */
    ASK,
    /** Auto-approve sandbox-scoped operations, ask Gateway for out-of-scope */
    AUTO,
    /** Silent mode: never send confirmation callbacks (default) */
    SILENT
}
