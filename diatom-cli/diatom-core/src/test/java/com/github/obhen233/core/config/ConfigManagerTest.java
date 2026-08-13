package com.github.obhen233.core.config;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.core.database.HibernateDatabaseManager;
import com.github.obhen233.core.database.SystemConfigDao;
import com.github.obhen233.util.I18n;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for ConfigManager
 */
public class ConfigManagerTest {

    private DatabaseManager db;
    private ConfigManager configManager;
    private File tempDbFile;

    @Before
    public void setUp() throws Exception {
        I18n.init("zh");
        tempDbFile = File.createTempFile("test_diatom_", ".db");
        tempDbFile.deleteOnExit();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + tempDbFile.getAbsolutePath(), "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        configManager = new ConfigManager(db);

        // Insert some test configs directly
        SystemConfigDao dao = new SystemConfigDao(db);
        insertTestConfig(dao, "api.key", "test-key", "string", "api", "Test API key", "default-key");
        insertTestConfig(dao, "command.timeout", "60", "int", "sandbox", "Command timeout", "60", 1, 3600);
        insertTestConfig(dao, "agent.language", "zh", "enum", "agent", "Language", "en", "en,zh");
        insertTestConfig(dao, "logging.enabled", "true", "boolean", "logging", "Enable logging", "false");

        // Populate cache from database
        configManager.loadFromDatabase();
    }

    private void insertTestConfig(SystemConfigDao dao, String key, String value, String type,
            String category, String description, String defaultValue) {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(key, value, type, category);
        config.i18nKey = description;
        config.defaultValue = defaultValue;
        dao.insert(config);
    }

    private void insertTestConfig(SystemConfigDao dao, String key, String value, String type,
            String category, String description, String defaultValue, int min, int max) {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(key, value, type, category);
        config.i18nKey = description;
        config.defaultValue = defaultValue;
        config.minValue = min;
        config.maxValue = max;
        dao.insert(config);
    }

    private void insertTestConfig(SystemConfigDao dao, String key, String value, String type,
            String category, String description, String defaultValue, String allowedValues) {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(key, value, type, category);
        config.i18nKey = description;
        config.defaultValue = defaultValue;
        config.allowedValues = allowedValues;
        dao.insert(config);
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
        if (tempDbFile != null && tempDbFile.exists()) {
            tempDbFile.delete();
        }
    }

    @Test
    public void testGet() {
        String value = configManager.get("api.key");
        assertEquals("test-key", value);
    }

    @Test
    public void testGetNonExistent() {
        String value = configManager.get("non.existent");
        assertNull(value);
    }

    @Test
    public void testGetConfig() {
        SystemConfigDao.SystemConfig config = configManager.getConfig("api.key");
        assertNotNull(config);
        assertEquals("api.key", config.configKey);
        assertEquals("test-key", config.configValue);
        assertEquals("string", config.configType);
    }

    @Test
    public void testGetByCategory() {
        List<SystemConfigDao.SystemConfig> configs = configManager.getByCategory("api");
        assertEquals(1, configs.size());
        assertEquals("api.key", configs.get(0).configKey);
    }

    @Test
    public void testGetAllCategories() {
        String[] categories = configManager.getCategories();
        assertNotNull(categories);
        assertTrue(categories.length > 0);
    }

    @Test
    public void testGetCategoryDisplayName() {
        assertEquals("API", configManager.getCategoryDisplayName("api"));
        assertEquals("Agent", configManager.getCategoryDisplayName("agent"));
        assertEquals("沙箱", configManager.getCategoryDisplayName("sandbox"));
    }

    @Test
    public void testValidateInt() {
        // Valid int
        ConfigManager.ValidationResult result = configManager.validate("command.timeout", "120");
        assertTrue(result.valid);

        // Invalid: out of range
        result = configManager.validate("command.timeout", "5000");
        assertFalse(result.valid);
        assertTrue(result.message.contains("600"));

        // Invalid: not a number
        result = configManager.validate("command.timeout", "abc");
        assertFalse(result.valid);
    }

    @Test
    public void testValidateBoolean() {
        // Valid
        ConfigManager.ValidationResult result = configManager.validate("logging.enabled", "false");
        assertTrue(result.valid);

        // Invalid
        result = configManager.validate("logging.enabled", "yes");
        assertFalse(result.valid);
        assertTrue(result.message.contains("true") && result.message.contains("false"));
    }

    @Test
    public void testValidateEnum() {
        // Valid
        ConfigManager.ValidationResult result = configManager.validate("agent.language", "en");
        assertTrue(result.valid);

        // Invalid
        result = configManager.validate("agent.language", "fr");
        assertFalse(result.valid);
        assertTrue(result.message.contains("en") && result.message.contains("zh"));
    }

    @Test
    public void testValidateNonExistentKey() {
        ConfigManager.ValidationResult result = configManager.validate("non.existent", "value");
        assertFalse(result.valid);
        assertTrue(result.message.contains("不存在") || result.message.contains("not found"));
    }

    @Test
    public void testExists() {
        assertTrue(configManager.exists("api.key"));
        assertFalse(configManager.exists("non.existent"));
    }

    @Test
    public void testSetValidValue() {
        String result = configManager.set("command.timeout", "120");
        assertTrue(result.contains("command.timeout"));
        assertTrue(result.contains("120"));
        assertFalse(result.startsWith(I18n.get("error", "")));
    }

    @Test
    public void testSetInvalidValue() {
        String result = configManager.set("command.timeout", "abc");
        assertTrue(result.startsWith(I18n.get("error", "")));
        assertTrue(result.contains("必须是整数"));
    }

    @Test
    public void testSetOutOfRangeValue() {
        String result = configManager.set("command.timeout", "999999");
        assertTrue(result.startsWith(I18n.get("error", "")));
        assertTrue(result.contains("600") || result.contains("范围"));
    }

    @Test
    public void testReset() {
        // First set a value
        configManager.set("command.timeout", "120");

        // Then reset
        String result = configManager.reset("command.timeout");
        assertTrue(result.contains("command.timeout") || result.contains("reset"));
        assertFalse(result.startsWith(I18n.get("error", "")));
    }

    @Test
    public void testResetNonExistent() {
        String result = configManager.reset("non.existent");
        assertTrue(result.startsWith(I18n.get("error", "")));
    }
}
