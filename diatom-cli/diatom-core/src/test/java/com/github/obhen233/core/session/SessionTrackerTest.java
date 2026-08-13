package com.github.obhen233.core.session;

import com.github.obhen233.util.I18n;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * SessionTracker 测试用例
 * 对应 TEST_CASES.md 2. 会话追踪测试 (SessionTracker)
 */
public class SessionTrackerTest {

    private SessionTracker tracker;
    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        tracker = new SessionTracker();
        tempDir = Files.createTempDirectory("diatom-test");
        I18n.init("en");
    }

    @After
    public void tearDown() throws Exception {
        // Clean up any files created during tests
        tracker.clear();
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted((a, b) -> -a.compareTo(b))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception e) {}
                });
        }
    }

    @Test
    public void testHasChanges() {
        assertFalse("Should have no changes initially", tracker.hasChanges());

        tracker.recordFileCreated(tempDir.resolve("test.txt").toString());
        assertTrue("Should have changes after creating a file", tracker.hasChanges());
    }

    @Test
    public void testRecordFileCreated() {
        String path = tempDir.resolve("created.txt").toString();
        tracker.recordFileCreated(path);

        Set<String> created = tracker.getCreatedFiles();
        assertTrue("Created file should be in created set", created.contains(path));
        assertFalse("Created file should not be in modified set", tracker.getModifiedFiles().contains(path));
    }

    @Test
    public void testRecordFileModified() {
        String path = tempDir.resolve("modified.txt").toString();
        tracker.recordFileModified(path);

        Set<String> modified = tracker.getModifiedFiles();
        assertTrue("Modified file should be in modified set", modified.contains(path));
    }

    @Test
    public void testRecordFileDeleted() {
        String path = tempDir.resolve("deleted.txt").toString();
        tracker.recordFileDeleted(path);

        Set<String> deleted = tracker.getDeletedFiles();
        assertTrue("Deleted file should be in deleted set", deleted.contains(path));
    }

    @Test
    public void testRecordContentTracking() throws Exception {
        String path = tempDir.resolve("content.txt").toString();

        // Use recordChange to track content, then verify through undo
        tracker.recordChange("CREATE", path, null, "new content here");
        assertEquals(1, tracker.getUndoableCount());

        // Create the file
        Files.write(Paths.get(path), "new content here".getBytes());

        // Undo should delete the file
        String result = tracker.undoLastChange();
        assertTrue(result.contains("已删除") || result.contains("Deleted"));
    }

    @Test
    public void testGetUndoableCount() {
        assertEquals(0, tracker.getUndoableCount());

        tracker.recordChange("CREATE", tempDir.resolve("file1.txt").toString(), null, null);
        assertEquals(1, tracker.getUndoableCount());

        tracker.recordChange("MODIFY", tempDir.resolve("file2.txt").toString(), "old", "new");
        assertEquals(2, tracker.getUndoableCount());
    }

    @Test
    public void testUndoLastChange_Create() throws Exception {
        String path = tempDir.resolve("undo_create.txt").toString();

        // Record a create
        tracker.recordChange("CREATE", path, null, "content");
        assertEquals(1, tracker.getUndoableCount());

        // Create the file so undo can delete it
        Files.write(Paths.get(path), "content".getBytes());
        assertTrue(Files.exists(Paths.get(path)));

        // Undo
        String result = tracker.undoLastChange();
        assertTrue(result.contains("撤销"));
        assertFalse(Files.exists(Paths.get(path)));
        assertEquals(0, tracker.getUndoableCount());
    }

    @Test
    public void testUndoLastChange_Modify() throws Exception {
        String path = tempDir.resolve("undo_modify.txt").toString();

        // Create original file
        Files.write(Paths.get(path), "original content".getBytes());

        // Record a modify with old content
        String oldContent = "original content";
        String newContent = "modified content";
        tracker.recordChange("MODIFY", path, oldContent, newContent);
        assertEquals(1, tracker.getUndoableCount());

        // Modify the file
        Files.write(Paths.get(path), newContent.getBytes());
        assertEquals("modified content", new String(Files.readAllBytes(Paths.get(path))));

        // Undo
        String result = tracker.undoLastChange();
        assertTrue(result.contains("已恢复"));
        assertEquals("original content", new String(Files.readAllBytes(Paths.get(path))));
        assertEquals(0, tracker.getUndoableCount());
    }

    @Test
    public void testUndoLastChange_Delete() throws Exception {
        String path = tempDir.resolve("undo_delete.txt").toString();

        // Create file first
        Files.write(Paths.get(path), "content to delete".getBytes());

        // Record a delete with content
        String deletedContent = "content to delete";
        tracker.recordChange("DELETE", path, deletedContent, null);
        assertEquals(1, tracker.getUndoableCount());

        // Delete the file
        Files.deleteIfExists(Paths.get(path));
        assertFalse(Files.exists(Paths.get(path)));

        // Undo
        String result = tracker.undoLastChange();
        assertTrue(result.contains("已恢复"));
        assertTrue(Files.exists(Paths.get(path)));
        assertEquals(deletedContent, new String(Files.readAllBytes(Paths.get(path))));
    }

    @Test
    public void testUndoLastChange_NoChanges() {
        String result = tracker.undoLastChange();
        assertTrue(result.contains("没有可撤销"));
    }

    @Test
    public void testRevertChanges() throws Exception {
        // Create multiple files
        Path file1 = tempDir.resolve("revert1.txt");
        Path file2 = tempDir.resolve("revert2.txt");

        Files.write(file1, "content1".getBytes());
        Files.write(file2, "content2".getBytes());

        // Record changes
        tracker.recordChange("CREATE", file1.toString(), null, "content1");
        tracker.recordChange("CREATE", file2.toString(), null, "content2");

        assertTrue(Files.exists(file1));
        assertTrue(Files.exists(file2));

        // Revert
        String result = tracker.revertChanges();
        assertTrue(result.contains("已删除"));

        assertFalse(Files.exists(file1));
        assertFalse(Files.exists(file2));
        assertEquals(0, tracker.getUndoableCount());
        assertFalse(tracker.hasChanges());
    }

    @Test
    public void testBuildSummary_Empty() {
        String summary = tracker.buildSummary();
        assertTrue(summary.contains("No file changes") || summary.contains("无文件改动"));
    }

    @Test
    public void testBuildSummary_WithChanges() throws Exception {
        // Create files with content
        Path file1 = tempDir.resolve("summary1.txt");
        Path file2 = tempDir.resolve("summary2.txt");

        Files.write(file1, "line1\nline2\nline3\n".getBytes());
        Files.write(file2, "content".getBytes());

        tracker.recordFileCreated(file1.toString());
        tracker.recordNewContent(file1.toString(), "line1\nline2\nline3\n");
        tracker.recordFileModified(file2.toString());
        tracker.recordOriginalContent(file2.toString(), "old");
        tracker.recordNewContent(file2.toString(), "content");

        String summary = tracker.buildSummary();

        assertTrue("Summary should contain 'created' or 'Created'",
            summary.toLowerCase().contains("created") || summary.contains("新建"));
        assertTrue("Summary should contain 'modified' or 'Modified'",
            summary.toLowerCase().contains("modified") || summary.contains("修改"));
    }

    @Test
    public void testClear() {
        String path = tempDir.resolve("clear.txt").toString();
        tracker.recordFileCreated(path);
        tracker.recordChange("CREATE", path, null, "content");

        assertTrue(tracker.hasChanges());
        assertEquals(1, tracker.getUndoableCount());

        tracker.clear();

        assertFalse(tracker.hasChanges());
        assertEquals(0, tracker.getUndoableCount());
    }

    @Test
    public void testGetCreatedFiles() {
        tracker.recordFileCreated(tempDir.resolve("a.txt").toString());
        tracker.recordFileCreated(tempDir.resolve("b.txt").toString());

        Set<String> created = tracker.getCreatedFiles();
        assertEquals(2, created.size());
        assertTrue(created.contains(tempDir.resolve("a.txt").toString()));
        assertTrue(created.contains(tempDir.resolve("b.txt").toString()));
    }

    @Test
    public void testGetModifiedFiles() {
        tracker.recordFileModified(tempDir.resolve("m.txt").toString());

        Set<String> modified = tracker.getModifiedFiles();
        assertEquals(1, modified.size());
        assertTrue(modified.contains(tempDir.resolve("m.txt").toString()));
    }

    @Test
    public void testGetDeletedFiles() {
        tracker.recordFileDeleted(tempDir.resolve("d.txt").toString());

        Set<String> deleted = tracker.getDeletedFiles();
        assertEquals(1, deleted.size());
        assertTrue(deleted.contains(tempDir.resolve("d.txt").toString()));
    }

    // ==================== Checkpoint Integration Tests ====================

    @Test
    public void testBuildFileChangeSummary_Empty() {
        String summary = tracker.buildFileChangeSummary();
        assertNull("Empty tracker should return null", summary);
    }

    @Test
    public void testBuildFileChangeSummary_Created() {
        tracker.recordFileCreated("/path/new1.txt");
        tracker.recordFileCreated("/path/new2.txt");

        String summary = tracker.buildFileChangeSummary();
        assertNotNull("Summary should not be null when there are changes", summary);
        assertTrue("Summary should contain CREATED", summary.contains("CREATED"));
        assertTrue("Summary should contain new1.txt", summary.contains("new1.txt"));
        assertTrue("Summary should contain new2.txt", summary.contains("new2.txt"));
    }

    @Test
    public void testBuildFileChangeSummary_Modified() {
        tracker.recordFileModified("/path/existing.txt");

        String summary = tracker.buildFileChangeSummary();
        assertNotNull(summary);
        assertTrue("Summary should contain MODIFIED", summary.contains("MODIFIED"));
        assertTrue("Summary should contain existing.txt", summary.contains("existing.txt"));
    }

    @Test
    public void testBuildFileChangeSummary_Deleted() {
        tracker.recordFileDeleted("/path/deleted.txt");

        String summary = tracker.buildFileChangeSummary();
        assertNotNull(summary);
        assertTrue("Summary should contain DELETED", summary.contains("DELETED"));
        assertTrue("Summary should contain deleted.txt", summary.contains("deleted.txt"));
    }

    @Test
    public void testBuildFileChangeSummary_Mixed() {
        tracker.recordFileCreated("/path/new.java");
        tracker.recordFileModified("/path/modified.java");
        tracker.recordFileDeleted("/path/deleted.java");

        String summary = tracker.buildFileChangeSummary();
        assertNotNull(summary);
        assertTrue("Summary should contain CREATED", summary.contains("CREATED"));
        assertTrue("Summary should contain MODIFIED", summary.contains("MODIFIED"));
        assertTrue("Summary should contain DELETED", summary.contains("DELETED"));
        assertTrue("Summary should contain all files",
            summary.contains("new.java") && summary.contains("modified.java") && summary.contains("deleted.java"));
    }

    @Test
    public void testGetToolResultHashes_Empty() {
        String hashes = tracker.getToolResultHashes();
        assertNull("Empty change history should return null", hashes);
    }

    @Test
    public void testGetToolResultHashes_WithChanges() throws Exception {
        // recordChange populates changeHistory which getToolResultHashes depends on
        tracker.recordChange("CREATE", "/path/new1.txt", null, "new content 1");
        tracker.recordChange("MODIFY", "/path/modified.txt", "old content", "new content 2");

        String hashes = tracker.getToolResultHashes();
        assertNotNull("Hashes should not be null with change history", hashes);
        assertTrue("Hashes should be JSON array", hashes.startsWith("["));
    }
}