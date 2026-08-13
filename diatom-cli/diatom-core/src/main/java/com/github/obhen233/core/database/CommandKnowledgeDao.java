package com.github.obhen233.core.database;

import com.github.obhen233.core.database.entity.CommandKnowledgeEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for command knowledge operations
 */
public class CommandKnowledgeDao {
    private static final Logger logger = LoggerFactory.getLogger(CommandKnowledgeDao.class);

    private final SessionFactory sf;

    public CommandKnowledgeDao(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    // ── Conversion helpers ──────────────────────────────────────────────────

    private CommandKnowledgeEntity toEntity(CommandKnowledge k) {
        CommandKnowledgeEntity e = new CommandKnowledgeEntity();
        e.setId(DiatomIdGenerator.idOrNull(k.id));
        e.setCommand(k.command);
        e.setToolType(k.toolType);
        e.setPermission(k.permission != null ? k.permission : "ALLOW");
        e.setRiskLevel(k.riskLevel);
        e.setConfidence(k.confidence);
        e.setSource(k.source != null ? k.source : "builtin");
        e.setLastVerified(k.lastVerified != null ? k.lastVerified : Instant.now().toEpochMilli());
        e.setVerifiedCount(k.verifiedCount);
        e.setCreatedAt(k.createdAt != null ? k.createdAt : Instant.now().toEpochMilli());
        e.setUpdatedAt(Instant.now().toEpochMilli());
        return e;
    }

    private CommandKnowledge toKnowledge(CommandKnowledgeEntity e) {
        CommandKnowledge k = new CommandKnowledge();
        k.id = e.getId() != null ? e.getId() : 0;
        k.command = e.getCommand();
        k.toolType = e.getToolType();
        k.permission = e.getPermission();
        k.riskLevel = e.getRiskLevel() != null ? e.getRiskLevel() : 0;
        k.confidence = e.getConfidence() != null ? e.getConfidence() : 0;
        k.source = e.getSource();
        k.lastVerified = e.getLastVerified();
        k.verifiedCount = e.getVerifiedCount() != null ? e.getVerifiedCount() : 0;
        k.createdAt = e.getCreatedAt();
        k.updatedAt = e.getUpdatedAt();
        return k;
    }

    // ── INSERT ──────────────────────────────────────────────────────────────

    /**
     * Insert a new command into the knowledge base
     */
    public void insertCommandKnowledge(CommandKnowledge knowledge) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            session.persist(toEntity(knowledge));
            session.getTransaction().commit();
            logger.debug("Inserted command knowledge: {}", knowledge.command);
        } catch (Exception e) {
            logger.error("Failed to insert command knowledge: {}", knowledge.command, e);
        }
    }

    // ── SELECT single ──────────────────────────────────────────────────────

    /**
     * Find command knowledge by command name
     */
    public CommandKnowledge findByCommand(String command) {
        try (Session session = sf.openSession()) {
            Query<CommandKnowledgeEntity> q = session.createQuery(
                    "FROM CommandKnowledgeEntity WHERE command = :cmd", CommandKnowledgeEntity.class);
            q.setParameter("cmd", command);
            CommandKnowledgeEntity entity = q.uniqueResult();
            return entity != null ? toKnowledge(entity) : null;
        } catch (Exception e) {
            logger.error("Failed to find command knowledge: {}", command, e);
            return null;
        }
    }

    // ── SELECT list ────────────────────────────────────────────────────────

    /**
     * Find command knowledge by command pattern (LIKE query)
     */
    public List<CommandKnowledge> findByCommandPattern(String pattern) {
        List<CommandKnowledge> results = new ArrayList<>();
        try (Session session = sf.openSession()) {
            Query<CommandKnowledgeEntity> q = session.createQuery(
                    "FROM CommandKnowledgeEntity WHERE command LIKE :pat ORDER BY command", CommandKnowledgeEntity.class);
            q.setParameter("pat", "%" + pattern + "%");
            for (CommandKnowledgeEntity e : q.list()) {
                results.add(toKnowledge(e));
            }
        } catch (Exception ex) {
            logger.error("Failed to find command knowledge by pattern: {}", pattern, ex);
        }
        return results;
    }

    /**
     * Get all commands with a specific permission
     */
    public List<CommandKnowledge> findByPermission(String permission) {
        List<CommandKnowledge> results = new ArrayList<>();
        try (Session session = sf.openSession()) {
            Query<CommandKnowledgeEntity> q = session.createQuery(
                    "FROM CommandKnowledgeEntity WHERE permission = :perm ORDER BY command", CommandKnowledgeEntity.class);
            q.setParameter("perm", permission);
            for (CommandKnowledgeEntity e : q.list()) {
                results.add(toKnowledge(e));
            }
        } catch (Exception ex) {
            logger.error("Failed to find commands by permission: {}", permission, ex);
        }
        return results;
    }

    /**
     * Get all commands with a specific tool type
     */
    public List<CommandKnowledge> findByToolType(String toolType) {
        List<CommandKnowledge> results = new ArrayList<>();
        try (Session session = sf.openSession()) {
            Query<CommandKnowledgeEntity> q = session.createQuery(
                    "FROM CommandKnowledgeEntity WHERE toolType = :tt ORDER BY command", CommandKnowledgeEntity.class);
            q.setParameter("tt", toolType);
            for (CommandKnowledgeEntity e : q.list()) {
                results.add(toKnowledge(e));
            }
        } catch (Exception ex) {
            logger.error("Failed to find commands by tool type: {}", toolType, ex);
        }
        return results;
    }

    /**
     * Get all commands with a specific source
     */
    public List<CommandKnowledge> findBySource(String source) {
        List<CommandKnowledge> results = new ArrayList<>();
        try (Session session = sf.openSession()) {
            Query<CommandKnowledgeEntity> q = session.createQuery(
                    "FROM CommandKnowledgeEntity WHERE source = :src ORDER BY command", CommandKnowledgeEntity.class);
            q.setParameter("src", source);
            for (CommandKnowledgeEntity e : q.list()) {
                results.add(toKnowledge(e));
            }
        } catch (Exception ex) {
            logger.error("Failed to find commands by source: {}", source, ex);
        }
        return results;
    }

    /**
     * Get all commands with risk_level >= specified value
     */
    public List<CommandKnowledge> findByMinRiskLevel(int minRiskLevel) {
        List<CommandKnowledge> results = new ArrayList<>();
        try (Session session = sf.openSession()) {
            Query<CommandKnowledgeEntity> q = session.createQuery(
                    "FROM CommandKnowledgeEntity WHERE riskLevel >= :min ORDER BY riskLevel DESC, command", CommandKnowledgeEntity.class);
            q.setParameter("min", minRiskLevel);
            for (CommandKnowledgeEntity e : q.list()) {
                results.add(toKnowledge(e));
            }
        } catch (Exception ex) {
            logger.error("Failed to find commands by min risk level: {}", minRiskLevel, ex);
        }
        return results;
    }

    /**
     * Get all commands ordered by usage frequency (verified_count)
     */
    public List<CommandKnowledge> findFrequentlyUsed(int limit) {
        List<CommandKnowledge> results = new ArrayList<>();
        try (Session session = sf.openSession()) {
            Query<CommandKnowledgeEntity> q = session.createQuery(
                    "FROM CommandKnowledgeEntity ORDER BY verifiedCount DESC", CommandKnowledgeEntity.class);
            q.setMaxResults(limit);
            for (CommandKnowledgeEntity e : q.list()) {
                results.add(toKnowledge(e));
            }
        } catch (Exception ex) {
            logger.error("Failed to find frequently used commands", ex);
        }
        return results;
    }

    /**
     * Get all commands ordered by confidence (lowest first - for learning)
     */
    public List<CommandKnowledge> findLowConfidence(int limit) {
        List<CommandKnowledge> results = new ArrayList<>();
        try (Session session = sf.openSession()) {
            Query<CommandKnowledgeEntity> q = session.createQuery(
                    "FROM CommandKnowledgeEntity WHERE confidence < 80 ORDER BY confidence ASC", CommandKnowledgeEntity.class);
            q.setMaxResults(limit);
            for (CommandKnowledgeEntity e : q.list()) {
                results.add(toKnowledge(e));
            }
        } catch (Exception ex) {
            logger.error("Failed to find low confidence commands", ex);
        }
        return results;
    }

    /**
     * Get all unknown commands (UNSURE permission)
     */
    public List<CommandKnowledge> findUnknown() {
        List<CommandKnowledge> results = new ArrayList<>();
        try (Session session = sf.openSession()) {
            Query<CommandKnowledgeEntity> q = session.createQuery(
                    "FROM CommandKnowledgeEntity WHERE permission = 'UNSURE' ORDER BY confidence ASC", CommandKnowledgeEntity.class);
            for (CommandKnowledgeEntity e : q.list()) {
                results.add(toKnowledge(e));
            }
        } catch (Exception ex) {
            logger.error("Failed to find unknown commands", ex);
        }
        return results;
    }

    /**
     * Get all command knowledge entries
     */
    public List<CommandKnowledge> findAll() {
        List<CommandKnowledge> results = new ArrayList<>();
        try (Session session = sf.openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<CommandKnowledgeEntity> cq = cb.createQuery(CommandKnowledgeEntity.class);
            Root<CommandKnowledgeEntity> root = cq.from(CommandKnowledgeEntity.class);
            cq.orderBy(cb.asc(root.get("command")));
            for (CommandKnowledgeEntity e : session.createQuery(cq).list()) {
                results.add(toKnowledge(e));
            }
        } catch (Exception ex) {
            logger.error("Failed to find all command knowledge", ex);
        }
        return results;
    }

    // ── UPDATE ──────────────────────────────────────────────────────────────

    /**
     * Update command knowledge
     */
    public void updateCommandKnowledge(CommandKnowledge knowledge) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query<?> q = session.createQuery(
                    "UPDATE CommandKnowledgeEntity SET toolType = :tt, permission = :perm, riskLevel = :rl, " +
                    "confidence = :conf, source = :src, lastVerified = :lv, verifiedCount = :vc, updatedAt = :ua " +
                    "WHERE command = :cmd");
            q.setParameter("tt", knowledge.toolType);
            q.setParameter("perm", knowledge.permission);
            q.setParameter("rl", knowledge.riskLevel);
            q.setParameter("conf", knowledge.confidence);
            q.setParameter("src", knowledge.source);
            q.setParameter("lv", knowledge.lastVerified != null ? knowledge.lastVerified : Instant.now().toEpochMilli());
            q.setParameter("vc", knowledge.verifiedCount);
            q.setParameter("ua", Instant.now().toEpochMilli());
            q.setParameter("cmd", knowledge.command);
            q.executeUpdate();
            session.getTransaction().commit();
            logger.debug("Updated command knowledge: {}", knowledge.command);
        } catch (Exception e) {
            logger.error("Failed to update command knowledge: {}", knowledge.command, e);
        }
    }

    /**
     * Update confidence for a command
     */
    public void updateConfidence(String command, int delta) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            CommandKnowledgeEntity entity = session.createQuery(
                    "FROM CommandKnowledgeEntity WHERE command = :cmd", CommandKnowledgeEntity.class)
                    .setParameter("cmd", command).uniqueResult();
            if (entity != null) {
                int newConfidence = Math.max(0, Math.min(100, entity.getConfidence() + delta));
                Query<?> q = session.createQuery(
                        "UPDATE CommandKnowledgeEntity SET confidence = :conf, updatedAt = :ua WHERE command = :cmd");
                q.setParameter("conf", newConfidence);
                q.setParameter("ua", Instant.now().toEpochMilli());
                q.setParameter("cmd", command);
                q.executeUpdate();
            }
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.error("Failed to update confidence for command: {}", command, e);
        }
    }

    /**
     * Increment verified count for a command
     */
    public void incrementVerifiedCount(String command) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query<?> q = session.createQuery(
                    "UPDATE CommandKnowledgeEntity SET verifiedCount = verifiedCount + 1, " +
                    "lastVerified = :lv, updatedAt = :ua WHERE command = :cmd");
            q.setParameter("lv", Instant.now().toEpochMilli());
            q.setParameter("ua", Instant.now().toEpochMilli());
            q.setParameter("cmd", command);
            q.executeUpdate();
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.error("Failed to increment verified count for command: {}", command, e);
        }
    }

    // ── DELETE ──────────────────────────────────────────────────────────────

    /**
     * Delete a command from knowledge base
     */
    public void deleteCommand(String command) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query<?> q = session.createQuery(
                    "DELETE FROM CommandKnowledgeEntity WHERE command = :cmd");
            q.setParameter("cmd", command);
            q.executeUpdate();
            session.getTransaction().commit();
            logger.info("Deleted command knowledge: {}", command);
        } catch (Exception e) {
            logger.error("Failed to delete command knowledge: {}", command, e);
        }
    }

    // ── COUNT queries ───────────────────────────────────────────────────────

    /**
     * Get count of all commands
     */
    public int getCount() {
        try (Session session = sf.openSession()) {
            Long count = session.createQuery(
                    "SELECT COUNT(c) FROM CommandKnowledgeEntity c", Long.class).uniqueResult();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.error("Failed to get command count", e);
            return 0;
        }
    }

    /**
     * Get count by source
     */
    public int getCountBySource(String source) {
        try (Session session = sf.openSession()) {
            Long count = session.createQuery(
                    "SELECT COUNT(c) FROM CommandKnowledgeEntity c WHERE c.source = :src", Long.class)
                    .setParameter("src", source).uniqueResult();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.error("Failed to get count by source: {}", source, e);
            return 0;
        }
    }

    /**
     * Get count by permission
     */
    public int getCountByPermission(String permission) {
        try (Session session = sf.openSession()) {
            Long count = session.createQuery(
                    "SELECT COUNT(c) FROM CommandKnowledgeEntity c WHERE c.permission = :perm", Long.class)
                    .setParameter("perm", permission).uniqueResult();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.error("Failed to get count by permission: {}", permission, e);
            return 0;
        }
    }

    // ── Inner data class ────────────────────────────────────────────────────

    /**
     * Command Knowledge data class
     */
    public static class CommandKnowledge {
        public int id;
        public String command;
        public String toolType;
        public String permission;  // ALLOW, DENY, UNSURE
        public int riskLevel;      // 0-3: 0=safe, 1=caution, 2=dangerous, 3=highly dangerous
        public int confidence;      // 0-100
        public String source;      // builtin, learned, llm
        public Long lastVerified;
        public int verifiedCount;
        public Long createdAt;
        public Long updatedAt;

        public CommandKnowledge() {}

        public CommandKnowledge(String command, String toolType, String permission, int riskLevel) {
            this.command = command;
            this.toolType = toolType;
            this.permission = permission;
            this.riskLevel = riskLevel;
            this.confidence = 50;
            this.source = "builtin";
            this.verifiedCount = 0;
        }
    }
}
