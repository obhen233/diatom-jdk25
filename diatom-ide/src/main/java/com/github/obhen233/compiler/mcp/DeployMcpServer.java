package com.github.obhen233.compiler.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.context.ProjectContext;
import com.github.obhen233.core.pipeline.DeployConfigGenerator;
import com.github.obhen233.core.pipeline.DeployConfigParams;
import com.github.obhen233.core.pipeline.DeployConfigService;
import com.github.obhen233.core.pipeline.HealthCheckConfig;
import com.github.obhen233.core.pipeline.ServerInfo;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.spi.DeployProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP server providing deploy config generation tools to the AI Agent.
 *
 * Tools:
 *   check_deploy_config  - Check if deploy.yaml exists for a project
 *   analyze_project      - Analyze project structure (type, directory tree, build files)
 *   generate_deploy_yaml - Generate and write deploy.yaml for a project
 *
 * IMPORTANT: This server ONLY handles deploy.yaml generation.
 * Deployment execution is done via the terminal "deploy" command or UI Deploy button,
 * which stream output in real-time through the terminal WebSocket.
 *
 * All tools use IDE path resolution (Constants.workspacePath + "/" + projectName).
 */
@Component
public class DeployMcpServer implements McpServer {

    private static final Logger logger = LoggerFactory.getLogger(DeployMcpServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired(required = false)
    private DeployProvider deployProvider;

    @Autowired(required = false)
    private DeployConfigService deployConfigService;

    @Override
    public String getName() {
        return "deploy";
    }

    @Override
    public String getDescription() {
        return "Deploy config tools: check config, analyze project, generate deploy.yaml. Execution via terminal 'deploy'.";
    }

    @Override
    public Map<String, Tool> listTools() {
        Map<String, Tool> tools = new LinkedHashMap<>();

        Tool checkConfig = new Tool(
                "check_deploy_config",
                "Check if a deploy.yaml configuration exists for a project. Parameters: projectName (required). Returns true/false.",
                "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"
        );
        checkConfig.setReadOnly(true);
        checkConfig.setRequiresConfirmation(false);
        checkConfig.setRiskLevel("none");
        tools.put("check_deploy_config", checkConfig);

        Tool analyzeProject = new Tool(
                "analyze_project",
                "Analyze a project's structure to understand its type, directory tree, and build files. Parameters: projectName (required). Returns project type, directory tree, and build file content.",
                "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"
        );
        analyzeProject.setReadOnly(true);
        analyzeProject.setRequiresConfirmation(false);
        analyzeProject.setRiskLevel("none");
        tools.put("analyze_project", analyzeProject);

        Tool generateYaml = new Tool(
                "generate_deploy_yaml",
                "Generate deploy.yaml. CRITICAL: pipeline handles SSH natively — NEVER raw ssh/scp commands. Use ssh_command for remote exec, scp with files:[{local,remote}] for file transfer. NEVER use action: upload or source/target. Put SSH auth under servers[].key (recommended) or servers[].password (auto-encrypted on save). Single-host mode uses top-level 'servers'; remote steps may omit host/cluster. Cluster mode uses clusters.<name>.hosts; remote steps MUST include cluster: '\u003cname\u003e'. After generation, user runs 'deploy' to execute. Params: projectName, clusterMode(bool), clusterName, strategy(all/rolling/canary), servers[{host,user,port,key?,password?}], envVars, healthCheck{type,port,path,timeout,retries}.",
                "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"clusterMode\":{\"type\":\"boolean\"},\"clusterName\":{\"type\":\"string\"},\"strategy\":{\"type\":\"string\"},\"servers\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"host\":{\"type\":\"string\"},\"user\":{\"type\":\"string\"},\"port\":{\"type\":\"integer\"},\"key\":{\"type\":\"string\"},\"password\":{\"type\":\"string\"}}}}},\"envVars\":{\"type\":\"object\"},\"healthCheck\":{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\"},\"port\":{\"type\":\"integer\"},\"path\":{\"type\":\"string\"},\"timeout\":{\"type\":\"integer\"},\"retries\":{\"type\":\"integer\"}}}},\"required\":[\"projectName\"]}"
        );
        generateYaml.setReadOnly(false);
        generateYaml.setRequiresConfirmation(true);
        generateYaml.setRiskLevel("medium");
        tools.put("generate_deploy_yaml", generateYaml);

        return tools;
    }

    @Override
    public String callTool(String name, String args) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JSON.readValue(args, Map.class);

            switch (name) {
                case "check_deploy_config":
                    return doCheckDeployConfig(toString(params.get("projectName")));
                case "analyze_project":
                    return doAnalyzeProject(toString(params.get("projectName")));
                case "generate_deploy_yaml":
                    return doGenerateDeployYaml(params);
                default:
                    return "{\"error\":\"Unknown tool: " + name + "\"}";
            }
        } catch (Exception e) {
            logger.error("Error calling deploy tool {}", name, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String doCheckDeployConfig(String projectName) {
        if (projectName == null || projectName.isEmpty()) {
            return "{\"error\":\"projectName is required\"}";
        }
        boolean hasConfig = deployProvider != null && deployProvider.hasDeployConfig(projectName);
        Map<String, Object> result = new HashMap<>();
        result.put("hasConfig", hasConfig);
        result.put("projectName", projectName);
        return toJson(result);
    }

    private String doAnalyzeProject(String projectName) {
        if (projectName == null || projectName.isEmpty()) {
            return "{\"error\":\"projectName is required\"}";
        }
        if (deployConfigService == null) {
            return "{\"error\":\"DeployConfigService not available\"}";
        }
        String projectDir = Constants.workspacePath + "/" + projectName;
        try {
            ProjectContext ctx = deployConfigService.analyzeProject(projectDir);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("projectName", ctx.getProjectName());
            result.put("projectType", ctx.getProjectType());
            result.put("directoryTree", ctx.getDirectoryTree());
            result.put("buildFileContent", ctx.getBuildFileContent());
            return toJson(result);
        } catch (Exception e) {
            logger.error("Failed to analyze project {}", projectName, e);
            return "{\"error\":\"Failed to analyze project: " + e.getMessage() + "\"}";
        }
    }

    private String doGenerateDeployYaml(Map<String, Object> params) {
        String projectName = toString(params.get("projectName"));
        if (projectName == null || projectName.isEmpty()) {
            return "{\"error\":\"projectName is required\"}";
        }
        if (deployConfigService == null) {
            return "{\"error\":\"DeployConfigService not available\"}";
        }

        DeployConfigParams configParams = new DeployConfigParams();
        configParams.setProjectName(projectName);

        Object clusterModeObj = params.get("clusterMode");
        boolean clusterMode = clusterModeObj instanceof Boolean && (Boolean) clusterModeObj;
        configParams.setClusterMode(clusterMode);

        if (params.containsKey("clusterName")) {
            configParams.setClusterName(toString(params.get("clusterName")));
        }
        if (params.containsKey("strategy")) {
            configParams.setStrategy(toString(params.get("strategy")));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> serverList = (List<Map<String, Object>>) params.get("servers");
        if (serverList != null) {
            List<ServerInfo> servers = new ArrayList<>();
            for (Map<String, Object> s : serverList) {
                ServerInfo si = new ServerInfo();
                si.setHost(toString(s.get("host")));
                si.setUser(toString(s.getOrDefault("user", "root")));
                Object portObj = s.get("port");
                si.setPort(portObj instanceof Number ? ((Number) portObj).intValue() : 22);
                si.setKey(toString(s.getOrDefault("key", "")));
                si.setPassword(toString(s.getOrDefault("password", "")));
                servers.add(si);
            }
            configParams.setServers(servers);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> hcMap = (Map<String, Object>) params.get("healthCheck");
        if (hcMap != null) {
            HealthCheckConfig hc = new HealthCheckConfig();
            hc.setType(toString(hcMap.getOrDefault("type", "http")));
            Object portObj = hcMap.get("port");
            if (portObj instanceof Number) {
                hc.setPort(((Number) portObj).intValue());
            }
            hc.setPath(toString(hcMap.getOrDefault("path", "/health")));
            Object retryObj = hcMap.get("retries");
            if (retryObj instanceof Number) {
                hc.setRetries(((Number) retryObj).intValue());
            }
            configParams.setHealthCheck(hc);
        }

        @SuppressWarnings("unchecked")
        Map<String, String> envVars = (Map<String, String>) params.get("envVars");
        if (envVars != null) {
            configParams.setEnvVars(envVars);
        }

        String yamlContent;
        try {
            yamlContent = deployConfigService.generateYaml(configParams);
        } catch (Exception e) {
            logger.error("Failed to generate deploy.yaml for {}", projectName, e);
            return "{\"error\":\"Failed to generate deploy.yaml: " + e.getMessage() + "\"}";
        }

        if (yamlContent == null || yamlContent.isEmpty()) {
            return "{\"error\":\"Generated YAML content is empty\"}";
        }

        // Encrypt password fields before writing, consistent with CLI generator
        yamlContent = DeployConfigGenerator.encryptPasswordFields(yamlContent);

        boolean written = deployConfigService.writeYaml(Constants.workspacePath, projectName, yamlContent);
        if (!written) {
            return "{\"error\":\"Failed to write deploy.yaml for project: " + projectName + "\"}";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("projectName", projectName);
        result.put("path", ".diatom/deploy.yaml");
        return toJson(result);
    }

    private String toString(Object o) {
        return o == null ? "" : o.toString();
    }

    private String toJson(Object obj) {
        try {
            return JSON.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
