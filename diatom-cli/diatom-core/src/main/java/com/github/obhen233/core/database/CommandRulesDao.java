package com.github.obhen233.core.database;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.obhen233.core.database.entity.CommandRuleEntity;
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
 * Data Access Object for command_rules table.
 * Handles CRUD operations for command validation rules.
 */
public class CommandRulesDao {
    private static final Logger logger = LoggerFactory.getLogger(CommandRulesDao.class);
    private final SessionFactory sf;

    public CommandRulesDao(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    /**
     * Command rule entity
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommandRule {
        public long id;
        public String mode;       // 'terminal' | 'agent'
        public String type;       // 'allowed' | 'blocked' | 'dangerous'
        public String pattern;
        public String source;      // 'built-in' | 'manual' | 'auto-learned'
        public boolean enabled;
        public long createdAt;
        public long updatedAt;

        public CommandRule() {}

        public CommandRule(String mode, String type, String pattern, String source) {
            this.mode = mode;
            this.type = type;
            this.pattern = pattern;
            this.source = source;
            this.enabled = true;
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = System.currentTimeMillis();
        }
    }

    /**
     * Find rule by ID
     */
    public CommandRule findById(long id) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            CommandRuleEntity entity = session.get(CommandRuleEntity.class, id);
            tx.commit();
            return entity != null ? toRule(entity) : null;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to find rule by id: {}", id, e);
            return null;
        } finally {
            session.close();
        }
    }

    /**
     * Find all rules
     */
    public List<CommandRule> findAll() {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<CommandRuleEntity> query = session.createQuery(
                    "FROM CommandRuleEntity ORDER BY mode, type, pattern",
                    CommandRuleEntity.class);
            List<CommandRuleEntity> entities = query.list();
            tx.commit();
            List<CommandRule> results = new ArrayList<>(entities.size());
            for (CommandRuleEntity entity : entities) {
                results.add(toRule(entity));
            }
            return results;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to find all rules", e);
            return new ArrayList<>();
        } finally {
            session.close();
        }
    }

    /**
     * Find rules by mode
     */
    public List<CommandRule> findByMode(String mode) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<CommandRuleEntity> query = session.createQuery(
                    "FROM CommandRuleEntity WHERE mode = :mode ORDER BY type, pattern",
                    CommandRuleEntity.class);
            query.setParameter("mode", mode);
            List<CommandRuleEntity> entities = query.list();
            tx.commit();
            List<CommandRule> results = new ArrayList<>(entities.size());
            for (CommandRuleEntity entity : entities) {
                results.add(toRule(entity));
            }
            return results;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to find rules by mode: {}", mode, e);
            return new ArrayList<>();
        } finally {
            session.close();
        }
    }

    /**
     * Find rules by mode and type
     */
    public List<CommandRule> findByModeAndType(String mode, String type) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<CommandRuleEntity> query = session.createQuery(
                    "FROM CommandRuleEntity WHERE mode = :mode AND type = :type ORDER BY pattern",
                    CommandRuleEntity.class);
            query.setParameter("mode", mode);
            query.setParameter("type", type);
            List<CommandRuleEntity> entities = query.list();
            tx.commit();
            List<CommandRule> results = new ArrayList<>(entities.size());
            for (CommandRuleEntity entity : entities) {
                results.add(toRule(entity));
            }
            return results;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to find rules by mode and type: {} {}", mode, type, e);
            return new ArrayList<>();
        } finally {
            session.close();
        }
    }

    /**
     * Find rules by source
     */
    public List<CommandRule> findBySource(String source) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<CommandRuleEntity> query = session.createQuery(
                    "FROM CommandRuleEntity WHERE source = :source ORDER BY mode, type, pattern",
                    CommandRuleEntity.class);
            query.setParameter("source", source);
            List<CommandRuleEntity> entities = query.list();
            tx.commit();
            List<CommandRule> results = new ArrayList<>(entities.size());
            for (CommandRuleEntity entity : entities) {
                results.add(toRule(entity));
            }
            return results;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to find rules by source: {}", source, e);
            return new ArrayList<>();
        } finally {
            session.close();
        }
    }

    /**
     * Find rules by type
     */
    public List<CommandRule> findByType(String type) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<CommandRuleEntity> query = session.createQuery(
                    "FROM CommandRuleEntity WHERE type = :type ORDER BY mode, pattern",
                    CommandRuleEntity.class);
            query.setParameter("type", type);
            List<CommandRuleEntity> entities = query.list();
            tx.commit();
            List<CommandRule> results = new ArrayList<>(entities.size());
            for (CommandRuleEntity entity : entities) {
                results.add(toRule(entity));
            }
            return results;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to find rules by type: {}", type, e);
            return new ArrayList<>();
        } finally {
            session.close();
        }
    }

    /**
     * Insert a new rule
     */
    public void insert(CommandRule rule) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            session.persist(toEntity(rule));
            tx.commit();
            logger.debug("Inserted rule: {} {} {}", rule.mode, rule.type, rule.pattern);
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to insert rule: {} {} {}", rule.mode, rule.type, rule.pattern, e);
        } finally {
            session.close();
        }
    }

    /**
     * Insert rule if not exists (based on unique constraint)
     */
    public boolean insertIfNotExists(CommandRule rule) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            session.persist(toEntity(rule));
            session.flush();
            tx.commit();
            logger.debug("Inserted rule if not exists: {} {} {}", rule.mode, rule.type, rule.pattern);
            return true;
        } catch (PersistenceException e) {
            tx.rollback();
            logger.debug("Rule already exists: {} {} {}", rule.mode, rule.type, rule.pattern);
            return false;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to insert rule: {} {} {}", rule.mode, rule.type, rule.pattern, e);
            return false;
        } finally {
            session.close();
        }
    }

    /**
     * Batch insert rules
     */
    public void batchInsert(List<CommandRule> rules) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            int batchSize = 25;
            for (int i = 0; i < rules.size(); i++) {
                CommandRule rule = rules.get(i);
                CommandRuleEntity existing = findEntityByUniqueKey(session, rule.mode, rule.type, rule.pattern);
                if (existing != null) {
                    existing.setSource(rule.source);
                    existing.setEnabled(rule.enabled);
                    existing.setUpdatedAt(rule.updatedAt);
                    session.merge(existing);
                } else {
                    session.persist(toEntity(rule));
                }
                if (i % batchSize == 0 && i > 0) {
                    session.flush();
                    session.clear();
                }
            }
            tx.commit();
            logger.info("Batch inserted {} rules", rules.size());
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to batch insert rules", e);
        } finally {
            session.close();
        }
    }

    /**
     * Update rule
     */
    public void update(CommandRule rule) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            CommandRuleEntity entity = session.get(CommandRuleEntity.class, rule.id);
            if (entity != null) {
                entity.setMode(rule.mode);
                entity.setType(rule.type);
                entity.setPattern(rule.pattern);
                entity.setSource(rule.source);
                entity.setEnabled(rule.enabled);
                entity.setUpdatedAt(System.currentTimeMillis());
                session.merge(entity);
                logger.debug("Updated rule: {}", rule.id);
            }
            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to update rule: {}", rule.id, e);
        } finally {
            session.close();
        }
    }

    /**
     * Update enabled status
     */
    public void updateEnabled(long id, boolean enabled) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<?> query = session.createQuery(
                    "UPDATE CommandRuleEntity SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id");
            query.setParameter("enabled", enabled);
            query.setParameter("updatedAt", System.currentTimeMillis());
            query.setParameter("id", id);
            query.executeUpdate();
            tx.commit();
            logger.debug("Updated rule enabled: {} -> {}", id, enabled);
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to update rule enabled: {}", id, e);
        } finally {
            session.close();
        }
    }

    /**
     * Delete rule by ID
     */
    public void delete(long id) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<?> query = session.createQuery("DELETE FROM CommandRuleEntity WHERE id = :id");
            query.setParameter("id", id);
            query.executeUpdate();
            tx.commit();
            logger.debug("Deleted rule: {}", id);
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to delete rule: {}", id, e);
        } finally {
            session.close();
        }
    }

    /**
     * Delete rule by mode, type, pattern
     */
    public void delete(String mode, String type, String pattern) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<?> query = session.createQuery(
                    "DELETE FROM CommandRuleEntity WHERE mode = :mode AND type = :type AND pattern = :pattern");
            query.setParameter("mode", mode);
            query.setParameter("type", type);
            query.setParameter("pattern", pattern);
            query.executeUpdate();
            tx.commit();
            logger.debug("Deleted rule: {} {} {}", mode, type, pattern);
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to delete rule: {} {} {}", mode, type, pattern, e);
        } finally {
            session.close();
        }
    }

    /**
     * Delete all rules by source
     */
    public int deleteBySource(String source) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<?> query = session.createQuery(
                    "DELETE FROM CommandRuleEntity WHERE source = :source");
            query.setParameter("source", source);
            int affected = query.executeUpdate();
            tx.commit();
            logger.info("Deleted {} rules with source: {}", affected, source);
            return affected;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to delete rules by source: {}", source, e);
            return 0;
        } finally {
            session.close();
        }
    }

    /**
     * Delete all non-built-in rules
     */
    public int deleteNonBuiltin() {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<?> query = session.createQuery(
                    "DELETE FROM CommandRuleEntity WHERE source != 'built-in'");
            int affected = query.executeUpdate();
            tx.commit();
            logger.info("Deleted {} non-built-in rules", affected);
            return affected;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to delete non-built-in rules", e);
            return 0;
        } finally {
            session.close();
        }
    }

    /**
     * Delete all rules
     */
    public int deleteAll() {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<?> query = session.createQuery("DELETE FROM CommandRuleEntity");
            int affected = query.executeUpdate();
            tx.commit();
            logger.info("Deleted all {} rules", affected);
            return affected;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to delete all rules", e);
            return 0;
        } finally {
            session.close();
        }
    }

    /**
     * Check if rule exists
     */
    public boolean exists(String mode, String type, String pattern) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<Long> query = session.createQuery(
                    "SELECT COUNT(*) FROM CommandRuleEntity WHERE mode = :mode AND type = :type AND pattern = :pattern",
                    Long.class);
            query.setParameter("mode", mode);
            query.setParameter("type", type);
            query.setParameter("pattern", pattern);
            Long count = query.uniqueResult();
            tx.commit();
            return count != null && count > 0;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to check rule exists: {} {} {}", mode, type, pattern, e);
            return false;
        } finally {
            session.close();
        }
    }

    /**
     * Get count
     */
    public int getCount() {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<Long> query = session.createQuery(
                    "SELECT COUNT(*) FROM CommandRuleEntity", Long.class);
            Long count = query.uniqueResult();
            tx.commit();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to get rule count", e);
            return 0;
        } finally {
            session.close();
        }
    }

    /**
     * Get count by mode
     */
    public int getCountByMode(String mode) {
        Session session = sf.openSession();
        Transaction tx = session.beginTransaction();
        try {
            Query<Long> query = session.createQuery(
                    "SELECT COUNT(*) FROM CommandRuleEntity WHERE mode = :mode", Long.class);
            query.setParameter("mode", mode);
            Long count = query.uniqueResult();
            tx.commit();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            tx.rollback();
            logger.error("Failed to get rule count by mode: {}", mode, e);
            return 0;
        } finally {
            session.close();
        }
    }

    private CommandRuleEntity toEntity(CommandRule rule) {
        CommandRuleEntity entity = new CommandRuleEntity();
        entity.setId(DiatomIdGenerator.idOrNull(rule.id));
        entity.setMode(rule.mode);
        entity.setType(rule.type);
        entity.setPattern(rule.pattern);
        entity.setSource(rule.source);
        entity.setEnabled(rule.enabled);
        entity.setCreatedAt(rule.createdAt);
        entity.setUpdatedAt(rule.updatedAt);
        return entity;
    }

    private CommandRule toRule(CommandRuleEntity entity) {
        CommandRule rule = new CommandRule();
        rule.id = entity.getId();
        rule.mode = entity.getMode();
        rule.type = entity.getType();
        rule.pattern = entity.getPattern();
        rule.source = entity.getSource();
        rule.enabled = entity.getEnabled();
        rule.createdAt = entity.getCreatedAt();
        rule.updatedAt = entity.getUpdatedAt();
        return rule;
    }

    private CommandRuleEntity findEntityByUniqueKey(Session session, String mode, String type, String pattern) {
        Query<CommandRuleEntity> query = session.createQuery(
                "FROM CommandRuleEntity WHERE mode = :mode AND type = :type AND pattern = :pattern",
                CommandRuleEntity.class);
        query.setParameter("mode", mode);
        query.setParameter("type", type);
        query.setParameter("pattern", pattern);
        return query.uniqueResult();
    }
}
