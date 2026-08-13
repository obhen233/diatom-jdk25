package com.github.obhen233.core.agent.plan;

import com.github.obhen233.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class PlanGenerator {
    private static final Logger logger = LoggerFactory.getLogger(PlanGenerator.class);
    private static final String NEWLINE = System.lineSeparator();

    /**
     * Generate alternative plans (Plan A/B/J) when tool execution fails after max retries
     */
    public String generateAlternativePlans(String toolName, String args, String error,
                                           Map<String, Tool> allTools, int maxRetry) {
        StringBuilder toolList = buildToolListDescription(allTools);

        return "Command execution failed after " + maxRetry + " attempts." + NEWLINE + NEWLINE +
               "Tool: " + toolName + NEWLINE +
               "Args: " + args + NEWLINE +
               "Error: " + error + NEWLINE + NEWLINE +
               "Available tools:" + NEWLINE + toolList.toString() + NEWLINE + NEWLINE +
               "Please provide Plan A, Plan B, AND Plan Java alternatives:" + NEWLINE +
               "Note: Plan Java is a pure Java implementation that bypasses shell commands entirely." + NEWLINE +
               "```PLAN_A" + NEWLINE + "<description>" + NEWLINE + "Pros: ... | Cons: ... | Risk: ..." + NEWLINE + "```" + NEWLINE +
               "```PLAN_B" + NEWLINE + "<description>" + NEWLINE + "Pros: ... | Cons: ... | Risk: ..." + NEWLINE + "```" + NEWLINE +
               "```PLAN_JAVA" + NEWLINE + "<description>" + NEWLINE + "Pros: No shell dependency, 100% controllable runtime | Cons: May be slower | Risk: Low" + NEWLINE + "```";
    }

    /**
     * Build a tool list description for prompts
     */
    public StringBuilder buildToolListDescription(Map<String, Tool> allTools) {
        StringBuilder toolList = new StringBuilder();
        for (Tool tool : allTools.values()) {
            toolList.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append(NEWLINE);
        }
        return toolList;
    }
}