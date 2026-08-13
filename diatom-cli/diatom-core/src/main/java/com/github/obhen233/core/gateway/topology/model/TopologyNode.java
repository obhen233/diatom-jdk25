package com.github.obhen233.core.gateway.topology.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * JSON model for a node in the topology definition.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TopologyNode {
    private String id;
    private String type;         // "gateway" or "worker"
    private String label;
    private Position position;
    private Map<String, Object> capabilities;
    private List<String> traits;
    private List<String> boundaries;
    private String model;
    private String tier;
    private Integer maxTokens;
    private Integer maxOutputTokens;
    private Boolean supportsToolCalls;
    private Boolean supportsStreaming;
    private Integer maxConcurrency;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Position {
        private double x;
        private double y;

        public Position() {}
        public Position(double x, double y) { this.x = x; this.y = y; }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }
        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Position getPosition() { return position; }
    public void setPosition(Position position) { this.position = position; }
    public Map<String, Object> getCapabilities() { return capabilities; }
    public void setCapabilities(Map<String, Object> capabilities) { this.capabilities = capabilities; }
    public List<String> getTraits() { return traits; }
    public void setTraits(List<String> traits) { this.traits = traits; }
    public List<String> getBoundaries() { return boundaries; }
    public void setBoundaries(List<String> boundaries) { this.boundaries = boundaries; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public Boolean getSupportsToolCalls() { return supportsToolCalls; }
    public void setSupportsToolCalls(Boolean supportsToolCalls) { this.supportsToolCalls = supportsToolCalls; }
    public Boolean getSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(Boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }
    public Integer getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(Integer maxConcurrency) { this.maxConcurrency = maxConcurrency; }
}
