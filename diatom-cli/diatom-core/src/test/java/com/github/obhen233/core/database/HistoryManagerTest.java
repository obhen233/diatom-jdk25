package com.github.obhen233.core.database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;

import static org.junit.Assert.*;

/**
 * HistoryManager 测试用例
 * 对应 TEST_CASES.md 9. HistoryManager 测试
 */
public class HistoryManagerTest {

    private DatabaseManager db;
    private HistoryManager history;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"), "diatom_history_test_" + System.currentTimeMillis() + ".db").toString();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + testDbPath, "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        history = new HistoryManager(db);
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
    public void testSaveAndRetrieveCommand() {
        history.saveCommand("ls -la");
        history.saveCommand("git status");

        List<String> recent = history.getRecentCommands(10);
        assertTrue("Should contain ls -la", recent.contains("ls -la"));
        assertTrue("Should contain git status", recent.contains("git status"));
    }

    @Test
    public void testDuplicateCommandNotSaved() {
        history.saveCommand("ls -la");
        history.saveCommand("ls -la");  // Same command, should not be saved twice

        List<String> recent = history.getRecentCommands(10);
        long count = recent.stream().filter(c -> c.equals("ls -la")).count();
        assertEquals("Duplicate consecutive command should not be saved", 1, count);
    }

    @Test
    public void testGetRecentCommandsOrder() throws InterruptedException {
        history.saveCommand("first");
        Thread.sleep(10);  // Ensure different timestamp
        history.saveCommand("second");
        Thread.sleep(10);
        history.saveCommand("third");

        List<String> recent = history.getRecentCommands(10);
        assertEquals("Most recent should be first", "third", recent.get(0));
        assertEquals("Second most recent should be second", "second", recent.get(1));
        assertEquals("Third most recent should be first", "first", recent.get(2));
    }

    @Test
    public void testGetRecentCommandsLimit() {
        for (int i = 0; i < 20; i++) {
            history.saveCommand("command" + i);
        }

        List<String> recent = history.getRecentCommands(5);
        assertEquals("Should return at most 5 commands", 5, recent.size());
    }

    @Test
    public void testSearchCommands() {
        history.saveCommand("git commit -m 'fix bug'");
        history.saveCommand("git status");
        history.saveCommand("git push origin main");
        history.saveCommand("ls -la");

        List<String> results = history.searchCommands("git", 10);
        assertEquals("Should find 3 git commands", 3, results.size());
        assertTrue("Should contain git push", results.stream().anyMatch(c -> c.contains("push")));
    }

    @Test
    public void testSearchCommandsPartialMatch() {
        history.saveCommand("git commit -m 'fix bug'");
        history.saveCommand("git status");

        List<String> results = history.searchCommands("commit", 10);
        assertEquals("Should find commit command", 1, results.size());
        assertTrue("Should contain commit", results.get(0).contains("commit"));
    }

    @Test
    public void testSearchCommandsNoMatch() {
        history.saveCommand("ls -la");
        history.saveCommand("git status");

        List<String> results = history.searchCommands("nonexistent", 10);
        assertTrue("Should return empty list for no match", results.isEmpty());
    }

    @Test
    public void testClearSessionHistory() {
        history.saveCommand("cmd1");
        history.saveCommand("cmd2");

        assertTrue("Should have history", history.getHistorySize() > 0);

        history.clearSessionHistory();

        assertEquals("Session history should be cleared", 0, history.getHistorySize());
    }

    @Test
    public void testClearAllHistory() {
        history.saveCommand("cmd1");
        history.saveCommand("cmd2");

        assertTrue("Should have history", history.getHistorySize() > 0);

        history.clearAllHistory();

        assertEquals("All history should be cleared", 0, history.getHistorySize());
    }

    @Test
    public void testGetHistorySize() {
        assertEquals("Should start with 0", 0, history.getHistorySize());

        history.saveCommand("cmd1");
        assertEquals("Should be 1 after first command", 1, history.getHistorySize());

        history.saveCommand("cmd2");
        assertEquals("Should be 2 after second command", 2, history.getHistorySize());
    }

    @Test
    public void testNullInputNotSaved() {
        history.saveCommand(null);
        assertEquals("Null command should not be saved", 0, history.getHistorySize());
    }

    @Test
    public void testEmptyInputNotSaved() {
        history.saveCommand("");
        assertEquals("Empty command should not be saved", 0, history.getHistorySize());
    }

    @Test
    public void testWhitespaceInputNotSaved() {
        history.saveCommand("   ");
        assertEquals("Whitespace command should not be saved", 0, history.getHistorySize());
    }

    @Test
    public void testGetSessionId() {
        String sessionId = history.getSessionId();
        assertNotNull("Session ID should not be null", sessionId);
        assertTrue("Session ID should start with session_", sessionId.startsWith("session_"));
    }

    @Test
    public void testSessionIdFormat() {
        // Two sessions created at different times should have different IDs
        String sessionId1 = history.getSessionId();
        try {
            Thread.sleep(2);  // Sleep 2ms to ensure different timestamp
        } catch (InterruptedException e) {
            // ignore
        }
        HistoryManager history2 = new HistoryManager(db);
        String sessionId2 = history2.getSessionId();

        // Session IDs should be different (different timestamps)
        assertNotEquals("Different sessions should have different IDs", sessionId1, sessionId2);
    }
}
