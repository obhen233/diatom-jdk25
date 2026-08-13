package com.github.obhen233.core.agent.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tracks exploration tool usage and enforces budget limits.
 * Forces Agent to transition from exploration to execution phase.
 * 
 * Improvements:
 * 1. Dynamic budget based on project size (file count)
 * 2. Deduplication: same file/args not counted multiple times
 * 3. Structured guidance instead of hard blocking
 * 4. User confirmation for extra exploration when budget exhausted
 * 5. get_source_tree is budget-exempt (cached, fast, one-time)
 */
public class ExplorationBudget {
    private static final Logger logger = LoggerFactory.getLogger(ExplorationBudget.class);

    // Tools considered as "exploration" (read-only, info gathering)
    private static final Set<String> EXPLORATION_TOOLS = new HashSet<>(Arrays.asList(
        "read_file", "read_multiple_files", "list_directory", "list_files",
        "search_files", "grep", "glob", "get_file_info", "stat", "cat",
        "head", "tail", "less", "more", "find", "locate", "which", "where"
        // NOTE: get_source_tree is EXEMPT from exploration budget (cached, fast)
    ));

    // Budget configuration
    private static final int MIN_EXPLORATION_CALLS = 20;      // Minimum budget (increased from 10)
    private static final int MAX_EXPLORATION_CALLS = 100;     // Maximum budget cap (increased from 50)
    private static final int CALLS_PER_100_FILES = 5;         // Additional calls per 100 files
    private static final int DEFAULT_MAX_EXPLORATION_STEPS = 50;  // Increased from 20 to allow more exploration
    
    // Extra budget granted by user confirmation
    private static final int EXTRA_BUDGET_PER_CONFIRMATION = 10;

