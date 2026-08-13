package com.github.obhen233.core.security;

import com.github.obhen233.spi.AutoApprovalStrategy;
import com.github.obhen233.spi.AutoApprovalStrategy.ApprovalDecision;
import com.github.obhen233.spi.SpiLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Resolves approval decisions by combining {@link SandboxLevel} and
 * {@link ApprovalPolicy} with SPI-loaded {@link AutoApprovalStrategy} plugins.
 * <p>
 * Built-in defaults for each (SandboxLevel, ApprovalPolicy) pair are registered
 * as the lowest-priority strategies. SPI-loaded strategies (with higher priority)
 * can override the built-in behavior.
 * <p>
 * Usage:
 * <pre>{@code
 * ApprovalStrategyResolver resolver = new ApprovalStrategyResolver(sandboxLevel, approvalPolicy);
 * ApprovalDecision decision = resolver.decide(context);
 * }</pre>
 */
public class ApprovalStrategyResolver {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalStrategyResolver.class);

    private final SandboxLevel sandboxLevel;
    private final ApprovalPolicy approvalPolicy;
    private final List<AutoApprovalStrategy> strategies;

    /**
     * Create a resolver with the given sandbox level and approval policy.
     * SPI-loaded {@link AutoApprovalStrategy} instances are discovered automatically
     * and merged with built-in defaults.
     */
    public ApprovalStrategyResolver(SandboxLevel sandboxLevel, ApprovalPolicy approvalPolicy) {
        this.sandboxLevel = sandboxLevel;
        this.approvalPolicy = approvalPolicy;
        this.strategies = buildStrategyChain();
    }

    public SandboxLevel getSandboxLevel() {
        return sandboxLevel;
    }

    public ApprovalPolicy getApprovalPolicy() {
        return approvalPolicy;
    }

    /**
     * Decide the approval action for a tool invocation by consulting all
     * strategies in priority order. The first non-ASK decision wins.
     */
    public ApprovalDecision decide(ApprovalContext context) {
        for (AutoApprovalStrategy strategy : strategies) {
            try {
                ApprovalDecision decision = strategy.decide(context);
                if (decision != ApprovalDecision.ASK) {
                    return decision;
                }
            } catch (Exception e) {
                logger.warn("AutoApprovalStrategy {} threw exception: {}", strategy.getClass().getName(), e.getMessage());
            }
        }
        // Fallback: ASK
        return ApprovalDecision.ASK;
    }

    /**
     * Quick check: are write operations (non-read, non-danger) auto-approved
     * within the workspace boundary? This mirrors the old {@code autoApproveWrite}
     * boolean for backward-compatible checks in existing code.
     */
    public boolean isWriteAutoApprovedWithinWorkspace() {
        return approvalPolicy == ApprovalPolicy.AUTO || approvalPolicy == ApprovalPolicy.SILENT;
    }

    /**
     * Quick check: are operations outside workspace silently rejected (no prompt)?
     */
    public boolean isSilentlyRejectOutsideWorkspace() {
        return approvalPolicy == ApprovalPolicy.SILENT;
    }

    // ==================== Strategy Chain Construction ====================

    private List<AutoApprovalStrategy> buildStrategyChain() {
        List<AutoApprovalStrategy> chain = new ArrayList<>();

        // 1. Load SPI strategies (highest priority)
        try {
            List<AutoApprovalStrategy> spiStrategies = SpiLoader.getAll(AutoApprovalStrategy.class);
            spiStrategies.sort(Comparator.comparingInt(AutoApprovalStrategy::getPriority).reversed());
            chain.addAll(spiStrategies);
            if (!spiStrategies.isEmpty()) {
                logger.info("Loaded {} AutoApprovalStrategy SPI implementations", spiStrategies.size());
            }
        } catch (Exception e) {
            logger.debug("No SPI AutoApprovalStrategy implementations found: {}", e.getMessage());
        }

        // 2. Built-in default strategy (lowest priority)
        chain.add(new BuiltInStrategy(sandboxLevel, approvalPolicy));

        return chain;
    }

    // ==================== Built-in Strategy ====================

    /**
     * Built-in default strategy that maps (SandboxLevel, ApprovalPolicy) to
     * approval decisions. This runs last, after all SPI strategies.
     */
    static class BuiltInStrategy implements AutoApprovalStrategy {

        private final SandboxLevel sandboxLevel;
        private final ApprovalPolicy approvalPolicy;

        BuiltInStrategy(SandboxLevel sandboxLevel, ApprovalPolicy approvalPolicy) {
            this.sandboxLevel = sandboxLevel;
            this.approvalPolicy = approvalPolicy;
        }

        @Override
        public ApprovalDecision decide(ApprovalContext context) {
            String aiClassification = context.getAiClassification();
            boolean isDanger = aiClassification != null && aiClassification.toUpperCase().contains("[DANGER]");
            boolean isRead = aiClassification != null && aiClassification.toUpperCase().contains("[READ]");
            boolean outsideWorkspace = context.isOutsideWorkspace();

            switch (sandboxLevel) {
                case READ_ONLY:
                    return decideReadOnly(isRead, isDanger, outsideWorkspace);
                case WORKSPACE:
                    return decideWorkspace(isRead, isDanger, outsideWorkspace);
                case FULL:
                    return decideFull(isRead, isDanger, outsideWorkspace);
                default:
                    return ApprovalDecision.ASK;
            }
        }

        private ApprovalDecision decideReadOnly(boolean isRead, boolean isDanger, boolean outsideWorkspace) {
            if (isRead) {
                return ApprovalDecision.APPROVE;
            }
            if (isDanger) {
                return ApprovalDecision.REJECT;
            }
            // WRITE operations
            switch (approvalPolicy) {
                case SILENT:
                    return ApprovalDecision.REJECT;
                case AUTO:
                case ASK:
                default:
                    return outsideWorkspace ? ApprovalDecision.ASK : ApprovalDecision.ASK;
            }
        }

        private ApprovalDecision decideWorkspace(boolean isRead, boolean isDanger, boolean outsideWorkspace) {
            if (isRead) {
                return outsideWorkspace && approvalPolicy == ApprovalPolicy.SILENT
                    ? ApprovalDecision.REJECT
                    : ApprovalDecision.APPROVE;
            }
            if (isDanger) {
                switch (approvalPolicy) {
                    case SILENT:
                        return ApprovalDecision.REJECT;
                    case AUTO:
                        return outsideWorkspace ? ApprovalDecision.ASK : ApprovalDecision.ASK;
                    case ASK:
                    default:
                        return ApprovalDecision.ASK;
                }
            }
            // WRITE operations
            switch (approvalPolicy) {
                case SILENT:
                    return outsideWorkspace ? ApprovalDecision.REJECT : ApprovalDecision.APPROVE;
                case AUTO:
                    return outsideWorkspace ? ApprovalDecision.ASK : ApprovalDecision.APPROVE;
                case ASK:
                default:
                    return outsideWorkspace ? ApprovalDecision.ASK : ApprovalDecision.ASK;
            }
        }

        private ApprovalDecision decideFull(boolean isRead, boolean isDanger, boolean outsideWorkspace) {
            if (isDanger) {
                switch (approvalPolicy) {
                    case SILENT:
                        return ApprovalDecision.REJECT;
                    case AUTO:
                    case ASK:
                    default:
                        return ApprovalDecision.ASK;
                }
            }
            // READ or WRITE — always approve in FULL mode
            switch (approvalPolicy) {
                case SILENT:
                case AUTO:
                case ASK:
                default:
                    return ApprovalDecision.APPROVE;
            }
        }
    }
}
