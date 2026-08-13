package com.github.obhen233.core.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.agent.context.ExplorationBudget;
import com.github.obhen233.core.agent.tool.ToolExecutor;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.security.ApprovalContext;
import com.github.obhen233.core.security.ApprovalStrategyResolver;
import com.github.obhen233.core.security.SandboxLevel;
import com.github.obhen233.spi.AutoApprovalStrategy.ApprovalDecision;
import com.github.obhen233.spi.ToolSecurityProvider;
import com.github.obhen233.util.I18n;
import com.github.obhen233.core.skill.Skill;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.github.obhen233.util.JsonUtils;

/**
 * Default implementation of PermissionChecker.
 *
 * Encapsulates all permission checking logic: tool read-only detection,
 * workspace boundary checks, command approval, exploration budget,
 * and confirmation dialog generation.
 */
public class DefaultPermissionChecker implements PermissionChecker {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPermissionChecker.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final ToolRegistry registry;
    private final ToolSecurityProvider securityProvider;
    private final ProjectIndexer projectIndexer;
    private final ToolExecutor toolExecutor;
    private final ExplorationBudget explorationBudget;
    private SkillManager skillManager;

    private volatile ApprovalStrategyResolver strategyResolver;
    private final Set<String> approvedCommands = new HashSet<>();

    public DefaultPermissionChecker(ToolRegistry registry, ToolSecurityProvider securityProvider,
                                     ProjectIndexer projectIndexer, ToolExecutor toolExecutor,
                                     ExplorationBudget explorationBudget) {
        this.registry = registry;
        this.securityProvider = securityProvider;
        this.projectIndexer = projectIndexer;
        this.toolExecutor = toolExecutor;
        this.explorationBudget = explorationBudget;
        this.strategyResolver = new ApprovalStrategyResolver(SandboxLevel.WORKSPACE, com.github.obhen233.core.security.ApprovalPolicy.ASK);
    }

    // ==================== Public API ====================

    /**
     * Set the approval strategy resolver. This replaces the old boolean
     * autoApproveWrite with a full strategy-based approach.
     */
    public void setApprovalStrategyResolver(ApprovalStrategyResolver resolver) {
        this.strategyResolver = resolver;
        this.toolExecutor.setApprovalStrategyResolver(resolver);
        logger.info("Approval strategy resolver set: level={}, policy={}",
            resolver.getSandboxLevel(), resolver.getApprovalPolicy());
    }

    public ApprovalStrategyResolver getApprovalStrategyResolver() {
        return strategyResolver;
    }

    /**
     * Backward-compatible setter — maps old boolean to AUTO/ASK policy.
     * true  → SandboxLevel.WORKSPACE + ApprovalPolicy.AUTO
     * false → SandboxLevel.WORKSPACE + ApprovalPolicy.ASK
     */
    public void setAutoApproveWrite(boolean autoApprove) {
        com.github.obhen233.core.security.ApprovalPolicy policy = autoApprove
            ? com.github.obhen233.core.security.ApprovalPolicy.AUTO
            : com.github.obhen233.core.security.ApprovalPolicy.ASK;
        this.strategyResolver = new ApprovalStrategyResolver(SandboxLevel.WORKSPACE, policy);
        this.toolExecutor.setApprovalStrategyResolver(this.strategyResolver);
        logger.info("Auto-approve write operations: {} (via backward-compat setter)", autoApprove);
    }

    public boolean isAutoApproveWrite() {
        return strategyResolver.isWriteAutoApprovedWithinWorkspace();
    }

    public void addApprovedCommand(String command) {
        this.approvedCommands.add(command.toLowerCase());
        this.toolExecutor.addApprovedCommand(command);
        logger.info("Added approved command: {}", command);
    }

    public void clearApprovedCommands() {
        this.approvedCommands.clear();
        this.toolExecutor.clearApprovedCommands();
    }

