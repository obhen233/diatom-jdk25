package com.github.obhen233.core.gateway.topology;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.entity.TopologyDefEntity;
import com.github.obhen233.core.database.entity.TopologyVersionEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for topology_def and topology_version tables.
 * Uses Hibernate SessionFactory aligned with {@link com.github.obhen233.core.database.CommandRulesDao}.
 */
public class TopologyDao {
    private static final Logger logger = LoggerFactory.getLogger(TopologyDao.class);
    private final SessionFactory sf;

    public TopologyDao(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    // ========== topology_def ==========

    public List<TopologyDef> findAllDefs() {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<TopologyDefEntity> query = session.createQuery(
                    "FROM TopologyDefEntity ORDER BY updatedAt DESC", TopologyDefEntity.class);
            List<TopologyDefEntity> entities = query.list();
            tx.commit();
            List<TopologyDef> result = new ArrayList<>(entities.size());
            for (TopologyDefEntity entity : entities) {
                result.add(toDef(entity));
            }
            return result;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to list topology defs", e);
            return new ArrayList<>();
        } finally {
            session.close();
        }
    }

    public TopologyDef findDefById(long id) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            TopologyDefEntity entity = session.get(TopologyDefEntity.class, id);
            tx.commit();
            return entity != null ? toDef(entity) : null;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to find topology def by id: {}", id, e);
            return null;
        } finally {
            session.close();
        }
    }

    public long insertDef(TopologyDef def) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            TopologyDefEntity entity = toEntity(def);
            session.persist(entity);
            session.flush();
            tx.commit();
            long id = entity.getId() != null ? entity.getId() : -1;
            logger.debug("Inserted topology def: {} (id={})", def.getName(), id);
            return id;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to insert topology def", e);
            return -1;
        } finally {
            session.close();
        }
    }

    public boolean updateDef(long id, String name, String description, String draftDefinition, long updatedAt) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            TopologyDefEntity entity = session.get(TopologyDefEntity.class, id);
            if (entity != null) {
                entity.setName(name);
                entity.setDescription(description);
                entity.setDraftDefinition(draftDefinition);
                entity.setStatus("draft");
                entity.setUpdatedAt(updatedAt);
                session.merge(entity);
                tx.commit();
                logger.debug("Updated topology def: {}", id);
                return true;
            }
            tx.commit();
            return false;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to update topology def {}", id, e);
            return false;
        } finally {
            session.close();
        }
    }

    public boolean deleteDef(long id) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<?> query = session.createQuery("DELETE FROM TopologyDefEntity WHERE id = :id");
            query.setParameter("id", id);
            int affected = query.executeUpdate();
            tx.commit();
            return affected > 0;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to delete topology def {}", id, e);
            return false;
        } finally {
            session.close();
        }
    }

    /**
     * Publish: increment version, set status=published, clear draft, set published_at.
     * Also creates an immutable version snapshot.
     */
    /**
     * Find the maximum version across all topology_version records.
     * Returns 0 if no versions exist.
     */
    public int findMaxVersion() {
        Session session = sf.openSession();
        try {
            Query<Integer> query = session.createQuery(
                    "SELECT COALESCE(MAX(v.version), 0) FROM TopologyVersionEntity v", Integer.class);
            Integer result = query.uniqueResult();
            return result != null ? result : 0;
        } finally {
            session.close();
        }
    }

    public boolean publishDef(long id, String definition, long now) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            // Get current def
            TopologyDefEntity defEntity = session.get(TopologyDefEntity.class, id);
            if (defEntity == null) {
                tx.commit();
                return false;
            }

            int newVersion = defEntity.getVersion() + 1;

            // Update topology_def
            defEntity.setStatus("published");
            defEntity.setVersion(newVersion);
            defEntity.setDraftDefinition(null);
            defEntity.setPublishedAt(now);
            defEntity.setUpdatedAt(now);
            session.merge(defEntity);

            // Supersede previous active version
            Query<?> supersedeQuery = session.createQuery(
                    "UPDATE TopologyVersionEntity SET status = 'superseded' WHERE topologyId = :topologyId AND status = 'active'");
            supersedeQuery.setParameter("topologyId", id);
            supersedeQuery.executeUpdate();

            // Insert new version snapshot
            TopologyVersionEntity versionEntity = new TopologyVersionEntity();
            versionEntity.setTopologyId(id);
            versionEntity.setVersion(newVersion);
            versionEntity.setDefinition(definition);
            versionEntity.setStatus("active");
            versionEntity.setPublishedAt(now);
            versionEntity.setCreatedAt(now);
            session.persist(versionEntity);

            tx.commit();
            logger.info("Published topology def {} as version {}", id, newVersion);
            return true;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to publish topology def {}", id, e);
            return false;
        } finally {
            session.close();
        }
    }

    /**
     * Rollback to a specific version: copy that version's definition as new draft.
     */
    public boolean rollbackToVersion(long topologyId, int version, long now) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            // Find the target version entity
            Query<TopologyVersionEntity> findQuery = session.createQuery(
                    "FROM TopologyVersionEntity WHERE topologyId = :topologyId AND version = :version",
                    TopologyVersionEntity.class);
            findQuery.setParameter("topologyId", topologyId);
            findQuery.setParameter("version", version);
            TopologyVersionEntity versionEntity = findQuery.uniqueResult();
            if (versionEntity == null) {
                tx.commit();
                return false;
            }

            // Update topology_def with the version's definition as draft
            TopologyDefEntity defEntity = session.get(TopologyDefEntity.class, topologyId);
            if (defEntity == null) {
                tx.commit();
                return false;
            }

            defEntity.setDraftDefinition(versionEntity.getDefinition());
            defEntity.setStatus("draft");
            defEntity.setUpdatedAt(now);
            session.merge(defEntity);

            tx.commit();
            logger.debug("Rolled back topology {} to version {}", topologyId, version);
            return true;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to rollback topology {} to version {}", topologyId, version, e);
            return false;
        } finally {
            session.close();
        }
    }

    // ========== topology_version ==========

    public List<TopologyVersion> findVersionsByTopologyId(long topologyId) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<TopologyVersionEntity> query = session.createQuery(
                    "FROM TopologyVersionEntity WHERE topologyId = :topologyId ORDER BY version DESC",
                    TopologyVersionEntity.class);
            query.setParameter("topologyId", topologyId);
            List<TopologyVersionEntity> entities = query.list();
            tx.commit();
            List<TopologyVersion> result = new ArrayList<>(entities.size());
            for (TopologyVersionEntity entity : entities) {
                result.add(toVersion(entity));
            }
            return result;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to list versions for topology {}", topologyId, e);
            return new ArrayList<>();
        } finally {
            session.close();
        }
    }

    /**
     * Find the most recent version snapshots across all definitions.
     * Returns at most {@code maxResults} entries, ordered by publishedAt DESC.
     */
    public List<TopologyVersion> findAllVersions(int maxResults) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<TopologyVersionEntity> query = session.createQuery(
                    "FROM TopologyVersionEntity ORDER BY publishedAt DESC", TopologyVersionEntity.class);
            query.setMaxResults(maxResults);
            List<TopologyVersionEntity> entities = query.list();
            tx.commit();
            List<TopologyVersion> result = new ArrayList<>(entities.size());
            for (TopologyVersionEntity entity : entities) {
                result.add(toVersion(entity));
            }
            return result;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to find all versions", e);
            return new ArrayList<>();
        } finally {
            session.close();
        }
    }

    public TopologyVersion findVersion(long topologyId, int version) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<TopologyVersionEntity> query = session.createQuery(
                    "FROM TopologyVersionEntity WHERE topologyId = :topologyId AND version = :version",
                    TopologyVersionEntity.class);
            query.setParameter("topologyId", topologyId);
            query.setParameter("version", version);
            TopologyVersionEntity entity = query.uniqueResult();
            tx.commit();
            return entity != null ? toVersion(entity) : null;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to find version {} for topology {}", version, topologyId, e);
            return null;
        } finally {
            session.close();
        }
    }

    /**
     * Find the active published topology definition (the one with status='active').
     * Falls back to the highest version among any published.
     */
    public TopologyVersion findActiveVersion() {
        // First try the one with status='active'
        TopologyVersion active = findActiveVersionByStatus();
        if (active != null) return active;
        // Fallback: highest version among any published
        return findLatestPublishedVersion();
    }

    /**
     * Delete a version snapshot. Only allowed for non-active (unpublished) versions.
     */
    public boolean deleteVersion(long topologyId, int version) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<?> query = session.createQuery(
                    "DELETE FROM TopologyVersionEntity WHERE topologyId = :topologyId AND version = :version AND status != 'active'");
            query.setParameter("topologyId", topologyId);
            query.setParameter("version", version);
            int affected = query.executeUpdate();
            tx.commit();
            return affected > 0;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to delete version {} for topology {}", version, topologyId, e);
            return false;
        } finally {
            session.close();
        }
    }

    private TopologyVersion findActiveVersionByStatus() {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<TopologyVersionEntity> query = session.createQuery(
                    "FROM TopologyVersionEntity WHERE status = 'active' ORDER BY publishedAt DESC",
                    TopologyVersionEntity.class);
            query.setMaxResults(1);
            TopologyVersionEntity entity = query.uniqueResult();
            tx.commit();
            return entity != null ? toVersion(entity) : null;
        } catch (Exception e) {
            tx.rollback();
            logger.debug("No active version by status found", e);
            return null;
        } finally {
            session.close();
        }
    }

    private TopologyVersion findLatestPublishedVersion() {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            // Subquery: max version per topology_id, then pick the latest published
            String hql = "FROM TopologyVersionEntity tv WHERE tv.version = (" +
                         "SELECT MAX(tv2.version) FROM TopologyVersionEntity tv2 WHERE tv2.topologyId = tv.topologyId" +
                         ") ORDER BY tv.publishedAt DESC";
            Query<TopologyVersionEntity> query = session.createQuery(hql, TopologyVersionEntity.class);
            query.setMaxResults(1);
            TopologyVersionEntity entity = query.uniqueResult();
            tx.commit();
            return entity != null ? toVersion(entity) : null;
        } catch (Exception e) {
            tx.rollback();
            logger.debug("No published version found", e);
            return null;
        } finally {
            session.close();
        }
    }

    // ========== mapping ==========

    private TopologyDefEntity toEntity(TopologyDef def) {
        TopologyDefEntity entity = new TopologyDefEntity();
        if (def.getId() > 0) {
            entity.setId(def.getId());
        }
        entity.setName(def.getName());
        entity.setDescription(def.getDescription());
        entity.setStatus(def.getStatus());
        entity.setVersion(def.getVersion());
        entity.setDraftDefinition(def.getDraftDefinition());
        entity.setPublishedAt(def.getPublishedAt() > 0 ? def.getPublishedAt() : null);
        entity.setCreatedAt(def.getCreatedAt());
        entity.setUpdatedAt(def.getUpdatedAt());
        return entity;
    }

    private TopologyDef toDef(TopologyDefEntity entity) {
        TopologyDef def = new TopologyDef();
        def.setId(entity.getId() != null ? entity.getId() : 0);
        def.setName(entity.getName());
        def.setDescription(entity.getDescription());
        def.setStatus(entity.getStatus());
        def.setVersion(entity.getVersion() != null ? entity.getVersion() : 0);
        def.setDraftDefinition(entity.getDraftDefinition());
        def.setPublishedAt(entity.getPublishedAt() != null ? entity.getPublishedAt() : 0);
        def.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt() : 0);
        def.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt() : 0);
        return def;
    }

    private TopologyVersion toVersion(TopologyVersionEntity entity) {
        TopologyVersion tv = new TopologyVersion();
        tv.setId(entity.getId() != null ? entity.getId() : 0);
        tv.setTopologyId(entity.getTopologyId() != null ? entity.getTopologyId() : 0);
        tv.setVersion(entity.getVersion() != null ? entity.getVersion() : 0);
        tv.setDefinition(entity.getDefinition());
        tv.setStatus(entity.getStatus());
        tv.setPublishedAt(entity.getPublishedAt() != null ? entity.getPublishedAt() : 0);
        tv.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt() : 0);
        return tv;
    }
}
