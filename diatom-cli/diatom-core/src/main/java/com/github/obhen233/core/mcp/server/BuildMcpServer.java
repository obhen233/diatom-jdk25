package com.github.obhen233.core.mcp.server;

import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.util.SoftwareLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Build MCP Server - Provides Maven and Gradle build tools
 *
 * This server exposes build tools for non-diatom projects.
 * It detects the build system automatically and provides compilation tools.
 */
public class BuildMcpServer implements McpServer {
    private static final Logger logger = LoggerFactory.getLogger(BuildMcpServer.class);
    private static final String SERVER_NAME = "build";
    private static final String SERVER_DESCRIPTION = "Build tools for Maven and Gradle projects - compile, test, and package Java/Gradle projects";
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private String workspaceDir;

    public BuildMcpServer() {
    }

    public BuildMcpServer(String workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

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

        tools.put("maven_build", new Tool(
            "maven_build",
            "Build a Maven project with specified goals. Default goal is 'package'. Example: mvn package, mvn clean install",
            "{\"type\": \"object\", \"properties\": {\"project_path\": {\"type\": \"string\", \"description\": \"Path to the Maven project (directory containing pom.xml)\"}, \"goals\": {\"type\": \"string\", \"description\": \"Maven goals to execute (default: package)\", \"default\": \"package\"}}}"
        ));

        tools.put("maven_clean_build", new Tool(
            "maven_clean_build",
            "Clean and build a Maven project with specified goals",
            "{\"type\": \"object\", \"properties\": {\"project_path\": {\"type\": \"string\", \"description\": \"Path to the Maven project\"}, \"goals\": {\"type\": \"string\", \"description\": \"Maven goals to execute after clean (default: package)\", \"default\": \"package\"}}}"
        ));

        tools.put("gradle_build", new Tool(
            "gradle_build",
            "Build a Gradle project with specified tasks. Default task is 'build'. Uses gradlew if available.",
            "{\"type\": \"object\", \"properties\": {\"project_path\": {\"type\": \"string\", \"description\": \"Path to the Gradle project (directory containing build.gradle)\"}, \"tasks\": {\"type\": \"string\", \"description\": \"Gradle tasks to execute (default: build)\", \"default\": \"build\"}}}"
        ));

        tools.put("gradle_clean_build", new Tool(
            "gradle_clean_build",
            "Clean and build a Gradle project. Uses gradlew if available.",
            "{\"type\": \"object\", \"properties\": {\"project_path\": {\"type\": \"string\", \"description\": \"Path to the Gradle project\"}, \"tasks\": {\"type\": \"string\", \"description\": \"Gradle tasks to execute (default: build)\", \"default\": \"build\"}}}"
        ));

        tools.put("detect_build_system", new Tool(
            "detect_build_system",
            "Detect the build system (Maven or Gradle) in a project directory",
            "{\"type\": \"object\", \"properties\": {\"project_path\": {\"type\": \"string\", \"description\": \"Path to the project directory\"}}}"
        ));

        tools.put("get_build_info", new Tool(
            "get_build_info",
            "Get information about available build tools (Maven, Gradle) and their versions",
            "{}"
        ));

        return tools;
    }

