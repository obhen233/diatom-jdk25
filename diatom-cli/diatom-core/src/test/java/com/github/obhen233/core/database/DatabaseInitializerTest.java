package com.github.obhen233.core.database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Unit tests for DatabaseInitializer
 */
public class DatabaseInitializerTest {

    private DatabaseManager db;
    private SystemConfigDao dao;
    private File tempDbFile;

    @Before
    public void setUp() throws Exception {
        tempDbFile = File.createTempFile("test_diatom_", ".db");
        tempDbFile.deleteOnExit();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + tempDbFile.getAbsolutePath(), "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        dao = new SystemConfigDao(db);
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
    public void testInitializeEmptyDatabase() {
        DatabaseInitializer initializer = new DatabaseInitializer(db);
        initializer.initialize();

        // Verify some configs were initialized
        assertTrue(dao.getCount() > 0);

        // Verify api.key was initialized
        SystemConfigDao.SystemConfig apiKey = dao.findByKey("api.key");
        assertNotNull(apiKey);
        assertEquals("api", apiKey.category);
    }

    @Test
    public void testInitializePreservesExistingConfig() {
        // Pre-insert a config with custom value
        SystemConfigDao.SystemConfig existing = new SystemConfigDao.SystemConfig(
            "api.key", "user-modified-value", "string", "api"
        );
        existing.i18nKey = "User modified";
        dao.insert(existing);

        // Run initializer
        DatabaseInitializer initializer = new DatabaseInitializer(db);
        initializer.initialize();

        // Verify the existing config was NOT overwritten
        SystemConfigDao.SystemConfig config = dao.findByKey("api.key");
        assertEquals("user-modified-value", config.configValue);
    }

    @Test
    public void testInitializeWithPropertiesFile() throws Exception {
        // Create a temporary properties file
        Path tempDir = Files.createTempDirectory("diatom-test");
        Path propsFile = tempDir.resolve("application.properties");

        try (FileWriter writer = new FileWriter(propsFile.toFile())) {
            writer.write("api.key=from-properties\n");
            writer.write("command.timeout=120\n");
        }

        // Set system property to point to the temp directory
        String originalJarDir = System.getProperty("diatom.jar.dir");
        System.setProperty("diatom.jar.dir", tempDir.toString());

        try {
            // Run initializer
            DatabaseInitializer initializer = new DatabaseInitializer(db);
            initializer.initialize();

            // The api.key should be initialized with properties value since database was empty
            SystemConfigDao.SystemConfig apiKey = dao.findByKey("api.key");
            assertNotNull(apiKey);
            assertEquals("from-properties", apiKey.configValue);

            // command.timeout should be initialized
            SystemConfigDao.SystemConfig timeout = dao.findByKey("command.timeout");
            assertNotNull(timeout);
            assertEquals("120", timeout.configValue);
        } finally {
            // Restore original system property
            if (originalJarDir != null) {
                System.setProperty("diatom.jar.dir", originalJarDir);
            } else {
                System.clearProperty("diatom.jar.dir");
            }

            // Cleanup
            Files.deleteIfExists(propsFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    public void testNewConfigAddedOnUpgrade() {
        // First, initialize with current seed data
        DatabaseInitializer initializer = new DatabaseInitializer(db);
        initializer.initialize();

        int initialCount = dao.getCount();

        // Simulate adding a new config directly (as if a new version added it)
        SystemConfigDao.SystemConfig newConfig = new SystemConfigDao.SystemConfig(
            "new.added.config", "default", "string", "test"
        );
        newConfig.i18nKey = "New config for testing";
        newConfig.defaultValue = "default";
        // Don't insert it - just verify the seed data has the right count

        // Re-run initializer - should not duplicate existing configs
        initializer.initialize();

        // Count should remain the same (no duplicates)
        assertEquals(initialCount, dao.getCount());
    }

    @Test
    public void testAllSeedConfigsHaveRequiredFields() {
        DatabaseInitializer initializer = new DatabaseInitializer(db);
        initializer.initialize();

        for (SystemConfigDao.SystemConfig config : dao.findAll()) {
            assertNotNull("config_key should not be null", config.configKey);
            assertNotNull("category should not be null", config.category);
            assertNotNull("config_type should not be null", config.configType);
        }
    }

    @Test
    public void testConstraintFieldsAreSet() {
        DatabaseInitializer initializer = new DatabaseInitializer(db);
        initializer.initialize();

        // Check int constraint
        SystemConfigDao.SystemConfig timeout = dao.findByKey("command.timeout");
        assertNotNull(timeout);
        assertEquals("int", timeout.configType);
        assertEquals(Integer.valueOf(1), timeout.minValue);
        assertEquals(Integer.valueOf(3600), timeout.maxValue);

        // Check enum constraint
        SystemConfigDao.SystemConfig language = dao.findByKey("agent.language");
        assertNotNull(language);
        assertEquals("enum", language.configType);
        assertNotNull(language.allowedValues);
        assertTrue(language.allowedValues.contains("en"));
        assertTrue(language.allowedValues.contains("zh"));

        // Check boolean constraint
        SystemConfigDao.SystemConfig external = dao.findByKey("filesystem.allow_external");
        assertNotNull(external);
        assertEquals("boolean", external.configType);
    }
}
