package com.github.obhen233.core.database;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.obhen233.core.database.entity.CommandHistoryEntity;
import com.github.obhen233.core.database.entity.InputHistoryEntity;
import com.github.obhen233.core.database.entity.TaskCheckpointEntity;
import com.github.obhen233.util.PathUtils;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.github.obhen233.util.JsonUtils;

/**
 * Manages command history persistence with SQLite.
 * Supports loading history at startup and navigating with up/down arrows.
 * Enhanced with token usage, response time, and tool call audit fields.
 */
public class HistoryManager {
    private static final Logger logger = LoggerFactory.getLogger(HistoryManager.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final String sessionId;
    private final String workspace;
    private final int maxHistorySize;
    private final SessionFactory sf;

    /**
     * Command record with full audit details
     */
    public static class CommandRecord {
        public int id;
        public String inputText;
        public long timestamp;
        public String sessionId;
        public String workspace;
        public String taskId;
        public int tokenCount;
        public int responseTokenCount;
        public String modelName;
        public long durationMs;
        public String toolCalls;

        public String getToolCalls() { return toolCalls; }
        public String getModelName() { return modelName; }
        public int getTokenCount() { return tokenCount; }
        public int getResponseTokenCount() { return responseTokenCount; }
        public long getDurationMs() { return durationMs; }
    }

    /**
     * Session summary statistics
     */
    public static class SessionStats {
        public int totalCommands;
        public long totalTokens;
        public long totalResponseTokens;
        public long avgDurationMs;
        public long totalDurationMs;
        public String mostUsedModel;
        public int totalToolCalls;
    }

    public HistoryManager(DatabaseManager db) {
        this(db, 100);
    }

    public HistoryManager(DatabaseManager db, int maxHistorySize) {
        this(db, maxHistorySize, PathUtils.getWorkingDir());
    }

    public HistoryManager(DatabaseManager db, int maxHistorySize, String workspace) {
        this.sessionId = generateSessionId();
        this.workspace = workspace != null ? workspace : PathUtils.getWorkingDir();
        this.maxHistorySize = maxHistorySize;
        this.sf = db.getSessionFactory();
    }

    /**
     * Generate a unique session ID using UUID
     */
    private String generateSessionId() {
        return "session_" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Save a command to history with associated task_id
     */
    public void saveCommand(String input, String taskId) {
        saveCommand(input, taskId, 0, 0, null, 0, null, null);
    }

    /**
     * Save a command to history with associated task_id and project name
     */
    public void saveCommand(String input, String taskId, String projectName) {
        saveCommand(input, taskId, 0, 0, null, 0, null, projectName);
    }

    /**
     * Save a command to history with full audit details
     */
    public void saveCommand(String input, String taskId, int tokenCount, int responseTokens,
                           String modelName, long durationMs, List<String> toolCallsList) {
        saveCommand(input, taskId, tokenCount, responseTokens, modelName, durationMs, toolCallsList, null);
    }

    /**
     * Save a command to history with full audit details and project name.
     * When projectName is provided, looks up workspace_id from workspace_context table
     * to maintain referential consistency.
     */
    public void saveCommand(String input, String taskId, int tokenCount, int responseTokens,
                           String modelName, long durationMs, List<String> toolCallsList,
                           String projectName) {
        if (input == null || input.trim().isEmpty()) {
            return;
        }

        // Hibernate: save input history
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            InputHistoryEntity inputEntity = new InputHistoryEntity();
            inputEntity.setTaskId(taskId);
            inputEntity.setSessionId(sessionId);
            inputEntity.setInputType("USER");
            inputEntity.setContent(input);
            inputEntity.setTokenCount(tokenCount);
            inputEntity.setCreatedAt(Instant.now().toEpochMilli());
            session.persist(inputEntity);
            session.getTransaction().commit();
        }

        // Don't save duplicate consecutive commands
        try {
            List<String> recent = getRecentCommands(1);
            if (!recent.isEmpty() && recent.get(0).equals(input)) return;
        } catch (Exception e) {
            // ignore
        }

        String toolCallsJson = null;
        if (toolCallsList != null && !toolCallsList.isEmpty()) {
            try {
                toolCallsJson = mapper.writeValueAsString(toolCallsList);
            } catch (Exception e) {
                logger.warn("Failed to serialize tool calls", e);
            }
        }

        // Hibernate: save command history
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            CommandHistoryEntity entity = new CommandHistoryEntity();
            entity.setInputText(input);
            entity.setTimestamp(Instant.now().toEpochMilli());
            entity.setSessionId(sessionId);
            entity.setWorkspace(workspace);
            entity.setTaskId(taskId);
            entity.setTokenCount(tokenCount);
            entity.setResponseTokenCount(responseTokens);
            entity.setModelName(modelName);
            entity.setDurationMs(durationMs);
            entity.setToolCalls(toolCallsJson);
            entity.setProjectName(projectName);
            session.persist(entity);
            session.getTransaction().commit();
        }

        cleanupOldRecords();
    }

