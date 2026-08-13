package com.github.obhen233.core.agent.context;

import com.github.obhen233.core.context.ProjectContext;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.skill.Skill;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.core.skill.SystemPromptManager;
import com.github.obhen233.config.SystemInfo;
import com.github.obhen233.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ContextManager {
    private static final Logger logger = LoggerFactory.getLogger(ContextManager.class);
    private static final int MAX_CONTEXT_MESSAGES = 40;
    
    // Context window compression settings
    private static final double COMPRESSION_THRESHOLD = 0.70;  // Trigger at 70% of context window
    private static final int MIN_KEEP_ROUNDS = 5;             // Keep at least 5 recent rounds
    private static final int MAX_KEEP_ROUNDS = 8;             // Keep at most 8 recent rounds
    private static final int DEFAULT_CONTEXT_WINDOW = 128000; // Default context window size
    private static final int MAX_TOOL_RESULT_CHARS = 8000;    // Condense oversized tool results to this

    private final SystemPromptManager promptManager;
    private final SkillManager skillManager;
    private final ProjectIndexer projectIndexer;
    private final SystemInfo systemInfo;
    private final TokenCounter tokenCounter;
    private final FileReadTracker fileReadTracker;
    private final int contextWindowSize;
    
    // Track compression state
    private int lastCompressedTokenCount = 0;
    private int compressionCount = 0;

    // Running token counter to avoid O(n) recalculation on every needsCompression() check
    private volatile long runningTokenTotal = -1; // -1 = needs recalculation

    // Whether to include ProjectIndexer context in system prompt
    // Set to false when the caller provides its own project context (e.g. IDE)
    private volatile boolean includeProjectContext = true;

    // Cached system prompt components (keyed by content comparison, auto-invalidated on change)
    private String cachedSystemPrompt;
    private String cachedSkillContextKey;
    private String cachedSkillContext;
    private String cachedProjectContextKey;
    private String cachedProjectContext;

    public ContextManager(SystemPromptManager promptManager, SkillManager skillManager,
                         ProjectIndexer projectIndexer, SystemInfo systemInfo) {
        this(promptManager, skillManager, projectIndexer, systemInfo, DEFAULT_CONTEXT_WINDOW);
    }
    
    public ContextManager(SystemPromptManager promptManager, SkillManager skillManager,
                         ProjectIndexer projectIndexer, SystemInfo systemInfo, int contextWindowSize) {
        this.promptManager = promptManager;
        this.skillManager = skillManager;
        this.projectIndexer = projectIndexer;
        this.systemInfo = systemInfo;
        this.tokenCounter = new TokenCounter();
        this.fileReadTracker = new FileReadTracker();
        this.contextWindowSize = contextWindowSize;
    }

    /**
     * Get the file read tracker for managing file read deduplication
     */
    public FileReadTracker getFileReadTracker() {
        return fileReadTracker;
    }

    /**
     * Set whether to include ProjectIndexer context in the system prompt.
     * Set to false when the caller provides its own project context.
     */
    public void setIncludeProjectContext(boolean include) {
        this.includeProjectContext = include;
    }

    /**
     * Build the initial system message with skills and project context
     */
    public ChatMessage buildSystemMessage(String userInput, List<String> involvedFiles) {
        // Cache systemPrompt by content comparison (auto-invalidated on change)
        String systemPrompt = promptManager.getSystemPrompt();
        if (!systemPrompt.equals(cachedSystemPrompt)) {
            cachedSystemPrompt = systemPrompt;
        }

        // Cache skillContext by matched skill names key (auto-invalidated when skills change)
        List<Skill> matchedSkills = skillManager.matchSkills(userInput, involvedFiles);
        String skillContext;
        String skillKey = buildSkillCacheKey(matchedSkills);
        if (skillKey.equals(cachedSkillContextKey)) {
            skillContext = cachedSkillContext;
        } else {
            skillContext = skillManager.buildContext(matchedSkills);
            cachedSkillContextKey = skillKey;
            cachedSkillContext = skillContext;
        }

        // Cache projectContext by its built summary text
        String projectContextSummary = null;
        if (includeProjectContext) {
            ProjectContext pc = projectIndexer.getContext();
            projectContextSummary = pc.buildContextSummary();
        }
        String projKey = projectContextSummary != null ? projectContextSummary : "";
        if (!projKey.equals(cachedProjectContextKey)) {
            cachedProjectContextKey = projKey;
            cachedProjectContext = projectContextSummary;
        }

        // Add system info (cheap to compute, no caching needed)
        String systemInfoSummary = systemInfo.buildSummary();

        // Build the full prompt
        StringBuilder full = new StringBuilder();
        full.append(cachedSystemPrompt).append("\n\n").append(systemInfoSummary);

        if (cachedProjectContext != null && !cachedProjectContext.isEmpty()) {
            full.append("\n").append(cachedProjectContext);
        }

        full.append("\n").append(skillContext);
        return new ChatMessage("system", full.toString());
    }

    /**
     * Build a cache key for matched skills by joining their names.
     * Changes when skills are added/removed/reordered.
     */
    private String buildSkillCacheKey(List<Skill> matchedSkills) {
        if (matchedSkills == null || matchedSkills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Skill s : matchedSkills) {
            if (sb.length() > 0) sb.append("|");
            sb.append(s.getName() != null ? s.getName() : "");
        }
        return sb.toString();
    }

    /**
     * Truncate messages if context window is too large
     * Keeps complete conversation rounds and summarizes older messages
     * Uses toolCallId for precise pairing of tool results with their tool_calls
     * 
     * IMPORTANT: The API requires that every assistant message with tool_calls
     * must be followed by tool messages for each tool_call_id. This method
     * ensures we never break this invariant during truncation.
     */
    public List<ChatMessage> truncateContext(List<ChatMessage> messages) {
        if (messages.size() <= MAX_CONTEXT_MESSAGES) {
            return new ArrayList<>(messages);
        }

        logger.warn("Context exceeds {} messages, will summarize older messages", MAX_CONTEXT_MESSAGES);

        ChatMessage systemMsg = messages.get(0);

        // Build a map: toolCallId → index of the assistant message that made the call
        Map<String, Integer> toolCallIdToAssistantIdx = new HashMap<>();
        // Build a map: assistantIdx → list of tool result indices for this assistant
        Map<Integer, Set<Integer>> assistantToToolResults = new HashMap<>();
        
        for (int i = 1; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                Set<Integer> toolResultIndices = new HashSet<>();
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getId() != null) {
                        toolCallIdToAssistantIdx.put(tc.getId(), i);
                        // Find corresponding tool result
                        for (int j = i + 1; j < messages.size(); j++) {
                            ChatMessage toolMsg = messages.get(j);
                            if ("tool".equals(toolMsg.getRole()) && tc.getId().equals(toolMsg.getToolCallId())) {
                                toolResultIndices.add(j);
                            }
                        }
                    }
                }
                assistantToToolResults.put(i, toolResultIndices);
            }
        }

        // Step 1: Identify complete "atomic units" that must stay together
        // An atomic unit is: assistant(+tool_calls) + ALL its tool results
        // These must NEVER be split
        Set<Integer> indicesToKeep = new LinkedHashSet<>();
        int roundsKept = 0;
        int idx = messages.size() - 1;

        while (idx > 0 && roundsKept < MAX_CONTEXT_MESSAGES - 1) {
            ChatMessage msg = messages.get(idx);

            if ("tool".equals(msg.getRole())) {
                // Tool result - mark it but don't count as a round
                // The round is counted when we process the parent assistant
                String toolCallId = msg.getToolCallId();
                if (toolCallId != null && toolCallIdToAssistantIdx.containsKey(toolCallId)) {
                    indicesToKeep.add(idx);
                }
                idx--;
            } else if ("assistant".equals(msg.getRole())) {
                if (msg.hasToolCalls()) {
                    // This is a complete unit: assistant + all its tool results
                    // We must include ALL tool results if we include this assistant
                    if (!indicesToKeep.contains(idx)) {
                        // Check if ALL tool results for this assistant are available
                        Set<Integer> toolResults = assistantToToolResults.get(idx);
                        if (toolResults != null && !toolResults.isEmpty()
                            && toolResults.size() == msg.getToolCalls().size()) {
                            // All tool results must be in range and available
                            boolean allAvailable = true;
                            for (int tri : toolResults) {
                                if (tri <= idx) {
                                    allAvailable = false;
                                    break;
                                }
                            }
                            if (allAvailable) {
                                // Include the complete atomic unit
                                indicesToKeep.add(idx);
                                indicesToKeep.addAll(toolResults);
                                roundsKept++; // Count this as one round
                                idx--;        // Move past this assistant
                            } else {
                                // Tool results exist but not all available — skip orphan
                                idx--;
                            }
                        } else {
                            // No tool results yet (pending tools in resume mode, or orphan).
                            // Keep the assistant with pending tool_calls intact so the
                            // resume handler can execute them.
                            indicesToKeep.add(idx);
                            roundsKept++;
                            idx--;
                        }
                    } else {
                        // Assistant already included via tool result processing
                        roundsKept++;
                        idx--;  // Move past this already-included assistant
                    }
                } else {
                    // Assistant without tool_calls - simple complete message
                    indicesToKeep.add(idx);
                    idx--;
                    roundsKept++;
                }
            } else if ("user".equals(msg.getRole())) {
                indicesToKeep.add(idx);
                idx--;
                roundsKept++;
            } else {
                indicesToKeep.add(idx);
                idx--;
            }
        }

        // Step 2: Final validation - ensure no broken tool chains
        // If an assistant with tool_calls is kept, ALL tool results MUST be kept
        Set<Integer> toRemove = new HashSet<>();
        for (int i : indicesToKeep) {
            ChatMessage m = messages.get(i);
            if ("assistant".equals(m.getRole()) && m.hasToolCalls()) {
                Set<Integer> expectedToolResults = assistantToToolResults.get(i);
                if (expectedToolResults != null) {
                    for (int toolResultIdx : expectedToolResults) {
                        if (!indicesToKeep.contains(toolResultIdx)) {
                            // Missing tool result - must remove this assistant
                            logger.warn("Assistant at index {} has incomplete tool results, removing", i);
                            toRemove.add(i);
                            break;
                        }
                    }
                }
            }
        }
        indicesToKeep.removeAll(toRemove);

        // Step 3: Remove orphan tool results (tool messages without their parent assistant)
        Set<Integer> orphanToolResults = new HashSet<>();
        for (int i : indicesToKeep) {
            ChatMessage m = messages.get(i);
            if ("tool".equals(m.getRole())) {
                String tcId = m.getToolCallId();
                if (tcId != null) {
                    Integer assistantIdx = toolCallIdToAssistantIdx.get(tcId);
                    if (assistantIdx == null || !indicesToKeep.contains(assistantIdx)) {
                        logger.warn("Found orphan tool result at index {}, removing", i);
                        orphanToolResults.add(i);
                    }
                }
            }
        }
        indicesToKeep.removeAll(orphanToolResults);

        // Sort indices and build the result
        List<Integer> sortedIndices = new ArrayList<>(indicesToKeep);
        Collections.sort(sortedIndices);

        // Build structured summary of older messages (replacing simple 100-char truncation)
        StringBuilder summaryBuilder = new StringBuilder();
        int summarizedCount = 0;

        // Extract original task (first user message)
        String originalTask = null;
        for (int i = 1; i <= idx; i++) {
            if (sortedIndices.contains(i)) continue;
            ChatMessage msg = messages.get(i);
            if ("user".equals(msg.getRole()) && msg.getContent() != null && !msg.getContent().startsWith("[")) {
                originalTask = msg.getContent().length() > 200 ? msg.getContent().substring(0, 200) + "..." : msg.getContent();
                break;
            }
        }

        // Extract completed operations, failures, and current progress
        List<String> completedOps = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        String currentProgress = null;

        for (int i = 1; i <= idx; i++) {
            if (sortedIndices.contains(i)) continue;

            ChatMessage msg = messages.get(i);
            String role = msg.getRole();
            String content = msg.getContent() != null ? msg.getContent() : "";

            // Extract completed operations from tool results
            if ("tool".equals(role) && (content.contains("success") || content.contains("completed"))) {
                String toolName = null;
                String toolPath = null;
                if (msg.getToolCallId() != null) {
                    Integer assistantIdx = toolCallIdToAssistantIdx.get(msg.getToolCallId());
                    if (assistantIdx != null) {
                        ChatMessage assistantMsg = messages.get(assistantIdx);
                        if (assistantMsg.hasToolCalls()) {
                            for (ToolCall tc : assistantMsg.getToolCalls()) {
                                if (msg.getToolCallId().equals(tc.getId())) {
                                    toolName = tc.getName();
                                    toolPath = extractToolPath(tc.getArguments());
                                    break;
                                }
                            }
                        }
                    }
                }
                if (toolName != null) {
                    completedOps.add(toolPath != null ? toolName + "(" + toolPath + ")" : toolName);
                }
            }

            // Extract failures/errors (keep at most 3, with 300 chars each)
            if (content.contains("error") || content.contains("failed") || content.contains("Error")) {
                String errorSnippet = content.length() > 300 ? content.substring(0, 300) + "..." : content;
                if (failures.size() < 3) {
                    failures.add(errorSnippet);
                }
            }

            // Track current progress (last assistant text message before keep region)
            if ("assistant".equals(role) && !msg.hasToolCalls() && content.length() > 0 && currentProgress == null) {
                currentProgress = content.length() > 150 ? content.substring(0, 150) + "..." : content;
            }

            summarizedCount++;
        }

        // Build structured summary
        summaryBuilder.append("[CONTEXT SUMMARY - ").append(summarizedCount).append(" messages summarized]\n\n");
        summaryBuilder.append("=== Original Task ===\n");
        summaryBuilder.append(originalTask != null ? originalTask : "(not captured)\n");
        summaryBuilder.append("\n=== Completed Operations ===\n");
        if (completedOps.isEmpty()) {
            summaryBuilder.append("(none recorded)\n");
        } else {
            Set<String> uniqueOps = new LinkedHashSet<>(completedOps);
            int count = 0;
            for (String op : uniqueOps) {
                if (count++ >= 10) {
                    summaryBuilder.append("... and ").append(uniqueOps.size() - 10).append(" more\n");
                    break;
                }
                summaryBuilder.append("- ").append(op).append("\n");
            }
        }
        summaryBuilder.append("\n=== Failures/Errors ===\n");
        if (failures.isEmpty()) {
            summaryBuilder.append("(none)\n");
        } else {
            for (String f : failures) {
                summaryBuilder.append("- ").append(f).append("\n");
            }
        }
        summaryBuilder.append("\n=== Current Progress ===\n");
        summaryBuilder.append(currentProgress != null ? currentProgress : "(in progress)\n");
        summaryBuilder.append("\n[End of summary - recent interactions follow]\n");

        List<ChatMessage> result = new ArrayList<>();
        result.add(systemMsg);

        if (summarizedCount > 0) {
            ChatMessage summaryMsg = new ChatMessage();
            summaryMsg.setRole("user");
            summaryMsg.setContent(summaryBuilder.toString());
            result.add(summaryMsg);
        }

        // Add kept messages in order
        for (int i : sortedIndices) {
            result.add(messages.get(i));
        }

        // Step 4: Final sanity check - verify the result is valid
        if (!validateToolChainIntegrity(result)) {
            logger.error("CRITICAL: truncateContext produced invalid message chain, returning original messages");
            return new ArrayList<>(messages);
        }

        logger.info("Context summarized: {} older messages -> summary, kept {} recent messages",
            summarizedCount, sortedIndices.size());

        return result;
    }

    /**
     * Validate that every assistant with tool_calls has all required tool responses.
     * This is a critical check to prevent API 400 errors.
     */
    private boolean validateToolChainIntegrity(List<ChatMessage> messages) {
        Set<String> pendingToolCallIds = new HashSet<>();
        
        for (ChatMessage msg : messages) {
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getId() != null) {
                        pendingToolCallIds.add(tc.getId());
                    }
                }
            } else if ("tool".equals(msg.getRole())) {
                String tcId = msg.getToolCallId();
                if (tcId != null) {
                    pendingToolCallIds.remove(tcId);
                }
            }
        }
        
        if (!pendingToolCallIds.isEmpty()) {
            logger.error("Invalid tool chain: missing responses for tool_call_ids: {}", pendingToolCallIds);
            return false;
        }
        return true;
    }

    /**
     * Clean up incomplete tool chains from the message list.
     * This is used when API returns 400 error due to broken tool chain.
     * 
     * An incomplete tool chain is an assistant message with tool_calls
     * that doesn't have all corresponding tool results.
     * 
     * @param messages The message list to clean up
     * @return Cleaned message list with incomplete tool chains removed
     */
    public List<ChatMessage> cleanupIncompleteToolChains(List<ChatMessage> messages) {
        if (messages == null || messages.size() <= 1) {
            return messages;
        }

        // Build a map: toolCallId -> assistant index that created it
        Map<String, Integer> toolCallIdToAssistantIdx = new HashMap<>();
        Map<Integer, Set<String>> assistantToToolCallIds = new HashMap<>();
        
        for (int i = 1; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                Set<String> tcIds = new HashSet<>();
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getId() != null) {
                        toolCallIdToAssistantIdx.put(tc.getId(), i);
                        tcIds.add(tc.getId());
                    }
                }
                assistantToToolCallIds.put(i, tcIds);
            }
        }

        // Check each assistant to see if all tool results exist
        Set<Integer> indicesToRemove = new HashSet<>();
        
        for (Map.Entry<Integer, Set<String>> entry : assistantToToolCallIds.entrySet()) {
            int assistantIdx = entry.getKey();
            Set<String> expectedToolCallIds = entry.getValue();
            Set<String> foundToolCallIds = new HashSet<>();
            
            // Look for tool results after this assistant
            for (int i = assistantIdx + 1; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                if ("tool".equals(msg.getRole())) {
                    String tcId = msg.getToolCallId();
                    if (tcId != null && expectedToolCallIds.contains(tcId)) {
                        foundToolCallIds.add(tcId);
                    }
                }
            }
            
            // If not all tool results are present, mark this assistant and its orphan tool results
            if (!foundToolCallIds.equals(expectedToolCallIds)) {
                logger.warn("Assistant at index {} has incomplete tool results. Expected: {}, Found: {}", 
                    assistantIdx, expectedToolCallIds, foundToolCallIds);
                indicesToRemove.add(assistantIdx);
                
                // Also remove any orphan tool results (partial results for this assistant)
                for (int i = assistantIdx + 1; i < messages.size(); i++) {
                    ChatMessage msg = messages.get(i);
                    if ("tool".equals(msg.getRole())) {
                        String tcId = msg.getToolCallId();
                        if (tcId != null && expectedToolCallIds.contains(tcId)) {
                            indicesToRemove.add(i);
                        }
                    }
                }
            }
        }

        // If nothing to remove, return original
        if (indicesToRemove.isEmpty()) {
            logger.info("No incomplete tool chains found, context is valid");
            return messages;
        }

        // Build cleaned message list
        List<ChatMessage> cleaned = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!indicesToRemove.contains(i)) {
                cleaned.add(messages.get(i));
            }
        }

        logger.info("Cleaned up {} messages with incomplete tool chains", indicesToRemove.size());

        // Final validation
        if (!validateToolChainIntegrity(cleaned)) {
            logger.error("CRITICAL: cleanupIncompleteToolChains still produced invalid chain, returning original");
            return messages;
        }

        return cleaned;
    }

    /**
     * Remove the last incomplete message pair (assistant + tool results).
     * This is a simpler cleanup for when we know the last message caused the error.
     * 
     * @param messages The message list to clean up
     * @return Cleaned message list with last incomplete pair removed
     */
    public List<ChatMessage> removeLastIncompletePair(List<ChatMessage> messages) {
        if (messages == null || messages.size() <= 1) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>(messages);
        
        // Check if last message is a tool result without its assistant
        ChatMessage lastMsg = result.get(result.size() - 1);
        if ("tool".equals(lastMsg.getRole())) {
            String tcId = lastMsg.getToolCallId();
            if (tcId != null) {
                // Find the assistant that made this tool call
                boolean foundAssistant = false;
                for (int i = result.size() - 2; i >= 0; i--) {
                    ChatMessage msg = result.get(i);
                    if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                        for (ToolCall tc : msg.getToolCalls()) {
                            if (tcId.equals(tc.getId())) {
                                foundAssistant = true;
                                break;
                            }
                        }
                        if (foundAssistant) break;
                    }
                }
                if (!foundAssistant) {
                    // Orphan tool result, remove it
                    result.remove(result.size() - 1);
                    logger.info("Removed orphan tool result with id: {}", tcId);
                }
            }
        }

        // Check if last assistant has incomplete tool calls
        lastMsg = result.get(result.size() - 1);
        if ("assistant".equals(lastMsg.getRole()) && lastMsg.hasToolCalls()) {
            Set<String> expectedIds = new HashSet<>();
            for (ToolCall tc : lastMsg.getToolCalls()) {
                if (tc.getId() != null) {
                    expectedIds.add(tc.getId());
                }
            }
            
            // This is the last message and it has tool calls but no tool results
            // Remove it as it's incomplete
            result.remove(result.size() - 1);
            logger.info("Removed last assistant message with incomplete tool calls: {}", expectedIds);
        }

        return result;
    }

    /**
     * Merge local tools with MCP tools
     * MCP tools take priority - they override local tools with the same name
     * This ensures system prompt descriptions match actual tool implementations
     * 
     * NOTE: Uses TreeMap for stable ordering, which is required for DeepSeek context caching.
     * DeepSeek caches the system message + tools definition, and the cache key includes
     * the exact JSON structure. If tools are in random order (HashMap), cache miss occurs.
     */
    public Map<String, com.github.obhen233.core.tool.Tool> mergeTools(
            Map<String, com.github.obhen233.core.tool.Tool> localTools,
            Map<String, com.github.obhen233.core.tool.Tool> mcpTools) {
        // Use TreeMap for stable ordering (required for DeepSeek context cache)
        Map<String, com.github.obhen233.core.tool.Tool> result = new TreeMap<>(localTools);
        if (mcpTools != null) {
            int addedCount = 0;
            int overriddenCount = 0;
            for (Map.Entry<String, com.github.obhen233.core.tool.Tool> entry : mcpTools.entrySet()) {
                String toolName = entry.getKey();
                if (result.containsKey(toolName)) {
                    overriddenCount++;
                    logger.debug("MCP tool '{}' overrides local tool", toolName);
                } else {
                    addedCount++;
                }
                result.put(toolName, entry.getValue());
            }
            if (addedCount > 0 || overriddenCount > 0) {
                logger.info("Merged tools: {} added, {} overridden by MCP", addedCount, overriddenCount);
            }
        }
        return result;
    }

    /**
     * Extract file extensions mentioned in user input for skill matching
     */
    public List<String> getInvolvedFiles(String query) {
        List<String> files = new ArrayList<>();
        Pattern pattern = Pattern.compile("(\\w+\\.\\w+)");
        Matcher matcher = pattern.matcher(query);
        while (matcher.find()) {
            String ext = matcher.group(1);
            if (ext.matches("\\w+\\.\\w+")) {
                files.add(matcher.group(1));
            }
        }
        return files;
    }

    /**
     * Extract the "path" field from a tool call's JSON arguments string.
     * Used to annotate completed operations with file paths in context compression.
     *
     * @param argumentsJson the JSON arguments string (e.g., {"path": "src/Main.java"})
     * @return the path value, or null if not found or on parse error
     */
    private String extractToolPath(String argumentsJson) {
        if (argumentsJson == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(argumentsJson);
            return root.has("path") ? root.get("path").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Estimate token count using jtokkit for precise counting.
     * Falls back to simple estimation if jtokkit is unavailable.
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return tokenCounter.count(text);
    }

    public int getMaxContextMessages() {
        return MAX_CONTEXT_MESSAGES;
    }
    
    /**
     * Calculate total token count for a list of messages.
     */
    public int calculateTotalTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage msg : messages) {
            // Count content
            if (msg.getContent() != null) {
                total += estimateTokens(msg.getContent());
            }
            // Count reasoning content
            if (msg.getReasoningContent() != null) {
                total += estimateTokens(msg.getReasoningContent());
            }
            // Count tool calls
            if (msg.hasToolCalls()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getName() != null) total += estimateTokens(tc.getName());
                    if (tc.getArguments() != null) total += estimateTokens(tc.getArguments());
                }
            }
        }
        return total;
    }
    
    /**
     * Check if context compression is needed based on token count.
     * Triggers when tokens exceed 70% of context window.
     * 
     * @param messages The message list to check
     * @return true if compression is needed
     */
    public boolean needsCompression(List<ChatMessage> messages) {
        if (runningTokenTotal < 0) {
            runningTokenTotal = calculateTotalTokens(messages);
        }
        int threshold = (int) (contextWindowSize * COMPRESSION_THRESHOLD);

        if (runningTokenTotal > threshold) {
            logger.info("Context compression needed: {} tokens > {} threshold ({}% of {})",
                runningTokenTotal, threshold, (int)(COMPRESSION_THRESHOLD * 100), contextWindowSize);
            return true;
        }
        return false;
    }

    /**
     * Invalidate the running token count so it is recalculated on the next check.
     * Should be called after messages are added, removed, or replaced.
     */
    public void invalidateTokenCount() {
        runningTokenTotal = -1;
    }
    
    /**
     * Compress context by replacing older messages with a structured summary.
     * Keeps: original task, completed operations, failure records, current progress.
     * Preserves only the most recent 5-8 rounds of raw interactions.
     * 
     * @param messages The message list to compress
     * @return Compressed message list
     */
    public List<ChatMessage> compressContext(List<ChatMessage> messages) {
        if (messages == null || messages.size() <= 2) {
            return messages;
        }
        
        int totalTokens = calculateTotalTokens(messages);
        int threshold = (int) (contextWindowSize * COMPRESSION_THRESHOLD);
        
        if (totalTokens <= threshold) {
            return messages;
        }
        
        logger.info("Compressing context: {} messages, {} tokens", messages.size(), totalTokens);
        compressionCount++;
        
        // Extract system message (always keep)
        ChatMessage systemMsg = messages.get(0);
        
        // Build maps for tool chain tracking
        Map<String, Integer> toolCallIdToAssistantIdx = new HashMap<>();
        Map<Integer, Set<Integer>> assistantToToolResults = new HashMap<>();
        
        for (int i = 1; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                Set<Integer> toolResultIndices = new HashSet<>();
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getId() != null) {
                        toolCallIdToAssistantIdx.put(tc.getId(), i);
                        for (int j = i + 1; j < messages.size(); j++) {
                            ChatMessage toolMsg = messages.get(j);
                            if ("tool".equals(toolMsg.getRole()) && tc.getId().equals(toolMsg.getToolCallId())) {
                                toolResultIndices.add(j);
                            }
                        }
                    }
                }
                assistantToToolResults.put(i, toolResultIndices);
            }
        }
        
        // Determine how many recent rounds to keep
        // Be more aggressive on subsequent compressions to prevent 0% reduction
        int effectiveMinKeep = compressionCount >= 2 ? 3 : MIN_KEEP_ROUNDS;
        int effectiveMaxKeep = compressionCount >= 2 ? 5 : MAX_KEEP_ROUNDS;
        int targetTokens = (int) (contextWindowSize * 0.4); // Target 40% after compression
        Set<Integer> indicesToKeep = new LinkedHashSet<>();
        int tokensKept = 0;
        int roundsKept = 0;
        
        // Walk backwards from the end
        for (int i = messages.size() - 1; i >= 1 && roundsKept < effectiveMaxKeep; i--) {
            ChatMessage msg = messages.get(i);
            
            // Skip tool results - they'll be included with their assistant
            if ("tool".equals(msg.getRole())) {
                String tcId = msg.getToolCallId();
                if (tcId != null && toolCallIdToAssistantIdx.containsKey(tcId)) {
                    indicesToKeep.add(i);
                }
                continue;
            }
            
            // For assistant with tool calls, include the complete unit
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                Set<Integer> toolResults = assistantToToolResults.get(i);
                if (toolResults != null && !toolResults.isEmpty()) {
                    indicesToKeep.add(i);
                    indicesToKeep.addAll(toolResults);
                    roundsKept++;
                } else {
                    // No tool results yet (pending in resume mode) — keep assistant as-is
                    indicesToKeep.add(i);
                    roundsKept++;
                }

                // Estimate tokens for this round
                tokensKept += estimateTokens(msg.getContent() != null ? msg.getContent() : "");
                if (msg.getReasoningContent() != null) {
                    tokensKept += estimateTokens(msg.getReasoningContent());
                }
            } else {
                // Simple message (user or assistant without tools)
                indicesToKeep.add(i);
                roundsKept++;
                tokensKept += estimateTokens(msg.getContent() != null ? msg.getContent() : "");
            }
            
            // Stop if we have enough rounds and tokens are reasonable
            if (roundsKept >= effectiveMinKeep && tokensKept >= targetTokens) {
                break;
            }
        }
        
        // Build summary of older messages
        StringBuilder summary = new StringBuilder();
        summary.append("[CONTEXT SUMMARY - ").append(compressionCount).append(" compression(s)]\n\n");
        
        // Extract key information from older messages
        String originalTask = null;
        List<String> completedOps = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        String currentProgress = null;
        
        for (int i = 1; i < messages.size(); i++) {
            if (indicesToKeep.contains(i)) continue;
            
            ChatMessage msg = messages.get(i);
            String role = msg.getRole();
            String content = msg.getContent() != null ? msg.getContent() : "";
            
            // Extract original task (first user message)
            if ("user".equals(role) && originalTask == null && !content.startsWith("[")) {
                originalTask = content.length() > 200 ? content.substring(0, 200) + "..." : content;
            }
            
            // Extract completed operations
            if ("tool".equals(role) && (content.contains("success") || content.contains("completed"))) {
                String toolName = "unknown";
                String toolPath = null;
                if (msg.getToolCallId() != null) {
                    // Find the tool name from the assistant that made this call
                    Integer assistantIdx = toolCallIdToAssistantIdx.get(msg.getToolCallId());
                    if (assistantIdx != null) {
                        ChatMessage assistantMsg = messages.get(assistantIdx);
                        if (assistantMsg.hasToolCalls()) {
                            for (ToolCall tc : assistantMsg.getToolCalls()) {
                                if (msg.getToolCallId().equals(tc.getId())) {
                                    toolName = tc.getName();
                                    toolPath = extractToolPath(tc.getArguments());
                                    break;
                                }
                            }
                        }
                    }
                }
                if (toolPath != null) {
                    completedOps.add(toolName + "(" + toolPath + ")");
                } else {
                    completedOps.add(toolName);
                }
            }
            
            // Extract failures
            if (content.contains("error") || content.contains("failed") || content.contains("Error")) {
                String errorSnippet = content.length() > 100 ? content.substring(0, 100) + "..." : content;
                if (failures.size() < 3) {  // Keep max 3 recent failures
                    failures.add(errorSnippet);
                }
            }
            
            // Track current progress (last assistant message before keep region)
            if ("assistant".equals(role) && !msg.hasToolCalls()) {
                currentProgress = content.length() > 150 ? content.substring(0, 150) + "..." : content;
            }
        }
        
        // Build structured summary
        summary.append("=== Original Task ===\n");
        summary.append(originalTask != null ? originalTask : "(not captured)\n");
        summary.append("\n=== Completed Operations ===\n");
        if (completedOps.isEmpty()) {
            summary.append("(none recorded)\n");
        } else {
            Set<String> uniqueOps = new LinkedHashSet<>(completedOps);
            int count = 0;
            for (String op : uniqueOps) {
                if (count++ >= 10) {
                    summary.append("... and ").append(uniqueOps.size() - 10).append(" more\n");
                    break;
                }
                summary.append("- ").append(op).append("\n");
            }
        }
        summary.append("\n=== Failures/Errors ===\n");
        if (failures.isEmpty()) {
            summary.append("(none)\n");
        } else {
            for (String f : failures) {
                summary.append("- ").append(f).append("\n");
            }
        }
        summary.append("\n=== Current Progress ===\n");
        summary.append(currentProgress != null ? currentProgress : "(in progress)\n");
        summary.append("\n[End of summary - recent interactions follow]\n");
        
        // Build the compressed message list
        List<ChatMessage> result = new ArrayList<>();
        result.add(systemMsg);
        
        // Add summary as a user message
        ChatMessage summaryMsg = new ChatMessage("user", summary.toString());
        result.add(summaryMsg);
        
        // Add kept messages in order, condensing oversized tool results
        List<Integer> sortedIndices = new ArrayList<>(indicesToKeep);
        Collections.sort(sortedIndices);
        for (int i : sortedIndices) {
            ChatMessage msg = messages.get(i);
            if ("tool".equals(msg.getRole()) &&
                msg.getContent() != null &&
                msg.getContent().length() > MAX_TOOL_RESULT_CHARS) {
                // Condense oversized tool results to prevent context bloat
                String content = msg.getContent();
                String condensed = content.substring(0, MAX_TOOL_RESULT_CHARS)
                    + "\n\n[TOOL RESULT CONDENSED: " + content.length()
                    + " total chars, showing first " + MAX_TOOL_RESULT_CHARS + "]\n";
                ChatMessage condensedMsg = new ChatMessage("tool", condensed);
                condensedMsg.setToolCallId(msg.getToolCallId());
                result.add(condensedMsg);
            } else {
                result.add(msg);
            }
        }
        
        // Validate tool chain integrity
        if (!validateToolChainIntegrity(result)) {
            logger.error("CRITICAL: compressContext produced invalid tool chain, returning original");
            return messages;
        }

        int newTokens = calculateTotalTokens(result);

        // Ensure compressed result fits within context window (trim oldest rounds if needed)
        // This is necessary because tokensKept underestimates actual tokens (tool results
        // are added to indicesToKeep but their token count is not tracked in the loop above).
        if (newTokens > contextWindowSize) {
            logger.warn("Compressed context still exceeds limit: {} > {}. Trimming oldest rounds...",
                newTokens, contextWindowSize);
            // Remove oldest non-essential rounds (keep system msg at 0, summary at 1, and recent rounds)
            // Walk forward from index 2 (after system+summary), removing the oldest round pair
            while (result.size() > 3 && calculateTotalTokens(result) > contextWindowSize) {
                ChatMessage removed = result.remove(2);
                logger.debug("Trimmed message from compressed context: role={}", removed.getRole());
                // If the next message is a tool result for the same removed assistant, remove it too
                if (result.size() > 2 && "tool".equals(result.get(2).getRole())) {
                    result.remove(2);
                }
            }
            newTokens = calculateTotalTokens(result);
            logger.info("Context trimmed to {} tokens ({} messages)", newTokens, result.size());
        }

        lastCompressedTokenCount = newTokens;

        logger.info("Context compressed: {} messages -> {} messages, {} tokens -> {} tokens ({}% reduction)",
            messages.size(), result.size(), totalTokens, newTokens,
            (100 - (newTokens * 100 / totalTokens)));

        // Invalidate running token count since content changed
        invalidateTokenCount();
        return result;
    }
    
    /**
     * Get compression statistics.
     */
    public int getCompressionCount() {
        return compressionCount;
    }
    
    public int getLastCompressedTokenCount() {
        return lastCompressedTokenCount;
    }
    
    public int getContextWindowSize() {
        return contextWindowSize;
    }

    /**
     * Build a structured markdown summary of a single run() execution.
     * Extracts: goal (user input), tool calls with key parameters, final result, duration, token usage.
     *
     * @param userInput the original user input for this run
     * @param messages  the messages exchanged during this run (including tool calls and results)
     * @param durationMs wall-clock duration of the run in milliseconds
     * @param promptTokens prompt token count
     * @param completionTokens completion token count
     * @return a structured markdown summary string
     */
    public String buildRunSummary(String userInput, List<ChatMessage> messages, long durationMs,
                                  long promptTokens, long completionTokens) {
        StringBuilder sb = new StringBuilder();

        // Extract goal (first user message that isn't a summary or system)
        String goal = userInput != null ? userInput : "";
        if (goal.length() > 150) {
            goal = goal.substring(0, 150) + "...";
        }

        sb.append("## Run ").append(determineRunNumber()).append("\n");
        sb.append("- **Goal**: ").append(escapeMarkdown(goal)).append("\n");

        if (durationMs > 0) {
            sb.append("- **Duration**: ").append(String.format("%.1f", durationMs / 1000.0)).append("s\n");
        }
        if (promptTokens > 0 || completionTokens > 0) {
            sb.append("- **Token**: input=").append(promptTokens).append(" / output=").append(completionTokens).append("\n");
        }

        // Extract tool calls (from assistant messages)
        Set<String> uniqueTools = new LinkedHashSet<>();
        String finalResult = null;
        for (ChatMessage msg : messages) {
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getName() != null) {
                        String paramSummary = extractToolParamSummary(tc);
                        uniqueTools.add(tc.getName() + (paramSummary != null ? "(" + paramSummary + ")" : ""));
                    }
                }
            }
            // Track final result (last assistant message without tool_calls)
            if ("assistant".equals(msg.getRole()) && !msg.hasToolCalls() && msg.getContent() != null) {
                String content = msg.getContent().trim();
                // Skip if it contains i18n token usage markers
                if (!content.contains("{{token_usage_summary")) {
                    finalResult = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                }
            }
        }

        if (!uniqueTools.isEmpty()) {
            sb.append("- **Tools**: ");
            int count = 0;
            for (String t : uniqueTools) {
                if (count++ > 0) sb.append(" → ");
                if (count > 8) { // Limit to 8 tool entries
                    sb.append("…(+").append(uniqueTools.size() - 8).append(" more)");
                    break;
                }
                sb.append(escapeMarkdown(t));
            }
            sb.append("\n");
        }

        if (finalResult != null && !finalResult.isEmpty()) {
            sb.append("- **Result**: ").append(escapeMarkdown(finalResult)).append("\n");
        }

        return sb.toString();
    }

    /**
     * Extract a short parameter summary from a tool call for display in the summary.
     * Only extracts key parameters like path, file, command.
     */
    private String extractToolParamSummary(ToolCall tc) {
        String args = tc.getArguments();
        if (args == null || args.isEmpty()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(args);
            // Try common parameter names for file paths / identifiers
            for (String key : new String[]{"path", "filePath", "file", "command", "name", "dir"}) {
                if (root.has(key)) {
                    String val = root.get(key).asText();
                    if (val.length() > 60) val = val.substring(0, 60) + "...";
                    return val;
                }
            }
        } catch (Exception e) {
            // Ignore parse errors
        }
        return null;
    }

    /**
     * Simple counter for run numbering in summaries.
     * Increments each time buildRunSummary is called during this agent session.
     * Reset externally when the agent is re-initialized.
     */
    private int summaryRunCounter = 0;

    private int determineRunNumber() {
        return ++summaryRunCounter;
    }

    /**
     * Merge a new run summary into the existing incremental summary.
     * If the merged result exceeds maxTokens, the oldest runs are trimmed.
     *
     * @param existingSummary the existing accumulated summary, or null/empty
     * @param newRunSummary   the summary for the latest run
     * @param maxTokens       maximum token limit for the merged summary
     * @return the merged summary string
     */
    public String mergeSummaries(String existingSummary, String newRunSummary, int maxTokens) {
        if (existingSummary == null || existingSummary.isEmpty()) {
            return newRunSummary;
        }

        String merged = existingSummary + "\n\n" + newRunSummary;
        int tokens = estimateTokens(merged);
        if (tokens <= maxTokens) {
            return merged;
        }

        // Trim oldest runs: split by "## Run", keep only the last N runs until under limit
        String[] runs = merged.split("(?=\\n## Run )");
        if (runs.length <= 1) {
            // Can't split further, truncate content
            return truncateToTokenLimit(merged, maxTokens);
        }

        // Walk backwards, collect newest runs until we're under limit
        StringBuilder trimmed = new StringBuilder();
        int keptTokens = 0;
        int keptCount = 0;
        for (int i = runs.length - 1; i >= 0; i--) {
            String run = runs[i];
            int runTokens = estimateTokens(run);
            if (keptTokens + runTokens <= maxTokens * 0.8 || keptCount == 0) {
                // Prepend (we're building in reverse)
                if (trimmed.length() > 0) {
                    trimmed.insert(0, "\n\n");
                }
                trimmed.insert(0, run);
                keptTokens += runTokens;
                keptCount++;
            } else {
                break;
            }
        }

        logger.info("Merged summary trimmed: {} runs kept ({} tokens) of {} total runs ({} tokens)",
            keptCount, keptTokens, runs.length, tokens);

        return trimmed.toString();
    }

    /**
     * Truncate text to approximately maxTokens by cutting from the end.
     * Fallback when run-based splitting is not possible.
     */
    private String truncateToTokenLimit(String text, int maxTokens) {
        int tokens = estimateTokens(text);
        if (tokens <= maxTokens) return text;

        // Binary search for the right length
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            String sub = text.substring(0, mid);
            if (estimateTokens(sub) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low) + "\n\n[truncated...]";
    }

    /**
     * Escape basic markdown special characters in summary text.
     */
    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_").replace("*", "\\*")
                   .replace("[", "\\[").replace("`", "\\`");
    }
}