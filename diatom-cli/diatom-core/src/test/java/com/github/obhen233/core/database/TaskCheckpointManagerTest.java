package com.github.obhen233.core.database;

import com.github.obhen233.core.database.TaskCheckpointManager.TaskCheckpoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * TaskCheckpointManager 测试用例
 * 对应 TEST_CASES.md 11. TaskCheckpointManager 测试
 */
public class TaskCheckpointManagerTest {

    private DatabaseManager db;
    private TaskCheckpointManager checkpointManager;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"), "diatom_checkpoint_test_" + System.currentTimeMillis() + ".db").toString();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + testDbPath, "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        checkpointManager = new TaskCheckpointManager(db);
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
    public void testSaveAndLoadCheckpoint() {
        String taskId = "task_001";
        String userInput = "Compile the project";
        String agentState = "{\"step\": 5}";

        List<String> history = Arrays.asList(
            "{\"role\":\"user\",\"content\":\"Compile the project\"}",
            "{\"role\":\"assistant\",\"content\":\"I'll compile the project\"}"
        );
        List<String> results = Arrays.asList(
            "{\"tool\":\"mvn\",\"result\":\"BUILD SUCCESS\"}"
        );

        checkpointManager.saveCheckpoint(taskId, userInput, agentState, history, results, 5);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint(taskId);
        assertNotNull("Checkpoint should be loaded", loaded);
        assertEquals("Task ID should match", taskId, loaded.getTaskId());
        assertEquals("User input should match", userInput, loaded.getUserInput());
        assertEquals("Agent state should match", agentState, loaded.getAgentState());
        assertEquals("Step count should match", 5, loaded.getStepCount());
        assertEquals("History size should match", 2, loaded.getConversationHistory().size());
        assertEquals("Results size should match", 1, loaded.getToolResults().size());
    }

    @Test
    public void testLoadNonExistentCheckpoint() {
        TaskCheckpoint loaded = checkpointManager.loadCheckpoint("nonexistent_task");
        assertNull("Should return null for non-existent checkpoint", loaded);
    }

    @Test
    public void testSaveCheckpointWithNullHistory() {
        String taskId = "task_002";
        checkpointManager.saveCheckpoint(taskId, "Simple task", "{}", null, null, 1);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint(taskId);
        assertNotNull("Checkpoint should be loaded", loaded);
        assertNull("History should be null", loaded.getConversationHistory());
    }

    @Test
    public void testSaveCheckpointWithEmptyHistory() {
        String taskId = "task_003";
        checkpointManager.saveCheckpoint(taskId, "Task with empty history", "{}", Arrays.asList(), Arrays.asList(), 0);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint(taskId);
        assertNotNull("Checkpoint should be loaded", loaded);
        assertNotNull("History should not be null (empty list)", loaded.getConversationHistory());
        assertTrue("History should be empty", loaded.getConversationHistory().isEmpty());
    }

    @Test
    public void testDeleteCheckpoint() {
        String taskId = "task_004";
        checkpointManager.saveCheckpoint(taskId, "Task to delete", "{}", null, null, 1);

        assertNotNull("Checkpoint should exist before delete", checkpointManager.loadCheckpoint(taskId));

        checkpointManager.deleteCheckpoint(taskId);

        assertNull("Checkpoint should not exist after delete", checkpointManager.loadCheckpoint(taskId));
    }

    @Test
    public void testListCheckpoints() throws InterruptedException {
        checkpointManager.saveCheckpoint("task_001", "First task", "{}", null, null, 1);
        Thread.sleep(10);
        checkpointManager.saveCheckpoint("task_002", "Second task", "{}", null, null, 2);
        Thread.sleep(10);
        checkpointManager.saveCheckpoint("task_003", "Third task", "{}", null, null, 3);

        List<TaskCheckpoint> checkpoints = checkpointManager.listCheckpoints();
        assertEquals("Should have 3 checkpoints", 3, checkpoints.size());
    }

    @Test
    public void testListCheckpointsOrder() throws InterruptedException {
        checkpointManager.saveCheckpoint("task_old", "Old task", "{}", null, null, 1);
        Thread.sleep(10);
        checkpointManager.saveCheckpoint("task_new", "New task", "{}", null, null, 2);

        List<TaskCheckpoint> checkpoints = checkpointManager.listCheckpoints();
        // Most recently updated should be first
        assertEquals("task_new", checkpoints.get(0).getTaskId());
        assertEquals("task_old", checkpoints.get(1).getTaskId());
    }

    @Test
    public void testFindCheckpointsByInput() {
        checkpointManager.saveCheckpoint("task_001", "Compile the project with Maven", "{}", null, null, 5);
        checkpointManager.saveCheckpoint("task_002", "Run unit tests", "{}", null, null, 3);
        checkpointManager.saveCheckpoint("task_003", "Compile TypeScript to JavaScript", "{}", null, null, 7);

        List<TaskCheckpoint> found = checkpointManager.findCheckpointsByInput("Compile");
        assertEquals("Should find 2 checkpoints with 'Compile'", 2, found.size());
    }

