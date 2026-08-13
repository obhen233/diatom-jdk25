package com.github.obhen233.spi;

import java.sql.Connection;
import java.util.Collections;
import java.util.List;

/**
 * Database extension provider.
 * Allows custom modules to add new tables, columns, or perform custom data management.
 */
public interface DatabaseExtension {

    /**
     * Provide CREATE TABLE SQL statements.
     * These are executed after the standard tables are created.
     * @return list of CREATE TABLE statements
     */
    default List<String> getCreateTables() {
        return Collections.emptyList();
    }

    /**
     * Provide ALTER TABLE SQL statements (e.g., ADD COLUMN).
     * @return list of ALTER TABLE statements
     */
    default List<String> getAlterTables() {
        return Collections.emptyList();
    }

    /**
     * Called after database initialization is complete.
     * Use this to insert initial data or perform other post-initialization tasks.
     * @param conn the database connection
     */
    default void onDatabaseInitialized(Connection conn) {}

    /**
     * Called when database schema upgrade is needed.
     * @param conn the database connection
     * @param oldVersion the old schema version
     * @param newVersion the new schema version
     */
    default void onUpgrade(Connection conn, int oldVersion, int newVersion) {}
}
