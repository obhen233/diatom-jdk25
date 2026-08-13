package com.github.obhen233.adapter.claude;

import com.github.obhen233.adapter.spi.AgentAdapter;
import com.github.obhen233.adapter.spi.AgentInfo;
import com.github.obhen233.adapter.spi.AgentRequest;
import com.github.obhen233.adapter.spi.AgentResponse;
import com.github.obhen233.adapter.spi.AgentResponse.FileDiff;
import com.github.obhen233.adapter.spi.StreamConsumer;
import com.github.obhen233.adapter.util.BinaryResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AgentAdapter implementation that wraps the Claude Code CLI ({@code claude})
 * as a diatom Worker.
 *
 * <p>Communicates with the claude binary via subprocess: builds CLI arguments,
 * sends the prompt via stdin, reads the JSON/streaming response from stdout,
 * and optionally compares workspace file snapshots to generate file diffs.</p>
 *
 * <p>Binary lookup order:</p>
 * <ol>
 *   <li>System PATH ({@code claude} command)</li>
 *   <li>Fallback: {@code /c/claude/claude} (Windows Git Bash path)</li>
 * </ol>
 */
public class ClaudeAgentAdapter implements AgentAdapter {

    private static final Logger LOG = Logger.getLogger(ClaudeAgentAdapter.class.getName());

    private final ObjectMapper mapper = new ObjectMapper();

    private String claudeBinary;
    private String model;
    private long timeoutMs;
    private ClaudeProcessManager processManager;
    private String adapterWorkspace;

    @Override
    public String getAgentType() {
        return "claude-code";
    }

    /**
     * Resolve the model name with fallback priority:
     *   1. Explicitly configured model (from YAML/properties config claude.model)
     *   2. ANTHROPIC_MODEL environment variable (Claude CLI default)
     *   3. OPENAI_MODEL environment variable (OpenAI-compatible mode)
     *   4. CLAUDE_MODEL environment variable (generic fallback)
     *   5. getAgentType() fallback
     */
    private String resolveModel() {
        if (model != null && !model.isEmpty()) {
            return model;
        }
        // Check env vars in priority order matching common Claude CLI setups
        String[] envVars = {"ANTHROPIC_MODEL", "OPENAI_MODEL", "CLAUDE_MODEL"};
        for (String envVar : envVars) {
            String envVal = System.getenv(envVar);
            if (envVal != null && !envVal.isEmpty()) {
                LOG.info("Resolved model from environment variable " + envVar + "=" + envVal);
                return envVal;
            }
        }
        return getAgentType();
    }

    @Override
    public AgentInfo getAgentInfo() {
        // Traits describing agent characteristics
        List<String> traits = new ArrayList<String>();
        traits.add("coding");
        traits.add("reasoning");
        traits.add("code-generation");

        // Capability scores for routing decisions
        Map<String, Double> capabilities = new LinkedHashMap<String, Double>();
        capabilities.put("\u4ee3\u7801\u751f\u6210", 0.9);    // 代码生成
        capabilities.put("\u4ee3\u7801\u91cd\u6784", 0.85);   // 代码重构
        capabilities.put("Bug\u4fee\u590d", 0.85);             // Bug修复
        capabilities.put("\u4ee3\u7801\u5ba1\u67e5", 0.85);   // 代码审查
        capabilities.put("\u5355\u5143\u6d4b\u8bd5", 0.8);    // 单元测试

        // Adapter bridges to external agent; use resolved model identifier.
        // maxConcurrency=1, costPer1kTokens=0.0, supportsStreaming=true,
        // supportsToolCalls=true, maxSteps=50, maxTokens=200000
        return new AgentInfo(resolveModel(), traits, capabilities, 1, 0.0, true, true, 50, 200000);
    }