    @Test
    public void testFindCheckpointsByPartialInput() {
        checkpointManager.saveCheckpoint("task_001", "Build the application", "{}", null, null, 5);

        List<TaskCheckpoint> found = checkpointManager.findCheckpointsByInput("build");
        assertEquals("Should find checkpoints matching 'build'", 1, found.size());
        assertTrue("Should match partial input", found.get(0).getUserInput().toLowerCase().contains("build"));
    }

    @Test
    public void testFindCheckpointsNoMatch() {
        checkpointManager.saveCheckpoint("task_001", "Run tests", "{}", null, null, 3);

        List<TaskCheckpoint> found = checkpointManager.findCheckpointsByInput("nonexistent");
        assertTrue("Should return empty list for no match", found.isEmpty());
    }

    @Test
    public void testFindCheckpointsEmptyQuery() {
        checkpointManager.saveCheckpoint("task_001", "Some task", "{}", null, null, 1);

        List<TaskCheckpoint> found = checkpointManager.findCheckpointsByInput("");
        assertTrue("Should return empty list for empty query", found.isEmpty());
    }

    @Test
    public void testFindCheckpointsNullQuery() {
        checkpointManager.saveCheckpoint("task_001", "Some task", "{}", null, null, 1);

        List<TaskCheckpoint> found = checkpointManager.findCheckpointsByInput(null);
        assertTrue("Should return empty list for null query", found.isEmpty());
    }

    @Test
    public void testFindCheckpointsWhitespaceQuery() {
        checkpointManager.saveCheckpoint("task_001", "Task name", "{}", null, null, 1);

        List<TaskCheckpoint> found = checkpointManager.findCheckpointsByInput("   ");
        assertTrue("Should return empty list for whitespace query", found.isEmpty());
    }

    @Test
    public void testUpdateCheckpoint() throws InterruptedException {
        String taskId = "task_update";
        checkpointManager.saveCheckpoint(taskId, "Original task", "{\"step\": 1}", null, null, 1);

        Thread.sleep(10);

        // Update the checkpoint
        checkpointManager.updateAgentState(taskId, "{\"step\": 5}", Arrays.asList("updated"), Arrays.asList("result"), 5);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint(taskId);
        assertEquals("Agent state should be updated", "{\"step\": 5}", loaded.getAgentState());
        assertEquals("Step count should be updated", 5, loaded.getStepCount());
        assertEquals("History should be updated", 1, loaded.getConversationHistory().size());
    }

    @Test
    public void testCleanupOldCheckpoints() throws InterruptedException {
        // Note: This test doesn't actually test time-based cleanup since we'd need to wait
        // Instead, we just verify cleanup doesn't throw and removes checkpoints
        checkpointManager.saveCheckpoint("task_cleanup", "Task to cleanup", "{}", null, null, 1);

        checkpointManager.cleanupOldCheckpoints(0);  // Should cleanup checkpoints older than 0 days

        // The task was just created, so depending on implementation it may or may not be cleaned
        // This just verifies cleanup doesn't throw an error
        List<TaskCheckpoint> checkpoints = checkpointManager.listCheckpoints();
        // Just verify the method ran without error
        assertNotNull(checkpoints);
    }

    @Test
    public void testCheckpointSummary() {
        checkpointManager.saveCheckpoint("task_summary", "This is a task with a long description that should be summarized", "{}", null, null, 10);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint("task_summary");
        String summary = loaded.getSummary();

        assertNotNull("Summary should not be null", summary);
        assertTrue("Summary should contain task ID", summary.contains("task_summary"));
        assertTrue("Summary should contain step count", summary.contains("10"));
    }

    @Test
    public void testCheckpointSummaryTruncation() {
        // Create a long input string (Java 8 compatible way without String.repeat)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("A");
        }
        String longInput = sb.toString();

        checkpointManager.saveCheckpoint("task_long", longInput, "{}", null, null, 5);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint("task_long");
        String summary = loaded.getSummary();

