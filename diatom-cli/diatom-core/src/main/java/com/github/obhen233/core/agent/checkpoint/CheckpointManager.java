package com.github.obhen233.core.agent.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.database.TaskCheckpointManager;
import com.github.obhen233.core.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import com.github.obhen233.util.JsonUtils;

public class CheckpointManager {
    private static final Logger logger = LoggerFactory.getLogger(CheckpointManager.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final TaskCheckpointManager checkpointManager;
    private String currentTaskId;

    // Enhanced checkpoint data (from SessionTracker)
    private String currentLlmSummary;
    private byte[] currentCompressedContext;
    private String currentFileChangeSummary;
    private String currentToolResultHashes;
    private int currentMessageCount;
    private int currentTokenUsage;

    public CheckpointManager(TaskCheckpointManager checkpointManager) {
        this.checkpointManager = checkpointManager;
    }

    /**
     * Generate a unique task ID
     */
    public String generateTaskId(String userInput) {
        this.currentTaskId = "task_" + UUID.randomUUID().toString();
        return currentTaskId;
    }

    /**
     * Get current task ID
     */
    public String getCurrentTaskId() {
        return currentTaskId;
    }

    /**
     * Set enhanced checkpoint data from SessionTracker
     */
    public void setCheckpointData(String llmSummary, byte[] compressedContext,
                                  String fileChangeSummary, String toolResultHashes,
                                  int messageCount, int tokenUsage) {
        this.currentLlmSummary = llmSummary;
        this.currentCompressedContext = compressedContext;
        this.currentFileChangeSummary = fileChangeSummary;
        this.currentToolResultHashes = toolResultHashes;
        this.currentMessageCount = messageCount;
        this.currentTokenUsage = tokenUsage;
    }

    /**
     * Clear enhanced checkpoint data
     */
    public void clearCheckpointData() {
        this.currentLlmSummary = null;
        this.currentCompressedContext = null;
        this.currentFileChangeSummary = null;
        this.currentToolResultHashes = null;
        this.currentMessageCount = 0;
        this.currentTokenUsage = 0;
    }

    /**
     * Get current LLM summary (incremental summary across runs).
     * Used by ReActAgent to restore summary on resume.
     */
    public String getCurrentLlmSummary() {
        return currentLlmSummary;
    }

    /**
     * Save current checkpoint for potential resume
     * @param userInput The original user input
     * @param messages The conversation messages
     * @param stepCount Current step count
     * @param agentStateData Agent state data to serialize (autoApproveWrite, approvedCommands, etc.)
     */
    public void saveCheckpoint(String userInput, List<ChatMessage> messages, int stepCount,
                               Map<String, Object> agentStateData) {
        if (checkpointManager == null || currentTaskId == null) {
            return;
        }

        try {
            // Extract conversation history (preserve all messages in order for proper restoration)
            // We keep ALL messages including system message for proper context restoration
            List<String> convHistory = new ArrayList<>();

            for (ChatMessage msg : messages) {
                String msgJson = msgToJson(msg);
                convHistory.add(msgJson);
            }

            // Serialize agent state
            String agentState = serializeAgentState(agentStateData);

            // Use enhanced save if we have the data
            if (currentLlmSummary != null || currentCompressedContext != null ||
                currentFileChangeSummary != null || currentToolResultHashes != null) {
                checkpointManager.saveCheckpoint(
                    currentTaskId,
                    userInput,
                    agentState,
                    convHistory,
                    new ArrayList<>(),
                    stepCount,
                    currentLlmSummary,
                    currentCompressedContext,
                    currentFileChangeSummary,
                    currentToolResultHashes,
                    currentMessageCount,
                    currentTokenUsage
                );
            } else {
                checkpointManager.saveCheckpoint(
                    currentTaskId,
                    userInput,
                    agentState,
                    convHistory,
                    new ArrayList<>(),
                    stepCount
                );
            }
            logger.info("Saved checkpoint for task: {} at step {} (msg={}, tokens={})",
                       currentTaskId, stepCount, currentMessageCount, currentTokenUsage);
        } catch (Exception e) {
            logger.warn("Failed to save checkpoint", e);
        }
    }

    /**
     * Save current checkpoint with default (empty) agent state
     */
    public void saveCheckpoint(String userInput, List<ChatMessage> messages, int stepCount) {
        saveCheckpoint(userInput, messages, stepCount, null);
    }

    private String msgToJson(ChatMessage msg) {
        try {
            return mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String serializeAgentState(Map<String, Object> agentStateData) {
        try {
            Map<String, Object> state = new HashMap<>();
            if (agentStateData != null) {
                state.putAll(agentStateData);
            }
            return mapper.writeValueAsString(state);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Resume agent from a saved checkpoint
     * @param taskId The task ID to resume
     * @param history Output parameter for conversation history
     * @param agentState Output for agent state data
     * @return true if checkpoint was found and loaded, false otherwise
     */
    public boolean resumeFromCheckpoint(String taskId, List<ChatMessage> history,
                                        Map<String, Object> agentState) {
        if (checkpointManager == null) {
            logger.warn("No checkpoint manager configured, cannot resume");
            return false;
        }

        TaskCheckpointManager.TaskCheckpoint checkpoint = checkpointManager.loadCheckpoint(taskId);
        if (checkpoint == null) {
            logger.warn("Checkpoint not found for task: {}", taskId);
            return false;
        }

        try {
            // Restore conversation history (all messages in order)
            history.clear();
            if (checkpoint.getConversationHistory() != null) {
                for (String msgJson : checkpoint.getConversationHistory()) {
                    ChatMessage msg = mapper.readValue(msgJson, ChatMessage.class);
                    history.add(msg);
                }
            }

            // Also restore tool results if they exist (for backwards compatibility)
            if (checkpoint.getToolResults() != null && !checkpoint.getToolResults().isEmpty()) {
                for (String msgJson : checkpoint.getToolResults()) {
                    ChatMessage msg = mapper.readValue(msgJson, ChatMessage.class);
                    history.add(msg);
                }
                logger.info("Restored {} tool results from checkpoint", checkpoint.getToolResults().size());
            }

            // Restore agent state
            if (checkpoint.getAgentState() != null && !checkpoint.getAgentState().isEmpty()) {
                Map<String, Object> state = mapper.readValue(checkpoint.getAgentState(), Map.class);
                agentState.putAll(state);
            }

            // Restore LLM summary (incremental summary across runs)
            if (checkpoint.getLlmSummary() != null) {
                this.currentLlmSummary = checkpoint.getLlmSummary();
                logger.info("Restored LLM summary from checkpoint ({} chars)", currentLlmSummary.length());
            }

            // Set current task ID
            currentTaskId = taskId;

            logger.info("Resumed from checkpoint: {} (step {}, {} messages restored)",
                taskId, checkpoint.getStepCount(), history.size());
            return true;
        } catch (Exception e) {
            logger.error("Failed to restore checkpoint", e);
            history.clear();
            return false;
        }
    }
}