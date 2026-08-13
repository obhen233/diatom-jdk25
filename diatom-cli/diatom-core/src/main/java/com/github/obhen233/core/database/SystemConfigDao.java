package com.github.obhen233.core.database;

import com.github.obhen233.core.database.entity.SystemConfigEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for system_config table.
 * Handles CRUD operations for system configuration.
 */
public class SystemConfigDao {
    private static final Logger logger = LoggerFactory.getLogger(SystemConfigDao.class);
    private final SessionFactory sf;

    public SystemConfigDao(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    /**
     * System configuration entity
     */
    public static class SystemConfig {
        public long id;
        public String configKey;
        public String configValue;
        public String configType;       // string, int, boolean, enum
        public String category;
        public String i18nKey;         // i18n key for display description
        public String defaultValue;
        public String allowedValues;    // comma-separated for enum
        public Integer minValue;
        public Integer maxValue;
        public String pattern;          // regex pattern for validation
        public String source;
        public long lastModified;
        public long createdAt;

        public SystemConfig() {}

        public SystemConfig(String configKey, String configValue, String configType, String category) {
            this.configKey = configKey;
            this.configValue = configValue;
            this.configType = configType;
            this.category = category;
        }
    }

    /**
     * Find config by key
     */
    public SystemConfig findByKey(String key) {
        try (Session session = sf.openSession()) {
            Query<SystemConfigEntity> query = session.createQuery(
                "FROM SystemConfigEntity WHERE configKey = :key", SystemConfigEntity.class);
            query.setParameter("key", key);
            SystemConfigEntity entity = query.uniqueResult();
            if (entity != null) {
                return toConfig(entity);
            }
        } catch (Exception e) {
            logger.error("Failed to find config by key: {}", key, e);
        }
        return null;
    }

    /**
     * Find all configs by category
     */
    public List<SystemConfig> findByCategory(String category) {
        try (Session session = sf.openSession()) {
            List<SystemConfigEntity> entities = session.createQuery(
                "FROM SystemConfigEntity WHERE category = :cat ORDER BY configKey", SystemConfigEntity.class)
                .setParameter("cat", category).list();
            List<SystemConfig> result = new ArrayList<>();
            for (SystemConfigEntity e : entities) result.add(toConfig(e));
            return result;
        } catch (Exception e) {
            logger.error("Failed to find configs by category: {}", category, e);
        }
        return new ArrayList<>();
    }

    /**
     * Find all configs
     */
    public List<SystemConfig> findAll() {
        try (Session session = sf.openSession()) {
            List<SystemConfigEntity> entities = session.createQuery(
                "FROM SystemConfigEntity ORDER BY category, configKey", SystemConfigEntity.class).list();
            List<SystemConfig> result = new ArrayList<>();
            for (SystemConfigEntity e : entities) result.add(toConfig(e));
            return result;
        } catch (Exception e) {
            logger.error("Failed to find all configs", e);
        }
        return new ArrayList<>();
    }

    /**
     * Insert a new config
     */
    public void insert(SystemConfig config) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            SystemConfigEntity entity = toEntity(config);
            session.merge(entity);
            session.getTransaction().commit();
            logger.debug("Inserted config: {}", config.configKey);
        } catch (Exception e) {
            logger.error("Failed to insert config: {}", config.configKey, e);
        }
    }

    /**
     * Update config value
     */
    public void updateValue(String key, String value) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query<?> query = session.createQuery(
                "UPDATE SystemConfigEntity SET configValue = :val, lastModified = :time, source = 'database' WHERE configKey = :key");
            query.setParameter("val", value);
            query.setParameter("time", System.currentTimeMillis());
            query.setParameter("key", key);
            query.executeUpdate();
            session.getTransaction().commit();
            logger.debug("Updated config: {} = {}", key, value);
        } catch (Exception e) {
            logger.error("Failed to update config: {}", key, e);
        }
    }

    /**
     * Update config with full details
     */
    public void update(SystemConfig config) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            SystemConfigEntity entity = toEntity(config);
            session.merge(entity);
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.error("Failed to update config: {}", config.configKey, e);
        }
    }

    /**
     * Delete config by key
     */
    public void delete(String key) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            Query<?> query = session.createQuery("DELETE FROM SystemConfigEntity WHERE configKey = :key");
            query.setParameter("key", key);
            query.executeUpdate();
            session.getTransaction().commit();
            logger.debug("Deleted config: {}", key);
        } catch (Exception e) {
            logger.error("Failed to delete config: {}", key, e);
        }
    }

    /**
     * Get config count
     */
    public int getCount() {
        try (Session session = sf.openSession()) {
            Long count = session.createQuery(
                "SELECT COUNT(*) FROM SystemConfigEntity", Long.class).uniqueResult();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.error("Failed to get config count", e);
        }
        return 0;
    }

    /**
     * Get config count by category
     */
    public int getCountByCategory(String category) {
        try (Session session = sf.openSession()) {
            Long count = session.createQuery(
                "SELECT COUNT(*) FROM SystemConfigEntity WHERE category = :cat", Long.class)
                .setParameter("cat", category).uniqueResult();
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.error("Failed to get config count by category: {}", category, e);
        }
        return 0;
    }

    /**
     * Check if config exists
     */
    public boolean exists(String key) {
        try (Session session = sf.openSession()) {
            Long count = session.createQuery(
                "SELECT COUNT(*) FROM SystemConfigEntity WHERE configKey = :key", Long.class)
                .setParameter("key", key).uniqueResult();
            return count != null && count > 0;
        } catch (Exception e) {
            logger.error("Failed to check config exists: {}", key, e);
        }
        return false;
    }

    /**
     * Batch insert configs (for initialization)
     */
    public void batchInsert(List<SystemConfig> configs) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            for (SystemConfig sc : configs) {
                session.merge(toEntity(sc));
            }
            session.getTransaction().commit();
            logger.info("Batch inserted {} configs", configs.size());
        } catch (Exception e) {
            logger.error("Failed to batch insert configs", e);
        }
    }

    private SystemConfigEntity toEntity(SystemConfig config) {
        SystemConfigEntity entity = new SystemConfigEntity();
        entity.setId(DiatomIdGenerator.idOrNull(config.id));
        entity.setConfigKey(config.configKey);
        entity.setConfigValue(config.configValue);
        entity.setConfigType(config.configType != null ? config.configType : "string");
        entity.setCategory(config.category);
        entity.setI18nKey(config.i18nKey);
        entity.setDefaultValue(config.defaultValue);
        entity.setAllowedValues(config.allowedValues);
        entity.setMinValue(config.minValue);
        entity.setMaxValue(config.maxValue);
        entity.setPattern(config.pattern);
        entity.setSource(config.source != null ? config.source : "database");
        entity.setLastModified(config.lastModified > 0 ? config.lastModified : System.currentTimeMillis());
        entity.setCreatedAt(config.createdAt > 0 ? config.createdAt : System.currentTimeMillis());
        return entity;
    }

    private SystemConfig toConfig(SystemConfigEntity entity) {
        SystemConfig config = new SystemConfig();
        config.id = entity.getId() != null ? entity.getId() : 0;
        config.configKey = entity.getConfigKey();
        config.configValue = entity.getConfigValue();
        config.configType = entity.getConfigType();
        config.category = entity.getCategory();
        config.i18nKey = entity.getI18nKey();
        config.defaultValue = entity.getDefaultValue();
        config.allowedValues = entity.getAllowedValues();
        config.minValue = entity.getMinValue();
        config.maxValue = entity.getMaxValue();
        config.pattern = entity.getPattern();
        config.source = entity.getSource();
        config.lastModified = entity.getLastModified() != null ? entity.getLastModified() : 0;
        config.createdAt = entity.getCreatedAt() != null ? entity.getCreatedAt() : 0;
        return config;
    }
}