    public void setSkillManager(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    // ==================== PermissionChecker Interface ====================

    @Override
    public String needsConfirmation(String toolName, String argsJson, String aiClassification) {
        if (argsJson == null) return null;

        // Check skill allowedTools restrictions
        if (skillManager != null) {
            String skillCheck = checkSkillToolAllowed(toolName, skillManager.getActiveSkillsWithRestrictions());
            if (skillCheck != null) {
                return skillCheck;
            }
        }

        boolean outsideWorkspace = isPathOutsideWorkspace(argsJson, toolName);

        // Build ApprovalContext for strategy resolution
        ApprovalContext context = ApprovalContext.builder()
            .toolName(toolName)
            .argsJson(argsJson)
            .aiClassification(aiClassification)
            .sandboxLevel(strategyResolver.getSandboxLevel())
            .outsideWorkspace(outsideWorkspace)
            .build();

        // Consult the strategy chain
        ApprovalDecision decision = strategyResolver.decide(context);

        switch (decision) {
            case APPROVE:
                return null; // No confirmation needed
            case REJECT:
                return getRejectionMessage(toolName, argsJson, outsideWorkspace);
            case ASK:
                // Fall through to existing ask logic
                break;
        }

        // ==================== ASK logic (existing behavior) ====================

        // Step 1: Check Tool metadata from registry (primary source)
        Tool tool = registry.getTool(toolName);
        if (tool != null) {
            if (tool.isReadOnly()) {
                if (outsideWorkspace) {
                    return getWriteConfirmation(toolName, argsJson, tool.getConfirmationTemplate());
                }
                return null;
            }

            if (tool.isRequiresConfirmation()) {
                return formatTemplate(tool.getConfirmationTemplate(), argsJson);
            }

            String riskLevel = tool.getRiskLevel();
            if (riskLevel != null && !"none".equals(riskLevel)) {
                return getWriteConfirmation(toolName, argsJson, tool.getConfirmationTemplate());
            }
        }

        // Step 2: Fallback to security provider for tools without metadata
        if (securityProvider.isReadOnly(toolName, argsJson)) {
            if (outsideWorkspace) {
                return getWriteConfirmation(toolName, argsJson, null);
            }
            return null;
        }

        if (aiClassification != null) {
            String upperClassification = aiClassification.toUpperCase();
            if (upperClassification.contains("[READ]")) {
                logger.debug("AI classified as [READ], will verify with local rules");
            } else if (upperClassification.contains("[DANGER]")) {
                return I18n.get("tool_dangerous_unknown", toolName);
            } else if (upperClassification.contains("[WRITE]")) {
                logger.debug("AI classified as [WRITE], proceeding with local rules");
            }
        }

        if (outsideWorkspace) {
            return getWriteConfirmation(toolName, argsJson, null);
        }

        if (isCommandApproved(toolName, argsJson)) {
            return null;
        }

        if ("run_command".equals(toolName)) {
            String cmd = extractCmd(argsJson);
            if (isReadOnlyCommand(cmd)) {
                return null;
            }
        }

        return getWriteConfirmation(toolName, argsJson, null);
    }

    @Override
    public String parseAiClassification(String reasoningContent) {
        if (reasoningContent == null) return null;
        if (reasoningContent.contains("[READ]") || reasoningContent.contains("[WRITE]") || reasoningContent.contains("[DANGER]")) {
            int start = Math.max(0, reasoningContent.indexOf("[READ]"));
            start = Math.max(start, Math.max(0, reasoningContent.indexOf("[WRITE]")));
            start = Math.max(start, Math.max(0, reasoningContent.indexOf("[DANGER]")));
            int end = Math.min(reasoningContent.length(), start + 50);
            return reasoningContent.substring(start, end);
        }
        return null;
    }

    @Override
    public String extractPathFromArgs(String argsJson) {
        if (argsJson == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(argsJson);
            if (node.has("path")) {
                return node.get("path").asText();
            }
            if (node.has("cmd")) {
                return null;
            }
        } catch (Exception e) {
            logger.debug("Failed to extract path from args: {}", argsJson);
        }
        return null;
    }

    @Override
    public boolean isPermissionError(String result) {
        if (result == null) return false;
        String lower = result.toLowerCase();
        return lower.startsWith("access denied") ||
               lower.startsWith("error: access denied") ||
               lower.startsWith("command not allowed") ||
               lower.startsWith("error: command not allowed") ||
               lower.startsWith("path outside workspace") ||
               lower.startsWith("error: path outside workspace") ||
               lower.startsWith("unauthorized access") ||
               lower.startsWith("error: unauthorized");
    }

    @Override
    public String checkExplorationBudget(String toolName, String argsJson) {
        boolean allowed = explorationBudget.recordToolCall(toolName, argsJson);
        if (!allowed) {
            return explorationBudget.getBudgetExceededErrorMessage(toolName);
        }
        // Track budget-exempt search calls for strategy guidance
        if ("search_symbols".equals(toolName) || "search_references".equals(toolName)) {
            String searchWarning = explorationBudget.recordSearchCall(toolName, argsJson);
            if (searchWarning != null) {
                return searchWarning;
            }
        }
        return null;
    }

    // ==================== Internal Permission Methods ====================

    private String formatTemplate(String template, String argsJson) {
        if (template == null || template.isEmpty()) {
            return null;
        }
        if (argsJson != null && template.contains("{}")) {
            String path = extractPathFromArgs(argsJson);
            String cmd = extractCmd(argsJson);
            if (path != null && !path.isEmpty()) {
                return template.replace("{}", path);
            }
            if (cmd != null && !cmd.isEmpty()) {
                return template.replace("{}", cmd);
            }
        }
        return template;
    }

    private boolean isReadOnlyTool(String toolName) {
        Tool tool = registry.getTool(toolName);
        if (tool != null) {
            return tool.isReadOnly();
        }
        return securityProvider.isReadOnly(toolName, null);
    }

    private boolean isPathOutsideWorkspace(String argsJson, String toolName) {
        Tool tool = registry.getTool(toolName);
        if (tool != null && !tool.isCheckWorkspaceBoundary()) {
            return false;
        }
        if (!securityProvider.checkWorkspaceBoundary(toolName, argsJson)) {
            return false;
        }
        String path = extractPathFromArgs(argsJson);
        if (path == null) return false;
        try {
            java.nio.file.Path workspacePath = projectIndexer.getContext().getProjectPath();
            if (workspacePath == null) return false;

            java.nio.file.Path workspace = workspacePath.toAbsolutePath().normalize();
            java.nio.file.Path rawPath = Paths.get(path);

            java.nio.file.Path targetPath;
            if (rawPath.isAbsolute()) {
                targetPath = rawPath.normalize();
            } else {
                // Resolve relative path against the project path (matching MCP behavior).
                // Handle project-prefixed paths like "SQLExecutor/src/main/java/..."
                // by stripping the project name prefix before resolving.
                String projectName = workspace.getFileName().toString();
                if (path.startsWith(projectName + "/") || path.startsWith(projectName + "\\")) {
                    String stripped = path.substring(projectName.length() + 1);
                    targetPath = workspace.resolve(stripped).normalize();
                } else {
                    targetPath = workspace.resolve(path).normalize();
                }
            }

            return !targetPath.startsWith(workspace);
        } catch (Exception e) {
            logger.warn("Failed to check workspace boundary, treating as outside workspace for safety: {}", path);
            return true;
        }
    }

    private String getWriteConfirmation(String toolName, String argsJson, String template) {
        if (template != null && !template.isEmpty()) {
            return formatTemplate(template, argsJson);
        }
        return securityProvider.getConfirmationMessage(toolName, argsJson);
    }

    private String getRejectionMessage(String toolName, String argsJson, boolean outsideWorkspace) {
        if (outsideWorkspace) {
            return I18n.get("tool_rejected_outside_workspace", toolName);
        }
        String riskMsg = securityProvider.getRiskDescription(toolName, argsJson);
        if (riskMsg != null) {
            return riskMsg;
        }
        return I18n.get("tool_rejected", toolName);
    }

    private boolean isCommandApproved(String toolName, String argsJson) {
        if ("run_command".equals(toolName) && argsJson != null) {
            String cmd = extractCmd(argsJson);
            if (cmd != null && securityProvider.isCommandApproved(cmd)) {
                return true;
            }
        }
        if (approvedCommands.isEmpty()) {
            return false;
        }
        if ("run_command".equals(toolName) && argsJson != null) {
            String cmd = extractCmd(argsJson);
            if (cmd != null) {
                String cmdName = extractCmdName(cmd).toLowerCase();
                for (String approved : approvedCommands) {
                    if (cmdName.equals(approved)) {
                        return true;
                    }
                }
            }
        }
        return approvedCommands.contains(toolName.toLowerCase());
    }

    private String extractRiskDescription(String toolName, String argsJson) {
        String msg = securityProvider.getRiskDescription(toolName, argsJson);
        if (msg != null) return msg;
        return I18n.get("tool_dangerous_unknown", toolName);
    }

    // ==================== Command Detection ====================

    boolean isReadOnlyCommand(String cmd) {
        return toolExecutor.isReadOnlyCommand(cmd);
    }

    private boolean isReadOnlyCommandChain(String cmd) {
        String[] separators = {" && ", " || ", ";", "|"};
        String lastPart = cmd;
        for (String sep : separators) {
            if (cmd.contains(sep)) {
                String[] parts = cmd.split(Pattern.quote(sep));
                lastPart = parts[parts.length - 1].trim();
            }
        }
        String lowerLast = lastPart.toLowerCase();
        if (lowerLast.startsWith("cd ") || lowerLast.startsWith("cd/")) {
            return true;
        }
        String[] readOnlyParts = {"tail", "head", "cat", "grep", "find", "ls", "dir", "wc", "sed", "awk", "findstr"};
        for (String part : readOnlyParts) {
            if (lowerLast.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private String extractCmdName(String cmd) {
        String[] parts = cmd.trim().split("\\s+");
        String rawName = parts[0];
        if (rawName.contains("/")) {
            return rawName.substring(rawName.lastIndexOf('/') + 1);
        }
        return rawName;
    }

    private String extractCmd(String argsJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(argsJson);
            String cmd = node.has("cmd") ? node.get("cmd").asText() : "";
            String args = node.has("args") ? node.get("args").asText() : "";
            String fullCmd = cmd;
            if (args != null && !args.isEmpty()) {
                fullCmd = cmd + " " + args;
            }
            return fullCmd.trim();
        } catch (Exception ignored) {}
        return argsJson;
    }
}
