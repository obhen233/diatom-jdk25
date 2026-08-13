package com.github.obhen233.cli.execute;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.agent.ToolConfirmationException;
import com.github.obhen233.core.security.ApprovalPolicy;
import com.github.obhen233.core.security.ApprovalStrategyResolver;
import com.github.obhen233.core.security.SandboxLevel;
import com.github.obhen233.core.tool.ToolRegistry.UnauthorizedAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synchronous executor for {@code --execute} mode.
 * <p>
 * Runs a single prompt through the agent, captures the result,
 * formats it according to the requested output format, and writes
 * to stdout or a file.
 */
public class ExecuteModeRunner {

    private static final Logger log = LoggerFactory.getLogger(ExecuteModeRunner.class);

    /** Pattern for {{token_usage_summary:1.2K:350:1.5K}} */
    private static final Pattern TOKEN_USAGE_PATTERN =
        Pattern.compile("\\{\\{token_usage_summary:([^:]+):([^:]+):([^}]+)\\}\\}");

    private final OutputFormatter formatter;
    private final Path outputPath;
    private final SandboxLevel level;
    private final ApprovalPolicy policy;
    private final String taskId;

    public ExecuteModeRunner(OutputFormatter formatter, Path outputPath,
                              SandboxLevel level, ApprovalPolicy policy) {
        this(formatter, outputPath, level, policy, null);
    }

    public ExecuteModeRunner(OutputFormatter formatter, Path outputPath,
                              SandboxLevel level, ApprovalPolicy policy,
                              String taskId) {
        this.formatter = formatter;
        this.outputPath = outputPath;
        this.level = level;
        this.policy = policy;
        this.taskId = taskId;
    }

    /**
     * Resolve the prompt from the --execute value, remaining args, or stdin.
     *
     * @param executeValue value of --execute / -e (may be null or empty)
     * @param allArgs      all CLI arguments (to find remaining non-flag tokens)
     * @return the resolved prompt string
     * @throws IllegalArgumentException if no prompt could be resolved
     */
    public static String resolvePrompt(String executeValue, String[] allArgs) {
        // Priority 1: --execute "prompt text"
        if (executeValue != null && !executeValue.isEmpty()) {
            return executeValue;
        }

        // Priority 2: Remaining CLI tokens after flag parsing
        String remaining = extractRemainingArgs(allArgs);
        if (remaining != null && !remaining.isEmpty()) {
            return remaining;
        }

        // Priority 3: Stdin pipe
        String stdin = readStdin();
        if (stdin != null && !stdin.isEmpty()) {
            return stdin;
        }

        throw new IllegalArgumentException(
            "No prompt provided for --execute mode. " +
            "Usage: -e \"your prompt\" or echo \"prompt\" | java -jar diatom-cli.jar -e");
    }

    /**
     * Execute the prompt against the agent and produce formatted output.
     */
    public void execute(ReActAgent agent, String prompt) throws IOException {
        // Set agent strategy — use specified level/policy (user may have overridden via -l/-a)
        // Default in execute mode is FULL+SILENT unless user specified otherwise
        ApprovalStrategyResolver resolver = new ApprovalStrategyResolver(level, policy);
        agent.setApprovalStrategyResolver(resolver);

        log.info("Execute mode: level={}, policy={}, prompt=\"{}\"",
            level.name().toLowerCase(), policy.name().toLowerCase(),
            prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt);

        // Resume from checkpoint if taskId is provided
        if (taskId != null) {
            log.info("Resuming from task: {}", taskId);
            boolean resumed = agent.resumeFromCheckpoint(taskId);
            if (!resumed) {
                ExecuteResult err = ExecuteResult.error(
                    "Checkpoint not found for task: " + taskId);
                err.setStatus("REJECTED");
                writeOutput(err);
                return;
            }
        }

        // Run agent
        String rawResult;
        try {
            rawResult = agent.run(prompt);
        } catch (ToolConfirmationException e) {
            // Should not happen with non-ASK policy, but handle gracefully
            ExecuteResult err = ExecuteResult.error(
                "Operation blocked: " + e.getMessage());
            err.setStatus("REJECTED");
            writeOutput(err);
            return;
        } catch (UnauthorizedAccessException e) {
            ExecuteResult err = ExecuteResult.error(
                "Access denied: " + e.getMessage());
            err.setStatus("REJECTED");
            writeOutput(err);
            return;
        } catch (Exception e) {
            log.error("Agent execution failed", e);
            writeOutput(ExecuteResult.error(e.getMessage()));
            return;
        }

        // Parse result and build structured output
        ExecuteResult result = parseResult(rawResult, agent);
        result.setTaskId(agent.getCurrentTaskId());
        writeOutput(result);
    }

