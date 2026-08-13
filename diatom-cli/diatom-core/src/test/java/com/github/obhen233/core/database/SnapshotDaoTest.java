package com.github.obhen233.core.database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.core.database.HibernateDatabaseManager;

import static org.junit.Assert.*;

/**
 * SnapshotDao 测试用例
 */
public class SnapshotDaoTest {

    private DatabaseManager db;
    private SnapshotDao snapshotDao;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"), "diatom_snapshot_test_" + System.currentTimeMillis() + ".db").toString();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + testDbPath, "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        // Disable foreign key checks for testing (tests use standalone records)
        db.getConnection().prepareStatement("PRAGMA foreign_keys = OFF").execute();
        snapshotDao = new SnapshotDao(db);
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
    public void testCreateAndFindSnapshot() {
        String taskId = "snapshot_task_1";
        int snapshotId = snapshotDao.createSnapshot(taskId, "MANUAL", "Test snapshot", null);
        assertTrue("Snapshot ID should be positive", snapshotId > 0);

        SnapshotDao.Snapshot found = snapshotDao.findSnapshotById(snapshotId);
        assertNotNull("Snapshot should be found", found);
        assertEquals("Task ID should match", taskId, found.taskId);
        assertEquals("Snapshot type should be MANUAL", "MANUAL", found.snapshotType);
        assertEquals("Description should match", "Test snapshot", found.description);
    }

    @Test
    public void testCreateSnapshotWithParent() {
        String taskId = "snapshot_task_2";
        int parentId = snapshotDao.createSnapshot(taskId, "AUTO", "Parent snapshot", null);
        int childId = snapshotDao.createSnapshot(taskId, "CHECKPOINT", "Child snapshot", parentId);

        SnapshotDao.Snapshot child = snapshotDao.findSnapshotById(childId);
        assertNotNull("Child snapshot should be found", child);
        assertEquals("Parent ID should match", parentId, child.parentSnapshotId.intValue());
    }

    @Test
    public void testFindSnapshotsByTaskId() {
        String taskId = "snapshot_task_3";
        snapshotDao.createSnapshot(taskId, "AUTO", "Auto 1", null);
        snapshotDao.createSnapshot(taskId, "AUTO", "Auto 2", null);
        snapshotDao.createSnapshot(taskId, "MANUAL", "Manual 1", null);

        List<SnapshotDao.Snapshot> snapshots = snapshotDao.findSnapshotsByTaskId(taskId);
        assertEquals("Should have 3 snapshots", 3, snapshots.size());
    }

    @Test
    public void testGetLatestSnapshotId() {
        String taskId = "snapshot_task_4";
        int id1 = snapshotDao.createSnapshot(taskId, "AUTO", "First", null);
        int id2 = snapshotDao.createSnapshot(taskId, "AUTO", "Second", null);
        int id3 = snapshotDao.createSnapshot(taskId, "AUTO", "Third", null);

        int latestId = snapshotDao.getLatestSnapshotId(taskId);
        assertEquals("Latest should be the most recent", id3, latestId);
    }

    @Test
    public void testGetLatestSnapshotIdNonExistent() {
        int latestId = snapshotDao.getLatestSnapshotId("nonexistent_task");
        assertEquals("Should return -1 for non-existent task", -1, latestId);
    }

    @Test
    public void testCreateFileSnapshot() {
        String taskId = "file_snapshot_task_1";
        int snapshotId = snapshotDao.createSnapshot(taskId, "AUTO", "Test", null);

        SnapshotDao.FileSnapshot fileSnapshot = SnapshotDao.FileSnapshot.create(
            taskId, "/path/to/file.txt", "CREATE", "file content", null
        );
        int fileSnapshotId = snapshotDao.createFileSnapshot(fileSnapshot);
        assertTrue("File snapshot ID should be positive", fileSnapshotId > 0);

        snapshotDao.linkFileSnapshot(snapshotId, fileSnapshotId);
    }

    @Test
    public void testGetSnapshotsFiles() {
        String taskId = "linked_files_task";
        int snapshotId = snapshotDao.createSnapshot(taskId, "AUTO", "With files", null);

        SnapshotDao.FileSnapshot fs1 = SnapshotDao.FileSnapshot.create(
            taskId, "/path/file1.txt", "CREATE", "content1", null
        );
        SnapshotDao.FileSnapshot fs2 = SnapshotDao.FileSnapshot.create(
            taskId, "/path/file2.txt", "MODIFY", "content2", null
        );

        int fsId1 = snapshotDao.createFileSnapshot(fs1);
        int fsId2 = snapshotDao.createFileSnapshot(fs2);

        snapshotDao.linkFileSnapshot(snapshotId, fsId1);
        snapshotDao.linkFileSnapshot(snapshotId, fsId2);

        List<SnapshotDao.FileSnapshot> files = snapshotDao.getSnapshotsFiles(taskId, snapshotId);
        assertEquals("Should have 2 files in snapshot", 2, files.size());
    }

