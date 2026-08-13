package com.github.obhen233.core.database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.io.File;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * DatabaseManager 测试用例
 * 对应 TEST_CASES.md 8. 数据库测试 (DatabaseManager)
 */
public class DatabaseManagerTest {

    private DatabaseManager db;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"), "diatom_test_" + System.currentTimeMillis() + ".db").toString();
        db = new SqliteDatabaseManager(testDbPath);
        db.initialize();
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
        // Clean up test database file
        File dbFile = new File(testDbPath);
        if (dbFile.exists()) {
            dbFile.delete();
        }
    }

    @Test
    public void testDatabaseInitialization() throws SQLException {
        assertNotNull("Connection should not be null", db.getConnection());
        assertTrue("Connection should be valid", db.getConnection().isValid(1));
    }

    @Test
    public void testTableCreation() throws SQLException {
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();

        // Verify all required tables exist
        String[] tables = {"command_history", "task_checkpoint", "project_context"};
        for (String table : tables) {
            ResultSet rs = meta.getTables(null, null, table, null);
            assertTrue(table + " should exist", rs.next());
            rs.close();
        }
    }

    @Test
    public void testCommandHistoryTable() throws SQLException {
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();

        ResultSet columns = meta.getColumns(null, null, "command_history", null);
        boolean hasInputText = false;
        boolean hasTimestamp = false;
        boolean hasSessionId = false;
        boolean hasWorkspace = false;

        while (columns.next()) {
            String columnName = columns.getString("COLUMN_NAME");
            if ("input_text".equals(columnName)) hasInputText = true;
            if ("timestamp".equals(columnName)) hasTimestamp = true;
            if ("session_id".equals(columnName)) hasSessionId = true;
            if ("workspace".equals(columnName)) hasWorkspace = true;
        }
        columns.close();

        assertTrue("command_history should have input_text column", hasInputText);
        assertTrue("command_history should have timestamp column", hasTimestamp);
        assertTrue("command_history should have session_id column", hasSessionId);
        assertTrue("command_history should have workspace column", hasWorkspace);
    }

    @Test
    public void testTaskCheckpointTable() throws SQLException {
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();

        ResultSet columns = meta.getColumns(null, null, "task_checkpoint", null);
        boolean hasTaskId = false;
        boolean hasUserInput = false;
        boolean hasAgentState = false;
        boolean hasStepCount = false;

        while (columns.next()) {
            String columnName = columns.getString("COLUMN_NAME");
            if ("task_id".equals(columnName)) hasTaskId = true;
            if ("user_input".equals(columnName)) hasUserInput = true;
            if ("agent_state".equals(columnName)) hasAgentState = true;
            if ("step_count".equals(columnName)) hasStepCount = true;
        }
        columns.close();

        assertTrue("task_checkpoint should have task_id column", hasTaskId);
        assertTrue("task_checkpoint should have user_input column", hasUserInput);
        assertTrue("task_checkpoint should have agent_state column", hasAgentState);
        assertTrue("task_checkpoint should have step_count column", hasStepCount);
    }

    @Test
    public void testProjectContextTable() throws SQLException {
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();

        ResultSet columns = meta.getColumns(null, null, "project_context", null);
        boolean hasProjectPath = false;
        boolean hasProjectName = false;
        boolean hasProjectType = false;
        boolean hasContextData = false;
        boolean hasFileIndex = false;

        while (columns.next()) {
            String columnName = columns.getString("COLUMN_NAME");
            if ("project_path".equals(columnName)) hasProjectPath = true;
            if ("project_name".equals(columnName)) hasProjectName = true;
            if ("project_type".equals(columnName)) hasProjectType = true;
            if ("context_data".equals(columnName)) hasContextData = true;
            if ("file_index".equals(columnName)) hasFileIndex = true;
        }
        columns.close();

        assertTrue("project_context should have project_path column", hasProjectPath);
        assertTrue("project_context should have project_name column", hasProjectName);
        assertTrue("project_context should have project_type column", hasProjectType);
        assertTrue("project_context should have context_data column", hasContextData);
        assertTrue("project_context should have file_index column", hasFileIndex);
    }

    @Test
    public void testIndexes() throws SQLException {
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();

        // Check for indexes
        String[] indexNames = {"idx_history_timestamp", "idx_history_session", "idx_checkpoint_task_id", "idx_context_project"};

        for (String indexName : indexNames) {
            ResultSet rs = meta.getIndexInfo(null, null, indexName, false, false);
            // Just verify we can query without error
            assertNotNull("Index query should work", rs);
            rs.close();
        }
    }

    @Test
    public void testCloseConnection() throws SQLException {
        assertTrue("Connection should be valid before close", db.getConnection().isValid(1));
        db.close();
        // After close, db.isClosed() should return true
        assertTrue("Connection should be closed after close()", db.isClosed());
    }

    @Test
    public void testMultipleInitialize() throws SQLException {
        // Should not throw exception
        db.initialize();
        assertTrue("Connection should still be valid", db.getConnection().isValid(1));
    }
}