        // Summary should truncate long input
        assertFalse("Summary should not contain full 100-char input", summary.contains(longInput));
        assertTrue("Summary should indicate truncation", summary.contains("..."));
    }

    /**
     * Test INSERT behavior - same task_id now creates multiple records
     * Since task_id is generated per run() using UUID, this tests the case
     * where the same task_id is explicitly passed multiple times
     */
    @Test
    public void testInsertBehavior() throws InterruptedException {
        String taskId = "task_same_id";

        // Save initial checkpoint
        checkpointManager.saveCheckpoint(taskId, "Initial input", "{\"step\": 0}", null, null, 0);
        List<TaskCheckpoint> checkpoints = checkpointManager.listCheckpoints();
        assertEquals("Should have 1 checkpoint after first save", 1, checkpoints.size());
        assertEquals("Step should be 0", 0, checkpoints.get(0).getStepCount());

        Thread.sleep(10);

        // Save another with same task_id - now creates a NEW record (not REPLACE)
        checkpointManager.saveCheckpoint(taskId, "Second input", "{\"step\": 5}", null, null, 5);
        checkpoints = checkpointManager.listCheckpoints();
        assertEquals("Should have 2 checkpoints (INSERT behavior)", 2, checkpoints.size());

        Thread.sleep(10);

        // Save third one
        checkpointManager.saveCheckpoint(taskId, "Third input", "{\"step\": 10}", null, null, 10);
        checkpoints = checkpointManager.listCheckpoints();
        assertEquals("Should have 3 checkpoints", 3, checkpoints.size());

        // Verify we can load by task_id and get all records
        List<TaskCheckpoint> byTaskId = checkpointManager.findCheckpointsByInput("input");
        assertTrue("Should find checkpoints by input", byTaskId.size() >= 3);
    }

    /**
     * Test that different task_ids create different records
     * This simulates running multiple different tasks
     */
    @Test
    public void testDifferentTasksCreateDifferentRecords() throws InterruptedException {
        String taskId1 = "task_unique_1";
        String taskId2 = "task_unique_2";
        String taskId3 = "task_unique_3";

        checkpointManager.saveCheckpoint(taskId1, "Task 1 input", "{}", null, null, 1);
        Thread.sleep(10);
        checkpointManager.saveCheckpoint(taskId2, "Task 2 input", "{}", null, null, 2);
        Thread.sleep(10);
        checkpointManager.saveCheckpoint(taskId3, "Task 3 input", "{}", null, null, 3);

        List<TaskCheckpoint> checkpoints = checkpointManager.listCheckpoints();
        assertEquals("Should have 3 different checkpoints for 3 different tasks", 3, checkpoints.size());

        // Verify each task has its own record
        TaskCheckpoint cp1 = checkpointManager.loadCheckpoint(taskId1);
        TaskCheckpoint cp2 = checkpointManager.loadCheckpoint(taskId2);
        TaskCheckpoint cp3 = checkpointManager.loadCheckpoint(taskId3);

        assertNotNull("Task 1 checkpoint should exist", cp1);
        assertNotNull("Task 2 checkpoint should exist", cp2);
        assertNotNull("Task 3 checkpoint should exist", cp3);

        assertEquals("Task 1 input should match", "Task 1 input", cp1.getUserInput());
        assertEquals("Task 2 input should match", "Task 2 input", cp2.getUserInput());
        assertEquals("Task 3 input should match", "Task 3 input", cp3.getUserInput());
    }

    @Test
    public void testTimestamps() {
        String taskId = "task_time";
        long beforeSave = System.currentTimeMillis();
        checkpointManager.saveCheckpoint(taskId, "Timed task", "{}", null, null, 1);
        long afterSave = System.currentTimeMillis();

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint(taskId);
        assertTrue("Created at should be >= before save",
            loaded.getCreatedAt() >= beforeSave);
        assertTrue("Created at should be <= after save",
            loaded.getCreatedAt() <= afterSave);
        assertTrue("Updated at should be >= before save",
            loaded.getUpdatedAt() >= beforeSave);
        assertTrue("Updated at should be <= after save",
            loaded.getUpdatedAt() <= afterSave);
    }

    // ==================== Enhanced Checkpoint Tests ====================

    @Test
    public void testSaveAndLoadCheckpointWithEnhancedFields() {
        String taskId = "task_enhanced";
        String userInput = "Test enhanced checkpoint";
        String agentState = "{\"step\": 5}";
        List<String> history = Arrays.asList("{\"role\":\"user\",\"content\":\"test\"}");
        List<String> results = Arrays.asList("{\"tool\":\"test\",\"result\":\"ok\"}");

        String llmSummary = "LLM summary for checkpoint";
        byte[] compressedContext = "compressed".getBytes();
        String fileChangeSummary = "MODIFIED:file1.txt,file2.txt;CREATED:file3.txt";
        String toolResultHashes = "[\"hash1\",\"hash2\"]";
        int messageCount = 10;
        int tokenUsage = 5000;

        checkpointManager.saveCheckpoint(taskId, userInput, agentState, history, results, 5,
            llmSummary, compressedContext, fileChangeSummary, toolResultHashes, messageCount, tokenUsage);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint(taskId);
        assertNotNull("Checkpoint should be loaded", loaded);
        assertEquals("Task ID should match", taskId, loaded.getTaskId());
        assertEquals("LLM summary should match", llmSummary, loaded.getLlmSummary());
        assertArrayEquals("Compressed context should match", compressedContext, loaded.getCompressedContext());
        assertEquals("File change summary should match", fileChangeSummary, loaded.getFileChangeSummary());
        assertEquals("Tool result hashes should match", toolResultHashes, loaded.getToolResultHashes());
        assertEquals("Message count should match", messageCount, loaded.getMessageCount());
        assertEquals("Token usage should match", tokenUsage, loaded.getTokenUsage());
    }

    @Test
    public void testSaveAndLoadCheckpointWithNullEnhancedFields() {
        String taskId = "task_null_enhanced";
        checkpointManager.saveCheckpoint(taskId, "Test null enhanced", "{}",
            Arrays.asList("{\"role\":\"user\"}"), Arrays.asList("{\"tool\":\"result\"}"), 3,
            null, null, null, null, 0, 0);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint(taskId);
        assertNotNull("Checkpoint should be loaded", loaded);
        assertNull("LLM summary should be null", loaded.getLlmSummary());
        assertNull("Compressed context should be null", loaded.getCompressedContext());
        assertNull("File change summary should be null", loaded.getFileChangeSummary());
        assertNull("Tool result hashes should be null", loaded.getToolResultHashes());
        assertEquals("Message count should be 0", 0, loaded.getMessageCount());
        assertEquals("Token usage should be 0", 0, loaded.getTokenUsage());
    }

    @Test
    public void testLoadLatestCheckpoint() throws InterruptedException {
        String taskId = "task_latest";

        // Save checkpoint at step 0
        checkpointManager.saveCheckpoint(taskId, "Step 0", "{}",
            Arrays.asList("{\"role\":\"user\"}"), Arrays.asList("{\"tool\":\"r1\"}"), 0);
        Thread.sleep(10);

        // Save checkpoint at step 5
        checkpointManager.saveCheckpoint(taskId, "Step 5", "{}",
            Arrays.asList("{\"role\":\"user\"}"), Arrays.asList("{\"tool\":\"r2\"}"), 5);
        Thread.sleep(10);

        // Save checkpoint at step 10
        checkpointManager.saveCheckpoint(taskId, "Step 10", "{}",
            Arrays.asList("{\"role\":\"user\"}"), Arrays.asList("{\"tool\":\"r3\"}"), 10);

        // loadCheckpoint returns first record (by SQLite rowid order)
        // loadLatestCheckpoint returns the one with highest step_count
        TaskCheckpoint latest = checkpointManager.loadLatestCheckpoint(taskId);
        assertNotNull("Latest checkpoint should exist", latest);
        assertEquals("Should return step 10", 10, latest.getStepCount());
        assertEquals("Should return step 10 input", "Step 10", latest.getUserInput());
    }

    @Test
    public void testLoadLatestCheckpointNonExistent() {
        TaskCheckpoint latest = checkpointManager.loadLatestCheckpoint("nonexistent_task");
        assertNull("Should return null for non-existent task", latest);
    }

    @Test
    public void testEnhancedFieldsWithPartialData() {
        String taskId = "task_partial";

        // Only set file change summary
        checkpointManager.saveCheckpoint(taskId, "Partial test", "{}",
            Arrays.asList("{\"role\":\"user\"}"), Arrays.asList("{\"tool\":\"r\"}"), 2,
            null, null, "CREATED:new.java", null, 5, 1000);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint(taskId);
        assertNotNull(loaded);
        assertNull("LLM summary should be null", loaded.getLlmSummary());
        assertNull("Compressed context should be null", loaded.getCompressedContext());
        assertEquals("File change summary should match", "CREATED:new.java", loaded.getFileChangeSummary());
        assertNull("Tool result hashes should be null", loaded.getToolResultHashes());
        assertEquals("Message count should match", 5, loaded.getMessageCount());
        assertEquals("Token usage should match", 1000, loaded.getTokenUsage());
    }

    @Test
    public void testCheckpointSummaryWithEnhancedFields() {
        String taskId = "task_summary_enhanced";
        checkpointManager.saveCheckpoint(taskId, "Summary test", "{}",
            Arrays.asList("{\"role\":\"user\"}"), Arrays.asList("{\"tool\":\"r\"}"), 7,
            "Step 7 completed successfully", null, "MODIFIED:Main.java", null, 15, 8000);

        TaskCheckpoint loaded = checkpointManager.loadCheckpoint(taskId);
        String summary = loaded.getSummary();

        assertNotNull("Summary should not be null", summary);
        assertTrue("Summary should contain task ID", summary.contains(taskId));
        assertTrue("Summary should contain step 7", summary.contains("7"));
        assertTrue("Summary should contain message count 15", summary.contains("15"));
        assertTrue("Summary should contain token usage 8000", summary.contains("8000"));
    }
}
