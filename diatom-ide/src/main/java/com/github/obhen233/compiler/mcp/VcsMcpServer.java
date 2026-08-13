package com.github.obhen233.compiler.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.compiler.service.VcsService;
import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * MCP server providing version control operations (Git + SVN).
 * Conditionally registers tools based on available CLI tools.
 *
 * Git tools: status, diff, log, branch
 * SVN tools: status, diff, log, info
 */
@Component
public class VcsMcpServer implements McpServer {

    private static final Logger logger = LoggerFactory.getLogger(VcsMcpServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired(required = false)
    private VcsService vcsService;

    private boolean gitAvailable = false;
    private boolean svnAvailable = false;

    @PostConstruct
    public void init() {
        gitAvailable = checkCommand("git", "--version");
        svnAvailable = checkCommand("svn", "--version");
        logger.info("VCS environment: git={}, svn={}", gitAvailable, svnAvailable);
    }

    private boolean checkCommand(String cmd, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add(cmd);
            command.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            logger.debug("Command not available: {} - {}", cmd, e.getMessage());
            return false;
        }
    }

    @Override
    public String getName() {
        return "vcs";
    }

    @Override
    public String getDescription() {
        StringBuilder desc = new StringBuilder("Version control operations.");
        if (gitAvailable) desc.append(" Git available.");
        if (svnAvailable) desc.append(" SVN available.");
        if (!gitAvailable && !svnAvailable) desc.append(" No VCS tools detected.");
        return desc.toString();
    }

    @Override
    public Map<String, Tool> listTools() {
        Map<String, Tool> tools = new LinkedHashMap<>();

        if (gitAvailable) {
            Tool gitStatus = new Tool(
                    "git_status",
                    "[SCENE: file-read] Get Git working tree status (modified, added, untracked files). Parameters: projectName (required).",
                    "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"
            );
            gitStatus.setReadOnly(true);
            tools.put("git_status", gitStatus);

            Tool gitDiff = new Tool(
                    "git_diff",
                    "[SCENE: file-read] Get Git diff for working tree or a specific file. Parameters: projectName (required), filePath (optional - omit for full diff).",
                    "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"filePath\":{\"type\":\"string\"}}}"
            );
            gitDiff.setReadOnly(true);
            tools.put("git_diff", gitDiff);

            Tool gitLog = new Tool(
                    "git_log",
                    "[SCENE: file-read] Get Git commit log. Parameters: projectName (required), limit (int, default 10).",
                    "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"default\":10}}}"
            );
            gitLog.setReadOnly(true);
            tools.put("git_log", gitLog);

            Tool gitBranch = new Tool(
                    "git_branch",
                    "[SCENE: file-read] List Git branches. Parameters: projectName (required).",
                    "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"
            );
            gitBranch.setReadOnly(true);
            tools.put("git_branch", gitBranch);
        }

        if (svnAvailable) {
            Tool svnStatus = new Tool(
                    "svn_status",
                    "[SCENE: file-read] Get SVN working tree status. Parameters: projectName (required).",
                    "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"
            );
            svnStatus.setReadOnly(true);
            tools.put("svn_status", svnStatus);

            Tool svnDiff = new Tool(
                    "svn_diff",
                    "[SCENE: file-read] Get SVN diff for working tree. Parameters: projectName (required).",
                    "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"
            );
            svnDiff.setReadOnly(true);
            tools.put("svn_diff", svnDiff);

            Tool svnLog = new Tool(
                    "svn_log",
                    "[SCENE: file-read] Get SVN commit log. Parameters: projectName (required), limit (int, default 10).",
                    "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"default\":10}}}"
            );
            svnLog.setReadOnly(true);
            tools.put("svn_log", svnLog);

            Tool svnInfo = new Tool(
                    "svn_info",
                    "[SCENE: file-read] Get SVN repository info (URL, revision, etc.). Parameters: projectName (required).",
                    "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"
            );
            svnInfo.setReadOnly(true);
            tools.put("svn_info", svnInfo);
        }

        return tools;
    }

    @Override
    public String callTool(String name, String args) {
        try {
            if (vcsService == null) {
                return "{\"error\":\"VCS service not available\"}";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> params = JSON.readValue(args, Map.class);
            String projectName = toString(params.get("projectName"));

            switch (name) {
                // Git
                case "git_status":
                    return callGitStatus(projectName);
                case "git_diff":
                    return callGitDiff(projectName, toString(params.get("filePath")));
                case "git_log":
                    return callGitLog(projectName, params.containsKey("limit") ? ((Number) params.get("limit")).intValue() : 10);
                case "git_branch":
                    return callGitBranch(projectName);
                // SVN
                case "svn_status":
                    return callSvnStatus(projectName);
                case "svn_diff":
                    return callSvnDiff(projectName);
                case "svn_log":
                    return callSvnLog(projectName, params.containsKey("limit") ? ((Number) params.get("limit")).intValue() : 10);
                case "svn_info":
                    return callSvnInfo(projectName);
                default:
                    return "{\"error\":\"Unknown tool: " + name + "\"}";
            }
        } catch (Exception e) {
            logger.error("Error calling tool {}", name, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // === Git implementations ===

    private String callGitStatus(String projectName) throws Exception {
        Map<String, Object> status = vcsService.gitStatus(projectName);
        return JSON.writeValueAsString(status);
    }

    private String callGitDiff(String projectName, String filePath) throws Exception {
        // gitDiff(projectName, oldRef, newRef) — null refs = working tree vs HEAD
        Map<String, Object> result = vcsService.gitDiff(projectName, null, null);
        if (filePath != null && !filePath.isEmpty()) {
            result.put("fileFilter", filePath);
        }
        return JSON.writeValueAsString(result);
    }

    private String callGitLog(String projectName, int limit) throws Exception {
        Map<String, Object> log = vcsService.gitLog(projectName, limit, 0);
        return JSON.writeValueAsString(log);
    }

    private String callGitBranch(String projectName) throws Exception {
        // gitStatus() includes branch list in response
        Map<String, Object> status = vcsService.gitStatus(projectName);
        // Return only branch-related fields
        Map<String, Object> branches = new HashMap<>();
        branches.put("currentBranch", status.get("branch"));
        branches.put("branches", status.get("branches"));
        branches.put("initialized", status.get("initialized"));
        if (status.containsKey("error")) {
            branches.put("error", status.get("error"));
        }
        return JSON.writeValueAsString(branches);
    }

    // === SVN implementations ===

    private String callSvnStatus(String projectName) throws Exception {
        Map<String, Object> status = vcsService.svnStatus(projectName);
        return JSON.writeValueAsString(status);
    }

    private String callSvnDiff(String projectName) throws Exception {
        Map<String, Object> diff = vcsService.svnDiff(projectName);
        if (diff.containsKey("output") && diff.get("output") instanceof String) {
            String out = (String) diff.get("output");
            if (out.length() > 50000) {
                diff.put("output", out.substring(0, 50000) + "\n... (truncated)");
                diff.put("truncated", true);
            }
        }
        return JSON.writeValueAsString(diff);
    }

    private String callSvnLog(String projectName, int limit) throws Exception {
        Map<String, Object> log = vcsService.svnLog(projectName, limit);
        return JSON.writeValueAsString(log);
    }

    private String callSvnInfo(String projectName) throws Exception {
        // SVN info via command line
        Map<String, Object> result = vcsService.svnExec(projectName, "info", "--xml");
        return JSON.writeValueAsString(result);
    }

    private String toString(Object o) {
        return o == null ? "" : o.toString();
    }
}
