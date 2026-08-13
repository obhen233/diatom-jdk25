package com.github.obhen233.core.pipeline;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Shared utility for executing local shell commands and streaming output.
 * <p>
 * Encapsulates the common pattern of:
 * <ol>
 *   <li>Selecting the correct shell (cmd.exe on Windows, /bin/sh on Unix)</li>
 *   <li>Starting the process with UTF-8 encoding</li>
 *   <li>Reading and streaming output with truncation</li>
 *   <li>Timeout handling</li>
 * </ol>
 * </p>
 */
public final class CommandExecutor {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    private CommandExecutor() {}

    /**
     * Execute a shell command and stream its output.
     *
     * @param command       the shell command string
     * @param workDir       working directory for the process
     * @param timeoutMinutes max wait time in minutes
     * @param outputLimit   max bytes of output before truncation
     * @param timeoutMessage message to print on timeout (e.g. "(command timeout)")
     * @param callback      output callback
     * @return the process exit code
     */
    public static int execute(String command, File workDir,
                               long timeoutMinutes, long outputLimit,
                               String timeoutMessage,
                               PipelineCallback callback) throws IOException, InterruptedException {
        String[] cmdArray = buildCommandArray(command);

        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Read and stream output
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            long totalOutput = 0;
            while ((line = reader.readLine()) != null) {
                callback.onOutput(line + "\n");
                totalOutput += line.length() + 1;
                if (totalOutput > outputLimit) {
                    callback.onOutput("... (output too long, truncated)\n");
                    process.destroyForcibly();
                    break;
                }
            }
        }

        // Wait for completion with timeout
        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            callback.onOutput("\n" + timeoutMessage + "\n");
            return -1;
        }
        return process.exitValue();
    }

    /**
     * Execute with default output limit (512KB) and auto-generated timeout message.
     */
    public static int execute(String command, File workDir,
                               long timeoutMinutes,
                               PipelineCallback callback) throws IOException, InterruptedException {
        return execute(command, workDir, timeoutMinutes,
                PipelineConstants.OUTPUT_LIMIT_DEFAULT,
                "(command timeout after " + timeoutMinutes + " minutes)",
                callback);
    }

    /**
     * Execute with custom ProcessBuilder configuration (e.g., environment variables).
     */
    public static int execute(String command, File workDir,
                               long timeoutMinutes, long outputLimit,
                               String timeoutMessage,
                               PipelineCallback callback,
                               java.util.function.Consumer<ProcessBuilder> configurer)
            throws IOException, InterruptedException {
        String[] cmdArray = buildCommandArray(command);

        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        if (configurer != null) {
            configurer.accept(pb);
        }

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            long totalOutput = 0;
            while ((line = reader.readLine()) != null) {
                callback.onOutput(line + "\n");
                totalOutput += line.length() + 1;
                if (totalOutput > outputLimit) {
                    callback.onOutput("... (output too long, truncated)\n");
                    process.destroyForcibly();
                    break;
                }
            }
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            callback.onOutput("\n" + timeoutMessage + "\n");
            return -1;
        }
        return process.exitValue();
    }

    private static String[] buildCommandArray(String command) {
        if (IS_WINDOWS) {
            return new String[]{
                    PipelineConstants.WIN_SHELL,
                    PipelineConstants.WIN_SHELL_ARG,
                    PipelineConstants.WIN_SHELL_PREFIX + command
            };
        } else {
            return new String[]{
                    PipelineConstants.UNIX_SHELL,
                    PipelineConstants.UNIX_SHELL_ARG,
                    command
            };
        }
    }
}