    @Override
    public String callTool(String toolName, String args) {
        try {
            switch (toolName) {
                case "maven_build": return mavenBuild(args);
                case "maven_clean_build": return mavenCleanBuild(args);
                case "gradle_build": return gradleBuild(args);
                case "gradle_clean_build": return gradleCleanBuild(args);
                case "detect_build_system": return detectBuildSystem(args);
                case "get_build_info": return getBuildInfo();
                default: return "{\"error\": \"Unknown tool: " + toolName + "\"}";
            }
        } catch (Exception e) {
            logger.error("Error executing build tool: {}", toolName, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String mavenBuild(String args) {
        String projectPath = null;
        String goals = "package";

        try {
            if (args != null && !args.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args);
                if (node.has("project_path")) {
                    projectPath = node.get("project_path").asText();
                }
                if (node.has("goals")) {
                    goals = node.get("goals").asText();
                }
            }
        } catch (Exception e) {
            return "{\"error\": \"Invalid args format\"}";
        }

        if (projectPath == null || projectPath.isEmpty()) {
            return "{\"error\": \"project_path is required\"}";
        }

        return runMaven(projectPath, goals, false);
    }

    private String mavenCleanBuild(String args) {
        String projectPath = null;
        String goals = "package";

        try {
            if (args != null && !args.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args);
                if (node.has("project_path")) {
                    projectPath = node.get("project_path").asText();
                }
                if (node.has("goals")) {
                    goals = node.get("goals").asText();
                }
            }
        } catch (Exception e) {
            return "{\"error\": \"Invalid args format\"}";
        }

        if (projectPath == null || projectPath.isEmpty()) {
            return "{\"error\": \"project_path is required\"}";
        }

        return runMaven(projectPath, "clean " + goals, true);
    }

    private String runMaven(String projectPath, String goals, boolean clean) {
        Path pomPath = Paths.get(projectPath, "pom.xml");
        if (!Files.exists(pomPath)) {
            return "{\"success\": false, \"error\": \"pom.xml not found in: " + projectPath + "\"}";
        }

        String mvnCommand;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Optional<SoftwareLocator.MavenInfo> mavenInfo = SoftwareLocator.findMavenOnWindows();
            mvnCommand = mavenInfo.map(SoftwareLocator.MavenInfo::getExecutablePath).orElse("mvn.cmd");
        } else {
            Optional<Path> mvnPath = SoftwareLocator.findInstallation("mvn");
            mvnCommand = mvnPath.map(path -> path.resolve("bin").resolve("mvn").toString()).orElse("mvn");
        }

        String[] cmd;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            cmd = new String[]{"cmd", "/c", mvnCommand, goals};
        } else {
            cmd = new String[]{"/bin/bash", "-c", mvnCommand + " " + goals};
        }

