package com.github.obhen233.core.mcp.server;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.SqliteDatabaseManager;
import com.github.obhen233.core.database.TaskCheckpointManager;
import com.github.obhen233.core.tool.Tool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Ignore;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * MCP 服务器测试用例
 * 对应 TEST_CASES.md 12. MCP 服务器测试
 */
public class McpServersTest {

    private DatabaseManager db;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"), "diatom_mcp_test_" + System.currentTimeMillis() + ".db").toString();
        db = new SqliteDatabaseManager(testDbPath);
        db.initialize();
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


    // ==================== SystemMcpServer Tests ====================

    @Test
    public void testSystemMcpServerNameAndDescription() {
        SystemMcpServer systemServer = new SystemMcpServer();
        assertEquals("system", systemServer.getName());
        assertNotNull(systemServer.getDescription());
        assertTrue("Description should indicate high risk", systemServer.getDescription().contains("HIGH RISK"));
    }

    @Test
    public void testSystemMcpServerListTools() {
        SystemMcpServer systemServer = new SystemMcpServer();
        Map<String, Tool> tools = systemServer.listTools();

        assertNotNull("Tools should not be null", tools);
        assertTrue("Should have find_software tool", tools.containsKey("find_software"));
        assertTrue("Should have detect_shell tool", tools.containsKey("detect_shell"));
        assertTrue("Should have get_system_info tool", tools.containsKey("get_system_info"));
        assertTrue("Should have get_java_home tool", tools.containsKey("get_java_home"));
        assertTrue("Should have get_git_path tool", tools.containsKey("get_git_path"));
    }

    @Test
    public void testSystemMcpServerGetSystemInfo() {
        SystemMcpServer systemServer = new SystemMcpServer();
        String result = systemServer.callTool("get_system_info", "{}");

        assertNotNull("System info should not be null", result);
        assertTrue("Result should be JSON", result.startsWith("{") || result.contains("\"os\""));
        assertTrue("Result should contain OS info", result.toLowerCase().contains("os"));
        assertTrue("Result should contain Java version", result.toLowerCase().contains("java"));
    }

    @Test
    public void testSystemMcpServerGetJavaHome() {
        SystemMcpServer systemServer = new SystemMcpServer();
        String result = systemServer.callTool("get_java_home", "{}");

        assertNotNull("Java home result should not be null", result);
        // Result should be JSON with success or error
        assertTrue("Result should be JSON format", result.contains("{") && result.contains("}"));
    }

    @Test
    public void testSystemMcpServerGetGitPath() {
        SystemMcpServer systemServer = new SystemMcpServer();
        String result = systemServer.callTool("get_git_path", "{}");

        assertNotNull("Git path result should not be null", result);
        assertTrue("Result should be JSON format", result.contains("{") && result.contains("}"));
    }

    @Test
    public void testSystemMcpServerFindSoftware() {
        SystemMcpServer systemServer = new SystemMcpServer();
        String result = systemServer.callTool("find_software", "{\"name\":\"java\"}");

        assertNotNull("Find software result should not be null", result);
        assertTrue("Result should be JSON format", result.contains("{") && result.contains("}"));
    }

    @Test
    public void testSystemMcpServerFindSoftwareEmptyName() {
        SystemMcpServer systemServer = new SystemMcpServer();
        String result = systemServer.callTool("find_software", "{}");

        assertNotNull("Result should not be null", result);
        // Should return error for empty name
        assertTrue("Should indicate name is required", result.contains("error") || result.contains("required"));
    }

    @Test
    public void testSystemMcpServerDetectShell() {
        SystemMcpServer systemServer = new SystemMcpServer();
        String result = systemServer.callTool("detect_shell", "{}");

        assertNotNull("Detect shell result should not be null", result);
        assertTrue("Result should be JSON format", result.contains("{") && result.contains("}"));
        assertTrue("Result should contain shellType", result.contains("shellType"));
    }

    @Test
    public void testSystemMcpServerUnknownTool() {
        SystemMcpServer systemServer = new SystemMcpServer();
        String result = systemServer.callTool("unknown_tool", "{}");

        assertTrue("Should return error for unknown tool", result.contains("error") || result.contains("Unknown tool"));
    }

