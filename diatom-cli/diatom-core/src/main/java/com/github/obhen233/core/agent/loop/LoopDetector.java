package com.github.obhen233.core.agent.loop;

import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects loops in agent tool calls and consecutive failures.
 * Enhanced to detect:
 * 1. Exact same tool calls (name + args identical)
 * 2. Similar tool calls (same tool name, args similarity >= 80%)
 * 3. Same tool name repeated many times
 * 4. Consecutive tool failures (different tools, all returning errors)
 */
public class LoopDetector {
    private static final Logger logger = LoggerFactory.getLogger(LoopDetector.class);
    private static final int MAX_REPEATED_TOOL_CALLS = 5;
    private static final int MAX_SAME_TOOL_NAME_CALLS = 15;
    private static final double SIMILARITY_THRESHOLD = 0.8;

    // Read-only exploration tools — legitimately called many times with different args
    private static final Set<String> EXPLORATION_TOOLS = new HashSet<>(Arrays.asList(
        "search_symbols", "search_files", "search_references",
        "list_files", "list_directory", "get_source_tree",
        "summarize_file", "read_file"
    ));

    // Tools called many times in development workflows with different arguments each time.
    // These should not trigger frequency-based loop detection (#3).
    // Exact-match (#1) and similarity (#2) checks still catch real loops for these tools.
    // Includes: built-in command/shell tools, and all MCP tools (added at runtime via addExcludedTools()).
    private final Set<String> frequentTools = new HashSet<>(Arrays.asList(
        "run_command"
    ));

    // Consecutive failure detection
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private static final int MAX_CONSECUTIVE_EMPTY = 3;

    // For exact match detection
    private String lastToolCall = null;
    private int sameToolCallCount = 0;

    // For similar call detection
    private String lastToolName = null;
    private String lastToolArgs = null;
    private int similarToolCallCount = 0;

    // For consecutive failure detection
    private int consecutiveErrorCount = 0;
    private int consecutiveEmptyCount = 0;

