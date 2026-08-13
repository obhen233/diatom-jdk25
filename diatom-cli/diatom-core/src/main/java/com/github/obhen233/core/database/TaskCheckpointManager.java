package com.github.obhen233.core.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.database.entity.TaskCheckpointEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.github.obhen233.util.JsonUtils;

/**
 * Manages task checkpoint persistence for resumable tasks.
 * Allows saving mid-task progress and resuming later.
 */
public class TaskCheckpointManager {
    private static final Logger logger = LoggerFactory.getLogger(TaskCheckpointManager.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final SessionFactory sf;

    public TaskCheckpointManager(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    /**
     * Save a task checkpoint
     * Each call inserts a new record. Since task_id is a UUID generated per run(),
     * there is no conflict between different runs.
     */
    public void saveCheckpoint(String taskId, String userInput, String agentState,
                               List<String> conversationHistory, List<String> toolResults, int stepCount) {
        saveCheckpoint(taskId, userInput, agentState, conversationHistory, toolResults, stepCount,
                       null, null, null, null, 0, 0);
    }

    /**
     * Save a task checkpoint with enhanced fields
     */
    public void saveCheckpoint(String taskId, String userInput, String agentState,
                               List<String> conversationHistory, List<String> toolResults, int stepCount,
                               String llmSummary, byte[] compressedContext, String fileChangeSummary,
                               String toolResultHashes, int messageCount, int tokenUsage) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            TaskCheckpointEntity entity = new TaskCheckpointEntity();
            entity.setTaskId(taskId);
            entity.setUserInput(userInput);
            entity.setAgentState(agentState);
            entity.setConversationHistory(serialize(conversationHistory));
            entity.setToolResults(serialize(toolResults));
            entity.setStepCount(stepCount);
            entity.setLlmSummary(llmSummary);
            entity.setCompressedContext(compressedContext);
            entity.setFileChangeSummary(fileChangeSummary);
            entity.setToolResultHashes(toolResultHashes);
            entity.setMessageCount(messageCount);
            entity.setTokenUsage(tokenUsage);
            long now = Instant.now().toEpochMilli();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            session.persist(entity);
            session.getTransaction().commit();
            logger.info("Saved checkpoint for task: {} at step {} (msg={}, tokens={})",
                       taskId, stepCount, messageCount, tokenUsage);
        } catch (Exception e) {
            logger.warn("Failed to save checkpoint", e);
        }
    }

    /**
     * Load a task checkpoint
     */
    public TaskCheckpoint loadCheckpoint(String taskId) {
        try (Session session = sf.openSession()) {
            Query<TaskCheckpointEntity> query = session.createQuery(
                "FROM TaskCheckpointEntity WHERE taskId = :taskId ORDER BY updatedAt DESC", TaskCheckpointEntity.class);
            query.setParameter("taskId", taskId);
            query.setMaxResults(1);
            TaskCheckpointEntity entity = query.uniqueResult();
            if (entity != null) {
                return toTaskCheckpoint(entity);
            }
        } catch (Exception e) {
            logger.warn("Failed to load checkpoint", e);
        }
        return null;
    }

    /**
     * Load the latest checkpoint for a task
     */
    public TaskCheckpoint loadLatestCheckpoint(String taskId) {
        try (Session session = sf.openSession()) {
            Query<TaskCheckpointEntity> query = session.createQuery(
                "FROM TaskCheckpointEntity WHERE taskId = :taskId ORDER BY stepCount DESC", TaskCheckpointEntity.class);
            query.setParameter("taskId", taskId);
            query.setMaxResults(1);
            TaskCheckpointEntity entity = query.uniqueResult();
            if (entity != null) {
                return toTaskCheckpoint(entity);
            }
        } catch (Exception e) {
            logger.warn("Failed to load latest checkpoint", e);
        }
        return null;
    }