    @Test
    public void testSystemMcpServerToolDescriptions() {
        SystemMcpServer systemServer = new SystemMcpServer();
        Map<String, Tool> tools = systemServer.listTools();

        assertTrue("find_software should be marked HIGH RISK",
            tools.get("find_software").getDescription().contains("HIGH RISK"));
        assertTrue("get_system_info should be marked HIGH RISK",
            tools.get("get_system_info").getDescription().contains("HIGH RISK"));
    }

    // ==================== CheckpointMcpServer Tests ====================

    @Test
    public void testCheckpointMcpServerNameAndDescription() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);
        assertEquals("checkpoint", checkpointServer.getName());
        assertNotNull(checkpointServer.getDescription());
        assertTrue("Description should indicate TASK",
            checkpointServer.getDescription().contains("[TASK]"));
    }

    @Test
    public void testCheckpointMcpServerListTools() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);
        Map<String, Tool> tools = checkpointServer.listTools();

        assertNotNull("Tools should not be null", tools);
        assertTrue("Should have list_checkpoints tool", tools.containsKey("list_checkpoints"));
        assertTrue("Should have search_checkpoints tool", tools.containsKey("search_checkpoints"));
        assertTrue("Should have get_checkpoint_detail tool", tools.containsKey("get_checkpoint_detail"));
    }

    @Test
    public void testCheckpointMcpServerListCheckpoints() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);

        // First save a checkpoint
        TaskCheckpointManager cm = new TaskCheckpointManager(db);
        cm.saveCheckpoint("test_task_1", "Test task 1", "{}", null, null, 5);
        cm.saveCheckpoint("test_task_2", "Test task 2", "{}", null, null, 3);

        String result = checkpointServer.callTool("list_checkpoints", "{}");

        assertNotNull("List checkpoints result should not be null", result);
        assertTrue("Result should be JSON", result.contains("{"));
        assertTrue("Result should indicate success", result.contains("success"));
        assertTrue("Result should contain checkpoints array", result.contains("checkpoints"));
    }

    @Test
    public void testCheckpointMcpServerListCheckpointsEmpty() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);

        String result = checkpointServer.callTool("list_checkpoints", "{}");

        assertNotNull("Result should not be null", result);
        assertTrue("Result should indicate success", result.contains("success") || result.contains("message"));
    }

    @Test
    public void testCheckpointMcpServerSearchCheckpoints() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);

        // Save a checkpoint first
        TaskCheckpointManager cm = new TaskCheckpointManager(db);
        cm.saveCheckpoint("search_task", "Compile the project", "{}", null, null, 5);

        String result = checkpointServer.callTool("search_checkpoints", "{\"query\":\"Compile\"}");

        assertNotNull("Search result should not be null", result);
        assertTrue("Result should be JSON", result.contains("{"));
    }

    @Test
    public void testCheckpointMcpServerSearchCheckpointsEmptyQuery() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);

        String result = checkpointServer.callTool("search_checkpoints", "{\"query\":\"\"}");

        assertNotNull("Result should not be null", result);
        assertTrue("Should indicate query is required", result.contains("error") || result.contains("required"));
    }

    @Test
    public void testCheckpointMcpServerGetCheckpointDetail() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);

        // Save a checkpoint first
        TaskCheckpointManager cm = new TaskCheckpointManager(db);
        cm.saveCheckpoint("detail_task", "Detail test task", "{\"step\":3}", null, null, 3);

        String result = checkpointServer.callTool("get_checkpoint_detail", "{\"task_id\":\"detail_task\"}");

        assertNotNull("Detail result should not be null", result);
        assertTrue("Result should be JSON", result.contains("{"));
    }

    @Test
    public void testCheckpointMcpServerGetCheckpointDetailNotFound() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);

        String result = checkpointServer.callTool("get_checkpoint_detail", "{\"task_id\":\"nonexistent\"}");

        assertNotNull("Result should not be null", result);
        assertTrue("Should indicate checkpoint not found", result.contains("not found") || result.contains("error"));
    }

    @Test
    public void testCheckpointMcpServerGetCheckpointDetailMissingId() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);

        String result = checkpointServer.callTool("get_checkpoint_detail", "{}");

        assertNotNull("Result should not be null", result);
        assertTrue("Should indicate task_id is required", result.contains("error") || result.contains("required"));
    }

    @Test
    public void testCheckpointMcpServerUnknownTool() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);

        String result = checkpointServer.callTool("unknown_checkpoint_tool", "{}");

        assertTrue("Should return error for unknown tool", result.contains("error") || result.contains("Unknown tool"));
    }

    @Test
    public void testCheckpointMcpServerToolDescriptions() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);
        Map<String, Tool> tools = checkpointServer.listTools();

        assertTrue("list_checkpoints should mention [TASK]",
            tools.get("list_checkpoints").getDescription().contains("[TASK]"));
        assertTrue("search_checkpoints should mention [TASK]",
            tools.get("search_checkpoints").getDescription().contains("[TASK]"));
        assertTrue("get_checkpoint_detail should mention [TASK]",
            tools.get("get_checkpoint_detail").getDescription().contains("[TASK]"));
    }

    @Test
    public void testCheckpointMcpServerInvalidArgs() {
        CheckpointMcpServer checkpointServer = new CheckpointMcpServer(db);

        // Invalid JSON should be handled gracefully
        String result = checkpointServer.callTool("search_checkpoints", "not valid json");

        assertNotNull("Result should not be null even with invalid args", result);
        // Should return error for invalid args
        assertTrue("Should handle invalid args gracefully",
            result.contains("error") || result.contains("Invalid"));
    }

    // ==================== FilesystemMcpServer Tests ====================

    @Test
    public void testFilesystemMcpServerReadFile() throws Exception {
        // Create a temp file with known content
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("diatom_test_", ".txt");
        try {
            String[] lines = {"line one", "line two", "line three", "line four", "line five"};
            java.nio.file.Files.write(tempFile, java.util.Arrays.asList(lines), java.nio.charset.StandardCharsets.UTF_8);

            FilesystemMcpServer server = new FilesystemMcpServer(tempFile.getParent().toString(), null, true);
            String result = server.callTool("read_file", "{\"path\":\"" + tempFile.toString().replace("\\", "\\\\") + "\"}");

            assertNotNull("Result should not be null", result);
            assertTrue("Should contain header with filename", result.contains("=== " + tempFile.getFileName().toString()));
            assertTrue("Should contain line numbers", result.contains("  1: line one"));
            assertTrue("Should contain last line", result.contains("  5: line five"));
            assertTrue("Should indicate 5 lines", result.contains("of 5"));
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testFilesystemMcpServerReadFileWithOffset() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("diatom_test_", ".txt");
        try {
            String[] lines = {"line one", "line two", "line three", "line four", "line five"};
            java.nio.file.Files.write(tempFile, java.util.Arrays.asList(lines), java.nio.charset.StandardCharsets.UTF_8);

            FilesystemMcpServer server = new FilesystemMcpServer(tempFile.getParent().toString(), null, true);
            String result = server.callTool("read_file",
                "{\"path\":\"" + tempFile.toString().replace("\\", "\\\\") + "\", \"offset\":3}");

            assertNotNull("Result should not be null", result);
            assertTrue("Should start at line 3", result.contains("  3: line three"));
            assertTrue("Should include line 4", result.contains("  4: line four"));
            assertTrue("Should include line 5", result.contains("  5: line five"));
            assertFalse("Should NOT include line 1", result.contains("  1: line one"));
            assertFalse("Should NOT include line 2", result.contains("  2: line two"));
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testFilesystemMcpServerReadFileWithLimit() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("diatom_test_", ".txt");
        try {
            String[] lines = {"line one", "line two", "line three", "line four", "line five"};
            java.nio.file.Files.write(tempFile, java.util.Arrays.asList(lines), java.nio.charset.StandardCharsets.UTF_8);

            FilesystemMcpServer server = new FilesystemMcpServer(tempFile.getParent().toString(), null, true);
            String result = server.callTool("read_file",
                "{\"path\":\"" + tempFile.toString().replace("\\", "\\\\") + "\", \"limit\":2}");

            assertNotNull("Result should not be null", result);
            assertTrue("Should include line 1", result.contains("  1: line one"));
            assertTrue("Should include line 2", result.contains("  2: line two"));
            assertFalse("Should NOT include line 3", result.contains("  3: line three"));
            assertTrue("Should have truncation marker", result.contains("truncated") || result.contains("more lines"));
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testFilesystemMcpServerReadFileWithOffsetAndLimit() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("diatom_test_", ".txt");
        try {
            String[] lines = {"line one", "line two", "line three", "line four", "line five",
                              "line six", "line seven", "line eight", "line nine", "line ten"};
            java.nio.file.Files.write(tempFile, java.util.Arrays.asList(lines), java.nio.charset.StandardCharsets.UTF_8);

            FilesystemMcpServer server = new FilesystemMcpServer(tempFile.getParent().toString(), null, true);
            String result = server.callTool("read_file",
                "{\"path\":\"" + tempFile.toString().replace("\\", "\\\\") + "\", \"offset\":4, \"limit\":3}");

            assertNotNull("Result should not be null", result);
            assertTrue("Should include line 4", result.contains("  4: line four"));
            assertTrue("Should include line 5", result.contains("  5: line five"));
            assertTrue("Should include line 6", result.contains("  6: line six"));
            assertFalse("Should NOT include line 3", result.contains("  3: line three"));
            assertFalse("Should NOT include line 7", result.contains("  7: line seven"));
            // Header should show lines 4-6 of 10
            assertTrue("Should indicate lines 4-6 of 10", result.contains("lines 4-6 of 10"));
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testFilesystemMcpServerReadFileNotFound() throws Exception {
        FilesystemMcpServer server = new FilesystemMcpServer(System.getProperty("java.io.tmpdir"), null, true);
        String result = server.callTool("read_file", "{\"path\":\"/nonexistent/path/file.txt\"}");

        assertNotNull("Result should not be null", result);
        assertTrue("Should indicate file not found", result.contains("Error") && result.contains("not found"));
    }

    @Test
    public void testFilesystemMcpServerReadFileLineNumberFormat() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("diatom_test_", ".txt");
        try {
            java.nio.file.Files.write(tempFile, "test".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            FilesystemMcpServer server = new FilesystemMcpServer(tempFile.getParent().toString(), null, true);
            String result = server.callTool("read_file", "{\"path\":\"" + tempFile.toString().replace("\\", "\\\\") + "\"}");

            assertNotNull("Result should not be null", result);
            // Line number should be exactly 4 characters wide (space-padded)
            assertTrue("Line number should be 4-digit padded", result.contains("   1: test"));
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testFilesystemMcpServerReadFileHeaderFormat() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("diatom_test_", ".txt");
        try {
            java.nio.file.Files.write(tempFile, "a\nb\nc".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            FilesystemMcpServer server = new FilesystemMcpServer(tempFile.getParent().toString(), null, true);
            String result = server.callTool("read_file", "{\"path\":\"" + tempFile.toString().replace("\\", "\\\\") + "\"}");

            assertNotNull("Result should not be null", result);
            // Header format: === filename (lines X-Y of Z) ===
            String filename = tempFile.getFileName().toString();
            assertTrue("Should start with header", result.startsWith("=== " + filename));
            assertTrue("Should contain line range", result.contains("lines 1-3 of 3"));
            // First content line should be right after header
            assertTrue("Content should follow header", result.contains("=== " + filename + " (lines 1-3 of 3) ===\n   1: a"));
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testFilesystemMcpServerReadFileEmptyPath() throws Exception {
        FilesystemMcpServer server = new FilesystemMcpServer(System.getProperty("java.io.tmpdir"), null, true);
        String result = server.callTool("read_file", "{\"path\":\"\"}");

        assertNotNull("Result should not be null", result);
        assertTrue("Should indicate error for empty path", result.contains("Error") || result.contains("not found"));
    }
}