    // ==================== Result Parsing ====================

    /**
     * Parse the agent's raw text result into a structured ExecuteResult.
     * Strips metadata markers like {{token_usage_summary:...}} from the response text.
     */
    static ExecuteResult parseResult(String rawResult, ReActAgent agent) {
        if (rawResult == null) {
            return ExecuteResult.error("Null result from agent");
        }

        // Determine status from content
        ExecuteResult result;
        if (rawResult.startsWith("{{api_rate_limit}}") ||
            rawResult.startsWith("{{api_circuit_open}}") ||
            rawResult.startsWith("{{api_auth_error}}") ||
            rawResult.startsWith("{{api_network_error}}") ||
            rawResult.startsWith("{{api_quota_exceeded}}")) {
            result = ExecuteResult.error(rawResult);
        } else {
            result = ExecuteResult.success(rawResult);
        }

        // Extract token usage
        ExecuteResult.TokenUsage tokenUsage = extractTokenUsage(rawResult);
        if (tokenUsage != null) {
            result.setTokenUsage(tokenUsage);
            // Strip token usage marker from response text
            String cleaned = TOKEN_USAGE_PATTERN.matcher(rawResult).replaceFirst("").trim();
            result.setResponse(cleaned);
        }

        return result;
    }

    static ExecuteResult.TokenUsage extractTokenUsage(String text) {
        if (text == null) return null;
        Matcher m = TOKEN_USAGE_PATTERN.matcher(text);
        if (m.find()) {
            try {
                long prompt = parseFormattedTokenCount(m.group(1));
                long completion = parseFormattedTokenCount(m.group(2));
                long total = parseFormattedTokenCount(m.group(3));
                return new ExecuteResult.TokenUsage(prompt, completion, total);
            } catch (Exception e) {
                log.warn("Failed to parse token usage from: {}", m.group(0));
            }
        }
        return null;
    }

    static long parseFormattedTokenCount(String formatted) {
        if (formatted == null || formatted.isEmpty()) return 0;
        String s = formatted.trim().toUpperCase();
        double multiplier = 1;
        if (s.endsWith("K")) {
            multiplier = 1_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("M")) {
            multiplier = 1_000_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("G")) {
            multiplier = 1_000_000_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("T")) {
            multiplier = 1_000_000_000_000L;
            s = s.substring(0, s.length() - 1);
        }
        return (long) (Double.parseDouble(s) * multiplier);
    }

    // ==================== Output ====================

    private void writeOutput(ExecuteResult result) throws IOException {
        byte[] formatted = formatter.format(result);

        if (outputPath != null) {
            // Ensure parent directory exists
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(outputPath, formatted);
            log.info("Output written to: {}", outputPath.toAbsolutePath());
        } else {
            // Write to stdout
            OutputStream out = new BufferedOutputStream(System.out);
            out.write(formatted);
            out.write('\n');
            out.flush();
        }
    }

    // ==================== Prompt Resolution Helpers ====================

    /**
     * Extract remaining CLI args after all known flags are consumed.
     * This picks up text after flags like: -e "prompt" -- any trailing non-flag tokens.
     * Actually for -e mode, the value of -e IS the prompt, so this is only used
     * when -e is given without a value (to consume remaining args).
     */
    private static String extractRemainingArgs(String[] args) {
        if (args == null) return null;
        StringBuilder sb = new StringBuilder();
        // Skip known flags and their values
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                // Check if it has a value
                if (a.contains("=")) continue; // --flag=value
                // Check if next arg is a value (not a flag)
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    i++; // skip value
                }
                continue;
            }
            if (a.startsWith("-") && a.length() == 2) {
                // Short flag with value
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    i++; // skip value
                }
                continue;
            }
            // Non-flag token
            if (sb.length() > 0) sb.append(" ");
            sb.append(a);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String readStdin() {
        // Only attempt stdin read if console is not available (piped input)
        if (System.console() != null) {
            return null; // Interactive terminal, not a pipe
        }
        try {
            byte[] buf = new byte[4096];
            int len = System.in.read(buf);
            if (len > 0) {
                return new String(buf, 0, len, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            log.debug("No stdin data available: {}", e.getMessage());
        }
        return null;
    }
}
