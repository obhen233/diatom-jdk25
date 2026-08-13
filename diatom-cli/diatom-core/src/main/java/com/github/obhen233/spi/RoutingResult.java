package com.github.obhen233.spi;

import com.github.obhen233.core.gateway.routing.TaskRequirement;

/**
 * Result of a local routing attempt.
 * <p>
 * Contains the derived {@link TaskRequirement}, a confidence score, and a
 * source identifier for observability.
 */
public class RoutingResult {

    private final TaskRequirement requirement;
    private final double confidence;
    private final String source;

    public RoutingResult(TaskRequirement requirement, double confidence, String source) {
        this.requirement = requirement;
        this.confidence = confidence;
        this.source = source;
    }

    public TaskRequirement getRequirement() {
        return requirement;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getSource() {
        return source;
    }
}