    @Override
    public void init(Map<String, String> config) {
        // Configure JUL to write to file (not console)
        try {
            String logDir = System.getProperty("user.home", "~") + "/.diatom/logs";
            java.io.File logDirFile = new java.io.File(logDir);
            if (!logDirFile.exists()) {
                logDirFile.mkdirs();
            }
            java.util.logging.FileHandler fileHandler = new java.util.logging.FileHandler(
                    logDir + "/claude-driver.log", true /* append */);
            fileHandler.setLevel(java.util.logging.Level.ALL);
            fileHandler.setFormatter(new java.util.logging.SimpleFormatter());
            LOG.setLevel(java.util.logging.Level.ALL);
            LOG.addHandler(fileHandler);
            LOG.setUseParentHandlers(false);
        } catch (Exception e) {
            // If file logging can't be set up, fall back to no handler
            LOG.setUseParentHandlers(false);
        }

        // Resolve claude binary via BinaryResolver (full priority chain)
        this.claudeBinary = BinaryResolver.resolve("claude", config);
        LOG.info("Claude binary resolved: " + claudeBinary);

        this.model = config != null ? config.get("claude.model") : null;
        this.processManager = new ClaudeProcessManager();

        // Timeout: default 5 minutes
        String timeoutStr = config != null ? config.get("claude.timeout") : null;
        this.timeoutMs = 300000L;
        if (timeoutStr != null) {
            try {
                this.timeoutMs = Long.parseLong(timeoutStr);
            } catch (NumberFormatException e) {
                LOG.warning("Invalid claude.timeout value: " + timeoutStr + ", using default 300000ms");
            }
        }

        // Verify claude is accessible
        try {
            String version = getClaudeVersion();
            LOG.info("Claude Code binary found: " + claudeBinary + " (version: " + version + ")");
        } catch (IOException e) {
            LOG.warning("Failed to verify claude version: " + e.getMessage());
        }
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        ClaudeProcessManager pm = new ClaudeProcessManager();
        long startTime = System.currentTimeMillis();

        // Snapshot workspace files before execution (if workspace path is set)
        Map<String, FileSnapshot> beforeSnapshots = null;
        String workspacePath = request.workspacePath();
        if (workspacePath != null && !workspacePath.isEmpty()) {
            beforeSnapshots = snapshotWorkspace(workspacePath);
        }

        try {
            // Build CLI command
            List<String> command = buildCommand(request, false);

            // Assemble stdin content
            String stdinContent = buildStdinContent(request);

            LOG.info("=== ClaudeAgentAdapter execute ===");
            LOG.info("Command: " + String.join(" ", command));
            LOG.info("Stdin content length: " + stdinContent.length() + " chars");
            LOG.info("Stdin content: [" + stdinContent + "]");
            byte[] stdinBytes = stdinContent.getBytes(StandardCharsets.UTF_8);
            StringBuilder stdinHex = new StringBuilder();
            for (int i = 0; i < stdinBytes.length; i++) {
                stdinHex.append(String.format("%02x ", stdinBytes[i]));
                if ((i + 1) % 16 == 0) stdinHex.append('\n');
            }
            LOG.info("Stdin hex dump (" + stdinBytes.length + " bytes):\n" + stdinHex.toString());

            // Start process
            pm.start(workspacePath != null ? workspacePath : ".", command);

            // Read stdout FIRST to prevent pipe buffer deadlock on Windows.
            // The OS pipe buffer (typically 64KB) can fill up if the process
            // produces output before we start consuming it, causing a deadlock
            // where the child blocks writing stdout and the parent waits for
            // the child to exit.
            final StringBuilder responseBuilder = new StringBuilder(4096);
            pm.readStdout(new ClaudeProcessManager.LineConsumer() {
                @Override
                public void onLine(String line) {
                    responseBuilder.append(line).append('\n');
                }
            });

            // Then read stderr (for logging, non-blocking)
            pm.readStderr();

            // Finally write stdin and close it
            pm.writeStdin(stdinContent);

            // Wait for completion
            int exitCode = pm.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            pm.joinStdoutReader();

            String rawOutput = responseBuilder.toString().trim();
            LOG.info("Claude process exit code: " + exitCode);
            LOG.info("Raw stdout length: " + rawOutput.length() + " chars");
            LOG.info("Raw stdout: [" + rawOutput + "]");
            byte[] rawBytes = rawOutput.getBytes(StandardCharsets.UTF_8);
            int dumpLen = Math.min(rawBytes.length, 500);
            StringBuilder rawHex = new StringBuilder();
            for (int i = 0; i < dumpLen; i++) {
                rawHex.append(String.format("%02x ", rawBytes[i]));
                if ((i + 1) % 16 == 0) rawHex.append('\n');
            }
            LOG.info("Raw stdout hex (first " + dumpLen + " bytes):\n" + rawHex.toString());

            if (exitCode != 0) {
                return new AgentResponse(null, AgentResponse.STATUS_ERROR,
                        "claude CLI exited with code " + exitCode
                                + ". Output: " + truncate(rawOutput, 500),
                        null);
            }

            // Parse response (claude -p outputs plain text)
            AgentResponse response = parseResponse(rawOutput, request);
            LOG.info("Parsed response: status=" + response.status()
                    + ", responseLen=" + (response.response() != null ? response.response().length() : 0)
                    + ", response=[" + response.response() + "]");

            // Compare workspace file snapshots to generate diffs
            if (beforeSnapshots != null && workspacePath != null) {
                List<FileDiff> diffs = computeFileDiffs(workspacePath, beforeSnapshots);
                if (!diffs.isEmpty()) {
                    response = new AgentResponse(response.response(), response.status(),
                            response.errorMessage(), diffs);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("Claude Code execution completed in " + elapsed + "ms, exit code=" + exitCode);

            return response;

        } catch (IOException e) {
            AgentResponse errorResp = new AgentResponse(null, AgentResponse.STATUS_ERROR,
                    "IO error executing claude: " + e.getMessage(), null);
            LOG.log(Level.SEVERE, "Claude execution failed", e);
            return errorResp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AgentResponse(null, AgentResponse.STATUS_CANCELLED,
                    "Execution was interrupted", null);
        } finally {
            pm.cancel();
        }
    }

    @Override
    public void executeStream(AgentRequest request, final StreamConsumer consumer) {
        ClaudeProcessManager pm = new ClaudeProcessManager();
        long startTime = System.currentTimeMillis();

        try {
            List<String> command = buildCommand(request, true);
            String stdinContent = buildStdinContent(request);
            String workspacePath = request.workspacePath();

            pm.start(workspacePath != null ? workspacePath : ".", command);
            // Must read stdout BEFORE writing stdin to prevent pipe buffer deadlock
            // In -p mode, claude outputs plain text line by line — each line is a token chunk
            pm.readStdout(new ClaudeProcessManager.LineConsumer() {
                @Override
                public void onLine(String line) {
                    if (line != null && !line.isEmpty()) {
                        consumer.onToken(line);
                    }
                }
            });

            // Then read stderr and write stdin
            pm.readStderr();
            pm.writeStdin(stdinContent);

            int exitCode = pm.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            pm.joinStdoutReader();

            if (exitCode == 0) {
                consumer.onComplete();
            } else {
                consumer.onError("claude CLI exited with code " + exitCode);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("Claude Code streaming completed in " + elapsed + "ms, exit code=" + exitCode);

        } catch (IOException e) {
            consumer.onError("IO error: " + e.getMessage());
            LOG.log(Level.SEVERE, "Claude streaming failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            consumer.onError("Streaming was interrupted");
        } finally {
            pm.cancel();
        }
    }

    @Override
    public void setWorkspace(String workspacePath) {
        this.adapterWorkspace = workspacePath;
        LOG.info("Adapter workspace set to: " + workspacePath);
    }

    @Override
    public void cancel() {
        if (processManager != null) {
            processManager.cancel();
        }
    }

    // ========== Private helpers ==========

    /**
     * Get the claude CLI version string.
     */
    private String getClaudeVersion() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(claudeBinary, "--version");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String version = reader.readLine();
            p.waitFor(5, TimeUnit.SECONDS);
            return version != null ? version : "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        } finally {
            p.destroyForcibly();
        }
    }

    /**
     * Build the CLI command list for claude.
     * Uses -p (print/non-interactive) mode which reads prompt from stdin
     * and outputs plain text response to stdout.
     */
    private List<String> buildCommand(AgentRequest request, boolean streaming) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(claudeBinary);
        cmd.add("-p"); // print/non-interactive mode — reads prompt from stdin

        // Security mapping
        Map<String, String> metadata = request.metadata();
        if (metadata != null) {
            String permissionMode = metadata.get(ClaudeSecurityMapper.KEY_PERMISSION_MODE);
            if (permissionMode != null && !permissionMode.isEmpty()) {
                cmd.add("--permission-mode");
                cmd.add(permissionMode);
            }

            String skipPerms = metadata.get(ClaudeSecurityMapper.KEY_SKIP_PERMISSIONS);
            if ("true".equals(skipPerms)) {
                cmd.add("--dangerously-skip-permissions");
            }
        }

        cmd.add("--no-session-persistence");

        // Working directory is already set via ProcessBuilder.directory() in
        // ClaudeProcessManager.start(). No need for --working-directory CLI flag.

        return cmd;
    }

    /**
     * Assemble the stdin content for claude -p mode from conversation history and message.
     */
    private String buildStdinContent(AgentRequest request) {
        StringBuilder sb = new StringBuilder(2048);

        // Include conversation history context
        List<AgentRequest.ChatMessage> history = request.conversationHistory();
        if (history != null && !history.isEmpty()) {
            for (AgentRequest.ChatMessage msg : history) {
                String role = msg.role();
                String content = msg.content();
                if (content != null && !content.isEmpty()) {
                    if ("user".equals(role) || "human".equalsIgnoreCase(role)) {
                        sb.append("User: ").append(content).append("\n\n");
                    } else if ("assistant".equals(role) || "ai".equalsIgnoreCase(role)) {
                        sb.append("Assistant: ").append(content).append("\n\n");
                    } else {
                        sb.append(role).append(": ").append(content).append("\n\n");
                    }
                }
            }
        }

        // Append the current message
        String message = request.message();
        if (message != null && !message.isEmpty()) {
            sb.append("User: ").append(message).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Parse the response from claude CLI.
     * Claude -p mode outputs plain text to stdout, which is the response directly.
     */
    private AgentResponse parseResponse(String rawOutput, AgentRequest request) {
        return new AgentResponse(rawOutput != null ? rawOutput : "",
                AgentResponse.STATUS_COMPLETED, null, null);
    }



    /**
     * Snapshot all files in a workspace directory before execution.
     * Records relative path, last modified timestamp, and file size.
     */
    private Map<String, FileSnapshot> snapshotWorkspace(String workspacePath) {
        Map<String, FileSnapshot> snapshots = new HashMap<String, FileSnapshot>();
        File workspaceDir = new File(workspacePath);
        if (!workspaceDir.isDirectory()) {
            return snapshots;
        }
        walkDirectory(workspaceDir, workspaceDir, snapshots);
        return snapshots;
    }

    /**
     * Recursively walk a directory and record file snapshots.
     */
    private void walkDirectory(File baseDir, File currentDir, Map<String, FileSnapshot> snapshots) {
        File[] files = currentDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            // Skip common non-project directories and hidden files
            if (name.equals(".git") || name.equals("node_modules")
                    || name.equals("target") || name.equals(".diatom")
                    || name.equals(".claude") || name.equals(".idea")
                    || name.equals(".DS_Store") || name.equals("__pycache__")) {
                continue;
            }
            if (file.isDirectory()) {
                walkDirectory(baseDir, file, snapshots);
            } else if (file.isFile()) {
                String relativePath = baseDir.toURI().relativize(file.toURI()).getPath();
                snapshots.put(relativePath, new FileSnapshot(file.lastModified(), file.length()));
            }
        }
    }

    /**
     * Compare current workspace state with before-snapshot and generate file diffs.
     */
    private List<FileDiff> computeFileDiffs(String workspacePath,
                                             Map<String, FileSnapshot> beforeSnapshots) {
        List<FileDiff> diffs = new ArrayList<FileDiff>();
        Map<String, FileSnapshot> afterSnapshots = snapshotWorkspace(workspacePath);
        File workspaceDir = new File(workspacePath);

        // Check for modified and deleted files
        for (Map.Entry<String, FileSnapshot> entry : beforeSnapshots.entrySet()) {
            String relPath = entry.getKey();
            FileSnapshot before = entry.getValue();
            FileSnapshot after = afterSnapshots.get(relPath);

            if (after == null) {
                // File was deleted
                diffs.add(new FileDiff(relPath, "deleted", null,
                        readFileContent(new File(workspaceDir, relPath))));
            } else if (before.lastModified != after.lastModified
                    || before.size != after.size) {
                // File was modified
                diffs.add(new FileDiff(relPath, "modified", null,
                        readFileContent(new File(workspaceDir, relPath))));
            }
        }

        // Check for new files
        for (Map.Entry<String, FileSnapshot> entry : afterSnapshots.entrySet()) {
            if (!beforeSnapshots.containsKey(entry.getKey())) {
                // File was added
                diffs.add(new FileDiff(entry.getKey(), "added", null,
                        readFileContent(new File(workspaceDir, entry.getKey()))));
            }
        }

        return diffs;
    }

    /**
     * Read a file's content as a string.
     * Returns null if the file doesn't exist or can't be read.
     */
    private String readFileContent(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] bytes = readAllBytes(file);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.fine("Could not read file content: " + file.getPath());
            return null;
        }
    }

    /**
     * Read all bytes from a file (Java 8 compatible).
     */
    private byte[] readAllBytes(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        try {
            long length = file.length();
            if (length > Integer.MAX_VALUE) {
                throw new IOException("File too large: " + file.getPath());
            }
            byte[] bytes = new byte[(int) length];
            int offset = 0;
            int read;
            while (offset < bytes.length
                    && (read = fis.read(bytes, offset, bytes.length - offset)) >= 0) {
                offset += read;
            }
            return bytes;
        } finally {
            fis.close();
        }
    }

    /**
     * Simple snapshot record for file comparison.
     */
    private static class FileSnapshot {
        final long lastModified;
        final long size;

        FileSnapshot(long lastModified, long size) {
            this.lastModified = lastModified;
            this.size = size;
        }
    }

    /**
     * Check if running on Windows.
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Truncate a string to maxLength chars for logging.
     */
    private static String truncate(String s, int maxLength) {
        if (s == null) {
            return null;
        }
        if (s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength) + "...";
    }
}
