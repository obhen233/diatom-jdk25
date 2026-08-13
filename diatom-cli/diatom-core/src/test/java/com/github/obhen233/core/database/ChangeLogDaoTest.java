package com.github.obhen233.core.database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ChangeLogDao 测试用例
 */
public class ChangeLogDaoTest {

    private DatabaseManager db;
    private ChangeLogDao changeLogDao;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"), "diatom_changelog_test_" + System.currentTimeMillis() + ".db").toString();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + testDbPath, "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        // Disable foreign key checks for testing (tests use standalone records)
        db.getConnection().prepareStatement("PRAGMA foreign_keys = OFF").execute();
        changeLogDao = new ChangeLogDao(db);
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
    public void testInsertAndFindByTaskId() {
        String taskId = "test_task_1";
        ChangeLogDao.ChangeLog log = ChangeLogDao.ChangeLog.create(
            taskId, 1, "WriteFile", "/path/to/file.txt", "CREATE",
            "abc123", "Created new file", "SUCCESS"
        );
        changeLogDao.insert(log);

        List<ChangeLogDao.ChangeLog> logs = changeLogDao.findByTaskId(taskId);
        assertEquals("Should have 1 log", 1, logs.size());
        assertEquals("Task ID should match", taskId, logs.get(0).taskId);
        assertEquals("Tool name should match", "WriteFile", logs.get(0).toolName);
        assertEquals("File path should match", "/path/to/file.txt", logs.get(0).filePath);
        assertEquals("Operation should match", "CREATE", logs.get(0).operation);
        assertEquals("Status should match", "SUCCESS", logs.get(0).status);
    }

    @Test
    public void testInsertMultipleLogsAndFindByTaskId() {
        String taskId = "test_task_2";

        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 1, "WriteFile", "/path/file1.txt", "CREATE",
            "hash1", "Created file1", "SUCCESS"
        ));
        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 2, "WriteFile", "/path/file2.txt", "MODIFY",
            "hash2", "Modified file2", "SUCCESS"
        ));
        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 3, "ReadFile", "/path/file1.txt", "READ",
            "hash1", "Read file1", "SUCCESS"
        ));

        List<ChangeLogDao.ChangeLog> logs = changeLogDao.findByTaskId(taskId);
        assertEquals("Should have 3 logs", 3, logs.size());
    }

    @Test
    public void testFindByTaskIdAndStep() {
        String taskId = "test_task_3";

        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 1, "WriteFile", "/path/file.txt", "CREATE",
            "hash1", "Step 1", "SUCCESS"
        ));
        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 2, "WriteFile", "/path/file.txt", "MODIFY",
            "hash2", "Step 2", "SUCCESS"
        ));

        List<ChangeLogDao.ChangeLog> step1Logs = changeLogDao.findByTaskIdAndStep(taskId, 1);
        assertEquals("Step 1 should have 1 log", 1, step1Logs.size());
        assertEquals("Step should be 1", Integer.valueOf(1), step1Logs.get(0).stepNumber);

        List<ChangeLogDao.ChangeLog> step2Logs = changeLogDao.findByTaskIdAndStep(taskId, 2);
        assertEquals("Step 2 should have 1 log", 1, step2Logs.size());
        assertEquals("Step should be 2", Integer.valueOf(2), step2Logs.get(0).stepNumber);
    }

    @Test
    public void testFindNonExistentTask() {
        List<ChangeLogDao.ChangeLog> logs = changeLogDao.findByTaskId("nonexistent_task");
        assertTrue("Should return empty list for non-existent task", logs.isEmpty());
    }

    @Test
    public void testChangeLogStatusValues() {
        String taskId = "status_test_task";

        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 1, "WriteFile", "/path/file.txt", "CREATE",
            "hash", "Success case", "SUCCESS"
        ));
        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 2, "WriteFile", "/path/file.txt", "MODIFY",
            "hash", "Failed case", "FAILED"
        ));
        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 3, "WriteFile", "/path/file.txt", "DELETE",
            "hash", "Rolled back case", "ROLLED_BACK"
        ));

        List<ChangeLogDao.ChangeLog> logs = changeLogDao.findByTaskId(taskId);
        assertEquals("Should have 3 logs", 3, logs.size());

        // Find by step number to verify correct values
        List<ChangeLogDao.ChangeLog> step1Logs = changeLogDao.findByTaskIdAndStep(taskId, 1);
        assertEquals("Step 1 should be SUCCESS", "SUCCESS", step1Logs.get(0).status);

        List<ChangeLogDao.ChangeLog> step2Logs = changeLogDao.findByTaskIdAndStep(taskId, 2);
        assertEquals("Step 2 should be FAILED", "FAILED", step2Logs.get(0).status);

        List<ChangeLogDao.ChangeLog> step3Logs = changeLogDao.findByTaskIdAndStep(taskId, 3);
        assertEquals("Step 3 should be ROLLED_BACK", "ROLLED_BACK", step3Logs.get(0).status);
    }

    @Test
    public void testChangeLogOperations() {
        String taskId = "ops_test_task";

        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 1, "WriteFile", "/path/new.txt", "CREATE",
            "hash1", "Created new file", "SUCCESS"
        ));
        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 2, "WriteFile", "/path/existing.txt", "MODIFY",
            "hash2", "Modified existing file", "SUCCESS"
        ));
        changeLogDao.insert(ChangeLogDao.ChangeLog.create(
            taskId, 3, "WriteFile", "/path/deleted.txt", "DELETE",
            "hash3", "Deleted file", "SUCCESS"
        ));

        // Find by step number to verify correct values
        List<ChangeLogDao.ChangeLog> step1Logs = changeLogDao.findByTaskIdAndStep(taskId, 1);
        assertEquals("Step 1 operation should be CREATE", "CREATE", step1Logs.get(0).operation);

        List<ChangeLogDao.ChangeLog> step2Logs = changeLogDao.findByTaskIdAndStep(taskId, 2);
        assertEquals("Step 2 operation should be MODIFY", "MODIFY", step2Logs.get(0).operation);

        List<ChangeLogDao.ChangeLog> step3Logs = changeLogDao.findByTaskIdAndStep(taskId, 3);
        assertEquals("Step 3 operation should be DELETE", "DELETE", step3Logs.get(0).operation);
    }

    @Test
    public void testContentHash() {
        String taskId = "hash_test_task";
        String content = "Test content for hashing";

        ChangeLogDao.ChangeLog log = ChangeLogDao.ChangeLog.create(
            taskId, 1, "WriteFile", "/path/file.txt", "CREATE",
            SnapshotDao.hash(content), "Hash test", "SUCCESS"
        );
        changeLogDao.insert(log);

        List<ChangeLogDao.ChangeLog> logs = changeLogDao.findByTaskId(taskId);
        assertNotNull("Content hash should not be null", logs.get(0).contentHash);
        assertFalse("Content hash should not be empty", logs.get(0).contentHash.isEmpty());
    }

    @Test
    public void testSummaryField() {
        String taskId = "summary_test_task";

        ChangeLogDao.ChangeLog log = ChangeLogDao.ChangeLog.create(
            taskId, 1, "WriteFile", "/path/file.txt", "CREATE",
            "hash", "Short summary", "SUCCESS"
        );
        changeLogDao.insert(log);

        List<ChangeLogDao.ChangeLog> logs = changeLogDao.findByTaskId(taskId);
        assertEquals("Summary should match", "Short summary", logs.get(0).summary);
    }
}
