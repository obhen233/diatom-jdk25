package com.github.obhen233.spi;

/**
 * Database dialect provider SPI for databases Hibernate's built-in resolver
 * does not recognize.
 *
 * <p>Primarily aimed at 信创库 (domestic databases) such as 达梦 DM, 人大金仓
 * KingbaseES, GaussDB and OceanBase, whose JDBC {@code DatabaseMetaData}
 * product name is often hidden behind a standard SQL mode (PG / MySQL / Oracle
 * compatible). Implementations receive both the database product name and the
 * driver class name so they can pick the correct Hibernate {@code Dialect}.</p>
 *
 * <p>Implementations are discovered via {@link SpiLoader} (classpath via
 * {@code ServiceLoader} first, then plugins) and return the fully-qualified
 * class name of the {@code org.hibernate.dialect.Dialect} subclass to use, or
 * {@code null} to skip. Returning a class <em>name</em> (rather than an
 * instance) keeps the SPI safe across the isolated plugin classloaders.</p>
 */
public interface DatabaseDialectProvider {

    /**
     * Return the Hibernate dialect class name for the given database, or {@code null}.
     *
     * @param databaseName JDBC database product name from {@code DatabaseMetaData}
     *                     (lower-cased), e.g. {@code "dm dbms"}, {@code "kingbasees"},
     *                     {@code "gaussdb"}, {@code "postgresql"}
     * @param driverName   JDBC driver class name from {@code DatabaseMetaData},
     *                     e.g. {@code "dm.jdbc.driver.DmDriver"},
     *                     {@code "com.kingbase8.Driver"}
     * @return fully-qualified {@code org.hibernate.dialect.Dialect} subclass name,
     *         or {@code null} if this provider does not handle the database
     */
    String getDialectClassName(String databaseName, String driverName);
}
