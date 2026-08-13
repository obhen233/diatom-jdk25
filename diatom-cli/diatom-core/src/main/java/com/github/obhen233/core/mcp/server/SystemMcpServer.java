package com.github.obhen233.core.mcp.server;

import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.util.SoftwareLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * System MCP Server - Provides system information and software detection
 * WARNING: This server provides sensitive system information and is marked HIGH RISK
 */
public class SystemMcpServer implements McpServer {
    private static final Logger logger = LoggerFactory.getLogger(SystemMcpServer.class);
    private static final String SERVER_NAME = "system";
    private static final String SERVER_DESCRIPTION = "[HIGH RISK] System information and software detection server - provides access to system paths, installed software, and shell environment detection";

    @Override
    public String getName() {
        return SERVER_NAME;
    }

    @Override
    public String getDescription() {
        return SERVER_DESCRIPTION;
    }

    @Override
    public Map<String, Tool> listTools() {
        Map<String, Tool> tools = new HashMap<>();

        // Software detection tools
        tools.put("find_software", new Tool(
            "find_software",
            "[HIGH RISK] Find installation path of software (git, java, maven, node, python, etc.)",
            "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\", \"description\": \"Software name: git, java, javac, maven, gradle, node, python, go\"}}}"
        ));

        tools.put("detect_shell", new Tool(
            "detect_shell",
            "[HIGH RISK] Detect available Unix-like shells on Windows (Git Bash, MinGW64, Cygwin, WSL2)",
            "{}"
        ));

        tools.put("get_system_info", new Tool(
            "get_system_info",
            "[HIGH RISK] Get detailed system information (OS, CPU, memory, Java version, user home)",
            "{}"
        ));

        tools.put("get_java_home", new Tool(
            "get_java_home",
            "[HIGH RISK] Get Java installation directory from JAVA_HOME",
            "{}"
        ));

        tools.put("get_git_path", new Tool(
            "get_git_path",
            "[HIGH RISK] Get Git installation directory",
            "{}"
        ));

        return tools;
    }

    @Override
    public String callTool(String toolName, String args) {
        try {
            switch (toolName) {
                case "find_software": return findSoftware(args);
                case "detect_shell": return detectShell();
                case "get_system_info": return getSystemInfo();
                case "get_java_home": return getJavaHome();
                case "get_git_path": return getGitPath();
                default: return "{\"error\": \"Unknown tool: " + toolName + "\"}";
            }
        } catch (Exception e) {
            logger.error("Error executing system tool: {}", toolName, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String findSoftware(String args) {
        String softwareName = null;
        try {
            if (args != null && !args.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args);
                if (node.has("name")) {
                    softwareName = node.get("name").asText();
                }
            }
        } catch (Exception e) {
            return "{\"error\": \"Invalid args format\"}";
        }

        if (softwareName == null || softwareName.isEmpty()) {
            return "{\"error\": \"Software name required. Examples: git, java, maven, node, python, go\"}";
        }

        Optional<Path> path = SoftwareLocator.findInstallation(softwareName);
        if (path.isPresent()) {
            return "{\"success\": true, \"name\": \"" + softwareName + "\", \"path\": \"" + path.get().toString().replace("\\", "\\\\") + "\"}";
        } else {
            return "{\"success\": false, \"name\": \"" + softwareName + "\", \"path\": null, \"message\": \"Not found in environment variables, PATH, or default locations\"}";
        }
    }

    private String detectShell() {
        Optional<SoftwareLocator.ShellInfo> shellInfo = SoftwareLocator.findBashOnWindows();

        if (shellInfo.isPresent()) {
            return "{\"success\": true, \"shellType\": \"" + shellInfo.get().getType() +
                   "\", \"shellPath\": \"" + shellInfo.get().getPath().replace("\\", "\\\\") +
                   "\", \"unixCommandsAvailable\": true}";
        } else {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("windows")) {
                return "{\"success\": false, \"shellType\": \"cmd\", \"shellPath\": null, \"unixCommandsAvailable\": false, \"message\": \"No Unix-like shell detected on Windows. Consider installing Git Bash, MinGW64, Cygwin, or WSL2\"}";
            } else {
                return "{\"success\": true, \"shellType\": \"native\", \"shellPath\": null, \"unixCommandsAvailable\": true, \"message\": \"Native Unix environment\"}";
            }
        }
    }

    private String getSystemInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"os\": \"").append(System.getProperty("os.name", "unknown")).append("\",\n");
        sb.append("  \"osVersion\": \"").append(System.getProperty("os.version", "unknown")).append("\",\n");
        sb.append("  \"osArch\": \"").append(System.getProperty("os.arch", "unknown")).append("\",\n");
        sb.append("  \"javaVersion\": \"").append(System.getProperty("java.version", "unknown")).append("\",\n");
        sb.append("  \"javaHome\": \"").append(System.getProperty("java.home", "unknown")).append("\",\n");
        sb.append("  \"userHome\": \"").append(System.getProperty("user.home", "unknown")).append("\",\n");
        sb.append("  \"userDir\": \"").append(System.getProperty("user.dir", "unknown")).append("\",\n");
        sb.append("  \"availableProcessors\": ").append(Runtime.getRuntime().availableProcessors()).append(",\n");
        sb.append("  \"maxMemory\": ").append(Runtime.getRuntime().maxMemory()).append(",\n");
        sb.append("  \"totalMemory\": ").append(Runtime.getRuntime().totalMemory()).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private String getJavaHome() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            return "{\"success\": true, \"javaHome\": \"" + javaHome.replace("\\", "\\\\") + "\"}";
        }
        // Try to find java
        Optional<Path> path = SoftwareLocator.findInstallation("java");
        if (path.isPresent()) {
            return "{\"success\": true, \"javaHome\": \"" + path.get().toString().replace("\\", "\\\\") + "\"}";
        }
        return "{\"success\": false, \"javaHome\": null, \"message\": \"JAVA_HOME not set and java not found in PATH\"}";
    }

    private String getGitPath() {
        String gitHome = System.getenv("GIT_HOME");
        if (gitHome != null && !gitHome.isEmpty()) {
            return "{\"success\": true, \"gitPath\": \"" + gitHome.replace("\\", "\\\\") + "\"}";
        }
        // Try to find git
        Optional<Path> path = SoftwareLocator.findInstallation("git");
        if (path.isPresent()) {
            return "{\"success\": true, \"gitPath\": \"" + path.get().toString().replace("\\", "\\\\") + "\"}";
        }
        return "{\"success\": false, \"gitPath\": null, \"message\": \"GIT_HOME not set and git not found in PATH\"}";
    }
}