    private void saveInputHistory(String input, String taskId, String inputType, int tokenCount) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            InputHistoryEntity entity = new InputHistoryEntity();
            entity.setTaskId(taskId);
            entity.setSessionId(sessionId);
            entity.setInputType(inputType);
            entity.setContent(input);
            entity.setTokenCount(tokenCount);
            entity.setCreatedAt(Instant.now().toEpochMilli());
            session.persist(entity);
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.warn("Failed to save input history", e);
        }
    }

    /**
     * Update audit fields for an existing command
     */
    public void updateCommandAudit(int id, int tokenCount, int responseTokens,
                                   String modelName, long durationMs, List<String> toolCallsList) {
        String toolCallsJson = null;
        if (toolCallsList != null && !toolCallsList.isEmpty()) {
            try {
                toolCallsJson = mapper.writeValueAsString(toolCallsList);
            } catch (Exception e) {
                logger.warn("Failed to serialize tool calls", e);
            }
        }

        try (Session session = sf.openSession()) {
            session.beginTransaction();
            CommandHistoryEntity entity = session.get(CommandHistoryEntity.class, (long) id);
            if (entity != null) {
                entity.setTokenCount(tokenCount);
                entity.setResponseTokenCount(responseTokens);
                entity.setModelName(modelName);
                entity.setDurationMs(durationMs);
                entity.setToolCalls(toolCallsJson);
                session.persist(entity);
            }
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.warn("Failed to update command audit", e);
        }
    }

    /**
     * Get session statistics
     */
    public SessionStats getSessionStats() {
        SessionStats stats = new SessionStats();

        try (Session session = sf.openSession()) {
            // Main aggregate stats
            Query<Object[]> query = session.createQuery(
                "SELECT COUNT(*), COALESCE(SUM(tokenCount), 0), COALESCE(SUM(responseTokenCount), 0), " +
                "COALESCE(SUM(durationMs), 0), COALESCE(AVG(durationMs), 0) " +
                "FROM CommandHistoryEntity WHERE sessionId = :sid", Object[].class);
            query.setParameter("sid", sessionId);
            Object[] row = query.uniqueResult();
            if (row != null) {
                stats.totalCommands = ((Number) row[0]).intValue();
                stats.totalTokens = ((Number) row[1]).longValue();
                stats.totalResponseTokens = ((Number) row[2]).longValue();
                stats.totalDurationMs = ((Number) row[3]).longValue();
                stats.avgDurationMs = ((Number) row[4]).longValue();
            }

            // Most used model
            Query<Object[]> modelQuery = session.createQuery(
                "SELECT modelName, COUNT(*) as cnt FROM CommandHistoryEntity " +
                "WHERE sessionId = :sid AND modelName IS NOT NULL " +
                "GROUP BY modelName ORDER BY cnt DESC", Object[].class);
            modelQuery.setParameter("sid", sessionId);
            modelQuery.setMaxResults(1);
            Object[] modelRow = modelQuery.uniqueResult();
            if (modelRow != null) {
                stats.mostUsedModel = (String) modelRow[0];
            }
        } catch (Exception e) {
            logger.warn("Failed to get session stats", e);
        }

        return stats;
    }

    /**
     * Get command records with stats
     */
    public List<CommandRecord> getRecentCommandsWithStats(int limit) {
        List<CommandRecord> records = new ArrayList<>();

        try (Session session = sf.openSession()) {
            List<CommandHistoryEntity> results = session.createQuery(
                "FROM CommandHistoryEntity WHERE workspace = :ws ORDER BY timestamp DESC",
                CommandHistoryEntity.class)
                .setParameter("ws", workspace)
                .setMaxResults(limit)
                .list();
            for (CommandHistoryEntity e : results) {
                CommandRecord rec = new CommandRecord();
                rec.id = e.getId().intValue();
                rec.inputText = e.getInputText();
                rec.timestamp = e.getTimestamp();
                rec.sessionId = e.getSessionId();
                rec.workspace = e.getWorkspace();
                rec.taskId = e.getTaskId();
                rec.tokenCount = e.getTokenCount() != null ? e.getTokenCount() : 0;
                rec.responseTokenCount = e.getResponseTokenCount() != null ? e.getResponseTokenCount() : 0;
                rec.modelName = e.getModelName();
                rec.durationMs = e.getDurationMs() != null ? e.getDurationMs() : 0;
                rec.toolCalls = e.getToolCalls();
                records.add(rec);
            }
        } catch (Exception e) {
            logger.warn("Failed to load recent commands with stats", e);
        }

        return records;
    }

    /**
     * Export history to a file
     */
    public void exportHistory(String filePath, int limit) {
        List<CommandRecord> records = getRecentCommandsWithStats(limit);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("# Command History Export\n");
            writer.write("# Generated: " + Instant.now().toString() + "\n");
            writer.write("# Session: " + sessionId + "\n\n");

            SessionStats stats = getSessionStats();
            writer.write("# Statistics:\n");
            writer.write("# Total commands: " + stats.totalCommands + "\n");
            writer.write("# Total tokens: " + stats.totalTokens + "\n");
            writer.write("# Total response tokens: " + stats.totalResponseTokens + "\n");
            writer.write("# Avg duration: " + (stats.avgDurationMs / 1000.0) + "s\n");
            writer.write("\n# Commands:\n\n");

            for (CommandRecord rec : records) {
                writer.write("## " + java.time.Instant.ofEpochMilli(rec.timestamp).toString() + "\n");
                writer.write("Input: " + rec.inputText + "\n");
                if (rec.modelName != null) {
                    writer.write("Model: " + rec.modelName + "\n");
                }
                if (rec.tokenCount > 0) {
                    writer.write("Tokens: " + rec.tokenCount + " (request), " + rec.responseTokenCount + " (response)\n");
                }
                if (rec.durationMs > 0) {
                    writer.write("Duration: " + (rec.durationMs / 1000.0) + "s\n");
                }
                writer.write("\n");
            }

            logger.info("Exported {} commands to {}", records.size(), filePath);
        } catch (Exception e) {
            logger.warn("Failed to export history", e);
        }
    }

    /**
     * Save a command to history without task_id (for backward compatibility)
     */
    public void saveCommand(String input) {
        saveCommand(input, null);
    }

    /**
     * Get recent commands for history navigation
     */
    public List<String> getRecentCommands(int limit) {
        return getRecentCommands(limit, null);
    }

    /**
     * Get recent commands for history navigation, optionally filtered by project name.
     * When projectName is provided, only commands for that project are returned.
     * Results are ordered by timestamp descending (newest first).
     */
    public List<String> getRecentCommands(int limit, String projectName) {
        List<String> commands = new ArrayList<>();

        try (Session session = sf.openSession()) {
            List<CommandHistoryEntity> results;
            if (projectName != null && !projectName.isEmpty()) {
                results = session.createQuery(
                    "FROM CommandHistoryEntity WHERE workspace = :ws AND projectName = :pn ORDER BY timestamp DESC",
                    CommandHistoryEntity.class)
                    .setParameter("ws", workspace)
                    .setParameter("pn", projectName)
                    .setMaxResults(limit)
                    .list();
            } else {
                results = session.createQuery(
                    "FROM CommandHistoryEntity WHERE workspace = :ws ORDER BY timestamp DESC",
                    CommandHistoryEntity.class)
                    .setParameter("ws", workspace)
                    .setMaxResults(limit)
                    .list();
            }
            for (CommandHistoryEntity e : results) {
                commands.add(e.getInputText());
            }
        } catch (Exception e) {
            logger.warn("Failed to load recent commands", e);
        }

        return commands;
    }

    /**
     * Search command history
     */
    public List<String> searchCommands(String query, int limit) {
        List<String> commands = new ArrayList<>();

        try (Session session = sf.openSession()) {
            List<CommandHistoryEntity> results = session.createQuery(
                "FROM CommandHistoryEntity WHERE workspace = :ws AND inputText LIKE :q ORDER BY timestamp DESC",
                CommandHistoryEntity.class)
                .setParameter("ws", workspace)
                .setParameter("q", "%" + query + "%")
                .setMaxResults(limit)
                .list();
            for (CommandHistoryEntity e : results) {
                commands.add(e.getInputText());
            }
        } catch (Exception e) {
            logger.warn("Failed to search commands", e);
        }

        return commands;
    }

    /**
     * Clear current session history
     */
    public void clearSessionHistory() {
        // First delete associated checkpoints
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            List<String> taskIds = session.createQuery(
                "SELECT DISTINCT taskId FROM CommandHistoryEntity WHERE sessionId = :sid AND taskId IS NOT NULL",
                String.class)
                .setParameter("sid", sessionId)
                .list();
            for (String taskId : taskIds) {
                if (taskId != null) {
                    deleteCheckpointsByTaskId(taskId);
                }
            }
            session.createQuery("DELETE FROM CommandHistoryEntity WHERE sessionId = :sid")
                .setParameter("sid", sessionId)
                .executeUpdate();
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.warn("Failed to clear session history", e);
        }
    }

    /**
     * Clear all history for current workspace
     */
    public void clearAllHistory() {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            List<String> taskIds = session.createQuery(
                "SELECT DISTINCT taskId FROM CommandHistoryEntity WHERE workspace = :ws AND taskId IS NOT NULL",
                String.class)
                .setParameter("ws", workspace)
                .list();
            for (String taskId : taskIds) {
                if (taskId != null) {
                    deleteCheckpointsByTaskId(taskId);
                }
            }
            session.createQuery("DELETE FROM CommandHistoryEntity WHERE workspace = :ws")
                .setParameter("ws", workspace)
                .executeUpdate();
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.warn("Failed to clear all history", e);
        }
    }

    /**
     * Delete checkpoints by task_id
     */
    private void deleteCheckpointsByTaskId(String taskId) {
        if (taskId == null) return;

        try (Session session = sf.openSession()) {
            session.beginTransaction();
            int deleted = session.createQuery("DELETE FROM TaskCheckpointEntity WHERE taskId = :tid")
                .setParameter("tid", taskId)
                .executeUpdate();
            if (deleted > 0) {
                logger.info("Deleted {} checkpoints for task_id: {}", deleted, taskId);
            }
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.warn("Failed to delete checkpoints for task_id: " + taskId, e);
        }
    }

    /**
     * Get history size for current workspace
     */
    public int getHistorySize() {
        try (Session session = sf.openSession()) {
            Long count = session.createQuery(
                "SELECT COUNT(*) FROM CommandHistoryEntity WHERE workspace = :ws", Long.class)
                .setParameter("ws", workspace)
                .uniqueResult();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.warn("Failed to get history size", e);
        }
        return 0;
    }

    /**
     * Cleanup old records beyond max size, delete associated checkpoints
     */
    public void cleanupOldRecords() {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            // Get IDs to keep
            List<Long> keepIds = session.createQuery(
                "SELECT id FROM CommandHistoryEntity WHERE workspace = :ws ORDER BY timestamp DESC", Long.class)
                .setParameter("ws", workspace)
                .setMaxResults(maxHistorySize)
                .list();

            if (!keepIds.isEmpty()) {
                // Get task IDs that will be deleted
                List<String> oldTaskIds = session.createQuery(
                    "SELECT DISTINCT taskId FROM CommandHistoryEntity WHERE workspace = :ws AND taskId IS NOT NULL AND id NOT IN (:ids)",
                    String.class)
                    .setParameter("ws", workspace)
                    .setParameter("ids", keepIds)
                    .list();
                for (String tid : oldTaskIds) {
                    if (tid != null) {
                        deleteCheckpointsByTaskId(tid);
                    }
                }

                // Delete old history records
                int deleted = session.createQuery(
                    "DELETE FROM CommandHistoryEntity WHERE workspace = :ws AND id NOT IN (:ids)")
                    .setParameter("ws", workspace)
                    .setParameter("ids", keepIds)
                    .executeUpdate();
                if (deleted > 0) {
                    logger.info("Cleaned up {} old history records", deleted);
                }
            }
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.warn("Failed to cleanup old records", e);
        }
    }

    /**
     * Get checkpoints for a specific task_id (for resume)
     */
    public List<TaskCheckpointManager.TaskCheckpoint> getCheckpointsByTaskId(String taskId) {
        List<TaskCheckpointManager.TaskCheckpoint> checkpoints = new ArrayList<>();

        try (Session session = sf.openSession()) {
            List<TaskCheckpointEntity> results = session.createQuery(
                "FROM TaskCheckpointEntity WHERE taskId = :tid ORDER BY stepCount ASC",
                TaskCheckpointEntity.class)
                .setParameter("tid", taskId)
                .list();
            for (TaskCheckpointEntity e : results) {
                checkpoints.add(new TaskCheckpointManager.TaskCheckpoint(
                    e.getTaskId(),
                    e.getUserInput(),
                    e.getAgentState(),
                    deserializeStringList(e.getConversationHistory()),
                    deserializeStringList(e.getToolResults()),
                    e.getStepCount() != null ? e.getStepCount() : 0,
                    e.getLlmSummary(),
                    e.getCompressedContext(),
                    e.getFileChangeSummary(),
                    e.getToolResultHashes(),
                    e.getMessageCount() != null ? e.getMessageCount() : 0,
                    e.getTokenUsage() != null ? e.getTokenUsage() : 0,
                    e.getCreatedAt(),
                    e.getUpdatedAt()
                ));
            }
        } catch (Exception e) {
            logger.warn("Failed to get checkpoints for task_id: " + taskId, e);
        }

        return checkpoints;
    }

    private List<String> deserializeStringList(String json) {
        if (json == null) return new ArrayList<>();
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            logger.warn("Failed to deserialize string list", e);
            return new ArrayList<>();
        }
    }
}
