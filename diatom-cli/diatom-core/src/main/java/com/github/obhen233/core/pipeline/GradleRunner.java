package com.github.obhen233.core.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;

import static com.github.obhen233.core.pipeline.PipelineConstants.*;

/**
 * PipelineRunner for Gradle build operations.
 * Supports the "gradle" action type.
 */
public class GradleRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(GradleRunner.class);

    @Override
    public String getActionType() {
        return ACTION_GRADLE;
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String tasks = step.getCommand();
        if (tasks == null || tasks.trim().isEmpty()) {
            if (step.getCommands() != null && !step.getCommands().isEmpty()) {
                tasks = String.join(" ", step.getCommands());
            } else {
                tasks = "build";
                callback.onOutput("  (no command specified, defaulting to: " + tasks + ")\n");
            }
        }

        tasks = tasks.trim();

        String projectDir = variables.get(VAR_PROJECT_DIR);
        File workDir = projectDir != null ? new File(projectDir) : new File(".");

        // Auto-detect Gradle command
        String gradleCmd = findGradleCommand(workDir);
        boolean usingWrapper = gradleCmd.contains("gradlew");

        if (usingWrapper) {
            callback.onOutput("$ ./" + new File(gradleCmd).getName() + " " + tasks + "\n");
        } else {
            callback.onOutput("$ " + new File(gradleCmd).getName() + " " + tasks + "\n");
        }
        logger.info("Gradle step '{}': {} {}", step.getName(), gradleCmd, tasks);

        // Build command
        StringBuilder fullCmd = new StringBuilder(gradleCmd);

        String buildFile = variables.get(VAR_GRADLE_BUILD_FILE);
        if (buildFile != null && !buildFile.isEmpty()) {
            fullCmd.append(" -b \"").append(buildFile).append("\"");
        }

        String projectProps = variables.get(VAR_GRADLE_PROJECT_PROPS);
        if (projectProps != null && !projectProps.isEmpty()) {
            fullCmd.append(" ").append(projectProps);
        }

        String noDaemon = variables.get(VAR_GRADLE_NO_DAEMON);
        if ("true".equalsIgnoreCase(noDaemon)) {
            fullCmd.append(" --no-daemon");
        }

        String parallel = variables.get(VAR_GRADLE_PARALLEL);
        if ("true".equalsIgnoreCase(parallel)) {
            fullCmd.append(" --parallel");
        }

        String noCache = variables.get(VAR_GRADLE_NO_CACHE);
        if ("true".equalsIgnoreCase(noCache)) {
            fullCmd.append(" --no-build-cache");
        }

        String stacktrace = variables.get(VAR_GRADLE_STACKTRACE);
        if ("true".equalsIgnoreCase(stacktrace)) {
            fullCmd.append(" --stacktrace");
        }

        String refreshDeps = variables.get(VAR_GRADLE_REFRESH_DEPS);
        if ("true".equalsIgnoreCase(refreshDeps)) {
            fullCmd.append(" --refresh-dependencies");
        }

        fullCmd.append(" ").append(tasks);

        String gradleUserHome = variables.get(VAR_GRADLE_USER_HOME);
        if (gradleUserHome != null && !gradleUserHome.isEmpty()) {
            fullCmd.insert(0, "GRADLE_USER_HOME=\"" + gradleUserHome + "\" ");
        }

        int exitCode = CommandExecutor.execute(fullCmd.toString(), workDir,
                TIMEOUT_BUILD, OUTPUT_LIMIT_BUILD,
                "(Gradle build timeout after " + TIMEOUT_BUILD + " minutes)",
                callback,
                pb -> {
                    pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");
                    String systemGradleHome = System.getenv("GRADLE_USER_HOME");
                    if (systemGradleHome != null) {
                        pb.environment().put("GRADLE_USER_HOME", systemGradleHome);
                    }
                });

        boolean success = exitCode == 0;
        if (success) {
            callback.onOutput("\n" + CHECK + " Gradle step '" + step.getName() + "' completed (BUILD SUCCESSFUL)\n");
        } else {
            callback.onOutput("\n" + CROSS + " Gradle step '" + step.getName() + "' failed (exit: " + exitCode + ")\n");
        }
        return success;
    }

    /**
     * Find the Gradle command to use, preferring project wrapper.
     */
    static String findGradleCommand(File projectDir) {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        // 1. Check for Gradle Wrapper (gradlew) in project directory
        File gradlew = new File(projectDir, isWin ? "gradlew.bat" : "gradlew");
        if (gradlew.exists()) {
            return gradlew.getAbsolutePath();
        }
        // 2. Check GRADLE_USER_HOME environment
        String gradleUserHome = System.getenv("GRADLE_USER_HOME");
        if (gradleUserHome != null && !gradleUserHome.isEmpty()) {
            File gradleBin = new File(gradleUserHome, "bin" + File.separator + (isWin ? "gradle.bat" : "gradle"));
            if (gradleBin.exists()) {
                return gradleBin.getAbsolutePath();
            }
        }
        // 3. Fall back to system PATH
        return isWin ? "gradle.bat" : "gradle";
    }
}