    @Test
    public void testFileSnapshotContentHash() {
        String taskId = "hash_task";
        String content = "Test content for hash";

        SnapshotDao.FileSnapshot fileSnapshot = SnapshotDao.FileSnapshot.create(
            taskId, "/path/file.txt", "CREATE", content, null
        );
        int fileSnapshotId = snapshotDao.createFileSnapshot(fileSnapshot);

        SnapshotDao.FileSnapshot found = snapshotDao.findFileSnapshotByHash(taskId, fileSnapshot.contentHash);
        assertNotNull("File snapshot should be found by hash", found);
        assertEquals("Content hash should match", fileSnapshot.contentHash, found.contentHash);
    }

    @Test
    public void testFileSnapshotOperations() {
        String taskId = "ops_task";

        SnapshotDao.FileSnapshot create = SnapshotDao.FileSnapshot.create(
            taskId, "/path/new.txt", "CREATE", "new content", null
        );
        SnapshotDao.FileSnapshot modify = SnapshotDao.FileSnapshot.create(
            taskId, "/path/existing.txt", "MODIFY", "modified content", null
        );
        SnapshotDao.FileSnapshot delete = SnapshotDao.FileSnapshot.create(
            taskId, "/path/deleted.txt", "DELETE", "deleted content", null
        );

        assertEquals("Operation should be CREATE", "CREATE", create.operation);
        assertEquals("Operation should be MODIFY", "MODIFY", modify.operation);
        assertEquals("Operation should be DELETE", "DELETE", delete.operation);
    }

    @Test
    public void testFileSnapshotDeltaCompression() {
        String taskId = "delta_task";
        int baseSnapshotId = 1;

        SnapshotDao.FileSnapshot delta = SnapshotDao.FileSnapshot.create(
            taskId, "/path/file.txt", "MODIFY", "delta content", baseSnapshotId
        );

        assertEquals("Content type should be delta", "delta", delta.contentType);
        assertEquals("Base snapshot ID should be set", baseSnapshotId, delta.baseSnapshotId.intValue());
        assertNotNull("Content should be set", delta.content);
    }

    @Test
    public void testDeleteSnapshot() {
        String taskId = "delete_task";
        int snapshotId = snapshotDao.createSnapshot(taskId, "MANUAL", "To be deleted", null);

        SnapshotDao.FileSnapshot fileSnapshot = SnapshotDao.FileSnapshot.create(
            taskId, "/path/file.txt", "CREATE", "content", null
        );
        int fileSnapshotId = snapshotDao.createFileSnapshot(fileSnapshot);
        snapshotDao.linkFileSnapshot(snapshotId, fileSnapshotId);

        snapshotDao.deleteSnapshot(snapshotId);

        SnapshotDao.Snapshot found = snapshotDao.findSnapshotById(snapshotId);
        assertNull("Snapshot should not exist after delete", found);
    }

    @Test
    public void testSnapshotTypes() {
        String taskId = "types_task";
        int auto = snapshotDao.createSnapshot(taskId, "AUTO", "Auto snapshot", null);
        int manual = snapshotDao.createSnapshot(taskId, "MANUAL", "Manual snapshot", null);
        int checkpoint = snapshotDao.createSnapshot(taskId, "CHECKPOINT", "Checkpoint snapshot", null);

        assertEquals("AUTO type should be AUTO", "AUTO", snapshotDao.findSnapshotById(auto).snapshotType);
        assertEquals("MANUAL type should be MANUAL", "MANUAL", snapshotDao.findSnapshotById(manual).snapshotType);
        assertEquals("CHECKPOINT type should be CHECKPOINT", "CHECKPOINT", snapshotDao.findSnapshotById(checkpoint).snapshotType);
    }

    @Test
    public void testHashMethod() {
        String content1 = "Test content 1";
        String content2 = "Test content 1";
        String content3 = "Test content 2";

        String hash1 = SnapshotDao.hash(content1);
        String hash2 = SnapshotDao.hash(content2);
        String hash3 = SnapshotDao.hash(content3);

        assertEquals("Same content should produce same hash", hash1, hash2);
        assertNotEquals("Different content should produce different hash", hash1, hash3);
        assertNotNull("Hash should not be null", hash1);
        assertEquals("Hash should be 64 characters (SHA-256 hex)", 64, hash1.length());
    }

    @Test
    public void testHashNullContent() {
        String hash = SnapshotDao.hash(null);
        assertNull("Hash of null should be null", hash);
    }
}