    private TaskCheckpoint toTaskCheckpoint(TaskCheckpointEntity entity) {
        return new TaskCheckpoint(
            entity.getTaskId(),
            entity.getUserInput(),
            entity.getAgentState(),
            deserializeStringList(entity.getConversationHistory()),
            deserializeStringList(entity.getToolResults()),
            entity.getStepCount() != null ? entity.getStepCount() : 0,
            entity.getLlmSummary(),
            entity.getCompressedContext(),
            entity.getFileChangeSummary(),
            entity.getToolResultHashes(),
            entity.getMessageCount() != null ? entity.getMessageCount() : 0,
            entity.getTokenUsage() != null ? entity.getTokenUsage() : 0,
            entity.getCreatedAt() != null ? entity.getCreatedAt() : 0L,
            entity.getUpdatedAt() != null ? entity.getUpdatedAt() : 0L
        );
    }

    /**
     * Delete a checkpoint
     */
    public void deleteCheckpoint(String taskId) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query query = session.createQuery("DELETE FROM TaskCheckpointEntity WHERE taskId = :taskId");
            query.setParameter("taskId", taskId);
            query.executeUpdate();
            session.getTransaction().commit();
            logger.info("Deleted checkpoint for task: {}", taskId);
        } catch (Exception e) {
            logger.warn("Failed to delete checkpoint", e);
        }
    }

    /**
     * List all saved checkpoints
     */
    public java.util.List<TaskCheckpoint> listCheckpoints() {
        java.util.List<TaskCheckpoint> checkpoints = new java.util.ArrayList<>();
        try (Session session = sf.openSession()) {
            List<TaskCheckpointEntity> entities = session.createQuery(
                "FROM TaskCheckpointEntity ORDER BY updatedAt DESC", TaskCheckpointEntity.class)
                .list();
            for (TaskCheckpointEntity entity : entities) {
                checkpoints.add(toTaskCheckpoint(entity));
            }
        } catch (Exception e) {
            logger.warn("Failed to list checkpoints", e);
        }
        return checkpoints;
    }

    /**
     * Clean up old checkpoints (older than specified days)
     */
    public void cleanupOldCheckpoints(int daysOld) {
        long cutoffTime = Instant.now().toEpochMilli() - (daysOld * 24L * 60 * 60 * 1000);
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query query = session.createQuery("DELETE FROM TaskCheckpointEntity WHERE updatedAt < :cutoff");
            query.setParameter("cutoff", cutoffTime);
            int deleted = query.executeUpdate();
            session.getTransaction().commit();
            if (deleted > 0) {
                logger.info("Cleaned up {} old checkpoints", deleted);
            }
        } catch (Exception e) {
            logger.warn("Failed to cleanup checkpoints", e);
        }
    }

    /**
     * Find checkpoints by user input (partial match, case-insensitive)
     */
    public List<TaskCheckpoint> findCheckpointsByInput(String query) {
        List<TaskCheckpoint> results = new java.util.ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return results;
        }
        String trimmedQuery = query.trim();
        try (Session session = sf.openSession()) {
            Query<TaskCheckpointEntity> hqlQuery = session.createQuery(
                "FROM TaskCheckpointEntity WHERE userInput LIKE :input ORDER BY updatedAt DESC", TaskCheckpointEntity.class);
            hqlQuery.setParameter("input", "%" + trimmedQuery + "%");
            List<TaskCheckpointEntity> entities = hqlQuery.list();
            for (TaskCheckpointEntity entity : entities) {
                results.add(toTaskCheckpoint(entity));
            }
        } catch (Exception e) {
            logger.warn("Failed to find checkpoints by input", e);
        }
        return results;
    }

    /**
     * Update agent state for an existing checkpoint
     */
    public void updateAgentState(String taskId, String agentState, List<String> conversationHistory,
                                 List<String> toolResults, int stepCount) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query query = session.createQuery(
                "UPDATE TaskCheckpointEntity SET agentState = :agentState, conversationHistory = :conversationHistory, " +
                "toolResults = :toolResults, stepCount = :stepCount, updatedAt = :updatedAt WHERE taskId = :taskId");
            query.setParameter("agentState", agentState);
            query.setParameter("conversationHistory", serialize(conversationHistory));
            query.setParameter("toolResults", serialize(toolResults));
            query.setParameter("stepCount", stepCount);
            query.setParameter("updatedAt", Instant.now().toEpochMilli());
            query.setParameter("taskId", taskId);
            query.executeUpdate();
            session.getTransaction().commit();
            logger.info("Updated checkpoint for task: {}", taskId);
        } catch (Exception e) {
            logger.warn("Failed to update checkpoint", e);
        }
    }

    private String serialize(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize", e);
            return "[]";
        }
    }

    private List<String> deserializeStringList(String json) {
        if (json == null) return new ArrayList<>();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            logger.warn("Failed to deserialize string list", e);
            return new ArrayList<>();
        }
    }

    /**
     * Task checkpoint data holder (enhanced with file_change_summary, compressed_context, etc.)
     */
    public static class TaskCheckpoint {
        private final String taskId;
        private final String userInput;
        private final String agentState;
        private final List<String> conversationHistory;
        private final List<String> toolResults;
        private final int stepCount;
        // Enhanced fields
        private final String llmSummary;
        private final byte[] compressedContext;
        private final String fileChangeSummary;
        private final String toolResultHashes;
        private final int messageCount;
        private final int tokenUsage;
        private final long createdAt;
        private final long updatedAt;

        public TaskCheckpoint(String taskId, String userInput, String agentState,
                             List<String> conversationHistory, List<String> toolResults,
                             int stepCount, long createdAt, long updatedAt) {
            this(taskId, userInput, agentState, conversationHistory, toolResults, stepCount,
                 null, null, null, null, 0, 0, createdAt, updatedAt);
        }

        public TaskCheckpoint(String taskId, String userInput, String agentState,
                             List<String> conversationHistory, List<String> toolResults,
                             int stepCount, String llmSummary, byte[] compressedContext,
                             String fileChangeSummary, String toolResultHashes,
                             int messageCount, int tokenUsage, long createdAt, long updatedAt) {
            this.taskId = taskId;
            this.userInput = userInput;
            this.agentState = agentState;
            this.conversationHistory = conversationHistory;
            this.toolResults = toolResults;
            this.stepCount = stepCount;
            this.llmSummary = llmSummary;
            this.compressedContext = compressedContext;
            this.fileChangeSummary = fileChangeSummary;
            this.toolResultHashes = toolResultHashes;
            this.messageCount = messageCount;
            this.tokenUsage = tokenUsage;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public String getTaskId() { return taskId; }
        public String getUserInput() { return userInput; }
        public String getAgentState() { return agentState; }
        public List<String> getConversationHistory() { return conversationHistory; }
        public List<String> getToolResults() { return toolResults; }
        public int getStepCount() { return stepCount; }
        public String getLlmSummary() { return llmSummary; }
        public byte[] getCompressedContext() { return compressedContext; }
        public String getFileChangeSummary() { return fileChangeSummary; }
        public String getToolResultHashes() { return toolResultHashes; }
        public int getMessageCount() { return messageCount; }
        public int getTokenUsage() { return tokenUsage; }
        public long getCreatedAt() { return createdAt; }
        public long getUpdatedAt() { return updatedAt; }

        public String getSummary() {
            String input = userInput != null && userInput.length() > 50
                ? userInput.substring(0, 50) + "..."
                : userInput;
            return String.format("Task: %s | Input: %s | Step: %d | Msgs: %d | Tokens: %d | Updated: %s",
                taskId, input, stepCount, messageCount, tokenUsage,
                java.time.Instant.ofEpochMilli(updatedAt).toString());
        }
    }
}
