package com.github.obhen233.core.database;

import com.github.obhen233.core.database.entity.FileSnapshotEntity;
import com.github.obhen233.core.database.entity.SnapshotEntity;
import com.github.obhen233.core.database.entity.SnapshotFileEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Data Access Object for snapshot operations (Git-like version control)
 */
public class SnapshotDao {
    private static final Logger logger = LoggerFactory.getLogger(SnapshotDao.class);

    private final SessionFactory sf;

    public SnapshotDao(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    // ==================== Snapshot Operations ====================

    public int createSnapshot(String taskId, String type, String description, Integer parentSnapshotId) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            SnapshotEntity entity = new SnapshotEntity();
            entity.setTaskId(taskId);
            entity.setSnapshotType(type);
            entity.setDescription(description);
            entity.setParentSnapshotId(parentSnapshotId);
            entity.setCreatedAt(Instant.now().toEpochMilli());
            session.persist(entity);
            session.getTransaction().commit();
            int snapshotId = entity.getId();
            logger.info("Created snapshot {} for task {}", snapshotId, taskId);
            return snapshotId;
        } catch (Exception e) {
            logger.error("Failed to create snapshot for task: {}", taskId, e);
            return -1;
        }
    }

    public List<Snapshot> findSnapshotsByTaskId(String taskId) {
        List<Snapshot> snapshots = new ArrayList<>();
        try (Session session = sf.openSession()) {
            List<SnapshotEntity> entities = session
                    .createQuery("FROM SnapshotEntity WHERE taskId = :taskId ORDER BY createdAt DESC", SnapshotEntity.class)
                    .setParameter("taskId", taskId)
                    .list();
            for (SnapshotEntity entity : entities) {
                snapshots.add(toSnapshot(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to find snapshots for task: {}", taskId, e);
        }
        return snapshots;
    }

    public Snapshot findSnapshotById(int id) {
        try (Session session = sf.openSession()) {
            SnapshotEntity entity = session.get(SnapshotEntity.class, id);
            if (entity != null) {
                return toSnapshot(entity);
            }
        } catch (Exception e) {
            logger.error("Failed to find snapshot: {}", id, e);
        }
        return null;
    }

    public int getLatestSnapshotId(String taskId) {
        try (Session session = sf.openSession()) {
            Integer result = session
                    .createQuery("SELECT e.id FROM SnapshotEntity e WHERE e.taskId = :taskId ORDER BY e.createdAt DESC", Integer.class)
                    .setParameter("taskId", taskId)
                    .setMaxResults(1)
                    .uniqueResult();
            return result != null ? result : -1;
        } catch (Exception e) {
            logger.error("Failed to get latest snapshot for task: {}", taskId, e);
            return -1;
        }
    }

    public void deleteSnapshot(int id) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();

            // Delete associated file links
            session.createQuery("DELETE FROM SnapshotFileEntity WHERE snapshotId = :id")
                    .setParameter("id", id)
                    .executeUpdate();

            // Delete file snapshots linked to this snapshot
            session.createQuery("DELETE FROM FileSnapshotEntity WHERE id IN " +
                    "(SELECT sfe.fileSnapshotId FROM SnapshotFileEntity sfe WHERE sfe.snapshotId = :id)")
                    .setParameter("id", id)
                    .executeUpdate();

            // Delete the snapshot
            session.createQuery("DELETE FROM SnapshotEntity WHERE id = :id")
                    .setParameter("id", id)
                    .executeUpdate();

            session.getTransaction().commit();
            logger.info("Deleted snapshot: {}", id);
        } catch (Exception e) {
            logger.error("Failed to delete snapshot: {}", id, e);
        }
    }

    // ==================== File Snapshot Operations ====================

    public int createFileSnapshot(FileSnapshot fileSnapshot) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            FileSnapshotEntity entity = new FileSnapshotEntity();
            entity.setTaskId(fileSnapshot.taskId);
            entity.setFilePath(fileSnapshot.filePath);
            entity.setOperation(fileSnapshot.operation);
            entity.setContentHash(fileSnapshot.contentHash);
            entity.setContentType(fileSnapshot.contentType);
            entity.setContent(fileSnapshot.content);
            entity.setBaseSnapshotId(fileSnapshot.baseSnapshotId);
            entity.setCreatedAt(fileSnapshot.createdAt);
            session.persist(entity);
            session.getTransaction().commit();
            return entity.getId();
        } catch (Exception e) {
            logger.error("Failed to create file snapshot: {}", fileSnapshot.filePath, e);
            return -1;
        }
    }

    public void linkFileSnapshot(int snapshotId, int fileSnapshotId) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            SnapshotFileEntity entity = new SnapshotFileEntity();
            entity.setSnapshotId(snapshotId);
            entity.setFileSnapshotId(fileSnapshotId);
            session.persist(entity);
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.error("Failed to link file snapshot to snapshot", e);
        }
    }

    public List<FileSnapshot> getSnapshotsFiles(String taskId, int snapshotId) {
        List<FileSnapshot> files = new ArrayList<>();
        try (Session session = sf.openSession()) {
            List<FileSnapshotEntity> entities = session
                    .createQuery("SELECT fs FROM FileSnapshotEntity fs " +
                            "JOIN SnapshotFileEntity sfe ON fs.id = sfe.fileSnapshotId " +
                            "WHERE sfe.snapshotId = :snapshotId AND fs.taskId = :taskId", FileSnapshotEntity.class)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("taskId", taskId)
                    .list();
            for (FileSnapshotEntity entity : entities) {
                files.add(toFileSnapshot(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to get files for snapshot: {}", snapshotId, e);
        }
        return files;
    }

    public List<FileSnapshot> getAllFileSnapshotsForTask(String taskId) {
        List<FileSnapshot> files = new ArrayList<>();
        try (Session session = sf.openSession()) {
            List<FileSnapshotEntity> entities = session
                    .createQuery("FROM FileSnapshotEntity WHERE taskId = :taskId ORDER BY createdAt DESC", FileSnapshotEntity.class)
                    .setParameter("taskId", taskId)
                    .list();
            for (FileSnapshotEntity entity : entities) {
                files.add(toFileSnapshot(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to get all file snapshots for task: {}", taskId, e);
        }
        return files;
    }

    public FileSnapshot findLatestFileSnapshot(String taskId, String filePath) {
        try (Session session = sf.openSession()) {
            FileSnapshotEntity entity = session
                    .createQuery("FROM FileSnapshotEntity WHERE taskId = :taskId AND filePath = :filePath ORDER BY createdAt DESC", FileSnapshotEntity.class)
                    .setParameter("taskId", taskId)
                    .setParameter("filePath", filePath)
                    .setMaxResults(1)
                    .uniqueResult();
            if (entity != null) {
                return toFileSnapshot(entity);
            }
        } catch (Exception e) {
            logger.error("Failed to find latest file snapshot: {}", filePath, e);
        }
        return null;
    }

    public FileSnapshot findFileSnapshotById(int id) {
        try (Session session = sf.openSession()) {
            FileSnapshotEntity entity = session.get(FileSnapshotEntity.class, id);
            if (entity != null) {
                return toFileSnapshot(entity);
            }
        } catch (Exception e) {
            logger.error("Failed to find file snapshot: {}", id, e);
        }
        return null;
    }

    public String resolveContent(FileSnapshot fileSnapshot) {
        if (fileSnapshot == null) return "";
        String content = fileSnapshot.content != null ? new String(fileSnapshot.content, StandardCharsets.UTF_8) : "";
        if (!"delta".equals(fileSnapshot.contentType) || fileSnapshot.baseSnapshotId == null) {
            return content;
        }

        FileSnapshot base = findFileSnapshotById(fileSnapshot.baseSnapshotId);
        String baseContent = resolveContent(base);
        return applyDelta(baseContent, content);
    }

    public FileSnapshot findFileSnapshotByHash(String taskId, String contentHash) {
        try (Session session = sf.openSession()) {
            FileSnapshotEntity entity = session
                    .createQuery("FROM FileSnapshotEntity WHERE taskId = :taskId AND contentHash = :contentHash", FileSnapshotEntity.class)
                    .setParameter("taskId", taskId)
                    .setParameter("contentHash", contentHash)
                    .setMaxResults(1)
                    .uniqueResult();
            if (entity != null) {
                return toFileSnapshot(entity);
            }
        } catch (Exception e) {
            logger.error("Failed to find file snapshot by hash", e);
        }
        return null;
    }

    // ==================== Entity Conversion (Hibernate path) ====================

    private Snapshot toSnapshot(SnapshotEntity entity) {
        Snapshot snapshot = new Snapshot();
        snapshot.id = entity.getId();
        snapshot.taskId = entity.getTaskId();
        snapshot.snapshotType = entity.getSnapshotType();
        snapshot.description = entity.getDescription();
        snapshot.parentSnapshotId = entity.getParentSnapshotId();
        snapshot.createdAt = entity.getCreatedAt();
        return snapshot;
    }

    private FileSnapshot toFileSnapshot(FileSnapshotEntity entity) {
        FileSnapshot file = new FileSnapshot();
        file.id = entity.getId();
        file.taskId = entity.getTaskId();
        file.filePath = entity.getFilePath();
        file.operation = entity.getOperation();
        file.contentHash = entity.getContentHash();
        file.contentType = entity.getContentType();
        file.content = entity.getContent();
        file.baseSnapshotId = entity.getBaseSnapshotId();
        file.createdAt = entity.getCreatedAt();
        return file;
    }

    // ==================== Static Utility Methods ====================

    public static String createDelta(String baseContent, String newContent) {
        if (baseContent == null) baseContent = "";
        if (newContent == null) newContent = "";

        int prefix = 0;
        int maxPrefix = Math.min(baseContent.length(), newContent.length());
        while (prefix < maxPrefix && baseContent.charAt(prefix) == newContent.charAt(prefix)) {
            prefix++;
        }

        int suffix = 0;
        int maxSuffix = Math.min(baseContent.length() - prefix, newContent.length() - prefix);
        while (suffix < maxSuffix
                && baseContent.charAt(baseContent.length() - 1 - suffix) == newContent.charAt(newContent.length() - 1 - suffix)) {
            suffix++;
        }

        String middle = newContent.substring(prefix, newContent.length() - suffix);
        String encoded = Base64.getEncoder().encodeToString(middle.getBytes(StandardCharsets.UTF_8));
        String delta = "slice:" + prefix + ":" + suffix + ":" + encoded;
        String literal = "literal:" + Base64.getEncoder().encodeToString(newContent.getBytes(StandardCharsets.UTF_8));
        return delta.length() < literal.length() ? delta : literal;
    }

    public static String applyDelta(String baseContent, String deltaContent) {
        if (baseContent == null) baseContent = "";
        if (deltaContent == null) return "";
        if (deltaContent.startsWith("slice:")) {
            try {
                String[] parts = deltaContent.split(":", 4);
                int prefix = Integer.parseInt(parts[1]);
                int suffix = Integer.parseInt(parts[2]);
                String middle = new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8);
                String prefixText = baseContent.substring(0, Math.min(prefix, baseContent.length()));
                String suffixText = suffix > 0 && suffix <= baseContent.length()
                    ? baseContent.substring(baseContent.length() - suffix)
                    : "";
                return prefixText + middle + suffixText;
            } catch (Exception e) {
                return deltaContent;
            }
        }
        if (deltaContent.startsWith("literal:")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(deltaContent.substring("literal:".length()));
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return deltaContent;
            }
        }
        return deltaContent;
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

    // ==================== Data Classes ====================

    public static class Snapshot {
        public int id;
        public String taskId;
        public String snapshotType;  // AUTO, MANUAL, CHECKPOINT
        public String description;
        public Integer parentSnapshotId;
        public long createdAt;
    }

    public static class FileSnapshot {
        public int id;
        public String taskId;
        public String filePath;
        public String operation;  // CREATE, MODIFY, DELETE
        public String contentHash;
        public String contentType;  // full, delta
        public byte[] content;
        public Integer baseSnapshotId;
        public long createdAt;

        public static FileSnapshot create(String taskId, String filePath, String operation,
                                        String content, Integer baseSnapshotId) {
            return create(taskId, filePath, operation, content, baseSnapshotId, null);
        }

        public static FileSnapshot create(String taskId, String filePath, String operation,
                                        String content, Integer baseSnapshotId, String storedContent) {
            FileSnapshot snapshot = new FileSnapshot();
            snapshot.taskId = taskId;
            snapshot.filePath = filePath;
            snapshot.operation = operation;
            snapshot.contentHash = hash(content);
            snapshot.contentType = baseSnapshotId != null ? "delta" : "full";
            String persistedContent = storedContent != null ? storedContent : content;
            snapshot.content = persistedContent != null ? persistedContent.getBytes(StandardCharsets.UTF_8) : null;
            snapshot.baseSnapshotId = baseSnapshotId;
            snapshot.createdAt = Instant.now().toEpochMilli();
            return snapshot;
        }
    }
}
