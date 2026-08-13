package com.github.obhen233.core.gateway.topology.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * JSON model for the full topology definition (draft or published).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TopologyDefinition {
    private List<TopologyNode> nodes;
    private List<TopologyEdge> edges;

    public List<TopologyNode> getNodes() { return nodes; }
    public void setNodes(List<TopologyNode> nodes) { this.nodes = nodes; }
    public List<TopologyEdge> getEdges() { return edges; }
    public void setEdges(List<TopologyEdge> edges) { this.edges = edges; }
}
