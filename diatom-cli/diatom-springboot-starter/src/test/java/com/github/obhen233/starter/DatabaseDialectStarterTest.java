package com.github.obhen233.starter;

import com.github.obhen233.core.database.DiatomDialectResolver;
import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.spi.DatabaseDialectProvider;
import com.github.obhen233.spi.SpiLoader;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Starter 侧验证数据库配置桥接 + 信创库方言 SPI 链路。
 *
 * <p>覆盖两件事：
 * <ul>
 *   <li>{@link SharedAutoConfiguration#hibernateConfig(DiatomProperties)} 把 Spring
 *       配置属性（{@code DiatomProperties.Database}）桥接到 System 属性并构造
 *       {@link HibernateConfig}——这是 starter 的 DB 配置入口。</li>
 *   <li>starter 应用 classpath 提供的 {@link DatabaseDialectProvider}（test-resources
 *       service 文件注册 {@link FakeDialectProvider}）被 {@link DiatomDialectResolver}
 *       消费——信创库方言自动探测链路。</li>
 * </ul>
 */
public class DatabaseDialectStarterTest {

    @Before
    public void setUp() {
        // 重置 SPI 状态，确保 test-resources 里的 FakeDialectProvider 被加载
        SpiLoader.reload();
    }

    @After
    public void tearDown() {
        System.clearProperty("diatom.database.url");
        System.clearProperty("diatom.database.username");
        System.clearProperty("diatom.database.password");
        System.clearProperty("diatom.database.pool-size");
        System.clearProperty("diatom.database.hibernatedialect");
        System.clearProperty("diatom.database.driver");
    }

    /** Spring 配置 → System 属性桥接 → HibernateConfig 字段（starter 专属链路） */
    @Test
    public void hibernateConfigBridgesDiatomProperties() {
        DiatomProperties props = new DiatomProperties();
        DiatomProperties.Database db = props.getDatabase();
        db.setUrl("jdbc:dm://127.0.0.1:5236");
        db.setUsername("sysdba");
        db.setPassword("secret");
        db.setPoolSize(5);
        db.setDialect("com.foo.DmDialect");
        db.setDriver("dm.jdbc.driver.DmDriver");

        HibernateConfig config = new SharedAutoConfiguration().hibernateConfig(props);

        assertEquals("jdbc:dm://127.0.0.1:5236", config.getJdbcUrl());
        assertEquals("sysdba", config.getUsername());
        assertEquals("secret", config.getPassword());
        assertEquals("com.foo.DmDialect", config.getDialect());
        assertEquals("dm.jdbc.driver.DmDriver", config.getDriverClass());
        // System 属性也被桥接（HibernateConfig 只读 System.getProperty）
        assertEquals("jdbc:dm://127.0.0.1:5236", System.getProperty("diatom.database.url"));
    }

    /** starter classpath 提供的 DatabaseDialectProvider 被 DiatomDialectResolver 消费 */
    @Test
    public void dialectSpiProvidesDialectForXinchuangDatabase() {
        DiatomDialectResolver resolver = new DiatomDialectResolver();
        Dialect dialect = resolver.resolveDialect(info("dm dbms", "dm.jdbc.driver.DmDriver"));
        assertNotNull("custom dialect SPI should supply a dialect for 达梦 DM", dialect);
        assertTrue("expected FakeDialect from starter-provided SPI, got " + dialect.getClass().getName(),
                dialect instanceof FakeDialect);
    }

    /** 未配置任何 DB 属性时，走默认 SQLite 路径 */
    @Test
    public void emptyDatabaseConfigDefaultsToSqlite() {
        DiatomProperties props = new DiatomProperties();
        HibernateConfig config = new SharedAutoConfiguration().hibernateConfig(props);
        assertTrue("default config should fall back to SQLite, got " + config.getJdbcUrl(),
                config.getJdbcUrl().startsWith("jdbc:sqlite:"));
    }

    /** 空值不覆盖已有 System 属性（setSystemPropertyIfNotEmpty 语义） */
    @Test
    public void emptyBridgeValuesLeaveSystemPropertiesUntouched() {
        System.setProperty("diatom.database.url", "jdbc:postgresql://localhost:5432/diatom");
        DiatomProperties props = new DiatomProperties(); // url 默认为空串
        new SharedAutoConfiguration().hibernateConfig(props);
        assertEquals("jdbc:postgresql://localhost:5432/diatom",
                System.getProperty("diatom.database.url"));
    }

    /** 测试用最小 Dialect 子类（无数据库驱动依赖） */
    public static class FakeDialect extends Dialect {}

    /** 测试用 provider：识别 达梦 DM 驱动 */
    public static class FakeDialectProvider implements DatabaseDialectProvider {
        @Override
        public String getDialectClassName(String databaseName, String driverName) {
            if (driverName != null && driverName.startsWith("dm.jdbc")) {
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
