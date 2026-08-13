package com.github.obhen233.core.gateway.routing;

/**
 * 动态权重配置，根据 budgetPriority 调整各维度权重
 */
public class WeightProfile {
    public static final WeightProfile QUALITY = new WeightProfile(0.40, 0.20, 0.10, 0.05, 0.15, 0.10);
    public static final WeightProfile SPEED   = new WeightProfile(0.20, 0.10, 0.30, 0.10, 0.10, 0.20);
    public static final WeightProfile COST    = new WeightProfile(0.15, 0.05, 0.10, 0.50, 0.10, 0.10);

    private final double capabilityWeight;
    private final double traitWeight;
    private final double loadWeight;
    private final double costWeight;
    private final double successRateWeight;
    private final double latencyWeight;

    public WeightProfile(double capabilityWeight, double traitWeight, double loadWeight,
                         double costWeight, double successRateWeight, double latencyWeight) {
        this.capabilityWeight = capabilityWeight;
        this.traitWeight = traitWeight;
        this.loadWeight = loadWeight;
        this.costWeight = costWeight;
        this.successRateWeight = successRateWeight;
        this.latencyWeight = latencyWeight;
    }

    public double getCapabilityWeight() { return capabilityWeight; }
    public double getTraitWeight() { return traitWeight; }
    public double getLoadWeight() { return loadWeight; }
    public double getCostWeight() { return costWeight; }
    public double getSuccessRateWeight() { return successRateWeight; }
    public double getLatencyWeight() { return latencyWeight; }

    public static WeightProfile forPriority(String priority) {
        if ("speed".equalsIgnoreCase(priority)) return SPEED;
        if ("cost".equalsIgnoreCase(priority)) return COST;
        return QUALITY;
    }
}
