package com.github.obhen233.core.gateway.routing;

import com.github.obhen233.core.gateway.registry.WorkerInfo;

import java.util.List;
import java.util.List;
import java.util.Map;

/**
 * Worker 打分计算器
 */
public class ScoreCalculator {

    public double calculate(WorkerInfo worker, TaskRequirement requirement) {
        // Apply penalty checks before scoring
        double boundaryPenalty = checkBoundaries(worker, requirement);
        double tokenPenalty = checkTokenBudget(worker, requirement);
        double toolPenalty = checkToolSupport(worker, requirement);

        WeightProfile profile = WeightProfile.forPriority(requirement.getBudgetPriority());

        double capabilityScore = calcCapabilityScore(worker, requirement.getRequiredCapabilities());
        double traitScore = calcTraitScore(worker, requirement.getPreferredModelTraits());
        double loadScore = 1.0 - worker.getMetrics().getCurrentLoad();
        double costScore = worker.getCostPer1kTokens() > 0
                ? 1.0 - Math.min(worker.getCostPer1kTokens() / 0.1, 1.0) : 0.5;
        double successRateScore = worker.getMetrics().getSuccessRate();
        double latencyScore = worker.getMetrics().getAvgLatencyMs() > 0
                ? 1.0 - Math.min(worker.getMetrics().getAvgLatencyMs() / 10000.0, 1.0) : 0.5;
        double workspaceScore = calcWorkspaceScore(worker, requirement.getWorkspaceHint());

        double rawScore = profile.getCapabilityWeight() * capabilityScore
             + profile.getTraitWeight() * traitScore
             + profile.getLoadWeight() * loadScore
             + profile.getCostWeight() * costScore
             + profile.getSuccessRateWeight() * successRateScore
             + profile.getLatencyWeight() * latencyScore
             + workspaceScore;

        // Apply penalties multiplicatively
        return rawScore * boundaryPenalty * tokenPenalty * toolPenalty;
    }

    /**
     * Check if the task requirement conflicts with worker's declared boundaries.
     * Returns a penalty multiplier (0.0 - 1.0).
     */
    private double checkBoundaries(WorkerInfo worker, TaskRequirement requirement) {
        List<String> boundaries = worker.getBoundaries();
        if (boundaries == null || boundaries.isEmpty()) return 1.0;

        List<String> requiredCaps = requirement.getRequiredCapabilities();
        if (requiredCaps == null || requiredCaps.isEmpty()) return 1.0;

        // Check if any boundary strongly conflicts with required capabilities
        for (String cap : requiredCaps) {
            String capLower = cap.toLowerCase();
            for (String boundary : boundaries) {
                String bLower = boundary.toLowerCase();
                // e.g., boundary "no internet access" conflicts with cap "网络访问" or "web_scraping"
                if (bLower.contains("no") || bLower.contains("无") || bLower.contains("不能") || bLower.contains("not")) {
                    // Boundary says worker CANNOT do something
                    // Check if the capability relates to the exclusion
                    if (capLower.contains("internet") || capLower.contains("web") || capLower.contains("网络") ||
                        capLower.contains("api") || capLower.contains("scraping") || capLower.contains("爬虫")) {
                        if (bLower.contains("internet") || bLower.contains("web") || bLower.contains("网络") ||
                            bLower.contains("external") || bLower.contains("api")) {
                            return 0.3;
                        }
                    }
                    if (capLower.contains("production") || capLower.contains("deploy") || capLower.contains("部署")) {
                        if (bLower.contains("production") || bLower.contains("deploy") || bLower.contains("部署") ||
                            bLower.contains("confirm") || bLower.contains("确认")) {
                            return 0.3;
                        }
                    }
                    if (capLower.contains("credential") || capLower.contains("secret") || capLower.contains("密钥") ||
                        capLower.contains("password") || capLower.contains("凭据")) {
                        if (bLower.contains("credential") || bLower.contains("secret") || bLower.contains("密钥") ||
                            bLower.contains("access") || bLower.contains("凭据")) {
                            return 0.3;
                        }
                    }
                }
            }
        }
        return 1.0;
    }

    /**
     * Check if the task's expected token count exceeds the worker's maxTokens.
     * Returns a penalty multiplier (0.0 - 1.0).
     */
    private double checkTokenBudget(WorkerInfo worker, TaskRequirement requirement) {
        int maxTokens = worker.getMaxTokens();
        if (maxTokens <= 0) return 1.0; // No limit declared

        int expectedTokens = requirement.getExpectedTokens();
        if (expectedTokens <= 0) return 1.0;

        if (expectedTokens > maxTokens) {
            return 0.5;
        }
        return 1.0;
    }

    /**
     * Check if the task requires tool-use capability but the worker doesn't support it.
     * Returns a penalty multiplier (0.0 - 1.0).
     */
    private double checkToolSupport(WorkerInfo worker, TaskRequirement requirement) {
        List<String> requiredCaps = requirement.getRequiredCapabilities();
        if (requiredCaps == null || requiredCaps.isEmpty()) return 1.0;

        boolean needsToolUse = requiredCaps.stream()
                .anyMatch(c -> c.toLowerCase().contains("tool") || c.toLowerCase().contains("命令") ||
                               c.toLowerCase().contains("command") || c.toLowerCase().contains("execute"));
        if (!needsToolUse) return 1.0;

        // If worker doesn't support tool calls, apply strong penalty
        if (!worker.isSupportsToolCalls()) {
            return 0.3;
        }
        return 1.0;
    }

    private double calcCapabilityScore(WorkerInfo worker, List<String> requiredCapabilities) {
        if (requiredCapabilities == null || requiredCapabilities.isEmpty()) return 0.5;
        Map<String, Double> workerCaps = worker.getCapabilities();
        if (workerCaps.isEmpty()) return 0.5; // No capabilities reported → can't discriminate

        double total = 0;
        for (String cap : requiredCapabilities) {
            // 1. Try exact match
            Double exact = workerCaps.get(cap);
            if (exact != null) {
                total += exact;
                continue;
            }
            // 2. Try case-insensitive match
            boolean matched = false;
            String capLower = cap.toLowerCase();
            for (Map.Entry<String, Double> entry : workerCaps.entrySet()) {
                String keyLower = entry.getKey().toLowerCase();
                // 3. Try substring match: required cap contains worker key OR worker key contains required cap
                if (keyLower.contains(capLower) || capLower.contains(keyLower)) {
                    total += entry.getValue();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                total += 0.0;
            }
        }
        return total / requiredCapabilities.size();
    }

    private double calcTraitScore(WorkerInfo worker, List<String> preferredTraits) {
        if (preferredTraits == null || preferredTraits.isEmpty() || worker.getTraits() == null) return 0.5;
        long hitCount = preferredTraits.stream()
                .filter(t -> worker.getTraits().contains(t))
                .count();
        return preferredTraits.isEmpty() ? 0.5 : (double) hitCount / preferredTraits.size();
    }

    /**
     * Calculate workspace affinity score.
     * Returns 0.5 if the worker's workspace path matches the task's workspace hint,
     * 0 otherwise. This ensures deploy tasks are routed to workers that have
     * access to the required project files.
     */
    private double calcWorkspaceScore(WorkerInfo worker, String workspaceHint) {
        if (workspaceHint == null || workspaceHint.isEmpty()) return 0;
        String ws = worker.getWorkspace();
        if (ws == null || ws.isEmpty()) return 0;
        if (ws.contains(workspaceHint) || workspaceHint.contains(ws)) return 0.5;
        return 0;
    }
}
