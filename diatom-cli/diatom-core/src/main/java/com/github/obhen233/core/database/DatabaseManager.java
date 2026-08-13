package com.github.obhen233.core.database;

import org.hibernate.SessionFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Database manager interface for persistent storage.
 * Default implementation uses SQLite ({@link SqliteDatabaseManager}).
 * Allows alternative implementations (H2, PostgreSQL, etc.) to be plugged in.
 */
public interface DatabaseManager {

    /**
     * Initialize database connection and create tables.
     */
    void initialize() throws SQLException;

    /**
     * Get the database connection with automatic reconnection if needed.
     */
    Connection getConnection() throws SQLException;

    /**
     * Close database connection.
     */
    void close();

    /**
     * Check if the database connection has been explicitly closed.
     */
    boolean isClosed();

    /**
     * Get the Hibernate SessionFactory if available.
     * Returns null for non-Hibernate implementations (e.g., {@link SqliteDatabaseManager}).
     */
    default SessionFactory getSessionFactory() {
        return null;
    }
}
