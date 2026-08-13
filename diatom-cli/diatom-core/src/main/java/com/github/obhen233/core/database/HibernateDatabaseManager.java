package com.github.obhen233.core.database;

import com.github.obhen233.spi.DatabaseExtension;
import com.github.obhen233.spi.SpiLoader;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Hibernate-based {@link DatabaseManager} implementation.
 * <p>
 * Uses Hibernate + HikariCP for database access. Supports both SQLite and PostgreSQL
 * based on the {@code diatom.database.*} system properties (see {@link HibernateConfig}).
 * <p>
 * In SQLite mode, connects to the local {@code .diatom/diatom.db} file with HikariCP pool-size=1.
 * In PostgreSQL mode, connects to the configured JDBC URL with HikariCP pool-size=10.
 */
public class HibernateDatabaseManager implements DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(HibernateDatabaseManager.class);

    private final HibernateConfig config;
    private SessionFactory sessionFactory;
    private boolean explicitlyClosed = false;

    public HibernateDatabaseManager() {
        this.config = new HibernateConfig();
    }

    public HibernateDatabaseManager(HibernateConfig config) {
        this.config = config;
    }

    @Override
    public void initialize() throws SQLException {
        try {
            sessionFactory = config.buildSessionFactory();
            logger.info("HibernateDatabaseManager initialized (type={})", config.getDbType());
        } catch (ExceptionInInitializerError e) {
            throw new SQLException("Failed to initialize Hibernate: " + e.getCause().getMessage(), e.getCause());
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (explicitlyClosed) {
            throw new SQLException("Database connection has been explicitly closed");
        }
        if (sessionFactory == null) {
            throw new SQLException("SessionFactory not initialized. Call initialize() first.");
        }
        // Obtain a live JDBC Connection from the HikariCP DataSource directly
        return HibernateConfig.getDataSource().getConnection();
    }

    /**
     * Get the Hibernate SessionFactory for direct Hibernate access.
     */
    public SessionFactory getSessionFactory() {
        if (explicitlyClosed) {
            throw new IllegalStateException("Database has been explicitly closed");
        }
        if (sessionFactory == null) {
            throw new IllegalStateException("SessionFactory not initialized. Call initialize() first.");
        }
        return sessionFactory;
    }

    /**
     * Get the HibernateConfig used by this manager.
     */
    public HibernateConfig getConfig() {
        return config;
    }

    @Override
    public void close() {
        explicitlyClosed = true;
        HibernateConfig.shutdown();
        logger.info("HibernateDatabaseManager closed");
    }

    @Override
    public boolean isClosed() {
        return explicitlyClosed || sessionFactory == null;
    }

    /**
     * Open a new Hibernate Session. Callers must close it in try-with-resources or finally block.
     */
    public Session openSession() {
        return sessionFactory.openSession();
    }
}
