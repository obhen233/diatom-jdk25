package com.github.obhen233.core.database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.SQLException;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for SystemConfigDao
 */
public class SystemConfigDaoTest {

    private DatabaseManager db;
    private SystemConfigDao dao;
    private File tempDbFile;
    

    @Before
    public void setUp() throws Exception {
        // Use temporary file database for testing
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
    public void testInsertAndFind() {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(
            "test.key", "testValue", "string", "test"
        );
        config.i18nKey = "Test description";
        config.defaultValue = "default";
        config.source = "builtin";

        dao.insert(config);

        SystemConfigDao.SystemConfig found = dao.findByKey("test.key");
        assertNotNull(found);
        assertEquals("test.key", found.configKey);
        assertEquals("testValue", found.configValue);
        assertEquals("string", found.configType);
        assertEquals("test", found.category);
        assertEquals("Test description", found.i18nKey);
    }

    @Test
    public void testFindByCategory() {
        SystemConfigDao.SystemConfig config1 = new SystemConfigDao.SystemConfig(
            "api.key", "value1", "string", "api"
        );
        SystemConfigDao.SystemConfig config2 = new SystemConfigDao.SystemConfig(
            "api.url", "value2", "string", "api"
        );
        SystemConfigDao.SystemConfig config3 = new SystemConfigDao.SystemConfig(
            "model.name", "value3", "string", "model"
        );

        dao.insert(config1);
        dao.insert(config2);
        dao.insert(config3);

        List<SystemConfigDao.SystemConfig> apiConfigs = dao.findByCategory("api");
        assertEquals(2, apiConfigs.size());

        List<SystemConfigDao.SystemConfig> modelConfigs = dao.findByCategory("model");
        assertEquals(1, modelConfigs.size());
    }

    @Test
    public void testFindAll() {
        for (int i = 0; i < 5; i++) {
            SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(
                "key" + i, "value" + i, "string", "category" + (i % 2)
            );
            dao.insert(config);
        }

        List<SystemConfigDao.SystemConfig> all = dao.findAll();
        assertEquals(5, all.size());
    }

    @Test
    public void testUpdateValue() {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(
            "update.test", "original", "string", "test"
        );
        dao.insert(config);

        dao.updateValue("update.test", "modified");

        SystemConfigDao.SystemConfig updated = dao.findByKey("update.test");
        assertEquals("modified", updated.configValue);
    }

    @Test
    public void testExists() {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(
            "exists.test", "value", "string", "test"
        );
        dao.insert(config);

        assertTrue(dao.exists("exists.test"));
        assertFalse(dao.exists("not.exists"));
    }

    @Test
    public void testDelete() {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(
            "delete.test", "value", "string", "test"
        );
        dao.insert(config);

        assertTrue(dao.exists("delete.test"));
        dao.delete("delete.test");
        assertFalse(dao.exists("delete.test"));
    }

    @Test
    public void testGetCount() {
        assertEquals(0, dao.getCount());

        for (int i = 0; i < 3; i++) {
            SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(
                "count" + i, "value", "string", "test"
            );
            dao.insert(config);
        }

        assertEquals(3, dao.getCount());
    }

    @Test
    public void testBatchInsert() {
        java.util.List<SystemConfigDao.SystemConfig> configs = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(
                "batch" + i, "value", "string", "test"
            );
            configs.add(config);
        }

        dao.batchInsert(configs);
        assertEquals(3, dao.getCount());
    }

    @Test
    public void testConstraintFields() {
        SystemConfigDao.SystemConfig config = new SystemConfigDao.SystemConfig(
            "constraint.test", "50", "int", "test"
        );
        config.minValue = 1;
        config.maxValue = 100;
        config.allowedValues = "a,b,c";
        config.pattern = "\\d+";

        dao.insert(config);

        SystemConfigDao.SystemConfig found = dao.findByKey("constraint.test");
        assertEquals(Integer.valueOf(1), found.minValue);
        assertEquals(Integer.valueOf(100), found.maxValue);
        assertEquals("a,b,c", found.allowedValues);
        assertEquals("\\d+", found.pattern);
    }
}
