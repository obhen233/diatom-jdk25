package com.github.obhen233.core.database;

import com.github.obhen233.core.database.entity.SourceCodeExtensionEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for source_code_extensions table.
 * Handles CRUD operations for configurable source code file extensions.
 */
public class SourceCodeExtensionsDao {
    private static final Logger logger = LoggerFactory.getLogger(SourceCodeExtensionsDao.class);
    private final SessionFactory sf;

    public SourceCodeExtensionsDao(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    /**
     * Source code extension entity
     */
    public static class SourceCodeExtension {
        public long id;
        public String extension;
        public boolean enabled;
        public String source;
        public long createdAt;
        public long updatedAt;

        public SourceCodeExtension() {}

        public SourceCodeExtension(String extension, String source) {
            this.extension = extension;
            this.enabled = true;
            this.source = source;
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = System.currentTimeMillis();
        }
    }

    /**
     * Find all extensions ordered by extension name
     */
    public List<SourceCodeExtension> findAll() {
        try (Session session = sf.openSession()) {
            List<SourceCodeExtensionEntity> entities = session.createQuery(
                    "FROM SourceCodeExtensionEntity ORDER BY extension", SourceCodeExtensionEntity.class)
                    .list();
            List<SourceCodeExtension> results = new ArrayList<>(entities.size());
            for (SourceCodeExtensionEntity entity : entities) {
                results.add(toSourceCodeExtension(entity));
            }
            return results;
        } catch (Exception e) {
            logger.error("Failed to find all extensions", e);
            return new ArrayList<>();
        }
    }

    /**
     * Find all enabled extensions
     */
    public List<SourceCodeExtension> findEnabled() {
        try (Session session = sf.openSession()) {
            List<SourceCodeExtensionEntity> entities = session.createQuery(
                    "FROM SourceCodeExtensionEntity WHERE enabled = true ORDER BY extension", SourceCodeExtensionEntity.class)
                    .list();
            List<SourceCodeExtension> results = new ArrayList<>(entities.size());
            for (SourceCodeExtensionEntity entity : entities) {
                results.add(toSourceCodeExtension(entity));
            }
            return results;
        } catch (Exception e) {
            logger.error("Failed to find enabled extensions", e);
            return new ArrayList<>();
        }
    }

    /**
     * Insert a new extension
     */
    public void insert(String extension, String source) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            SourceCodeExtensionEntity entity = new SourceCodeExtensionEntity();
            entity.setExtension(extension);
            entity.setEnabled(true);
            entity.setSource(source);
            long now = System.currentTimeMillis();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            session.persist(entity);
            session.getTransaction().commit();
            logger.debug("Inserted extension: {} ({})", extension, source);
        } catch (Exception e) {
            logger.error("Failed to insert extension: {}", extension, e);
        }
    }

    /**
     * Delete extension
     */
    public void delete(String extension) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query query = session.createQuery("DELETE FROM SourceCodeExtensionEntity WHERE extension = :ext");
            query.setParameter("ext", extension);
            query.executeUpdate();
            session.getTransaction().commit();
            logger.debug("Deleted extension: {}", extension);
        } catch (Exception e) {
            logger.error("Failed to delete extension: {}", extension, e);
        }
    }

    /**
     * Delete all extensions by source
     */
    public int deleteBySource(String source) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query query = session.createQuery("DELETE FROM SourceCodeExtensionEntity WHERE source = :src");
            query.setParameter("src", source);
            int affected = query.executeUpdate();
            session.getTransaction().commit();
            logger.info("Deleted {} extensions with source: {}", affected, source);
            return affected;
        } catch (Exception e) {
            logger.error("Failed to delete extensions by source: {}", source, e);
            return 0;
        }
    }

    /**
     * Delete all non-built-in extensions
     */
    public int deleteNonBuiltin() {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query query = session.createQuery("DELETE FROM SourceCodeExtensionEntity WHERE source != :builtin");
            query.setParameter("builtin", "built-in");
            int affected = query.executeUpdate();
            session.getTransaction().commit();
            logger.info("Deleted {} non-built-in extensions", affected);
            return affected;
        } catch (Exception e) {
            logger.error("Failed to delete non-built-in extensions", e);
            return 0;
        }
    }

    /**
     * Update enabled status
     */
    public void updateEnabled(long id, boolean enabled) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query query = session.createQuery("UPDATE SourceCodeExtensionEntity SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id");
            query.setParameter("enabled", enabled);
            query.setParameter("updatedAt", System.currentTimeMillis());
            query.setParameter("id", id);
            query.executeUpdate();
            session.getTransaction().commit();
            logger.debug("Updated extension enabled: {} -> {}", id, enabled);
        } catch (Exception e) {
            logger.error("Failed to update extension enabled: {}", id, e);
        }
    }

    /**
     * Insert extension if not exists (based on UNIQUE constraint)
     */
    public boolean insertIfNotExists(String extension, String source) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            SourceCodeExtensionEntity entity = new SourceCodeExtensionEntity();
            entity.setExtension(extension);
            entity.setEnabled(true);
            entity.setSource(source);
            long now = System.currentTimeMillis();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            session.persist(entity);
            session.getTransaction().commit();
            return true;
        } catch (Exception e) {
            // Unique constraint violation or other error
            return false;
        }
    }

    /**
     * Check if extension exists
     */
    public boolean exists(String extension) {
        try (Session session = sf.openSession()) {
            Long count = session.createQuery(
                    "SELECT COUNT(*) FROM SourceCodeExtensionEntity WHERE extension = :ext", Long.class)
                    .setParameter("ext", extension)
                    .uniqueResult();
            return count != null && count > 0;
        } catch (Exception e) {
            logger.error("Failed to check extension exists: {}", extension, e);
            return false;
        }
    }

    /**
     * Get count
     */
    public int getCount() {
        try (Session session = sf.openSession()) {
            Long count = session.createQuery(
                    "SELECT COUNT(*) FROM SourceCodeExtensionEntity", Long.class)
                    .uniqueResult();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.error("Failed to get extension count", e);
            return 0;
        }
    }

    private SourceCodeExtension toSourceCodeExtension(SourceCodeExtensionEntity entity) {
        SourceCodeExtension ext = new SourceCodeExtension();
        ext.id = entity.getId();
        ext.extension = entity.getExtension();
        ext.enabled = entity.getEnabled();
        ext.source = entity.getSource();
        ext.createdAt = entity.getCreatedAt();
        ext.updatedAt = entity.getUpdatedAt();
        return ext;
    }
}
