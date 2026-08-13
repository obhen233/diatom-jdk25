package com.github.obhen233.core.database;

import com.github.obhen233.core.database.entity.*;
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
 * Hibernate configuration manager.
 * Reads diatom.database.* system properties to configure SessionFactory.
 *
 * <p>Configuration rules (system properties → default values):
 * <ul>
 *   <li>{@code diatom.database.url} — JDBC URL (auto-constructed for SQLite if not specified)</li>
 *   <li>{@code diatom.database.username} — DB username (optional, defaults to empty)</li>
 *   <li>{@code diatom.database.password} — DB password (optional, defaults to empty)</li>
 *   <li>{@code diatom.database.pool-size} — HikariCP pool size (default: 2 for SQLite, 10 for others)</li>
 *   <li>{@code diatom.database.hibernatedialect} — Hibernate dialect class (optional, auto-detected if not set)</li>
 *   <li>{@code diatom.database.driver} — JDBC driver class (optional, auto-detected from URL if not set)</li>
 * </ul>
 *
 * <p>When no URL is specified, defaults to SQLite at {@code {workdir}/.diatom/diatom.db}.
 * Hibernate dialect is auto-detected via {@link DiatomDialectResolver} (registered via SPI).
 * For SQLite, the resolver returns the Hibernate community {@link org.hibernate.community.dialect.SQLiteDialect};
 * for other databases, Hibernate's built-in resolver handles detection from JDBC metadata.
 */
public class HibernateConfig {
    private static final Logger logger = LoggerFactory.getLogger(HibernateConfig.class);
    private static final String DB_FILE = ".diatom" + File.separator + "diatom.db";

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

    /**
     * Constructor with explicit parameters (used by tests).
     * The dbType parameter is retained for backward compatibility but
     * dialect/driver are auto-detected from the URL.
     */
    public HibernateConfig(String dbType, String jdbcUrl, String username, String password, int poolSize) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        this.dialectClassName = null; // auto-detect
        this.driverClassName = null;  // auto-detect
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
        String dbPath = baseDir + File.separator + DB_FILE;
        return "jdbc:sqlite:" + dbPath;
    }

    private String maskUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("://([^:]+):([^@]+)@", "://$1:****@");
    }

    /**
     * Get the JDBC URL with database type prefix.
     * E.g., "sqlite", "mysql", "postgresql" — derived from the JDBC URL.
     */
    public String getDbType() {
        if (jdbcUrl == null) return "sqlite";
        if (jdbcUrl.startsWith("jdbc:sqlite:")) return "sqlite";
        if (jdbcUrl.startsWith("jdbc:mysql:")) return "mysql";
        if (jdbcUrl.startsWith("jdbc:postgresql:")) return "postgresql";
        if (jdbcUrl.startsWith("jdbc:mariadb:")) return "mariadb";
        if (jdbcUrl.startsWith("jdbc:h2:")) return "h2";
        // extract from jdbc:xxx: format
        int start = 5; // "jdbc:".length()
        int end = jdbcUrl.indexOf(':', start);
        if (end > start) return jdbcUrl.substring(start, end);
        return "unknown";
    }

    /**
     * Get the configured JDBC URL.
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * Get the configured username.
     */
    public String getUsername() {
        return username;
    }

    /**
     * Get the configured password.
     */
    public String getPassword() {
        return password;
    }

    /**
     * Get the configured HikariCP pool size.
     */
    public int getPoolSize() {
        return poolSize;
    }

    /**
     * Get the Hibernate dialect class name.
     * Returns null to let Hibernate auto-detect via {@link DiatomDialectResolver}.
     */
    public String getDialect() {
        return dialectClassName;
    }

    /**
     * Get the JDBC driver class name.
     * Returns null to let HikariCP auto-detect from the JDBC URL.
     */
    public String getDriverClass() {
        return driverClassName;
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
        // Register DiatomDialectResolver directly (not relying on SPI classloading
        // which may fail under some test environments). Hibernate's built-in
        // StandardDialectResolver handles all standard databases.
        settings.put(Environment.DIALECT_RESOLVERS,
                "com.github.obhen233.core.database.DiatomDialectResolver");
        settings.put(Environment.HBM2DDL_AUTO, "update");
        settings.put(Environment.SHOW_SQL, false);
        settings.put(Environment.FORMAT_SQL, false);

        // Contextual LOB creation
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
        // When driverClassName is not set, HikariCP auto-detects from the JDBC URL
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
     * Annotated entity classes must be registered via {@link #registerEntities(MetadataSources)}.
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
        sources.addAnnotatedClass(CommandHistoryEntity.class);
        sources.addAnnotatedClass(InputHistoryEntity.class);
        sources.addAnnotatedClass(TaskEntity.class);
        sources.addAnnotatedClass(TaskStepEntity.class);
        sources.addAnnotatedClass(TaskCheckpointEntity.class);
        sources.addAnnotatedClass(ChangeLogEntity.class);
        sources.addAnnotatedClass(SnapshotEntity.class);
        sources.addAnnotatedClass(FileSnapshotEntity.class);
        sources.addAnnotatedClass(SnapshotFileEntity.class);
        sources.addAnnotatedClass(CommandRuleEntity.class);
        sources.addAnnotatedClass(SystemConfigEntity.class);
        sources.addAnnotatedClass(SourceCodeExtensionEntity.class);
        sources.addAnnotatedClass(CommandKnowledgeEntity.class);
        sources.addAnnotatedClass(CommandExecutionLogEntity.class);
        sources.addAnnotatedClass(WorkspaceContextEntity.class);
        sources.addAnnotatedClass(ProjectContextEntity.class);
        sources.addAnnotatedClass(GatewayTaskEntity.class);
        sources.addAnnotatedClass(GatewayCheckpointEntity.class);
        sources.addAnnotatedClass(WorkerTaskEntity.class);
        sources.addAnnotatedClass(TopologyDefEntity.class);
        sources.addAnnotatedClass(TopologyVersionEntity.class);
        sources.addAnnotatedClass(HibernateSequenceEntity.class);
    }

    /**
     * Get the singleton SessionFactory (must be initialized first).
     */
    public static SessionFactory getSessionFactory() {
        if (!initialized || sessionFactory == null) {
            throw new IllegalStateException("Hibernate SessionFactory not initialized. " +
                    "Call buildSessionFactory() or HibernateDatabaseManager.initialize() first.");
        }
        return sessionFactory;
    }

    /**
     * Get the HikariCP DataSource for direct JDBC access.
     */
    public static com.zaxxer.hikari.HikariDataSource getDataSource() {
        if (dataSource == null) {
            throw new IllegalStateException("DataSource not initialized. " +
                    "Call buildSessionFactory() or HibernateDatabaseManager.initialize() first.");
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
     * Shutdown the SessionFactory.
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
