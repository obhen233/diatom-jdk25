package com.github.obhen233.core.database;

import com.github.obhen233.spi.DatabaseDialectProvider;
import com.github.obhen233.spi.SpiLoader;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom {@link DialectResolver} that handles databases not covered by
 * Hibernate's built-in resolver (e.g., SQLite).
 * <p>
 * Resolution order:
 * <ol>
 *   <li>SQLite — always handled locally</li>
 *   <li>{@link DatabaseDialectProvider} SPI implementations (classpath + plugins) —
 *       the extension point for 信创库 / other unrecognized databases</li>
 *   <li>Delegation to Hibernate's standard resolver ({@code null} return)</li>
 * </ol>
 * <p>
 * Registered via {@code META-INF/services/org.hibernate.dialect.DialectResolver}.
 */
public class DiatomDialectResolver implements DialectResolver {

    private static final Logger logger = LoggerFactory.getLogger(DiatomDialectResolver.class);

    @Override
    public Dialect resolveDialect(DialectResolutionInfo info) {
        String name = info.getDatabaseName() != null ? info.getDatabaseName().toLowerCase() : "";
        if (name.contains("sqlite")) {
            return new org.hibernate.community.dialect.SQLiteDialect();
        }

        String driverName = info.getDriverName() != null ? info.getDriverName() : "";
        // Ensure SPI is loaded even if Hibernate initializes the SessionFactory
        // before the application startup sequence has run SpiLoader.loadAll().
        SpiLoader.loadAll();

        for (DatabaseDialectProvider provider : SpiLoader.getAll(DatabaseDialectProvider.class)) {
            String dialectClassName = provider.getDialectClassName(name, driverName);
            if (dialectClassName == null || dialectClassName.trim().isEmpty()) {
                continue;
            }
            Dialect dialect = instantiateDialect(dialectClassName.trim());
            if (dialect != null) {
                logger.info("Using custom Hibernate dialect {} for database '{}' (driver {})",
                        dialectClassName, name, driverName);
                return dialect;
            }
        }
        // Return null to let Hibernate's built-in resolver handle it
        return null;
    }

    private Dialect instantiateDialect(String className) {
        ClassLoader loader = SpiLoader.getPluginClassLoader();
        if (loader == null) {
            loader = getClass().getClassLoader();
        }
        try {
            Class<?> clazz = Class.forName(className, true, loader);
            if (!Dialect.class.isAssignableFrom(clazz)) {
                logger.warn("Dialect class {} is not a subclass of {}, ignoring",
                        className, Dialect.class.getName());
                return null;
            }
            return (Dialect) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            logger.warn("Failed to instantiate Hibernate dialect {}: {}",
                    className, e.getMessage());
            return null;
        }
    }
}
