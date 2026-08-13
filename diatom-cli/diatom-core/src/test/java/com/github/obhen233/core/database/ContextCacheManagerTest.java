package com.github.obhen233.core.database;

import com.github.obhen233.core.database.ContextCacheManager.CachedContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.core.database.HibernateDatabaseManager;

import static org.junit.Assert.*;

/**
 * ContextCacheManager 测试用例
 * 对应 TEST_CASES.md 10. ContextCacheManager 测试
 */
public class ContextCacheManagerTest {

    private DatabaseManager db;
    private ContextCacheManager cache;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"), "diatom_context_test_" + System.currentTimeMillis() + ".db").toString();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + testDbPath, "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        cache = new ContextCacheManager(db);
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
        File dbFile = new File(testDbPath);
        if (dbFile.exists()) {
            dbFile.delete();
        }
    }

    @Test
    public void testSaveAndLoadContext() {
        String projectPath = "/workspace/testproject";
        String projectName = "TestProject";
        String projectType = "java";

        Map<String, Object> contextData = new HashMap<>();
        contextData.put("files", Arrays.asList("a.java", "b.java"));
        contextData.put("size", 1024);

        Map<String, String> fileIndex = new HashMap<>();
        fileIndex.put("a.java", "/workspace/testproject/src/a.java");
        fileIndex.put("b.java", "/workspace/testproject/src/b.java");

        cache.saveContext(projectPath, projectName, projectType, contextData, fileIndex);

        CachedContext loaded = cache.loadContext(projectPath);
        assertNotNull("Context should be loaded", loaded);
        assertEquals("Project name should match", projectName, loaded.getProjectName());
        assertEquals("Project type should match", projectType, loaded.getProjectType());
        assertEquals("Project path should match", projectPath, loaded.getProjectPath());
        assertNotNull("Context data should not be null", loaded.getContextData());
        assertNotNull("File index should not be null", loaded.getFileIndex());
    }

    @Test
    public void testLoadNonExistentContext() {
        CachedContext loaded = cache.loadContext("/nonexistent/path");
        assertNull("Should return null for non-existent context", loaded);
    }

    @Test
    public void testSaveContextWithNullData() {
        String projectPath = "/workspace/testproject2";
        cache.saveContext(projectPath, "TestProject2", "python", null, null);

        CachedContext loaded = cache.loadContext(projectPath);
        assertNotNull("Context should be loaded", loaded);
        assertEquals("Project name should match", "TestProject2", loaded.getProjectName());
    }

    @Test
    public void testCacheValidity() {
        String projectPath = "/workspace/testproject3";
        cache.saveContext(projectPath, "TestProject3", "java", null, null);

        // Valid cache (within 1 hour)
        assertTrue("Cache should be valid for 1 hour", cache.isCacheValid(projectPath, 3600000));

        // Invalid cache (0 milliseconds = expired)
        assertFalse("Cache should be invalid with 0 max age", cache.isCacheValid(projectPath, 0));
    }

    @Test
    public void testCacheValidityNonExistent() {
        boolean valid = cache.isCacheValid("/nonexistent/path", 3600000);
        assertFalse("Non-existent cache should not be valid", valid);
    }

    @Test
    public void testDeleteContext() {
        String projectPath = "/workspace/testproject4";
        cache.saveContext(projectPath, "TestProject4", "java", null, null);

        assertNotNull("Context should exist before delete", cache.loadContext(projectPath));

        cache.deleteContext(projectPath);

        assertNull("Context should not exist after delete", cache.loadContext(projectPath));
    }

    @Test
    public void testClearAll() {
        cache.saveContext("/workspace/proj1", "Project1", "java", null, null);
        cache.saveContext("/workspace/proj2", "Project2", "python", null, null);

        assertEquals("Should have 2 contexts before clear", 2, cache.listCachedContexts().size());

        cache.clearAll();

        assertEquals("Should have 0 contexts after clear", 0, cache.listCachedContexts().size());
    }

    @Test
    public void testListCachedContexts() {
        cache.saveContext("/workspace/proj1", "Project1", "java", null, null);
        cache.saveContext("/workspace/proj2", "Project2", "python", null, null);
        cache.saveContext("/workspace/proj3", "Project3", "javascript", null, null);

        List<CachedContext> contexts = cache.listCachedContexts();
        assertEquals("Should have 3 contexts", 3, contexts.size());
    }

    @Test
    public void testListCachedContextsOrder() throws InterruptedException {
        cache.saveContext("/workspace/proj1", "Project1", "java", null, null);
        Thread.sleep(10);
        cache.saveContext("/workspace/proj2", "Project2", "python", null, null);

        List<CachedContext> contexts = cache.listCachedContexts();
        // Most recently indexed should be first
        assertEquals("Project2", contexts.get(0).getProjectName());
        assertEquals("Project1", contexts.get(1).getProjectName());
    }

    @Test
    public void testCachedContextSummary() {
        cache.saveContext("/workspace/testproject", "TestProject", "java", null, null);

        CachedContext loaded = cache.loadContext("/workspace/testproject");
        String summary = loaded.getSummary();

        assertNotNull("Summary should not be null", summary);
        assertTrue("Summary should contain project name", summary.contains("TestProject"));
        assertTrue("Summary should contain project type", summary.contains("java"));
    }

    @Test
    public void testUpdateExistingContext() {
        String projectPath = "/workspace/testproject5";
        cache.saveContext(projectPath, "Project5", "java", null, null);

        // Update with new data
        Map<String, Object> newContextData = new HashMap<>();
        newContextData.put("updated", true);

        cache.saveContext(projectPath, "Project5Updated", "kotlin", newContextData, null);

        CachedContext loaded = cache.loadContext(projectPath);
        assertEquals("Project name should be updated", "Project5Updated", loaded.getProjectName());
        assertEquals("Project type should be updated", "kotlin", loaded.getProjectType());
        assertNotNull("Context data should not be null after update", loaded.getContextData());
    }

    @Test
    public void testEmptyFileIndex() {
        String projectPath = "/workspace/testproject6";
        cache.saveContext(projectPath, "Project6", "java", null, new HashMap<String, String>());

        CachedContext loaded = cache.loadContext(projectPath);
        assertNotNull("Context should be loaded", loaded);
        assertNotNull("File index should not be null", loaded.getFileIndex());
        assertTrue("File index should be empty", loaded.getFileIndex().isEmpty());
    }

    @Test
    public void testIndexedAtTimestamp() {
        String projectPath = "/workspace/testproject7";
        long beforeSave = System.currentTimeMillis();
        cache.saveContext(projectPath, "Project7", "java", null, null);
        long afterSave = System.currentTimeMillis();

        CachedContext loaded = cache.loadContext(projectPath);
        assertTrue("Indexed at should be after before save time",
            loaded.getIndexedAt() >= beforeSave);
        assertTrue("Indexed at should be before after save time",
            loaded.getIndexedAt() <= afterSave);
    }
}
