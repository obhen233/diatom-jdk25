package com.github.obhen233.core.config;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.SystemConfigDao;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuration Manager
 *
 * Manages system configuration from multiple sources:
 * 1. application.properties (loaded first)
 * 2. SQLite database (loaded second, overrides properties if different)
 * 3. Runtime changes via config set command (overrides both)
 *
 * Priority: runtime > database > properties > synced (from gateway)
 */
public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);

    private final DatabaseManager db;
    private final SystemConfigDao configDao;
    private final Map<String, String> propertiesConfig;
    private final Map<String, String> runtimeOverrides;
    private final Map<String, String> syncedConfig;
    private final Map<String, SystemConfigDao.SystemConfig> dbConfigCache;

    // Config categories
    public static final String CATEGORY_API = "api";
    public static final String CATEGORY_WORKSPACE = "workspace";
    public static final String CATEGORY_AGENT = "agent";
    public static final String CATEGORY_SANDBOX = "sandbox";
    public static final String CATEGORY_LOGGING = "logging";
    public static final String CATEGORY_CLEANUP = "cleanup";

    public ConfigManager(DatabaseManager db) {
        this.db = db;
        this.configDao = new SystemConfigDao(db);
        this.propertiesConfig = new HashMap<>();
        this.runtimeOverrides = new HashMap<>();
        this.syncedConfig = new HashMap<>();
        this.dbConfigCache = new HashMap<>();
    }

    /**
     * Load properties configuration into memory
     */
    public void loadFromProperties(Map<String, String> props) {
        propertiesConfig.clear();
        propertiesConfig.putAll(props);
        logger.info("Loaded {} properties config entries", propertiesConfig.size());
    }

    /**
     * Load configuration from database
     */
    public void loadFromDatabase() {
        List<SystemConfigDao.SystemConfig> configs = configDao.findAll();
        dbConfigCache.clear();
        for (SystemConfigDao.SystemConfig c : configs) {
            if (c.configKey != null) {
                dbConfigCache.put(c.configKey, c);
            }
        }
        logger.info("Loaded {} configs from database into cache", configs.size());
    }

    /**
     * Load synced configuration from gateway into memory
     */
    public void loadSyncedConfig(Map<String, String> config) {
        syncedConfig.clear();
        if (config != null) {
            syncedConfig.putAll(config);
        }
        logger.info("Loaded {} synced config entries from gateway", syncedConfig.size());
    }

    /**
     * Get effective config value (runtime > database > properties > synced)
     */
    public String get(String key) {
        // 1. Check runtime overrides first
        if (runtimeOverrides.containsKey(key)) {
            return runtimeOverrides.get(key);
        }

        // 2. Check database cache (populated by loadFromDatabase)
        SystemConfigDao.SystemConfig dbConfig = dbConfigCache.get(key);
        if (dbConfig != null && dbConfig.configValue != null) {
            return dbConfig.configValue;
        }

        // 3. Fall back to properties
        if (propertiesConfig.containsKey(key)) {
            return propertiesConfig.get(key);
        }

        // 4. Fall back to synced config from gateway
        return syncedConfig.get(key);
    }

    /**
     * Get all effective config values (all sources merged).
     * Returns a map of all known keys with their effective (highest-priority) value.
     * Used by Gateway config sync endpoint to send full config snapshot.
     */
    public Map<String, String> getAllEffective() {
        Map<String, String> result = new HashMap<>();
        // Start with synced (lowest priority), then properties, then database, then runtime
        result.putAll(syncedConfig);
        result.putAll(propertiesConfig);
        for (SystemConfigDao.SystemConfig c : dbConfigCache.values()) {
            if (c.configValue != null) {
                result.put(c.configKey, c.configValue);
            }
        }
        result.putAll(runtimeOverrides);
        return result;
    }

    /**
     * Get config with metadata
     */
    public SystemConfigDao.SystemConfig getConfig(String key) {
        // Check runtime first
        if (runtimeOverrides.containsKey(key)) {
            SystemConfigDao.SystemConfig config = dbConfigCache.get(key);
            if (config != null) {
                config.configValue = runtimeOverrides.get(key);
                config.source = "runtime";
            }
            return config;
        }

        return dbConfigCache.get(key);
    }

    /**
     * Get all configs by category
     */
    public List<SystemConfigDao.SystemConfig> getByCategory(String category) {
        return configDao.findByCategory(category);
    }

    /**
     * Get all configs
     */
    public List<SystemConfigDao.SystemConfig> getAll() {
        return configDao.findAll();
    }

    /**
     * Set config value at runtime (in-memory + database)
     * Returns warning message if properties file has different value
     */
    public String set(String key, String value) {
        // Validate first
        ValidationResult validation = validate(key, value);
        if (!validation.valid) {
            return I18n.get("error", validation.message);
        }

        // Check if properties has different value
        String propsValue = propertiesConfig.get(key);
        boolean hasPropertiesConflict = propsValue != null && !propsValue.equals(value);

        // Update database
        SystemConfigDao.SystemConfig config = dbConfigCache.get(key);
        if (config == null) {
            return I18n.get("error", I18n.get("config.error.key_not_found", key));
        }
        value = value.toLowerCase();
        configDao.updateValue(key, value);
        // Update both runtime and cache
        runtimeOverrides.put(key, value);
        if (config.configValue != null) {
            config.configValue = value;
        }

        String warning = "";
        if (hasPropertiesConflict) {
            warning = "\n" + I18n.get("config.set.warning", propsValue);
        }

        return I18n.get("config.set.success", key, value) + warning;
    }

    /**
     * Reset config to default (from properties or database default)
     */
    public String reset(String key) {
        SystemConfigDao.SystemConfig config = dbConfigCache.get(key);
        if (config == null) {
            return I18n.get("error", I18n.get("config.error.key_not_found", key));
        }

        String defaultValue = config.defaultValue;
        if (defaultValue == null) {
            defaultValue = "";
        }

        configDao.updateValue(key, defaultValue);
        // Update both runtime and cache
        runtimeOverrides.remove(key);
        if (config != null) {
            config.configValue = defaultValue;
        }

        return I18n.get("config.reset.success", key, defaultValue);
    }

    /**
     * Validate a config value
     */
    public ValidationResult validate(String key, String value) {
        SystemConfigDao.SystemConfig config = dbConfigCache.get(key);
        if (config == null) {
            return new ValidationResult(false, I18n.get("config.error.key_not_found", key));
        }

        String type = config.configType != null ? config.configType : "string";

        switch (type) {
            case "int":
                return validateInt(config, value);
            case "boolean":
                return validateBoolean(config, value);
            case "enum":
                return validateEnum(config, value);
            case "pattern":
                return validatePattern(config, value);
            default:
                return new ValidationResult(true, null);
        }
    }

    private ValidationResult validateInt(SystemConfigDao.SystemConfig config, String value) {
        try {
            int intValue = Integer.parseInt(value);
            if (config.minValue != null && intValue < config.minValue) {
                return new ValidationResult(false, I18n.get("config.error.out_of_range_min", config.minValue));
            }
            if (config.maxValue != null && intValue > config.maxValue) {
                return new ValidationResult(false, I18n.get("config.error.out_of_range_max", config.maxValue));
            }
            return new ValidationResult(true, null);
        } catch (NumberFormatException e) {
            return new ValidationResult(false, I18n.get("config.error.int_required"));
        }
    }

    private ValidationResult validateBoolean(SystemConfigDao.SystemConfig config, String value) {
        String lower = value.toLowerCase();
        if ("true".equals(lower) || "false".equals(lower)) {
            return new ValidationResult(true, null);
        }
        return new ValidationResult(false, I18n.get("config.error.boolean_required"));
    }

    private ValidationResult validateEnum(SystemConfigDao.SystemConfig config, String value) {
        if (config.allowedValues == null || config.allowedValues.isEmpty()) {
            return new ValidationResult(true, null);
        }
        String[] allowed = config.allowedValues.split(",");
        for (String a : allowed) {
            if (a.trim().equalsIgnoreCase(value.trim())) {
                return new ValidationResult(true, null);
            }
        }
        return new ValidationResult(false, I18n.get("config.error.enum_values", config.allowedValues));
    }

    private ValidationResult validatePattern(SystemConfigDao.SystemConfig config, String value) {
        if (config.pattern == null || config.pattern.isEmpty()) {
            return new ValidationResult(true, null);
        }
        try {
            if (Pattern.matches(config.pattern, value)) {
                return new ValidationResult(true, null);
            } else {
                return new ValidationResult(false, I18n.get("config.error.pattern_mismatch"));
            }
        } catch (Exception e) {
            return new ValidationResult(false, I18n.get("config.error.pattern_failed"));
        }
    }

    /**
     * Check if a config key exists
     */
    public boolean exists(String key) {
        return dbConfigCache.containsKey(key) || propertiesConfig.containsKey(key) || syncedConfig.containsKey(key);
    }

    /**
     * Get all categories
     */
    public String[] getCategories() {
        return new String[] {
            CATEGORY_API, CATEGORY_WORKSPACE,
            CATEGORY_AGENT, CATEGORY_SANDBOX, CATEGORY_LOGGING, CATEGORY_CLEANUP
        };
    }

    /**
     * Get category display name
     */
    public String getCategoryDisplayName(String category) {
        String i18nKey = "config.category." + category;
        if (I18n.hasKey(i18nKey)) {
            return I18n.get(i18nKey);
        }
        // Fallback to English display names
        switch (category) {
            case CATEGORY_API: return "API";
            case CATEGORY_WORKSPACE: return "Workspace";
            case CATEGORY_AGENT: return "Agent";
            case CATEGORY_SANDBOX: return "Sandbox";
            case CATEGORY_LOGGING: return "Logging";
            case CATEGORY_CLEANUP: return "Cleanup";
            default: return category;
        }
    }

    /**
     * Validation result
     */
    public static class ValidationResult {
        public final boolean valid;
        public final String message;

        public ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
    }
}
