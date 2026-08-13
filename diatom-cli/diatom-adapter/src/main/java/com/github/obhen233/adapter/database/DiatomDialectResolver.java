package com.github.obhen233.adapter.database;

import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolver;

/**
 * Custom {@link DialectResolver} that handles databases not covered by
 * Hibernate's built-in resolver (e.g., SQLite).
 * <p>
 * Only resolves SQLite to the community {@link org.hibernate.community.dialect.SQLiteDialect}.
 * For all other databases, returns {@code null} to let Hibernate's built-in resolver handle them.
 * <p>
 * Extension point: to support a custom/Xinchuang database dialect, either
 * specify a dialect class explicitly via the {@code diatom.database.hibernatedialect}
 * property, or provide your own {@code DialectResolver} via ServiceLoader
 * ({@code META-INF/services/org.hibernate.dialect.DialectResolver}).
 * Registered via {@code META-INF/services/org.hibernate.dialect.DialectResolver}.
 */
public class DiatomDialectResolver implements DialectResolver {

    @Override
    public Dialect resolveDialect(DialectResolutionInfo info) {
        String name = info.getDatabaseName() != null ? info.getDatabaseName().toLowerCase() : "";
        if (name.contains("sqlite")) {
            return new org.hibernate.community.dialect.SQLiteDialect();
        }
        // Return null to let Hibernate's built-in resolver handle it
        return null;
    }
}
