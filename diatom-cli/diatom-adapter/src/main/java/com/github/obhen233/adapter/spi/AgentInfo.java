package com.github.obhen233.adapter.spi;

import java.util.List;
import java.util.Map;

/**
 * Metadata describing an adapter's capabilities and characteristics.
 *
 * <p>This information is sent to Gateway during registration for capability-based routing.</p>
 *
 * @param model             the agent model identifier
 * @param traits            agent characteristics (e.g. "coding", "reasoning")
 * @param capabilities      capability routing scores
 * @param maxConcurrency    max concurrent tasks (defaults to 1 when &le; 0)
 * @param costPer1kTokens   cost per 1000 tokens
 * @param supportsStreaming whether SSE streaming is supported
 * @param supportsToolCalls whether tool calls are supported
 * @param maxSteps          max agent steps (defaults to 50 when &le; 0)
 * @param maxTokens         max context tokens (defaults to 128000 when &le; 0)
 */
public record AgentInfo(
        String model,
        List<String> traits,
        Map<String, Double> capabilities,
        int maxConcurrency,
        double costPer1kTokens,
        boolean supportsStreaming,
        boolean supportsToolCalls,
        int maxSteps,
        int maxTokens) {

    public AgentInfo {
        if (maxConcurrency <= 0) maxConcurrency = 1;
        if (maxSteps <= 0) maxSteps = 50;
        if (maxTokens <= 0) maxTokens = 128000;
    }
}
