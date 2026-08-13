package com.github.obhen233.core.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;

import static com.github.obhen233.core.pipeline.PipelineConstants.*;

/**
 * PipelineRunner for Maven build operations.
 * Supports the "maven" action type.
 */
public class MavenRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(MavenRunner.class);

    @Override
    public String getActionType() {
        return ACTION_MAVEN;
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String goals = step.getCommand();
        if (goals == null || goals.trim().isEmpty()) {
            if (step.getCommands() != null && !step.getCommands().isEmpty()) {
                goals = String.join(" ", step.getCommands());
            } else {
                goals = "clean package -DskipTests";
                callback.onOutput("  (no command specified, defaulting to: " + goals + ")\n");
            }
        }

        goals = goals.trim();

        String projectDir = variables.get(VAR_PROJECT_DIR);
        File workDir = projectDir != null ? new File(projectDir) : new File(".");

        // Auto-detect Maven command
        String mvnCmd = findMavenCommand(workDir);
        boolean usingWrapper = mvnCmd.contains("mvnw");

        if (usingWrapper) {
            callback.onOutput("$ ./" + new File(mvnCmd).getName() + " " + goals + "\n");
        } else {
            callback.onOutput("$ " + new File(mvnCmd).getName() + " " + goals + "\n");
        }
        logger.info("Maven step '{}': {} {}", step.getName(), mvnCmd, goals);

        // Build command
        StringBuilder fullCmd = new StringBuilder(mvnCmd);

        String settings = variables.get(VAR_MAVEN_SETTINGS);
        if (settings != null && !settings.isEmpty()) {
            File settingsFile = new File(settings);
            if (settingsFile.exists()) {
                fullCmd.append(" -s \"").append(settingsFile.getAbsolutePath()).append("\"");
            }
        }

        String userSettings = variables.get(VAR_MAVEN_USER_SETTINGS);
        if (userSettings != null && !userSettings.isEmpty()) {
            File usFile = new File(userSettings);
            if (usFile.exists()) {
                fullCmd.append(" --global-settings \"").append(usFile.getAbsolutePath()).append("\"");
            }
        }

        String localRepo = variables.get(VAR_MAVEN_LOCAL_REPO);
        if (localRepo != null && !localRepo.isEmpty()) {
            fullCmd.append(" -Dmaven.repo.local=\"").append(localRepo).append("\"");
        }

        String mavenOpts = variables.get(VAR_MAVEN_OPTS);
        if (mavenOpts != null && !mavenOpts.isEmpty()) {
            fullCmd.insert(0, "MAVEN_OPTS=\"" + mavenOpts + "\" ");
        }

        String offline = variables.get(VAR_MAVEN_OFFLINE);
        if ("true".equalsIgnoreCase(offline)) {
            fullCmd.append(" -o");
        }

        fullCmd.append(" ").append(goals);

        int exitCode = CommandExecutor.execute(fullCmd.toString(), workDir,
                TIMEOUT_BUILD, OUTPUT_LIMIT_BUILD,
                "(Maven build timeout after " + TIMEOUT_BUILD + " minutes)",
                callback,
                pb -> pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8"));

        boolean success = exitCode == 0;
        if (success) {
            callback.onOutput("\n" + CHECK + " Maven step '" + step.getName() + "' completed (BUILD SUCCESS)\n");
        } else {
            callback.onOutput("\n" + CROSS + " Maven step '" + step.getName() + "' failed (exit: " + exitCode + ")\n");
        }
        return success;
    }

    /**
     * Find the Maven command to use, preferring project wrapper.
     */
    static String findMavenCommand(File projectDir) {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        // 1. Check for Maven Wrapper (mvnw) in project directory
        File mvnw = new File(projectDir, isWin ? "mvnw.cmd" : "mvnw");
        if (mvnw.exists()) {
            return mvnw.getAbsolutePath();
        }
        // 2. Check MAVEN_HOME / M2_HOME environment
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome == null || mavenHome.isEmpty()) {
            mavenHome = System.getenv("M2_HOME");
        }
        if (mavenHome != null && !mavenHome.isEmpty()) {
            File mvnBin = new File(mavenHome, "bin" + File.separator + (isWin ? "mvn.cmd" : "mvn"));
            if (mvnBin.exists()) {
                return mvnBin.getAbsolutePath();
            }
        }
        // 3. Fall back to system PATH
        return isWin ? "mvn.cmd" : "mvn";
    }
}
