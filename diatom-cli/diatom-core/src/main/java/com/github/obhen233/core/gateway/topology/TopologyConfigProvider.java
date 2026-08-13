package com.github.obhen233.core.gateway.topology;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.topology.model.TopologyDefinition;
import com.github.obhen233.core.gateway.topology.model.TopologyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton that provides topology-defined capabilities for WorkerInfo override.
 *
 * <p>When an active published topology exists, worker capabilities defined in the
 * topology take precedence over capability.md. When no active topology exists,
 * the system falls back to existing behavior (capability.md).</p>
 *
 * <p>Called by {@link com.github.obhen233.core.gateway.registry.FileSystemWorkerRegistry}
 * during {@code register()} to merge topology capabilities.</p>
 */
public class TopologyConfigProvider {
    private static final Logger logger = LoggerFactory.getLogger(TopologyConfigProvider.class);

    private static volatile TopologyConfigProvider instance;

    private volatile TopologyService topologyService;

    public TopologyConfigProvider() {
        instance = this;
    }

    /**
     * Get the singleton instance. May be null if no TopologyService is configured.
     */
    public static TopologyConfigProvider getInstance() {
        return instance;
    }

    /**
     * Set the TopologyService (called at startup after GatewayHttpServer creates it).
     */
    public void setTopologyService(TopologyService topologyService) {
        this.topologyService = topologyService;
    }

    /**
     * Parse the topology editor's capabilities format into Map<String, Double>.
     * <p>
     * The topology editor stores capabilities as:
     * <ul>
     *   <li>String: {"strengths": "数学计算"} — single comma-separated string</li>
     *   <li>List: {"strengths": ["数学计算", "数据分析"]} — JSON array of strings</li>
     *   <li>Map: {"数学计算": 0.85} — directly in Map&lt;String, Double&gt; format</li>
     * </ul>
     * Returns a map of individual capability names to default score 0.85.
     */
    static Map<String, Double> parseCapabilities(Map<String, Object> rawCaps) {
        if (rawCaps == null || rawCaps.isEmpty()) return null;

        Map<String, Double> result = new HashMap<>();

        // Check for "strengths" key (topology editor format)
        Object strengths = rawCaps.get("strengths");
        if (strengths instanceof String) {
            String strengthsStr = (String) strengths;
            String[] items = strengthsStr.split("[,，、]");
            for (String item : items) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    result.put(trimmed, 0.85);
                }
            }
            return result;
        }
        if (strengths instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) strengths;
            for (String item : list) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    result.put(trimmed, 0.85);
                }
            }
            return result;
        }

        // Fallback: try direct Map<String, Double> format
        for (Map.Entry<String, Object> entry : rawCaps.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Number) {
                result.put(key, ((Number) val).doubleValue());
            } else if (val instanceof String && !((String) val).trim().isEmpty()) {
                // Individual capability as string value
                result.put(((String) val).trim(), 0.85);
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Apply topology-defined capabilities to a WorkerInfo during registration.
     *
     * <p>If an active published topology exists and has a node matching the worker's ID,
     * that node's capabilities are merged into the WorkerInfo. If no match is found,
     * or no active topology exists, the WorkerInfo is unchanged.</p>
     *
     * @param worker the worker being registered (modified in-place)
     */
    public void applyCapabilities(WorkerInfo worker) {
        if (worker == null) return;
        TopologyService svc = topologyService;
        if (svc == null) return;

        TopologyDefinition def = svc.getActiveDefinition();
        if (def == null) return;

        List<TopologyNode> nodes = def.getNodes();
        if (nodes == null || nodes.isEmpty()) return;

        // Find a node matching this worker's ID
        TopologyNode matchingNode = null;
        for (TopologyNode node : nodes) {
            if (worker.getWorkerId().equals(node.getId())) {
                matchingNode = node;
                break;
            }
        }
        if (matchingNode == null) return;

        // Merge capabilities from topology node into worker.
        // The topology editor stores capabilities as {"strengths": "数学计算, 数据分析"} (String)
        // or {"strengths": ["数学计算", "数据分析"]} (JSON array).
        // Convert to Map<String, Double> format expected by WorkerInfo.
        Map<String, Object> rawCaps = matchingNode.getCapabilities();
        Map<String, Double> parsedCaps = parseCapabilities(rawCaps);
        if (parsedCaps != null && !parsedCaps.isEmpty()) {
            worker.mergeCapabilities(parsedCaps);
            logger.debug("Applied topology capabilities to worker {}: {}",
                    worker.getWorkerId(), parsedCaps.keySet());
        }

        // Apply other worker-level properties from the topology node
        if (matchingNode.getTraits() != null && !matchingNode.getTraits().isEmpty()) {
            worker.setTraits(matchingNode.getTraits());
        }
        if (matchingNode.getBoundaries() != null && !matchingNode.getBoundaries().isEmpty()) {
            worker.setBoundaries(matchingNode.getBoundaries());
        }
        if (matchingNode.getModel() != null && !matchingNode.getModel().isEmpty()) {
            worker.setModel(matchingNode.getModel());
        }
        if (matchingNode.getTier() != null && !matchingNode.getTier().isEmpty()) {
            worker.setTier(matchingNode.getTier());
        }
        if (matchingNode.getMaxConcurrency() != null) {
            worker.setMaxConcurrency(matchingNode.getMaxConcurrency());
        }
        if (matchingNode.getMaxTokens() != null) {
            worker.setMaxTokens(matchingNode.getMaxTokens());
        }
        if (matchingNode.getSupportsToolCalls() != null) {
            worker.setSupportsToolCalls(matchingNode.getSupportsToolCalls());
        }
    }
}
