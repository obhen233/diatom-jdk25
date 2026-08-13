package com.github.obhen233.core.database;

import com.github.obhen233.core.database.entity.ChangeLogEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for change log operations
 */
public class ChangeLogDao {
    private static final Logger logger = LoggerFactory.getLogger(ChangeLogDao.class);

    private final SessionFactory sf;

    public ChangeLogDao(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    public void insert(ChangeLog log) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            ChangeLogEntity entity = toEntity(log);
            session.persist(entity);
            session.getTransaction().commit();
            logger.debug("Inserted change log: {} {} {}", log.taskId, log.operation, log.filePath);
        } catch (Exception e) {
            logger.error("Failed to insert change log", e);
        }
    }

    public List<ChangeLog> findByTaskId(String taskId) {
        try (Session session = sf.openSession()) {
            List<ChangeLogEntity> entities = session.createQuery(
                    "FROM ChangeLogEntity WHERE taskId = :taskId ORDER BY createdAt DESC", ChangeLogEntity.class)
                    .setParameter("taskId", taskId)
                    .list();
            List<ChangeLog> results = new ArrayList<>(entities.size());
            for (ChangeLogEntity entity : entities) {
                results.add(toChangeLog(entity));
            }
            return results;
        } catch (Exception e) {
            logger.error("Failed to find change logs for task: {}", taskId, e);
            return new ArrayList<>();
        }
    }

    public List<ChangeLog> findByTaskIdAndStep(String taskId, int stepNumber) {
        try (Session session = sf.openSession()) {
            List<ChangeLogEntity> entities = session.createQuery(
                    "FROM ChangeLogEntity WHERE taskId = :taskId AND stepNumber = :stepNumber ORDER BY createdAt", ChangeLogEntity.class)
                    .setParameter("taskId", taskId)
                    .setParameter("stepNumber", stepNumber)
                    .list();
            List<ChangeLog> results = new ArrayList<>(entities.size());
            for (ChangeLogEntity entity : entities) {
                results.add(toChangeLog(entity));
            }
            return results;
        } catch (Exception e) {
            logger.error("Failed to find change logs for task: {} step: {}", taskId, stepNumber, e);
            return new ArrayList<>();
        }
    }

    public List<ChangeLog> findByTaskIdAndStatus(String taskId, String status) {
        try (Session session = sf.openSession()) {
            List<ChangeLogEntity> entities = session.createQuery(
                    "FROM ChangeLogEntity WHERE taskId = :taskId AND status = :status ORDER BY createdAt", ChangeLogEntity.class)
                    .setParameter("taskId", taskId)
                    .setParameter("status", status)
                    .list();
            List<ChangeLog> results = new ArrayList<>(entities.size());
            for (ChangeLogEntity entity : entities) {
                results.add(toChangeLog(entity));
            }
            return results;
        } catch (Exception e) {
            logger.error("Failed to find change logs for task: {} status: {}", taskId, status, e);
            return new ArrayList<>();
        }
    }

    public void updateStatus(long id, String status, String errorMessage) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            org.hibernate.query.Query query = session.createQuery(
                    "UPDATE ChangeLogEntity SET status = :status, errorMessage = :errorMessage WHERE id = :id");
            query.setParameter("status", status);
            query.setParameter("errorMessage", errorMessage);
            query.setParameter("id", id);
            query.executeUpdate();
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.error("Failed to update change log status: {}", id, e);
        }
    }

    public void deleteByTaskId(String taskId) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            org.hibernate.query.Query query = session.createQuery(
                    "DELETE FROM ChangeLogEntity WHERE taskId = :taskId");
            query.setParameter("taskId", taskId);
            query.executeUpdate();
            session.getTransaction().commit();
            logger.info("Deleted change logs for task: {}", taskId);
        } catch (Exception e) {
            logger.error("Failed to delete change logs for task: {}", taskId, e);
        }
    }

    // ==================== Conversion Methods ====================

    private ChangeLogEntity toEntity(ChangeLog log) {
        ChangeLogEntity entity = new ChangeLogEntity();
        entity.setId(DiatomIdGenerator.idOrNull(log.id));
        entity.setTaskId(log.taskId);
        entity.setStepNumber(log.stepNumber);
        entity.setSnapshotId(log.snapshotId);
        entity.setToolName(log.toolName);
        entity.setFilePath(log.filePath);
        entity.setOperation(log.operation);
        entity.setContentHash(log.contentHash);
        entity.setSummary(log.summary);
        entity.setStatus(log.status);
        entity.setErrorMessage(log.errorMessage);
        entity.setCreatedAt(log.createdAt);
        return entity;
    }

    private ChangeLog toChangeLog(ChangeLogEntity entity) {
        ChangeLog log = new ChangeLog();
        log.id = entity.getId();
        log.taskId = entity.getTaskId();
        log.stepNumber = entity.getStepNumber();
        log.snapshotId = entity.getSnapshotId();
        log.toolName = entity.getToolName();
        log.filePath = entity.getFilePath();
        log.operation = entity.getOperation();
        log.contentHash = entity.getContentHash();
        log.summary = entity.getSummary();
        log.status = entity.getStatus();
        log.errorMessage = entity.getErrorMessage();
        log.createdAt = entity.getCreatedAt();
        return log;
    }

    public static String hash(String content) {
        if (content == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(content.hashCode());
        }
    }

    // ==================== Data Class ====================

    public static class ChangeLog {
        public long id;
        public String taskId;
        public Integer stepNumber;
        public Integer snapshotId;
        public String toolName;
        public String filePath;
        public String operation;
        public String contentHash;
        public String summary;
        public String status;
        public String errorMessage;
        public long createdAt;

        public static ChangeLog create(String taskId, Integer stepNumber, String toolName,
                                      String filePath, String operation, String content, String summary, String status) {
            ChangeLog log = new ChangeLog();
            log.taskId = taskId;
            log.stepNumber = stepNumber;
            log.toolName = toolName;
            log.filePath = filePath;
            log.operation = operation;
            log.contentHash = hash(content);
            log.summary = summary;
            log.status = status;
            log.createdAt = Instant.now().toEpochMilli();
            return log;
        }
    }
}
