package com.github.obhen233.core.database;

import com.github.obhen233.core.database.entity.CommandExecutionLogEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for command execution log operations
 */
public class CommandExecutionLogDao {
    private static final Logger logger = LoggerFactory.getLogger(CommandExecutionLogDao.class);

    private final SessionFactory sf;

    public CommandExecutionLogDao(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    /**
     * Insert a new execution log entry
     */
    public void insertExecutionLog(CommandExecutionLog log) {
        Session session = null;
        Transaction tx = null;
        try {
            session = sf.openSession();
            tx = session.beginTransaction();
            session.persist(toEntity(log));
            tx.commit();
            logger.debug("Inserted execution log: {}", log.command);
        } catch (Exception e) {
            if (tx != null) {
                try { tx.rollback(); } catch (Exception ignored) {}
            }
            logger.error("Failed to insert execution log: {}", log.command, e);
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Find logs by command name
     */
    public List<CommandExecutionLog> findByCommand(String command) {
        List<CommandExecutionLog> results = new ArrayList<>();
        Session session = null;
        try {
            session = sf.openSession();
            Query<CommandExecutionLogEntity> query = session.createQuery(
                "FROM CommandExecutionLogEntity WHERE command = :command ORDER BY timestamp DESC",
                CommandExecutionLogEntity.class);
            query.setParameter("command", command);
            List<CommandExecutionLogEntity> entities = query.list();
            for (CommandExecutionLogEntity entity : entities) {
                results.add(toLog(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to find execution logs by command: {}", command, e);
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
        return results;
    }

    /**
     * Find logs within a time range
     */
    public List<CommandExecutionLog> findByTimeRange(long startTime, long endTime) {
        List<CommandExecutionLog> results = new ArrayList<>();
        Session session = null;
        try {
            session = sf.openSession();
            Query<CommandExecutionLogEntity> query = session.createQuery(
                "FROM CommandExecutionLogEntity WHERE timestamp >= :start AND timestamp <= :end ORDER BY timestamp DESC",
                CommandExecutionLogEntity.class);
            query.setParameter("start", startTime);
            query.setParameter("end", endTime);
            List<CommandExecutionLogEntity> entities = query.list();
            for (CommandExecutionLogEntity entity : entities) {
                results.add(toLog(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to find execution logs by time range", e);
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
        return results;
    }

    /**
     * Find recent logs
     */
    public List<CommandExecutionLog> findRecent(int limit) {
        List<CommandExecutionLog> results = new ArrayList<>();
        Session session = null;
        try {
            session = sf.openSession();
            Query<CommandExecutionLogEntity> query = session.createQuery(
                "FROM CommandExecutionLogEntity ORDER BY timestamp DESC",
                CommandExecutionLogEntity.class);
            query.setMaxResults(limit);
            List<CommandExecutionLogEntity> entities = query.list();
            for (CommandExecutionLogEntity entity : entities) {
                results.add(toLog(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to find recent execution logs", e);
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
        return results;
    }

    /**
     * Find logs with user feedback
     */
    public List<CommandExecutionLog> findWithFeedback() {
        List<CommandExecutionLog> results = new ArrayList<>();
        Session session = null;
        try {
            session = sf.openSession();
            Query<CommandExecutionLogEntity> query = session.createQuery(
                "FROM CommandExecutionLogEntity WHERE userFeedback IS NOT NULL AND userFeedback != '' ORDER BY timestamp DESC",
                CommandExecutionLogEntity.class);
            List<CommandExecutionLogEntity> entities = query.list();
            for (CommandExecutionLogEntity entity : entities) {
                results.add(toLog(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to find execution logs with feedback", e);
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
        return results;
    }

    /**
     * Find logs by result (success/failure)
     */
    public List<CommandExecutionLog> findByResult(String result) {
        List<CommandExecutionLog> results = new ArrayList<>();
        Session session = null;
        try {
            session = sf.openSession();
            Query<CommandExecutionLogEntity> query = session.createQuery(
                "FROM CommandExecutionLogEntity WHERE result = :result ORDER BY timestamp DESC",
                CommandExecutionLogEntity.class);
            query.setParameter("result", result);
            List<CommandExecutionLogEntity> entities = query.list();
            for (CommandExecutionLogEntity entity : entities) {
                results.add(toLog(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to find execution logs by result: {}", result, e);
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
        return results;
    }

    /**
     * Update user feedback for a log entry
     */
    public void updateUserFeedback(long id, String feedback) {
        Session session = null;
        Transaction tx = null;
        try {
            session = sf.openSession();
            tx = session.beginTransaction();
            CommandExecutionLogEntity entity = session.get(CommandExecutionLogEntity.class, (int) id);
            if (entity != null) {
                entity.setUserFeedback(feedback);
                session.merge(entity);
                logger.debug("Updated user feedback for log id: {}", id);
            }
            tx.commit();
        } catch (Exception e) {
            if (tx != null) {
                try { tx.rollback(); } catch (Exception ignored) {}
            }
            logger.error("Failed to update user feedback for log id: {}", id, e);
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Delete logs older than specified timestamp
     */
    public void deleteOlderThan(long timestamp) {
        Session session = null;
        Transaction tx = null;
        try {
            session = sf.openSession();
            tx = session.beginTransaction();
            Query<?> query = session.createQuery(
                "DELETE FROM CommandExecutionLogEntity WHERE timestamp < :ts");
            query.setParameter("ts", timestamp);
            int deleted = query.executeUpdate();
            tx.commit();
            logger.info("Deleted {} execution logs older than {}", deleted, timestamp);
        } catch (Exception e) {
            if (tx != null) {
                try { tx.rollback(); } catch (Exception ignored) {}
            }
            logger.error("Failed to delete old execution logs", e);
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Get count of all logs
     */
    public int getCount() {
        Session session = null;
        try {
            session = sf.openSession();
            Query<Number> query = session.createQuery(
                "SELECT COUNT(*) FROM CommandExecutionLogEntity", Number.class);
            Number count = query.uniqueResult();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.error("Failed to get execution log count", e);
            return 0;
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Get count of logs by command
     */
    public int getCountByCommand(String command) {
        Session session = null;
        try {
            session = sf.openSession();
            Query<Number> query = session.createQuery(
                "SELECT COUNT(*) FROM CommandExecutionLogEntity WHERE command = :command", Number.class);
            query.setParameter("command", command);
            Number count = query.uniqueResult();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.error("Failed to get count by command: {}", command, e);
            return 0;
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    private CommandExecutionLogEntity toEntity(CommandExecutionLog log) {
        CommandExecutionLogEntity entity = new CommandExecutionLogEntity();
        entity.setCommand(log.command);
        entity.setArgs(log.args);
        entity.setToolType(log.toolType);
        entity.setResult(log.result);
        entity.setRiskAssessed(log.riskAssessed);
        entity.setUserFeedback(log.userFeedback);
        entity.setTimestamp(log.timestamp);
        entity.setPermission(log.permission);
        entity.setRiskLevel(log.riskLevel);
        entity.setReasoning(log.reasoning);
        entity.setClassificationMethod(log.classificationMethod);
        entity.setDurationMs(log.durationMs);
        entity.setStatus(log.status);
        return entity;
    }

    private CommandExecutionLog toLog(CommandExecutionLogEntity entity) {
        CommandExecutionLog log = new CommandExecutionLog();
        log.id = entity.getId() != null ? entity.getId() : 0;
        log.command = entity.getCommand();
        log.args = entity.getArgs();
        log.toolType = entity.getToolType();
        log.result = entity.getResult();
        log.riskAssessed = entity.getRiskAssessed() != null ? entity.getRiskAssessed() : 0;
        log.userFeedback = entity.getUserFeedback();
        log.timestamp = entity.getTimestamp();
        log.permission = entity.getPermission();
        log.riskLevel = entity.getRiskLevel() != null ? entity.getRiskLevel() : 0;
        log.reasoning = entity.getReasoning();
        log.classificationMethod = entity.getClassificationMethod();
        log.durationMs = entity.getDurationMs() != null ? entity.getDurationMs() : 0L;
        log.status = entity.getStatus();
        return log;
    }

    /**
     * Command Execution Log data class
     */
    public static class CommandExecutionLog {
        public int id;
        public String command;
        public String args;
        public String toolType;
        public String result;     // success, failure
        public int riskAssessed;  // risk level assessed by agent
        public String userFeedback;
        public Long timestamp;

        // Learning-specific fields
        public String permission;            // ALLOW, DENY, UNSURE
        public int riskLevel;              // 0-3: safe, caution, dangerous, highly dangerous
        public String reasoning;           // explanation of classification
        public String classificationMethod; // "llm", "builtin", "learned"
        public long durationMs;            // how long classification took
        public String status;              // "success", "failed"

        public CommandExecutionLog() {}

        public CommandExecutionLog(String command, String args, String toolType, String result, int riskAssessed) {
            this.command = command;
            this.args = args;
            this.toolType = toolType;
            this.result = result;
            this.riskAssessed = riskAssessed;
        }
    }

    /**
     * Insert a learning log entry (LLM classification result)
     */
    public void insertLearningLog(CommandExecutionLog log) {
        Session session = null;
        Transaction tx = null;
        try {
            session = sf.openSession();
            tx = session.beginTransaction();
            session.persist(toEntity(log));
            tx.commit();
            logger.debug("Inserted learning log: {} (status={})", log.command, log.status);
        } catch (Exception e) {
            if (tx != null) {
                try { tx.rollback(); } catch (Exception ignored) {}
            }
            logger.error("Failed to insert learning log: {}", log.command, e);
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }
}
