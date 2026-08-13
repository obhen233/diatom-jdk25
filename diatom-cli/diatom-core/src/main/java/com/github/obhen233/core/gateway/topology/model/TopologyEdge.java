package com.github.obhen233.core.gateway.topology.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * JSON model for an edge (connection) in the topology definition.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TopologyEdge {
    private String id;
    private String from;
    private String to;
    private EdgeRules rules;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EdgeRules {
        private String priority;           // e.g. "quality", "speed", "cost"
        private List<String> taskTypes;    // list of capability names
        private Double loadThreshold;      // 0.0 to 1.0
        private Boolean fallbackAllowed;
        private Double weightMultiplier;

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public List<String> getTaskTypes() { return taskTypes; }
        public void setTaskTypes(List<String> taskTypes) { this.taskTypes = taskTypes; }
        public Double getLoadThreshold() { return loadThreshold; }
        public void setLoadThreshold(Double loadThreshold) { this.loadThreshold = loadThreshold; }
        public Boolean getFallbackAllowed() { return fallbackAllowed; }
        public void setFallbackAllowed(Boolean fallbackAllowed) { this.fallbackAllowed = fallbackAllowed; }
        public Double getWeightMultiplier() { return weightMultiplier; }
        public void setWeightMultiplier(Double weightMultiplier) { this.weightMultiplier = weightMultiplier; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public EdgeRules getRules() { return rules; }
    public void setRules(EdgeRules rules) { this.rules = rules; }
}