    // For same tool name frequency detection
    private final Map<String, Integer> toolNameFrequency = new LinkedHashMap<String, Integer>() {
        private static final int MAX_SIZE = 50;
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_SIZE;
        }
    };

    // Step-based window for frequency detection - must be >= MAX_SAME_TOOL_NAME_CALLS
    private final Map<String, Integer> toolNameFrequencyWindow = new LinkedHashMap<String, Integer>() {
        private static final int MAX_SIZE = 50;
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_SIZE;
        }
    };
    private int recentToolCallCount = 0;
    // Window size must be >= MAX_SAME_TOOL_NAME_CALLS for detection to work
    private static final int WINDOW_SIZE = 25;

    /**
     * Detect if there's a loop in tool calls
     * @return true if loop is detected
     */
    public boolean detectLoop(ChatMessage lastMessage) {
        if (lastMessage == null || !lastMessage.hasToolCalls()) {
            // No tool calls means final response, reset loop detection
            reset();
            return false;
        }

        boolean loopDetected = false;
        for (ToolCall tc : lastMessage.getToolCalls()) {
            String currentToolCall = tc.getName() + "|" + tc.getArguments();
            String currentToolName = tc.getName();
            String currentToolArgs = tc.getArguments() != null ? tc.getArguments() : "";

            // 1. Check for exact same tool call
            if (currentToolCall.equals(lastToolCall)) {
                sameToolCallCount++;
                if (sameToolCallCount >= MAX_REPEATED_TOOL_CALLS) {
                    logger.warn("Loop detected: Exact same tool call repeated {} times: {}", sameToolCallCount, currentToolName);
                    loopDetected = true;
                    break;
                }
            } else {
                sameToolCallCount = 0;
            }

            // 2. Check for similar tool calls (same name, similar args)
            // Skip for exploration tools and frequent tools — these are legitimately
            // called with structurally similar but semantically different arguments.
            // Only exact-match (#1) catches real loops for these tools.
            if (currentToolName.equals(lastToolName) && lastToolArgs != null
                && !EXPLORATION_TOOLS.contains(currentToolName)
                && !frequentTools.contains(currentToolName)) {
                double similarity = calculateSimilarity(currentToolArgs, lastToolArgs);
                if (similarity >= SIMILARITY_THRESHOLD) {
                    similarToolCallCount++;
                    if (similarToolCallCount >= MAX_REPEATED_TOOL_CALLS) {
                        logger.warn("Loop detected: Similar tool call repeated {} times: {} (similarity: {}%)",
                            similarToolCallCount, currentToolName, (int)(similarity * 100));
                        loopDetected = true;
                        break;
                    }
                } else {
                    similarToolCallCount = 0;
                }
            } else {
                similarToolCallCount = 0;
            }

            // 3. Check for same tool name called too many times within recent window
            // Only applies to NON-exploration tools (search/read tools are legitimately
            // called many times with different args during exploration).
            // Also excludes frequentTools (e.g. run_command, MCP tools) which are commonly
            // called with different arguments during development (test, patch, build cycles).
            // Check 1 (exact match) and Check 2 (similar args) still catch real loops
            // for these tools when args are identical or very similar.
            if (!EXPLORATION_TOOLS.contains(currentToolName) && !frequentTools.contains(currentToolName)) {
                recentToolCallCount++;
                int currentFreq = toolNameFrequencyWindow.getOrDefault(currentToolName, 0) + 1;
                toolNameFrequencyWindow.put(currentToolName, currentFreq);

                // Detect loop if same tool called MAX_SAME_TOOL_NAME_CALLS or more times
                // within a window of WINDOW_SIZE recent calls
                if (currentFreq >= MAX_SAME_TOOL_NAME_CALLS) {
                    logger.warn("Loop detected: Tool '{}' called {} times within last {} calls",
                        currentToolName, currentFreq, recentToolCallCount);
                    loopDetected = true;
                    break;
                }

                // Clear window when it fills up (sliding window)
                if (recentToolCallCount >= WINDOW_SIZE) {
                    toolNameFrequencyWindow.clear();
                    recentToolCallCount = 0;
                }
            }

            lastToolCall = currentToolCall;
            lastToolName = currentToolName;
            lastToolArgs = currentToolArgs;
        }

        return loopDetected;
    }

    /**
     * Calculate string similarity using Levenshtein distance
     * @return similarity ratio between 0.0 and 1.0
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;

        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;

        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLen;
    }

    /**
     * Calculate Levenshtein distance between two strings
     */
    private int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();

        // Use a rolling array to save memory
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                );
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[n];
    }

    /**
     * Reset loop detection state
     */
    public void reset() {
        lastToolCall = null;
        lastToolName = null;
        lastToolArgs = null;
        sameToolCallCount = 0;
        similarToolCallCount = 0;
        // Don't clear toolNameFrequency - it persists across steps to detect overall repetition

        // Note: recentToolCallCount is incremented in detectLoop(), not here
        // Window is cleared there after WINDOW_SIZE calls or when it fills
    }

    /**
     * Add tool names to the frequency-based exclusion list.
     * Tools in this set will not trigger frequency-based loop detection (#3),
     * but are still subject to exact-match (#1) and similarity (#2) checks.
     * <p>
     * Used by ReActAgent to exclude MCP tools, which are called with diverse
     * arguments in legitimate development workflows.
     *
     * @param toolNames set of tool names to exclude from frequency detection
     */
    public void addExcludedTools(Set<String> toolNames) {
        if (toolNames != null) {
            frequentTools.addAll(toolNames);
        }
    }

    /**
     * Record a tool execution result to detect consecutive failures.
     * @param toolName name of the tool that was executed
     * @param result the result content returned by the tool
     * @return true if now stuck (consecutive errors exceeded threshold)
     */
    public boolean recordToolResult(String toolName, String result) {
        if (isErrorResult(result)) {
            consecutiveErrorCount++;
            if ("search_files".equals(toolName) || "list_files".equals(toolName)) {
                consecutiveEmptyCount++;
            }
            logger.debug("Consecutive error #{} from tool '{}'", consecutiveErrorCount, toolName);
        } else {
            consecutiveErrorCount = 0;
            consecutiveEmptyCount = 0;
        }
        return isStuck();
    }

    /**
     * Check if the agent is stuck on consecutive failures.
     */
    public boolean isStuck() {
        return consecutiveErrorCount >= MAX_CONSECUTIVE_ERRORS
            || consecutiveEmptyCount >= MAX_CONSECUTIVE_EMPTY;
    }

    /**
     * Reset consecutive error counters (call after injecting stuck message).
     */
    public void resetErrorCount() {
        consecutiveErrorCount = 0;
        consecutiveEmptyCount = 0;
    }

    /**
     * Get consecutive error count for logging.
     */
    public int getConsecutiveErrorCount() {
        return consecutiveErrorCount;
    }

    /**
     * Check if a tool result indicates failure.
     */
    private boolean isErrorResult(String result) {
        if (result == null) return true;
        String trimmed = result.trim();
        if (trimmed.isEmpty()) return true;
        if (trimmed.startsWith("ERROR") || trimmed.startsWith("Error:")) return true;
        if (trimmed.contains("\"error\"")) return true;
        return false;
    }

    /**
     * Clear all state including tool name frequency (call at start of new task)
     */
    public void clearAll() {
        reset();
        resetErrorCount();
        toolNameFrequency.clear();
        toolNameFrequencyWindow.clear();
        recentToolCallCount = 0;
    }

    /**
     * Build loop exceeded message
     */
    public String buildLoopExceededMessage(long totalPromptTokens, long totalCompletionTokens,
                                           long totalTokens, com.github.obhen233.core.session.SessionTracker sessionTracker) {
        String result = I18n.get("loop_timeout");
        if (totalTokens > 0) {
            result += "\n\n" + I18n.get("token_usage_summary",
                I18n.formatTokenCount(totalPromptTokens),
                I18n.formatTokenCount(totalCompletionTokens),
                I18n.formatTokenCount(totalTokens));
        }
        if (sessionTracker != null && sessionTracker.hasChanges()) {
            result += "\n\n" + sessionTracker.buildSummary();
        }
        return result;
    }

    /**
     * Build the "stuck" message to inject when consecutive errors exceed threshold.
     * Tells the model to report to the user and ask for guidance.
     */
    public String buildStuckMessage() {
        String countMsg = I18n.get("agent.stuck.count", consecutiveErrorCount);
        String stuckMsg = I18n.get("agent.stuck.message");
        return "## " + countMsg + "\n\n" + stuckMsg;
    }

    public String getLastToolCall() {
        return lastToolCall;
    }

    public int getSameToolCallCount() {
        return sameToolCallCount;
    }

    public Map<String, Integer> getToolNameFrequency() {
        return new HashMap<>(toolNameFrequency);
    }
}