        return runCommand(cmd, projectPath);
    }

    private String gradleBuild(String args) {
        String projectPath = null;
        String tasks = "build";

        try {
            if (args != null && !args.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args);
                if (node.has("project_path")) {
                    projectPath = node.get("project_path").asText();
                }
                if (node.has("tasks")) {
                    tasks = node.get("tasks").asText();
                }
            }
        } catch (Exception e) {
            return "{\"error\": \"Invalid args format\"}";
        }

        if (projectPath == null || projectPath.isEmpty()) {
            return "{\"error\": \"project_path is required\"}";
        }

        return runGradle(projectPath, tasks, false);
    }

    private String gradleCleanBuild(String args) {
        String projectPath = null;
        String tasks = "build";

        try {
            if (args != null && !args.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args);
                if (node.has("project_path")) {
                    projectPath = node.get("project_path").asText();
                }
                if (node.has("tasks")) {
                    tasks = node.get("tasks").asText();
                }
            }
        } catch (Exception e) {
            return "{\"error\": \"Invalid args format\"}";
        }

        if (projectPath == null || projectPath.isEmpty()) {
            return "{\"error\": \"project_path is required\"}";
        }

        return runGradle(projectPath, "clean " + tasks, true);
    }

    private String runGradle(String projectPath, String tasks, boolean clean) {
        Path buildGradle = Paths.get(projectPath, "build.gradle");
        Path settingsGradle = Paths.get(projectPath, "settings.gradle");
        if (!Files.exists(buildGradle) && !Files.exists(settingsGradle)) {
            return "{\"success\": false, \"error\": \"build.gradle or settings.gradle not found in: " + projectPath + "\"}";
        }

        // Check for gradlew
        String gradleCommand;
        Path gradlew = Paths.get(projectPath, "gradlew");
        if (Files.exists(gradlew)) {
            gradleCommand = "./gradlew";
        } else {
            // Find system gradle
            Optional<Path> gradlePath = SoftwareLocator.findInstallation("gradle");
            if (gradlePath.isPresent()) {
                gradleCommand = gradlePath.get().toString();
            } else {
                gradleCommand = "gradle";
            }
        }

        String[] cmd;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            if (gradleCommand.equals("./gradlew")) {
                cmd = new String[]{"cmd", "/c", "gradlew.bat", tasks};
            } else {
                cmd = new String[]{"cmd", "/c", gradleCommand, tasks};
            }
        } else {
            cmd = new String[]{"/bin/bash", "-c", gradleCommand + " " + tasks};
        }

        return runCommand(cmd, projectPath);
    }

    private String detectBuildSystem(String args) {
        String projectPath = null;

        try {
            if (args != null && !args.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args);
                if (node.has("project_path")) {
                    projectPath = node.get("project_path").asText();
                }
            }
        } catch (Exception e) {
            return "{\"error\": \"Invalid args format\"}";
        }

        if (projectPath == null || projectPath.isEmpty()) {
            return "{\"error\": \"project_path is required\"}";
        }

        Path path = Paths.get(projectPath);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            return "{\"success\": false, \"error\": \"Directory does not exist: " + projectPath + "\"}";
        }

        boolean hasPom = Files.exists(path.resolve("pom.xml"));
        boolean hasBuildGradle = Files.exists(path.resolve("build.gradle"));
        boolean hasSettingsGradle = Files.exists(path.resolve("settings.gradle"));
        boolean hasGradlew = Files.exists(path.resolve("gradlew")) || Files.exists(path.resolve("gradlew.bat"));

        String buildSystem;
        if (hasPom) {
            buildSystem = "maven";
        } else if (hasBuildGradle || hasSettingsGradle) {
            buildSystem = "gradle";
        } else {
            buildSystem = "none";
        }

        return "{\"success\": true, \"project_path\": \"" + projectPath.replace("\\", "\\\\") + "\", " +
               "\"build_system\": \"" + buildSystem + "\", " +
               "\"has_pom\": " + hasPom + ", " +
               "\"has_build_gradle\": " + hasBuildGradle + ", " +
               "\"has_settings_gradle\": " + hasSettingsGradle + ", " +
               "\"has_gradlew\": " + hasGradlew + "}";
    }

    private String getBuildInfo() {
        StringBuilder info = new StringBuilder();
        info.append("{\n");

        // Maven info
        Optional<Path> mvnPath = SoftwareLocator.findInstallation("mvn");
        if (mvnPath.isPresent()) {
            info.append("  \"maven\": {\n");
            info.append("    \"available\": true,\n");
            info.append("    \"path\": \"").append(mvnPath.get().toString().replace("\\", "\\\\")).append("\"\n");
            info.append("  },\n");

            // Try to get Maven version
            String version = getMavenVersion(mvnPath.get().toString());
            if (version != null) {
                info.append("  \"maven_version\": \"").append(version).append("\",\n");
            }
        } else {
            info.append("  \"maven\": {\n");
            info.append("    \"available\": false,\n");
            info.append("    \"path\": null\n");
            info.append("  },\n");
        }

        // Gradle info
        Optional<Path> gradlePath = SoftwareLocator.findInstallation("gradle");
        if (gradlePath.isPresent()) {
            info.append("  \"gradle\": {\n");
            info.append("    \"available\": true,\n");
            info.append("    \"path\": \"").append(gradlePath.get().toString().replace("\\", "\\\\")).append("\"\n");
            info.append("  },\n");

            // Try to get Gradle version
            String version = getGradleVersion(gradlePath.get().toString());
            if (version != null) {
                info.append("  \"gradle_version\": \"").append(version).append("\"\n");
            }
        } else {
            info.append("  \"gradle\": {\n");
            info.append("    \"available\": false,\n");
            info.append("    \"path\": null\n");
            info.append("  }\n");
        }

        info.append("}");
        return info.toString();
    }

    private String getMavenVersion(String mvnPath) {
        try {
            String[] cmd;
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                cmd = new String[]{mvnPath.endsWith(".cmd") ? mvnPath : mvnPath + ".cmd", "-version"};
            } else {
                cmd = new String[]{mvnPath, "-version"};
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Apache Maven")) {
                    return line.trim();
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Failed to get Maven version: {}", e.getMessage());
        }
        return null;
    }

    private String getGradleVersion(String gradlePath) {
        try {
            String[] cmd;
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                cmd = new String[]{gradlePath, "--version"};
            } else {
                cmd = new String[]{gradlePath, "--version"};
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Gradle")) {
                    return line.trim();
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Failed to get Gradle version: {}", e.getMessage());
        }
        return null;
    }

    private String runCommand(String[] cmd, String workingDir) {
        StringBuilder output = new StringBuilder();
        int exitCode = -1;

        try {
            logger.info("Running build command: {} in {}", String.join(" ", cmd), workingDir);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // Limit output size
                if (output.length() > 50000) {
                    output.append("\n[Output truncated due to size]\n");
                    break;
                }
            }

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            exitCode = process.exitValue();

            if (!finished) {
                process.destroyForcibly();
                return "{\"success\": false, \"error\": \"Build timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds\", \"output\": \"" +
                       escapeJson(output.toString()) + "\"}";
            }

        } catch (Exception e) {
            logger.error("Error running build command", e);
            return "{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}";
        }

        boolean success = exitCode == 0;
        String result = "{\"success\": " + success + ", " +
                       "\"exit_code\": " + exitCode + ", " +
                       "\"output\": \"" + escapeJson(output.toString()) + "\"}";
        return result;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("\t", "\\t");
    }
}
