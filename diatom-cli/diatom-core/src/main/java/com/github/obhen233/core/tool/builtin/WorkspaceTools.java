package com.github.obhen233.core.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.core.workspace.WorkspaceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.github.obhen233.util.JsonUtils;

/**
 * AI-callable tools for managing additional workspace registrations.
 * <p>
 * When {@code filesystem.allow_external=false} (the default), paths must be
 * within the user's home directory ({@code user.home}).
 */
public class WorkspaceTools {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceTools.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();
    private static final String NEWLINE = System.lineSeparator();

    private final WorkspaceRegistry registry;
    private final boolean allowExternal;

    public WorkspaceTools(WorkspaceRegistry registry, boolean allowExternal) {
        this.registry = registry;
        this.allowExternal = allowExternal;
    }

    @ToolMethod(
        name = "add_workspace",
        description = "Register an additional workspace directory so the AI can access files outside the current project. " +
            "Use this when working with multiple related projects (e.g. a backend and a frontend in different directories). " +
            "The path must be an existing directory. When filesystem.allow_external=false (default), " +
            "the path must be under the user's home directory.",
        parametersSchema = "{\"type\": \"object\", \"properties\": {" +
            "\"name\": {\"type\": \"string\", \"description\": \"Display name for the workspace\"}," +
            "\"path\": {\"type\": \"string\", \"description\": \"Absolute path to the workspace root directory\"}," +
            "\"description\": {\"type\": \"string\", \"description\": \"Optional description of the workspace\"}" +
            "}, \"required\": [\"name\", \"path\"]}",
        requiresConfirmation = true,
        riskLevel = "low",
        confirmationTemplate = "tool_confirm_workspace_add",
        riskDescriptionTemplate = "tool_dangerous_workspace"
    )
    public String addWorkspace(String argsJson) {
        try {
            JsonNode params = mapper.readTree(argsJson);
            String name = params.has("name") ? params.get("name").asText("") : "";
            String path = params.has("path") ? params.get("path").asText("") : "";
            String description = params.has("description") ? params.get("description").asText("") : "";

            if (name.isEmpty() || path.isEmpty()) {
                return "Error: 'name' and 'path' are required.";
            }

            // Validate path
            Path normalizedPath = Paths.get(path).toAbsolutePath().normalize();
            if (!Files.exists(normalizedPath)) {
                return "Error: Path does not exist: " + normalizedPath;
            }
            if (!Files.isDirectory(normalizedPath)) {
                return "Error: Path is not a directory: " + normalizedPath;
            }

            // Path restriction: when external resources are not allowed,
            // the path must be under user.home
            if (!allowExternal) {
                String userHome = System.getProperty("user.home");
                Path homePath = Paths.get(userHome).toAbsolutePath().normalize();
                if (!normalizedPath.startsWith(homePath)) {
                    return "Error: Path must be under user home directory when filesystem.allow_external=false. " +
                        "To enable external paths, set filesystem.allow_external=true in application.properties. " +
                        "Path: " + normalizedPath;
                }
            }

            WorkspaceRegistry.WorkspaceEntry entry = registry.addWorkspace(name, normalizedPath.toString(), description);
            if (entry == null) {
                // Check if it's the primary workspace
                Path primaryPath = Paths.get(registry.getPrimaryWorkspaceDir()).toAbsolutePath().normalize();
                if (normalizedPath.equals(primaryPath)) {
                    return "Info: This is already the current workspace directory. No additional registration needed.";
                }
                return "Warning: Workspace already registered: " + name + " (" + normalizedPath + ")";
            }

            return "Workspace registered successfully:\n" +
                "  Name: " + entry.getName() + "\n" +
                "  Path: " + entry.getRootPath() + "\n" +
                (description.isEmpty() ? "" : "  Description: " + description + "\n");
        } catch (Exception e) {
            logger.error("add_workspace error", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMethod(
        name = "list_workspaces",
        description = "List all registered additional workspace directories that the AI can access.",
        parametersSchema = "{}",
        readOnly = true
    )
    public String listWorkspaces(String argsJson) {
        List<WorkspaceRegistry.WorkspaceEntry> workspaces = registry.listWorkspaces();
        if (workspaces.isEmpty()) {
            return "No additional workspaces registered." + NEWLINE +
                "Use add_workspace to register directories outside the current project.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Registered Workspaces (").append(workspaces.size()).append(") ===").append(NEWLINE);
        sb.append("Primary workspace: ").append(registry.getPrimaryWorkspaceDir()).append(NEWLINE);
        sb.append(NEWLINE);
        for (WorkspaceRegistry.WorkspaceEntry entry : workspaces) {
            sb.append("  [").append(entry.getId()).append("] ").append(entry.getName()).append(NEWLINE);
            sb.append("      Path: ").append(entry.getRootPath()).append(NEWLINE);
            if (entry.getDescription() != null && !entry.getDescription().isEmpty()) {
                sb.append("      Description: ").append(entry.getDescription()).append(NEWLINE);
            }
            sb.append(NEWLINE);
        }
        return sb.toString();
    }

    @ToolMethod(
        name = "remove_workspace",
        description = "Remove a registered workspace by its ID (use list_workspaces to find IDs). " +
            "The AI will no longer be able to access files in that directory.",
        parametersSchema = "{\"type\": \"object\", \"properties\": {" +
            "\"id\": {\"type\": \"integer\", \"description\": \"The workspace ID from list_workspaces\"}" +
            "}, \"required\": [\"id\"]}",
        requiresConfirmation = true,
        riskLevel = "medium",
        confirmationTemplate = "tool_confirm_workspace_remove",
        riskDescriptionTemplate = "tool_dangerous_workspace"
    )
    public String removeWorkspace(String argsJson) {
        try {
            JsonNode params = mapper.readTree(argsJson);
            if (!params.has("id")) {
                return "Error: 'id' is required. Use list_workspaces to find workspace IDs.";
            }
            long id = params.get("id").asLong();
            boolean removed = registry.removeWorkspace(id);
            if (removed) {
                return "Workspace removed successfully (id=" + id + ").";
            } else {
                return "Error: Workspace not found with id=" + id + ". Use list_workspaces to see available workspaces.";
            }
        } catch (Exception e) {
            logger.error("remove_workspace error", e);
            return "Error: " + e.getMessage();
        }
    }
}
