package com.github.obhen233.core.database;

import com.github.obhen233.spi.DatabaseDialectProvider;
import com.github.obhen233.spi.SpiLoader;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DiatomDialectResolver} tests.
 *
 * <p>Verifies the dialect SPI path: a {@link DatabaseDialectProvider} registered
 * via {@code META-INF/services/} (test resource {@code FakeDialectProvider})
 * supplies a Hibernate dialect for 信创库-style databases, while SQLite and
 * databases no provider handles keep their existing behavior.</p>
 */
public class DiatomDialectResolverTest {

    @Before
    public void setUp() {
        SpiLoader.reload();
    }

    @Test
    public void sqliteReturnsBuiltInDialect() {
        DiatomDialectResolver resolver = new DiatomDialectResolver();
        Dialect dialect = resolver.resolveDialect(info("sqlite", "org.sqlite.JDBC"));
        assertNotNull("SQLite should be handled locally", dialect);
        assertTrue("expected SQLiteDialect, got " + dialect.getClass().getName(),
                dialect instanceof org.hibernate.community.dialect.SQLiteDialect);
    }

    @Test
    public void providerSuppliesDialectForXinchuangDatabase() {
        DiatomDialectResolver resolver = new DiatomDialectResolver();
        Dialect dialect = resolver.resolveDialect(info("dm dbms", "dm.jdbc.driver.DmDriver"));
        assertNotNull("custom dialect SPI should supply a dialect for 达梦 DM", dialect);
        assertTrue("expected FakeDialect from provider, got " + dialect.getClass().getName(),
                dialect instanceof FakeDialect);
    }

    @Test
    public void providerMayMatchByDriverName() {
        DiatomDialectResolver resolver = new DiatomDialectResolver();
        // databaseName 伪装成 postgresql，但 driverName 是人大金仓 KingbaseES 驱动
        Dialect dialect = resolver.resolveDialect(info("postgresql", "com.kingbase8.Driver"));
        assertNotNull("provider should match by driver name for KingbaseES", dialect);
        assertTrue(dialect instanceof FakeDialect);
    }

    @Test
    public void unhandledDatabaseDefersToHibernate() {
        DiatomDialectResolver resolver = new DiatomDialectResolver();
        // 测试 provider 只处理 "dm dbms" / kingbase8 驱动；标准 PostgreSQL 未被覆盖
        assertNull("unhandled database should return null to defer to Hibernate",
                resolver.resolveDialect(info("postgresql", "org.postgresql.Driver")));
    }

    /** 测试用最小 Dialect 子类（无数据库驱动依赖） */
    public static class FakeDialect extends Dialect {}

    /** 测试用 provider：识别 达梦 DM 数据库名与 KingbaseES 驱动名 */
    public static class FakeDialectProvider implements DatabaseDialectProvider {
        @Override
        public String getDialectClassName(String databaseName, String driverName) {
            if (databaseName != null && databaseName.contains("dm dbms")) {
                return FakeDialect.class.getName();
            }
            if (driverName != null && driverName.contains("kingbase8")) {
                return FakeDialect.class.getName();
            }
            return null;
        }
    }

    private static DialectResolutionInfo info(String databaseName, String driverName) {
        return new DialectResolutionInfo() {
            @Override public String getDatabaseName() { return databaseName; }
            @Override public String getDatabaseVersion() { return "0.0"; }
            @Override public int getDatabaseMajorVersion() { return 0; }
            @Override public int getDatabaseMinorVersion() { return 0; }
            @Override public String getDriverName() { return driverName; }
            @Override public int getDriverMajorVersion() { return 0; }
            @Override public int getDriverMinorVersion() { return 0; }
            @Override public String getSQLKeywords() { return null; }
        };
    }
}
