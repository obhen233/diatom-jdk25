package com.github.obhen233.adapter.database;

import com.github.obhen233.adapter.database.entity.WorkerTaskEntity;
import com.github.obhen233.adapter.internal.DirectoryLayout;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;
import org.hibernate.service.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Hibernate configuration for the adapter module.
 *
 * <p>Reads {@code diatom.database.*} system properties to configure the
 * {@link SessionFactory}. When no URL is specified, defaults to SQLite at
 * {@code {jarDir}/.diatom/diatom.db}.</p>
 *
 * <p>Configuration rules (system property → default):</p>
 * <ul>
 *   <li>{@code diatom.database.url} — JDBC URL (auto-constructed for SQLite)</li>
 *   <li>{@code diatom.database.username} — DB username (default: empty)</li>
 *   <li>{@code diatom.database.password} — DB password (default: empty)</li>
 *   <li>{@code diatom.database.pool-size} — HikariCP pool size (default: 2 for SQLite, 10 for others)</li>
 *   <li>{@code diatom.database.hibernatedialect} — dialect class (optional, auto-detected)</li>
 *   <li>{@code diatom.database.driver} — JDBC driver class (optional, auto-detected)</li>
 * </ul>
 */
public class HibernateConfig {
    private static final Logger logger = LoggerFactory.getLogger(HibernateConfig.class);

    private static SessionFactory sessionFactory;
    private static com.zaxxer.hikari.HikariDataSource dataSource;
    private static volatile boolean initialized = false;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int poolSize;
    private final String dialectClassName;
    private final String driverClassName;

    public HibernateConfig() {
        this.jdbcUrl = resolveJdbcUrl();
        this.username = getProperty("diatom.database.username", "");
        this.password = getProperty("diatom.database.password", "");
        this.poolSize = Integer.parseInt(getProperty("diatom.database.pool-size",
                jdbcUrl != null && jdbcUrl.startsWith("jdbc:sqlite:") ? "2" : "10"));
        this.dialectClassName = getProperty("diatom.database.hibernatedialect", null);
        this.driverClassName = getProperty("diatom.database.driver", null);

        logger.info("HibernateConfig: url={}, poolSize={}, dialect={}, driver={}",
                maskUrl(jdbcUrl), poolSize,
                dialectClassName != null ? dialectClassName : "(auto)",
                driverClassName != null ? driverClassName : "(auto)");
    }

    private static String getProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    private String resolveJdbcUrl() {
        String url = getProperty("diatom.database.url", "");
        if (!url.isEmpty()) {
            return url;
        }
        // Auto-construct SQLite path
        String jarDir = System.getProperty("diatom.jar.dir");
        String baseDir;
        if (jarDir != null && !jarDir.isEmpty()) {
            baseDir = jarDir;
        } else {
            baseDir = System.getProperty("user.dir");
        }
        String dbPath = baseDir + File.separator + ".diatom" + File.separator + "diatom.db";
        return "jdbc:sqlite:" + dbPath;
    }

    private String maskUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("://([^:]+):([^@]+)@", "://$1:****@");
    }

    /**
     * Get the configured JDBC URL.
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * Build Hibernate configuration properties.
     */
    public Map<String, Object> buildSettings() {
        Map<String, Object> settings = new HashMap<>();

        // HikariCP connection pool via DataSource
        settings.put(Environment.DATASOURCE, buildDataSource());

        // Hibernate settings
        if (dialectClassName != null) {
            settings.put(Environment.DIALECT, dialectClassName);
        }
        // Register DiatomDialectResolver explicitly (not relying solely on SPI)
        settings.put(Environment.DIALECT_RESOLVERS,
                "com.github.obhen233.adapter.database.DiatomDialectResolver");
        settings.put(Environment.HBM2DDL_AUTO, "update");
        settings.put(Environment.SHOW_SQL, false);
        settings.put(Environment.FORMAT_SQL, false);
        settings.put(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");

        return settings;
    }

    /**
     * Build a HikariCP DataSource.
     */
    private com.zaxxer.hikari.HikariDataSource buildDataSource() {
        com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        if (driverClassName != null) {
            ds.setDriverClassName(driverClassName);
        }
        if (username != null && !username.isEmpty()) {
            ds.setUsername(username);
        }
        if (password != null && !password.isEmpty()) {
            ds.setPassword(password);
        }
        ds.setMaximumPoolSize(poolSize);
        ds.setMinimumIdle(1);
        ds.setIdleTimeout(30000);
        ds.setConnectionTimeout(10000);
        ds.setMaxLifetime(1800000);

        // SQLite needs special connection settings
        if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:sqlite:")) {
            ds.addDataSourceProperty("journal_mode", "WAL");
            ds.addDataSourceProperty("foreign_keys", "ON");
            ds.addDataSourceProperty("busy_timeout", "5000");
        }

        dataSource = ds;
        return ds;
    }

    /**
     * Build and initialize the SessionFactory.
     */
    public synchronized SessionFactory buildSessionFactory() {
        if (sessionFactory != null) {
            return sessionFactory;
        }

        try {
            Map<String, Object> settings = buildSettings();
            ServiceRegistry serviceRegistry = new StandardServiceRegistryBuilder()
                    .applySettings(settings)
                    .build();

            MetadataSources metadataSources = new MetadataSources(serviceRegistry);
            registerEntities(metadataSources);

            sessionFactory = metadataSources.buildMetadata().buildSessionFactory();
            initialized = true;
            logger.info("Hibernate SessionFactory initialized for url={}",
                    maskUrl(jdbcUrl));
            return sessionFactory;
        } catch (Throwable ex) {
            logger.error("Failed to initialize Hibernate SessionFactory", ex);
            throw new ExceptionInInitializerError(ex);
        }
    }

    /**
     * Register all JPA @Entity classes with Hibernate.
     */
    private void registerEntities(MetadataSources sources) {
        sources.addAnnotatedClass(WorkerTaskEntity.class);
    }

    /**
     * Get the singleton SessionFactory (must be initialized first).
     */
    public static SessionFactory getSessionFactory() {
        if (!initialized || sessionFactory == null) {
            throw new IllegalStateException("Hibernate SessionFactory not initialized. " +
                    "Call buildSessionFactory() first.");
        }
        return sessionFactory;
    }

    /**
     * Get the HikariCP DataSource for direct JDBC access.
     */
    public static com.zaxxer.hikari.HikariDataSource getDataSource() {
        if (dataSource == null) {
            throw new IllegalStateException("DataSource not initialized. " +
                    "Call buildSessionFactory() first.");
        }
        return dataSource;
    }

    /**
     * Check if the SessionFactory has been initialized.
     */
    public static boolean isInitialized() {
        return initialized && sessionFactory != null;
    }

    /**
     * Shutdown the SessionFactory and DataSource.
     */
    public static synchronized void shutdown() {
        if (sessionFactory != null) {
            sessionFactory.close();
            sessionFactory = null;
            initialized = false;
            logger.info("Hibernate SessionFactory shut down");
        }
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            logger.info("HikariCP DataSource shut down");
        }
    }
}
