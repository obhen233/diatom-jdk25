package com.github.obhen233.core.database;

import com.github.obhen233.spi.DatabaseExtension;
import com.github.obhen233.spi.SpiLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite database manager for persistent storage.
 * Handles connection lifecycle and table creation.
 * This is the default implementation of {@link DatabaseManager}.
 */
public class SqliteDatabaseManager implements DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(SqliteDatabaseManager.class);
    private static final String DB_FILE = ".diatom" + File.separator + "diatom.db";

    private final String dbPath;
    private Connection connection;
    private boolean explicitlyClosed = false;

    public SqliteDatabaseManager() {
        // Use JAR directory first (set by Bootstrap), fallback to user.dir (IDE mode)
        String jarDir = System.getProperty("diatom.jar.dir");
        if (jarDir != null && !jarDir.isEmpty()) {
            this.dbPath = jarDir + File.separator + DB_FILE;
        } else {
            String userDir = System.getProperty("user.dir");
            this.dbPath = userDir + File.separator + DB_FILE;
        }
    }

    public SqliteDatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * Initialize database connection and create tables
     */
    @Override
    public void initialize() throws SQLException {
        // Migration: if the new JAR dir path doesn't exist but the old user.dir path does,
        // copy the old DB to the new path and remove the old file.
        migrateFromUserDirIfNeeded();

        // Ensure directory exists
        Path dbFile = Paths.get(dbPath);
        Path dbDir = dbFile.getParent();
        if (dbDir != null) {
            dbDir.toFile().mkdirs();
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        connection.setAutoCommit(true);
        createTables();
        applyDatabaseExtensions();
        logger.info("Database initialized: {}", dbPath);
    }

    /**
     * Get the database connection with automatic reconnection if needed
     */
    @Override
    public Connection getConnection() throws SQLException {
        if (explicitlyClosed) {
            throw new SQLException("Database connection has been explicitly closed");
        }
        if (connection == null || connection.isClosed()) {
            logger.info("Database connection lost, reinitializing...");
            initialize();
        }
        return connection;
    }

    /**
     * Create all required tables
     */
    private void createTables() throws SQLException {
        // 启用外键约束
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        }

        try (Statement stmt = connection.createStatement()) {
            // History table for command history (enhanced with audit fields)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS command_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "input_text TEXT NOT NULL," +
                "timestamp INTEGER NOT NULL," +
                "session_id TEXT," +
                "workspace TEXT," +
                "task_id TEXT," +
                "token_count INTEGER DEFAULT 0," +
                "response_token_count INTEGER DEFAULT 0," +
                "model_name TEXT," +
                "duration_ms INTEGER DEFAULT 0," +
                "tool_calls TEXT," +
                "project_name TEXT," +
                "workspace_id INTEGER)"
            );

            // Task checkpoint table for resuming tasks
            // task_id is NOT UNIQUE - multiple checkpoints can exist for the same task_id
            // (one per step: 0, 5, 10, etc.)
            // 增强字段: llm_summary, compressed_context, file_change_summary, tool_result_hashes, message_count, token_usage
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS task_checkpoint (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "task_id TEXT NOT NULL," +
                "user_input TEXT," +
                "agent_state TEXT," +
                "conversation_history TEXT," +
                "tool_results TEXT," +
                "step_count INTEGER DEFAULT 0," +
                "project_id INTEGER," +
                "llm_summary TEXT," +
                "compressed_context BLOB," +
                "file_change_summary TEXT," +
                "tool_result_hashes TEXT," +
                "message_count INTEGER DEFAULT 0," +
                "token_usage INTEGER DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)"
            );

            // ========================================
            // Workspace and Project tables (model conversation data recovery / input history)
            // ========================================

            // Workspace context table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS workspace_context (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "root_path TEXT NOT NULL UNIQUE," +
                "description TEXT," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)"
            );

            // Project context table (enhanced with workspace reference)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS project_context (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "workspace_id INTEGER NOT NULL," +
                "project_path TEXT NOT NULL UNIQUE," +
                "project_name TEXT," +
                "project_type TEXT," +
                "indexed_at INTEGER," +
                "context_data TEXT," +
                "file_index TEXT," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "FOREIGN KEY (workspace_id) REFERENCES workspace_context(id) ON DELETE CASCADE)"
            );

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_project_workspace ON project_context(workspace_id)");

            // ========================================
            // New tables for persistence design
            // ========================================

            // Tasks table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS tasks (" +
                "id TEXT PRIMARY KEY," +
                "status TEXT NOT NULL DEFAULT 'CREATED'," +
                "original_request TEXT NOT NULL," +
                "current_step INTEGER DEFAULT 0," +
                "total_steps INTEGER DEFAULT 0," +
                "workspace_path TEXT NOT NULL," +
                "project_id INTEGER," +
                "context_checkpoint_id INTEGER," +
                "latest_snapshot_id INTEGER," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)"
            );

            // Task steps table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS task_steps (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "task_id TEXT NOT NULL," +
                "step_number INTEGER NOT NULL," +
                "description TEXT," +
                "status TEXT NOT NULL DEFAULT 'PENDING'," +
                "tool_calls TEXT," +
                "error_message TEXT," +
                "created_at INTEGER NOT NULL," +
                "completed_at INTEGER," +
                "FOREIGN KEY (task_id) REFERENCES tasks(id)," +
                "UNIQUE(task_id, step_number))"
            );

            // File snapshots table (similar to Git blob)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS file_snapshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "task_id TEXT NOT NULL," +
                "file_path TEXT NOT NULL," +
                "operation TEXT NOT NULL," +
                "content_hash TEXT NOT NULL," +
                "content_type TEXT DEFAULT 'full'," +
                "content BLOB," +
                "base_snapshot_id INTEGER," +
                "created_at INTEGER NOT NULL," +
                "FOREIGN KEY (task_id) REFERENCES tasks(id)," +
                "FOREIGN KEY (base_snapshot_id) REFERENCES file_snapshots(id))"
            );

            // Snapshots table (similar to Git commit)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS snapshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "task_id TEXT NOT NULL," +
                "snapshot_type TEXT NOT NULL," +
                "description TEXT," +
                "parent_snapshot_id INTEGER," +
                "created_at INTEGER NOT NULL," +
                "FOREIGN KEY (task_id) REFERENCES tasks(id)," +
                "FOREIGN KEY (parent_snapshot_id) REFERENCES snapshots(id))"
            );

            // Snapshot-files association table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS snapshot_files (" +
                "snapshot_id INTEGER NOT NULL," +
                "file_snapshot_id INTEGER NOT NULL," +
                "FOREIGN KEY (snapshot_id) REFERENCES snapshots(id)," +
                "FOREIGN KEY (file_snapshot_id) REFERENCES file_snapshots(id)," +
                "PRIMARY KEY (snapshot_id, file_snapshot_id))"
            );

            // Change logs table (task_id has no FK constraint — change logs can exist
            // without a persisted task row, e.g. ad-hoc file modifications)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS change_logs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "task_id TEXT," +
                "step_number INTEGER," +
                "snapshot_id INTEGER," +
                "tool_name TEXT NOT NULL," +
                "file_path TEXT," +
                "operation TEXT NOT NULL," +
                "content_hash TEXT," +
                "summary TEXT," +
                "status TEXT NOT NULL," +
                "error_message TEXT," +
                "created_at INTEGER NOT NULL," +
                "FOREIGN KEY (snapshot_id) REFERENCES snapshots(id))"
            );

            // Input history table (no FK on task_id — it's an optional reference
            // that may point to a non-existent task in the tasks table)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS input_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "task_id TEXT," +
                "session_id TEXT NOT NULL," +
                "input_type TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "token_count INTEGER," +
                "created_at INTEGER NOT NULL)"
            );

            // Create indexes for new tables
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tasks_updated ON tasks(updated_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_task_steps_task ON task_steps(task_id, step_number)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_snapshots_task ON file_snapshots(task_id, created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_snapshots_hash ON file_snapshots(content_hash)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_task ON snapshots(task_id, created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_checkpoints_task ON task_checkpoint(task_id, created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_change_logs_task ON change_logs(task_id, created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_change_logs_step ON change_logs(task_id, step_number)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_input_history_session ON input_history(session_id, created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_input_history_task ON input_history(task_id, created_at)");

            // Command knowledge base table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS command_knowledge (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "command TEXT NOT NULL UNIQUE," +
                "tool_type TEXT," +
                "permission TEXT DEFAULT 'ALLOW'," +
                "risk_level INTEGER DEFAULT 0," +
                "confidence INTEGER DEFAULT 50," +
                "source TEXT DEFAULT 'builtin'," +
                "last_verified INTEGER," +
                "verified_count INTEGER DEFAULT 0," +
                "created_at INTEGER," +
                "updated_at INTEGER)"
            );

            // Command execution log table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS command_execution_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "command TEXT NOT NULL," +
                "args TEXT," +
                "tool_type TEXT," +
                "result TEXT," +
                "risk_assessed INTEGER," +
                "user_feedback TEXT," +
                "timestamp INTEGER," +
                "permission TEXT," +
                "risk_level INTEGER," +
                "reasoning TEXT," +
                "classification_method TEXT," +
                "duration_ms INTEGER," +
                "status TEXT)"
            );

            // Create indexes for command knowledge
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_command_knowledge_command ON command_knowledge(command)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_command_knowledge_tool_type ON command_knowledge(tool_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_command_knowledge_permission ON command_knowledge(permission)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_command_execution_log_command ON command_execution_log(command)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_command_execution_log_timestamp ON command_execution_log(timestamp)");

            // Create indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_history_timestamp ON command_history(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_history_session ON command_history(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_history_task_id ON command_history(task_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_checkpoint_task_id ON task_checkpoint(task_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_checkpoint_created_at ON task_checkpoint(created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_context_project ON project_context(project_path)");

            // System config table for persistent configuration
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS system_config (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "config_key TEXT NOT NULL UNIQUE," +
                "config_value TEXT," +
                "config_type TEXT DEFAULT 'string'," +
                "category TEXT NOT NULL," +
                "i18n_key TEXT," +
                "default_value TEXT," +
                "allowed_values TEXT," +
                "min_value INTEGER," +
                "max_value INTEGER," +
                "pattern TEXT," +
                "source TEXT DEFAULT 'database'," +
                "last_modified INTEGER," +
                "created_at INTEGER)"
            );

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_system_config_key ON system_config(config_key)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_system_config_category ON system_config(category)");

            // Command rules table for validation
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS command_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "mode TEXT NOT NULL DEFAULT 'agent'," +
                "type TEXT NOT NULL," +
                "pattern TEXT NOT NULL," +
                "source TEXT NOT NULL DEFAULT 'manual'," +
                "enabled INTEGER NOT NULL DEFAULT 1," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "UNIQUE(mode, type, pattern))"
            );

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_command_rules_mode ON command_rules(mode)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_command_rules_type ON command_rules(type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_command_rules_source ON command_rules(source)");

            // ========================================
            // Topology editor tables
            // ========================================
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS topology_def (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE," +
                "description TEXT," +
                "status TEXT NOT NULL DEFAULT 'draft'," +
                "version INTEGER NOT NULL DEFAULT 1," +
                "draft_definition TEXT," +
                "published_at INTEGER," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)"
            );

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS topology_version (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "topology_id INTEGER NOT NULL," +
                "version INTEGER NOT NULL," +
                "definition TEXT NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'active'," +
                "published_at INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "FOREIGN KEY (topology_id) REFERENCES topology_def(id) ON DELETE CASCADE," +
                "UNIQUE(topology_id, version))"
            );

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_topology_version_topo ON topology_version(topology_id, version)");

            // Source code extensions table for configurable TOOL_RESULT_SUMMARIZER
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS source_code_extensions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "extension TEXT NOT NULL UNIQUE," +
                "enabled INTEGER NOT NULL DEFAULT 1," +
                "source TEXT NOT NULL DEFAULT 'built-in'," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)"
            );

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_source_code_extensions_enabled ON source_code_extensions(enabled)");

            // Migration: remove FK constraint from input_history for existing databases.
            // Old schema had "FOREIGN KEY (task_id) REFERENCES tasks(id)" which fails
            // when task_id doesn't exist in tasks table (e.g. ad-hoc commands without a task).
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='input_history' AND sql LIKE '%REFERENCES%'")) {
                if (rs.next()) {
                    stmt.execute("DROP TABLE IF EXISTS input_history");
                    stmt.execute(
                        "CREATE TABLE input_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "task_id TEXT," +
                        "session_id TEXT NOT NULL," +
                        "input_type TEXT NOT NULL," +
                        "content TEXT NOT NULL," +
                        "token_count INTEGER," +
                        "created_at INTEGER NOT NULL)");
                    logger.info("Migrated input_history: removed FK constraint on task_id");
                }
            } catch (SQLException e) {
                logger.debug("No input_history migration needed: {}", e.getMessage());
            }

            // Migration: remove FK constraint from change_logs for existing databases.
            // Old schema had FK (task_id) REFERENCES tasks(id) which fails when task_id
            // doesn't exist in tasks table (e.g. ad-hoc file modifications without a task).
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='change_logs' AND sql LIKE '%REFERENCES%'")) {
                if (rs.next()) {
                    stmt.execute("DROP TABLE IF EXISTS change_logs");
                    stmt.execute(
                        "CREATE TABLE change_logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "task_id TEXT," +
                        "step_number INTEGER," +
                        "snapshot_id INTEGER," +
                        "tool_name TEXT NOT NULL," +
                        "file_path TEXT," +
                        "operation TEXT NOT NULL," +
                        "content_hash TEXT," +
                        "summary TEXT," +
                        "status TEXT NOT NULL," +
                        "error_message TEXT," +
                        "created_at INTEGER NOT NULL," +
                        "FOREIGN KEY (snapshot_id) REFERENCES snapshots(id))");
                    logger.info("Migrated change_logs: removed FK constraint on task_id");
                }
            } catch (SQLException e) {
                logger.debug("No change_logs migration needed: {}", e.getMessage());
            }

            // Migration: add project_id column to tasks for existing databases
            try {
                stmt.execute("ALTER TABLE tasks ADD COLUMN project_id INTEGER");
                logger.info("Migrated tasks: added project_id column");
            } catch (SQLException e) {
                if (!e.getMessage().contains("duplicate column")) {
                    throw e;
                }
            }

            // Migration: add project_id column to task_checkpoint for existing databases
            try {
                stmt.execute("ALTER TABLE task_checkpoint ADD COLUMN project_id INTEGER");
                logger.info("Migrated task_checkpoint: added project_id column");
            } catch (SQLException e) {
                if (!e.getMessage().contains("duplicate column")) {
                    throw e;
                }
            }
        }
    }

    /**
     * Apply DatabaseExtension SPI implementations.
     * Executes CREATE TABLE and ALTER TABLE statements from custom modules.
     */
    private void applyDatabaseExtensions() {
        for (DatabaseExtension ext : SpiLoader.getAll(DatabaseExtension.class)) {
            try {
                // Execute CREATE TABLE statements
                for (String sql : ext.getCreateTables()) {
                    if (sql != null && !sql.trim().isEmpty()) {
                        try (Statement stmt = connection.createStatement()) {
                            stmt.execute(sql);
                            logger.debug("Executed custom CREATE TABLE: {}", sql.substring(0, Math.min(50, sql.length())));
                        }
                    }
                }

                // Execute ALTER TABLE statements (ignore duplicate column errors)
                for (String sql : ext.getAlterTables()) {
                    if (sql != null && !sql.trim().isEmpty()) {
                        try (Statement stmt = connection.createStatement()) {
                            stmt.execute(sql);
                        } catch (SQLException e) {
                            // Ignore "duplicate column" errors for compatibility
                            if (!e.getMessage().contains("duplicate column name")) {
                                throw e;
                            }
                            logger.debug("ALTER TABLE skipped (column may already exist): {}", sql);
                        }
                    }
                }

                // Call post-initialization hook
                ext.onDatabaseInitialized(connection);
                logger.debug("Applied DatabaseExtension: {}", ext.getClass().getName());
            } catch (SQLException e) {
                logger.warn("Failed to apply DatabaseExtension {}: {}", ext.getClass().getName(), e.getMessage());
            }
        }
    }

    /**
     * Migrate database from old {user.dir}/.diatom/diatom.db to new {jarDir}/.diatom/diatom.db.
     * Only triggers when:
     * - The current dbPath uses the JAR directory (diatom.jar.dir is set)
     * - The current dbPath doesn't exist yet
     * - The old user.dir path exists and is different from the current path
     */
    private void migrateFromUserDirIfNeeded() {
        String jarDir = System.getProperty("diatom.jar.dir");
        if (jarDir == null || jarDir.isEmpty()) {
            return; // Not running from JAR (IDE mode), no migration needed
        }
        Path newPath = Paths.get(dbPath);
        if (Files.exists(newPath)) {
            return; // New path already has a database
        }
        String userDir = System.getProperty("user.dir");
        if (userDir == null || userDir.isEmpty()) {
            return;
        }
        String oldDbPath = userDir + File.separator + DB_FILE;
        Path oldPath = Paths.get(oldDbPath);
        if (!Files.exists(oldPath)) {
            return; // No old database to migrate
        }
        if (oldPath.toAbsolutePath().normalize().equals(newPath.toAbsolutePath().normalize())) {
            return; // Paths are the same, no migration needed
        }
        try {
            // Ensure target directory exists
            Path dbDir = newPath.getParent();
            if (dbDir != null) {
                Files.createDirectories(dbDir);
            }
            Files.copy(oldPath, newPath, StandardCopyOption.COPY_ATTRIBUTES);
            logger.info("Migrated database from {} to {}", oldDbPath, dbPath);
            Files.deleteIfExists(oldPath);
            // Also delete WAL and SHM files if they exist
            Files.deleteIfExists(Paths.get(oldDbPath + "-wal"));
            Files.deleteIfExists(Paths.get(oldDbPath + "-shm"));
            logger.info("Removed old database: {}", oldDbPath);
        } catch (IOException e) {
            logger.warn("Failed to migrate database from {} to {}: {}", oldDbPath, dbPath, e.getMessage());
        }
    }

    /**
     * Close database connection
     */
    @Override
    public void close() {
        explicitlyClosed = true;
        if (connection != null) {
            try {
                connection.close();
                logger.info("Database connection closed");
            } catch (SQLException e) {
                logger.warn("Error closing database", e);
            }
        }
    }

    /**
     * Check if the database connection has been explicitly closed
     */
    @Override
    public boolean isClosed() {
        return explicitlyClosed || (connection == null);
    }
}