    // Keywords indicating agent has understood the task
    private static final Pattern UNDERSTANDING_PATTERN = Pattern.compile(
        "(我已经理解|我已理解|已理解|理解了|清楚了|已清楚|明白了|已明白|" +
        "I understand|I have understood|understood|I see|I got it|got it|" +
        "总结如下|以下是|综上所述|现在开始|接下来|我将|我会|开始执行|开始实施|" +
        "首先|第一步|开始|着手|执行以下)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private int maxExplorationCalls;
    private final int maxExplorationSteps;
    
    private int explorationCallCount = 0;
    private int explorationStepCount = 0;
    private boolean understandingDetected = false;
    private boolean budgetExceeded = false;
    private boolean guidanceAdded = false;
    private boolean guidanceInjected = false;  // Track if Plan guidance was injected
    
    // Track which files have been explored
    private final Set<String> exploredFiles = new HashSet<>();
    private final Set<String> exploredDirectories = new HashSet<>();
    
    // Track tool calls by signature (toolName|argsHash) to avoid double-counting
    private final Set<String> seenToolCalls = ConcurrentHashMap.newKeySet();
    
    // Track if get_source_tree has been called (only counts once)
    private boolean sourceTreeCalled = false;

    // Track budget-exempt search calls (search_symbols, search_references) to detect inefficient searching
    private int searchCallCount = 0;
    private static final int MAX_SEARCH_CALLS = 8;
    private boolean searchLimitWarningIssued = false;

    // Track if user has confirmed extra exploration
    private boolean userConfirmedExtra = false;
    private int extraBudgetGranted = 0;

    public ExplorationBudget() {
        this.maxExplorationCalls = MIN_EXPLORATION_CALLS;
        this.maxExplorationSteps = DEFAULT_MAX_EXPLORATION_STEPS;
    }

    public ExplorationBudget(int maxExplorationCalls, int maxExplorationSteps) {
        this.maxExplorationCalls = maxExplorationCalls;
        this.maxExplorationSteps = maxExplorationSteps;
    }
    
    /**
     * Dynamically adjust budget based on project size.
     * Call this when project file count is known.
     * @param fileCount Number of files in the project
     */
    public void adjustBudgetForProjectSize(int fileCount) {
        // Calculate dynamic budget: base + extra per 100 files
        int dynamicBudget = MIN_EXPLORATION_CALLS + (fileCount / 100) * CALLS_PER_100_FILES;
        // Cap at maximum
        this.maxExplorationCalls = Math.min(dynamicBudget, MAX_EXPLORATION_CALLS);
        logger.info("Exploration budget adjusted for {} files: {} calls (min={}, max={})", 
            fileCount, maxExplorationCalls, MIN_EXPLORATION_CALLS, MAX_EXPLORATION_CALLS);
    }
    
    /**
     * Adjust budget based on workspace directory.
     * Counts files recursively to determine project size.
     * @param workspaceDir The workspace directory path
     */
    public void adjustBudgetForWorkspace(String workspaceDir) {
        if (workspaceDir == null || !new File(workspaceDir).exists()) {
            return;
        }
        try {
            int fileCount = countFilesRecursively(new File(workspaceDir));
            adjustBudgetForProjectSize(fileCount);
        } catch (Exception e) {
            logger.debug("Could not count files in workspace: {}", e.getMessage());
        }
    }
    
    private int countFilesRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        
        int count = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                // Skip hidden dirs and common non-project dirs
                String name = f.getName();
                if (!name.startsWith(".") && !name.equals("node_modules") && 
                    !name.equals("target") && !name.equals("build")) {
                    count += countFilesRecursively(f);
                }
            } else {
                count++;
            }
        }
        return count;
    }

    /**
     * Record a search_symbols or search_references call.
     * These are budget-exempt but we track them to detect inefficient searching.
     * After MAX_SEARCH_CALLS, a warning is returned to guide the model.
     * @return warning message if limit exceeded, null otherwise
     */
    public String recordSearchCall(String toolName, String query) {
        searchCallCount++;
        logger.debug("Search call #{}: {} (query: {})", searchCallCount, toolName, query);

        if (searchCallCount > MAX_SEARCH_CALLS && !searchLimitWarningIssued) {
            searchLimitWarningIssued = true;
            return "[SEARCH STRATEGY WARNING] 已执行 " + searchCallCount + " 次搜索但未定位到目标文件。\n" +
                   "请暂停搜索，重新用 get_source_tree 观察项目命名模式，提炼更精确的关键词再试。\n" +
                   "避免使用 audit、list、get 等通用英文词。\n" +
                   "If " + searchCallCount + "+ searches haven't found the target, stop and refine strategy:\n" +
                   "1. Use get_source_tree to observe naming patterns\n" +
                   "2. Generate project-specific keywords\n" +
                   "3. Avoid generic English words";
        }
        return null;
    }

    /**
     * Get the current search call count.
     */
    public int getSearchCallCount() {
        return searchCallCount;
    }

    /**
     * Check if search limit warning was issued.
     */
    public boolean isSearchLimitWarningIssued() {
        return searchLimitWarningIssued;
    }

    /**
     * Record a tool call and check if it's an exploration tool.
     * Deduplicates same tool+args to avoid penalizing legitimate re-reads.
     * 
     * Special: get_source_tree is EXEMPT from budget (cached, fast, one-time use).
     * 
     * @param toolName Name of the tool being called
     * @param argsJson Tool arguments (for extracting paths)
     * @return true if the call is allowed, false if budget exceeded
     */
    public boolean recordToolCall(String toolName, String argsJson) {
        // get_source_tree is exempt from exploration budget - only count once
        if ("get_source_tree".equals(toolName)) {
            if (!sourceTreeCalled) {
                sourceTreeCalled = true;
                logger.debug("get_source_tree called for the first time (budget exempt)");
            } else {
                logger.debug("get_source_tree already called, skipping budget count");
            }
            return true;  // Always allow
        }
        
        if (!isExplorationTool(toolName)) {
            return true;
        }
        
        // Generate signature for deduplication
        String callSignature = toolName + "|" + (argsJson != null ? argsJson.hashCode() : 0);
        
        // Check if this exact call was made before - don't double-count
        if (seenToolCalls.contains(callSignature)) {
            logger.debug("Deduplicated exploration call: {} (already seen)", toolName);
            return true;  // Allow without counting
        }
        
        // Mark this call as seen
        seenToolCalls.add(callSignature);
        explorationCallCount++;
        
        // Track explored paths
        String path = extractPathFromArgs(argsJson);
        if (path != null) {
            if (toolName.contains("list") || toolName.contains("search") || toolName.contains("glob")) {
                exploredDirectories.add(path);
            } else {
                exploredFiles.add(path);
            }
        }
        
        logger.debug("Exploration tool call #{}: {} (path: {})", 
            explorationCallCount, toolName, path);
        
        // Check against effective limit (base + extra from user confirmation)
        int effectiveLimit = maxExplorationCalls + extraBudgetGranted;
        if (explorationCallCount >= effectiveLimit) {
            budgetExceeded = true;
            logger.warn("Exploration budget exceeded: {} calls (limit: {})", 
                explorationCallCount, effectiveLimit);
            return false;
        }
        
        return true;
    }
    
    /**
     * Grant extra exploration budget after user confirmation.
     * Called when user allows additional exploration.
     */
    public void grantExtraBudget() {
        extraBudgetGranted += EXTRA_BUDGET_PER_CONFIRMATION;
        userConfirmedExtra = true;
        budgetExceeded = false;  // Reset exceeded flag
        logger.info("User granted extra exploration budget: +{} calls (total extra: {})", 
            EXTRA_BUDGET_PER_CONFIRMATION, extraBudgetGranted);
    }
    
    /**
     * Check if user has confirmed extra exploration.
     */
    public boolean isUserConfirmedExtra() {
        return userConfirmedExtra;
    }
    
    /**
     * Get the effective budget limit (base + extra).
     */
    public int getEffectiveBudgetLimit() {
        return maxExplorationCalls + extraBudgetGranted;
    }

    /**
     * Record a step (LLM call) and check exploration budget.
     * @return true if the step is allowed, false if should transition to execution
     */
    public boolean recordStep() {
        explorationStepCount++;
        
        if (explorationStepCount >= maxExplorationSteps) {
            logger.warn("Exploration step budget exceeded: {} steps (limit: {})", 
                explorationStepCount, maxExplorationSteps);
            return false;
        }
        return true;
    }

    /**
     * Check reasoning content for understanding keywords.
     * @param reasoningContent The reasoning content from the model
     * @return true if understanding is detected, should transition to execution
     */
    public boolean checkUnderstanding(String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isEmpty()) {
            return false;
        }

        if (UNDERSTANDING_PATTERN.matcher(reasoningContent).find()) {
            understandingDetected = true;
            logger.info("Understanding detected in reasoning content, should transition to execution phase");
            return true;
        }
        return false;
    }

    /**
     * Check if guidance message has been added.
     */
    public boolean isGuidanceAdded() {
        return guidanceAdded;
    }
    
    /**
     * Set guidance message added flag.
     */
    public void setGuidanceAdded(boolean added) {
        this.guidanceAdded = added;
    }
    
    /**
     * Get current exploration call count.
     */
    public int getUsedCount() {
        return explorationCallCount;
    }
    
    /**
     * Set exploration call count (for checkpoint restoration).
     */
    public void setUsedCount(int count) {
        this.explorationCallCount = count;
        if (count >= maxExplorationCalls + extraBudgetGranted) {
            this.budgetExceeded = true;
        }
    }
    
    /**
     * Check if we should force transition to execution phase.
     * @param reasoningContent Optional reasoning content to check for understanding
     * @return true if should transition to execution
     */
    public boolean shouldTransitionToExecution(String reasoningContent) {
        // Check for understanding keywords
        if (reasoningContent != null && checkUnderstanding(reasoningContent)) {
            return true;
        }
        
        // Check budget
        if (budgetExceeded || explorationCallCount >= maxExplorationCalls) {
            return true;
        }
        
        return false;
    }

    /**
     * Get a message to inform the model about budget constraints.
     * @return Budget warning message or null if no constraint
     */
    public String getBudgetWarning() {
        if (explorationCallCount >= maxExplorationCalls - 2 && explorationCallCount < maxExplorationCalls) {
            return String.format(
                "[BUDGET WARNING] 探索预算即将耗尽: 已使用 %d/%d 次探索调用。请尽快开始执行任务。\n" +
                "[BUDGET WARNING] Exploration budget almost exhausted: %d/%d calls used. Please start executing the task soon.\n" +
                "已探索的文件: %s\n已探索的目录: %s",
                explorationCallCount, maxExplorationCalls,
                explorationCallCount, maxExplorationCalls,
                exploredFiles.isEmpty() ? "(无)" : String.join(", ", exploredFiles),
                exploredDirectories.isEmpty() ? "(无)" : String.join(", ", exploredDirectories)
            );
        }

        if (budgetExceeded) {
            return String.format(
                "[BUDGET EXCEEDED] 探索预算已耗尽: 已使用 %d/%d 次探索调用。请立即开始执行任务。\n" +
                "[BUDGET EXCEEDED] Exploration budget exhausted: %d/%d calls used. Please start executing the task immediately.\n" +
                "已探索的文件: %s\n已探索的目录: %s",
                explorationCallCount, maxExplorationCalls,
                explorationCallCount, maxExplorationCalls,
                exploredFiles.isEmpty() ? "(无)" : String.join(", ", exploredFiles),
                exploredDirectories.isEmpty() ? "(无)" : String.join(", ", exploredDirectories)
            );
        }

        return null;
    }

    /**
     * Check if a tool is an exploration tool.
     */
    public boolean isExplorationTool(String toolName) {
        return EXPLORATION_TOOLS.contains(toolName);
    }

    /**
     * Check if budget has been exceeded.
     */
    public boolean isBudgetExceeded() {
        int effectiveLimit = maxExplorationCalls + extraBudgetGranted;
        return budgetExceeded || explorationCallCount >= effectiveLimit;
    }
    
    /**
     * Check if more exploration is possible (for user confirmation prompt).
     * Returns true if budget is exceeded but user can still grant extra.
     */
    public boolean canGrantExtraExploration() {
        return budgetExceeded && !userConfirmedExtra;
    }

    /**
     * Check if Plan guidance has been injected.
     */
    public boolean isGuidanceInjected() {
        return guidanceInjected;
    }
    
    /**
     * Get an error message for when budget is exceeded during tool execution.
     * Returns structured guidance instead of hard blocking.
     * @param toolName the tool that was blocked
     * @return error message to return to the model
     */
    public String getBudgetExceededErrorMessage(String toolName) {
        // If guidance already injected, don't block - let the tool execute
        if (guidanceInjected) {
            logger.debug("Guidance already injected, allowing exploration tool: {}", toolName);
            return null;  // Allow the tool to execute
        }
        
        // If user hasn't confirmed extra, ask for confirmation
        if (!userConfirmedExtra) {
            return String.format(
                "[EXPLORATION BUDGET EXHAUSTED] 探索预算已耗尽: %d/%d 次调用。\\n" +
                "[EXPLORATION BUDGET EXHAUSTED] Exploration budget exhausted: %d/%d calls.\\n\\n" +
                "已探索文件/Explored files: %d\\n" +
                "已探索目录/Explored directories: %d\\n\\n" +
                "如需继续探索，请输入 'explore' 确认额外探索 (额外 +%d 次)。\\n" +
                "To continue exploration, type 'explore' to confirm (extra +%d calls).\\n\\n" +
                "或开始执行任务: write_file, run_command 等修改工具可用。\\n" +
                "Or start executing: write_file, run_command, etc. are available.",
                explorationCallCount, maxExplorationCalls,
                explorationCallCount, maxExplorationCalls,
                exploredFiles.size(), exploredDirectories.size(),
                EXTRA_BUDGET_PER_CONFIRMATION, EXTRA_BUDGET_PER_CONFIRMATION
            );
        }
        
        // Mark that we've injected guidance (only once)
        guidanceInjected = true;
        
        return String.format(
            "[EXPLORATION GUIDANCE] 探索预算已使用 %d/%d 次，请基于已探索信息开始执行。\\n" +
            "[EXPLORATION GUIDANCE] Exploration budget used: %d/%d calls. Please start executing with explored information.\\n\\n" +
            "已探索的文件/Explored files: %s\\n" +
            "已探索的目录/Explored directories: %s\\n\\n" +
            "请在信息不完整的情况下，输出执行方案：\\n" +
            "Please output your execution plan with incomplete information:\\n\\n" +
            "```PLAN_A\\n" +
            "[方案A: 基于现有信息直接执行]\\n" +
            "描述: \\n" +
            "优点: 无需更多探索 | 缺点: 可能需要中途调整 | 风险: 中\\n" +
            "```\\n\\n" +
            "```PLAN_B\\n" +
            "[方案B: 假设常见模式继续]\\n" +
            "描述: \\n" +
            "优点: 快速推进 | 缺点: 可能需要假设 | 风险: 中\\n" +
            "```\\n\\n" +
            "```PLAN_C\\n" +
            "[方案C: 请求用户提供更多信息]\\n" +
            "描述: \\n" +
            "优点: 信息准确 | 缺点: 需要等待 | 风险: 低\\n" +
            "```\\n\\n" +
            "请选择一个方案并继续执行。/ Please choose one plan and continue.",
            explorationCallCount, maxExplorationCalls,
            explorationCallCount, maxExplorationCalls,
            exploredFiles.isEmpty() ? "(无/None)" : String.join(", ", exploredFiles),
            exploredDirectories.isEmpty() ? "(无/None)" : String.join(", ", exploredDirectories)
        );
    }

    /**
     * Get current exploration statistics.
     */
    public ExplorationStats getStats() {
        return new ExplorationStats(
            explorationCallCount, maxExplorationCalls,
            explorationStepCount, maxExplorationSteps,
            exploredFiles.size(), exploredDirectories.size(),
            understandingDetected, budgetExceeded
        );
    }

    /**
     * Reset the budget tracker for a new task.
     */
    public void reset() {
        explorationCallCount = 0;
        explorationStepCount = 0;
        understandingDetected = false;
        budgetExceeded = false;
        guidanceAdded = false;
        guidanceInjected = false;
        exploredFiles.clear();
        exploredDirectories.clear();
        seenToolCalls.clear();
        sourceTreeCalled = false;
        userConfirmedExtra = false;
        extraBudgetGranted = 0;
        searchCallCount = 0;
        searchLimitWarningIssued = false;
        logger.debug("Exploration budget reset");
    }

    /**
     * Statistics about exploration usage.
     */
    public static class ExplorationStats {
        public final int callCount;
        public final int maxCalls;
        public final int stepCount;
        public final int maxSteps;
        public final int filesExplored;
        public final int directoriesExplored;
        public final boolean understandingDetected;
        public final boolean budgetExceeded;

        public ExplorationStats(int callCount, int maxCalls, int stepCount, int maxSteps,
                               int filesExplored, int directoriesExplored,
                               boolean understandingDetected, boolean budgetExceeded) {
            this.callCount = callCount;
            this.maxCalls = maxCalls;
            this.stepCount = stepCount;
            this.maxSteps = maxSteps;
            this.filesExplored = filesExplored;
            this.directoriesExplored = directoriesExplored;
            this.understandingDetected = understandingDetected;
            this.budgetExceeded = budgetExceeded;
        }

        @Override
        public String toString() {
            return String.format("ExplorationStats{calls=%d/%d, steps=%d/%d, files=%d, dirs=%d, understood=%s, exceeded=%s}",
                callCount, maxCalls, stepCount, maxSteps, filesExplored, directoriesExplored,
                understandingDetected, budgetExceeded);
        }
    }

    private String extractPathFromArgs(String argsJson) {
        if (argsJson == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(argsJson);
            if (node.has("path")) {
                return node.get("path").asText();
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return null;
    }
}
