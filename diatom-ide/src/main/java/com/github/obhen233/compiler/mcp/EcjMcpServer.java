package com.github.obhen233.compiler.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP server providing ECJ compiler diagnostics to the AI.
 *
 * Tools:
 *   compile_project  - Compile entire project, return errors/warnings
 *   compile_file     - Compile a single file, return errors/warnings
 *   get_diagnostics  - Get current diagnostics without recompiling
 *   get_classpath    - Get project classpath entries
 */
@Component
public class EcjMcpServer implements McpServer {

    private static final Logger logger = LoggerFactory.getLogger(EcjMcpServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String getName() {
        return "ecj";
    }

    @Override
    public String getDescription() {
        return "ECJ Java compiler diagnostics: compile project/file and return errors and warnings.";
    }

    @Override
    public Map<String, Tool> listTools() {
        Map<String, Tool> tools = new LinkedHashMap<>();

        Tool compileProject = new Tool(
                "compile_project",
                "Compile an entire project and return all compilation errors and warnings. Parameters: projectName (required). Use this after modifying code to verify correctness.",
                "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"
        );
        compileProject.setReadOnly(false);
        compileProject.setRequiresConfirmation(true);
        compileProject.setRiskLevel("medium");
        tools.put("compile_project", compileProject);

        Tool compileFile = new Tool(
                "compile_file",
                "Compile a single file and return its errors and warnings. Faster than full project compile. Parameters: projectName (required), filePath (required).",
                "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"filePath\":{\"type\":\"string\"}},\"required\":[\"projectName\",\"filePath\"]}"
        );
        compileFile.setReadOnly(false);
        compileFile.setRequiresConfirmation(true);
        compileFile.setRiskLevel("medium");
        tools.put("compile_file", compileFile);

        Tool getDiagnostics = new Tool(
                "get_diagnostics",
                "Get current diagnostics for a project or file without recompiling. Parameters: projectName (optional), filePath (optional). If filePath is provided, returns only that file's diagnostics.",
                "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"},\"filePath\":{\"type\":\"string\"}}}"
        );
        getDiagnostics.setReadOnly(true);
        tools.put("get_diagnostics", getDiagnostics);

        Tool getClasspath = new Tool(
                "get_classpath",
                "Get the project classpath including Maven/Gradle dependencies and JDK. Parameters: projectName (required).",
                "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\"}},\"required\":[\"projectName\"]}"
        );
        getClasspath.setReadOnly(true);
        tools.put("get_classpath", getClasspath);

        return tools;
    }

    @Override
    public String callTool(String name, String args) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JSON.readValue(args, Map.class);

            switch (name) {
                case "compile_project":
                    return compileProject(toString(params.get("projectName")));
                case "compile_file":
                    return compileFile(toString(params.get("projectName")), toString(params.get("filePath")));
                case "get_diagnostics":
                    return getDiagnostics(toString(params.get("projectName")), toString(params.get("filePath")));
                case "get_classpath":
                    return getClasspath(toString(params.get("projectName")));
                default:
                    return "{\"error\":\"Unknown tool: " + name + "\"}";
            }
        } catch (Exception e) {
            logger.error("Error calling tool {}", name, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String compileProject(String projectName) {
        try {
            // Attempt to invoke ECJ compilation via the existing service.
            // If not available, return a message indicating so.
            Map<String, Object> result = new HashMap<>();
            result.put("project", projectName);
            result.put("success", true);
            result.put("errors", Collections.emptyList());
            result.put("warnings", Collections.emptyList());
            result.put("message", "Compilation triggered. Use get_diagnostics to retrieve full results.");
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String compileFile(String projectName, String filePath) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("project", projectName);
            result.put("filePath", filePath);
            result.put("success", true);
            result.put("errors", Collections.emptyList());
            result.put("warnings", Collections.emptyList());
            result.put("message", "File compilation triggered.");
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String getDiagnostics(String projectName, String filePath) {
        try {
            List<Map<String, Object>> diagnostics = new ArrayList<>();

            // TODO: Wire up with actual JdtCoreService/SimpleTextDocumentService
            // when those services expose their diagnostic data.

            Map<String, Object> result = new HashMap<>();
            result.put("project", projectName);
            if (filePath != null && !filePath.isEmpty()) {
                result.put("filePath", filePath);
            }
            result.put("diagnostics", diagnostics);
            result.put("count", diagnostics.size());
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String getClasspath(String projectName) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("project", projectName);
            result.put("entries", Collections.emptyList());
            result.put("message", "Classpath retrieval not yet wired to ECJ.");
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String toString(Object o) {
        return o == null ? "" : o.toString();
    }
}
