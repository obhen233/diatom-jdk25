package com.github.obhen233.core.tool.builtin;

import com.github.obhen233.core.command.tools.ConfigTools;
import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.core.database.HibernateDatabaseManager;
import com.github.obhen233.core.database.SystemConfigDao;
import com.github.obhen233.util.I18n;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Unit tests for ConfigTools
 */
public class ConfigToolsTest {

    private DatabaseManager db;
    private ConfigManager configManager;
    private ConfigTools configTools;
    private File tempDbFile;

    @Before
    public void setUp() throws Exception {
        // Initialize I18n for tests
        I18n.init("en");

        tempDbFile = File.createTempFile("test_diatom_", ".db");
        tempDbFile.deleteOnExit();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + tempDbFile.getAbsolutePath(), "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();

        // Insert test configs
        SystemConfigDao dao = new SystemConfigDao(db);
        insertTestConfig(dao, "api.key", "test-key-123", "string", "api", "API密钥", "");
        insertTestConfig(dao, "command.timeout", "60", "int", "sandbox", "命令超时", "60", 1, 3600);
        insertTestConfig(dao, "agent.language", "zh", "enum", "agent", "语言", "en", "en,zh");
        insertTestConfig(dao, "logging.enabled", "true", "boolean", "logging", "日志开关", "false");
        insertTestConfig(dao, "command.sandbox.mode", "whitelist", "enum", "sandbox", "沙箱模式", "whitelist", "whitelist,none");

        configManager = new ConfigManager(db);
        configManager.loadFromDatabase();
        configTools = new ConfigTools(configManager, db);
    }

    private void insertTestConfig(SystemConfigDao dao, String key, String value, String type,
            String category, String i18nKey, String defaultValue) {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(key, value, type, category);
        config.i18nKey = i18nKey;
        config.defaultValue = defaultValue;
        dao.insert(config);
    }

    private void insertTestConfig(SystemConfigDao dao, String key, String value, String type,
            String category, String i18nKey, String defaultValue, int min, int max) {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(key, value, type, category);
        config.i18nKey = i18nKey;
        config.defaultValue = defaultValue;
        config.minValue = min;
        config.maxValue = max;
        dao.insert(config);
    }

    private void insertTestConfig(SystemConfigDao dao, String key, String value, String type,
            String category, String i18nKey, String defaultValue, String allowedValues) {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(key, value, type, category);
        config.i18nKey = i18nKey;
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
    public void testConfigList() {
        String result = configTools.configList("{}");
        assertNotNull(result);
        assertTrue(result.contains("api.key"));
        assertTrue(result.contains("command.timeout"));
    }

    @Test
    public void testConfigListWithCategory() {
        String result = configTools.configList("{\"category\":\"api\"}");
        assertNotNull(result);
        assertTrue(result.contains("api.key"));
        assertFalse(result.contains("command.timeout")); // Should only show api configs
    }

    @Test
    public void testConfigGet() {
        String result = configTools.configGet("{\"key\":\"api.key\"}");
        assertNotNull(result);
        assertTrue(result.contains("api.key"));
        // api.key is masked for security — shows only first 3 and last 4 characters
        // "test-key-123" (12 chars) → "tes*****-123" (first 3, 5 asterisks, last 4)
        assertTrue(result.contains("tes*****-123"));
    }

    @Test
    public void testConfigGetWithConstraintInfo() {
        String result = configTools.configGet("{\"key\":\"command.timeout\"}");
        assertNotNull(result);
        assertTrue(result.contains("command.timeout"));
        assertTrue(result.contains("Type") || result.contains("类型"));
    }

    @Test
    public void testConfigGetNonExistent() {
        String result = configTools.configGet("{\"key\":\"non.existent\"}");
        assertTrue(result.startsWith("Error"));
    }

    @Test
    public void testConfigSet() {
        String result = configTools.configSet("{\"key\":\"command.timeout\",\"value\":\"120\"}");
        assertNotNull(result);
        // Check for success message - I18n message is "Config set: {0} = {1}" or fallback key
        assertTrue("Result: " + result,
            result.contains("Config") || result.startsWith("config."));
        // If using I18n format, should contain the value
        if (!result.startsWith("config.")) {
            assertTrue(result.contains("120"));
        }
    }

    @Test
    public void testConfigSetInvalidInt() {
        String result = configTools.configSet("{\"key\":\"command.timeout\",\"value\":\"abc\"}");
        assertTrue(result.startsWith("Error"));
    }

    @Test
    public void testConfigSetOutOfRange() {
        String result = configTools.configSet("{\"key\":\"command.timeout\",\"value\":\"99999\"}");
        assertTrue(result.startsWith("Error"));
    }

    @Test
    public void testConfigSetInvalidEnum() {
        String result = configTools.configSet("{\"key\":\"agent.language\",\"value\":\"fr\"}");
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("有效值") || result.contains("en") && result.contains("zh"));
    }

    @Test
    public void testConfigSetInvalidBoolean() {
        String result = configTools.configSet("{\"key\":\"logging.enabled\",\"value\":\"maybe\"}");
        assertTrue(result.startsWith("Error"));
    }

    @Test
    public void testConfigReset() {
        // First set a value
        configTools.configSet("{\"key\":\"command.timeout\",\"value\":\"120\"}");

        // Then reset
        String result = configTools.configReset("{\"key\":\"command.timeout\"}");
        assertNotNull(result);
        assertTrue(result.contains("Success") || result.contains("success") || result.contains("reset"));
    }

    @Test
    public void testConfigHelp() {
        String result = configTools.configHelp("{}");
        assertNotNull(result);
        assertTrue(result.contains("config"));
        assertTrue(result.contains("list") || result.contains("get") || result.contains("set"));
    }

    @Test
    public void testListAllowedCommands() {
        String result = configTools.listAllowedCommands("{}");
        assertNotNull(result);
        // Should load from command-whitelist.json
        assertTrue(result.contains("git") || result.contains("mvn") || result.contains("Allowed Commands"));
    }

    @Test
    public void testConfigSetWithWarningForPropertiesConflict() {
        // Note: This test would need a properties file to actually show the warning
        // For now, just verify it doesn't crash
        String result = configTools.configSet("{\"key\":\"command.timeout\",\"value\":\"120\"}");
        assertNotNull(result);
        // Success or Error, but not crash - also accept raw key as fallback
        assertTrue("Result: " + result,
            result.startsWith("Success") || result.startsWith("Error") || result.startsWith("config.") || result.startsWith("Config"));
    }

    @Test
    public void testConfigSetMissingKey() {
        String result = configTools.configSet("{\"value\":\"120\"}");
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("key") || result.contains("必需"));
    }

    @Test
    public void testConfigSetMissingValue() {
        String result = configTools.configSet("{\"key\":\"command.timeout\"}");
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("value") || result.contains("必需"));
    }
}
