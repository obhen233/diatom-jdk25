package com.github.obhen233.config;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * AppConfig 测试用例
 * 测试配置加载优先级：
 * 1. JAR同级目录的application.properties（最高优先级）
 * 2. {jarDir}/.diatom/application.properties（安装级配置）
 * 3. classpath内置默认配置（最低优先级）
 *
 * 注意：这些测试使用完全隔离的临时目录来避免用户真实配置干扰
 */
public class AppConfigTest {

    private String originalJarDir;

    @Before
    public void setUp() throws Exception {
        // 保存原始状态
        originalJarDir = System.getProperty("diatom.jar.dir");
    }

    @After
    public void tearDown() throws Exception {
        // 恢复原始系统属性
        if (originalJarDir != null) {
            System.setProperty("diatom.jar.dir", originalJarDir);
        } else {
            System.clearProperty("diatom.jar.dir");
        }
        clearDatabaseSystemProps();
    }

    /**
     * 场景1：JAR同级有配置， install级也有配置
     * 期望：优先使用JAR同级配置
     */
    @Test
    public void testLoadConfig_JarSideHasHigherPriorityThanInstallDir() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-test");
        try {
            // 设置系统属性模拟Bootstrap设置的jar目录
            System.setProperty("diatom.jar.dir", tempDir.toString());

            // 创建JAR同级配置
            Path jarSideConfig = tempDir.resolve("application.properties");
            String jarConfigContent = "api.key=jar-key-123\napi.model=jar-model";
            Files.write(jarSideConfig, jarConfigContent.getBytes());

            // 创建install级配置（{jarDir}/.diatom/）
            Path installConfig = tempDir.resolve(".diatom").resolve("application.properties");
            Files.createDirectories(installConfig.getParent());
            String installConfigContent = "api.key=install-key-456\napi.model=install-model";
            Files.write(installConfig, installConfigContent.getBytes());

            AppConfig config = new AppConfig();

            // 验证：JAR同级配置优先
            assertEquals("jar-key-123", config.getApiKey());
            assertEquals("jar-model", config.getModel());
            assertTrue("应该从JAR同级加载", config.getLoadedFromPath().contains("diatom-test"));

        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 场景2：JAR同级没有配置，安装级（{jarDir}/.diatom/）有配置
     * 期望：使用安装级配置
     */
    @Test
    public void testLoadConfig_InstallDirConfigWhenJarSideMissing() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-test");
        try {
            System.setProperty("diatom.jar.dir", tempDir.toString());

            // 不创建JAR同级配置，只创安装级配置
            Path installConfig = tempDir.resolve(".diatom").resolve("application.properties");
            Files.createDirectories(installConfig.getParent());
            String installConfigContent = "api.key=install-key-789\napi.model=install-model-gpt";
            Files.write(installConfig, installConfigContent.getBytes());

            AppConfig config = new AppConfig();

            // 验证：使用安装级配置
            assertEquals("install-key-789", config.getApiKey());
            assertEquals("install-model-gpt", config.getModel());
            assertTrue("应该从安装级加载", config.getLoadedFromPath().contains(".diatom"));

        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 场景3：JAR同级和安装级都没有配置
     * 期望：使用classpath内置默认配置
     */
    @Test
    public void testLoadConfig_ClasspathDefaultWhenBothMissing() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-test");
        try {
            System.setProperty("diatom.jar.dir", tempDir.toString());

            // 不创建任何配置文件
            AppConfig config = new AppConfig();

            // 验证：使用classpath默认配置
            assertNotNull(config.getLoadedFromPath());
            assertTrue("应该从classpath加载", config.getLoadedFromPath().startsWith("classpath:"));

            // 默认值应该来自classpath
            assertNotNull(config.getModel());

        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 场景4：syncToUserDir - JAR同级有配置，同步到安装级（{jarDir}/.diatom/）
     * 期望：配置文件被复制到安装级目录
     */
    @Test
    public void testSyncToUserDir_JarSideCopiesToInstallDir() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-test");
        try {
            System.setProperty("diatom.jar.dir", tempDir.toString());

            // 创建JAR同级配置
            Path jarSideConfig = tempDir.resolve("application.properties");
            String jarConfigContent = "api.key=sync-test-key\napi.model=sync-model";
            Files.write(jarSideConfig, jarConfigContent.getBytes());

            // 确保安装级下没有配置
            Path installConfig = tempDir.resolve(".diatom").resolve("application.properties");
            Files.deleteIfExists(installConfig);

            AppConfig config = new AppConfig();
            config.syncToUserDir();

            // 验证：安装级应该被创建/更新
            assertTrue("安装级配置应该存在", Files.exists(installConfig));

            // 验证：内容与JAR同级一致
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(installConfig)) {
                props.load(is);
            }
            assertEquals("sync-test-key", props.getProperty("api.key"));
            assertEquals("sync-model", props.getProperty("api.model"));

        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 场景5：syncToUserDir - JAR同级没有配置，安装级已有配置
     * 期望：不修改安装级的配置
     */
    @Test
    public void testSyncToUserDir_InstallDirConfigPreservedWhenJarSideMissing() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-test");
        try {
            System.setProperty("diatom.jar.dir", tempDir.toString());

            // 创建安装级配置
            Path installConfig = tempDir.resolve(".diatom").resolve("application.properties");
            Files.createDirectories(installConfig.getParent());
            String originalContent = "api.key=original-key\napi.model=original-model";
            Files.write(installConfig, originalContent.getBytes());
            long originalLastModified = Files.getLastModifiedTime(installConfig).toMillis();

            // 等待一小段时间确保能检测到时间变化
            Thread.sleep(10);

            AppConfig config = new AppConfig();
            config.syncToUserDir();

            // 验证：安装级配置未被修改
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(installConfig)) {
                props.load(is);
            }
            assertEquals("original-key", props.getProperty("api.key"));
            assertEquals("original-model", props.getProperty("api.model"));

        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 场景6：getJarDirectory 优先使用 diatom.jar.dir 系统属性
     */
    @Test
    public void testGetJarDirectory_UsesSystemPropertyFirst() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-test-custom");
        try {
            // 设置系统属性
            System.setProperty("diatom.jar.dir", tempDir.toString());

            // 使用反射验证 getJarDirectory 行为
            AppConfig config = new AppConfig();

            // 创建一个新的 AppConfig，它会使用设置的 jar.dir
            // 通过检查加载路径来验证
            Path expectedConfig = tempDir.resolve("application.properties");
            Files.write(expectedConfig, "test=value".getBytes());

            // 重新创建 config 验证它使用新的 jar.dir
            AppConfig config2 = new AppConfig();
            assertTrue("应该从自定义临时目录加载", config2.getLoadedFromPath().contains("diatom-test-custom"));

        } finally {
            System.clearProperty("diatom.jar.dir");
            deleteDirectory(tempDir);
        }
    }

    /**
     * 场景7：exportDatabaseProps - 配置文件里的 diatom.database.* 桥接到 System 属性
     * 期望：配置加载后 System.getProperty 能看到数据库配置
     */
    @Test
    public void testExportDatabaseProps_BridgesToSystem() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-test-db");
        try {
            System.setProperty("diatom.jar.dir", tempDir.toString());

            Path installConfig = tempDir.resolve(".diatom").resolve("application.properties");
            Files.createDirectories(installConfig.getParent());
            String content = "api.key=not-bridged\n"
                    + "diatom.database.url=jdbc:dm://127.0.0.1:5236\n"
                    + "diatom.database.username=sysdba\n"
                    + "diatom.database.password=secret\n"
                    + "diatom.database.pool-size=5\n"
                    + "diatom.database.hibernatedialect=com.foo.DmDialect\n"
                    + "diatom.database.driver=dm.jdbc.driver.DmDriver\n";
            Files.write(installConfig, content.getBytes());

            new AppConfig();

            assertEquals("jdbc:dm://127.0.0.1:5236", System.getProperty("diatom.database.url"));
            assertEquals("sysdba", System.getProperty("diatom.database.username"));
            assertEquals("secret", System.getProperty("diatom.database.password"));
            assertEquals("5", System.getProperty("diatom.database.pool-size"));
            assertEquals("com.foo.DmDialect", System.getProperty("diatom.database.hibernatedialect"));
            assertEquals("dm.jdbc.driver.DmDriver", System.getProperty("diatom.database.driver"));
            // 非 diatom.database.* 键不导出
            assertNull(System.getProperty("api.key"));
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 场景8：exportDatabaseProps - 已有 System 属性（-D JVM 参数）优先，不被配置覆盖
     */
    @Test
    public void testExportDatabaseProps_DoesNotOverrideExistingSystemProperty() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-test-db");
        try {
            System.setProperty("diatom.jar.dir", tempDir.toString());
            System.setProperty("diatom.database.url", "-D-value");

            Path installConfig = tempDir.resolve(".diatom").resolve("application.properties");
            Files.createDirectories(installConfig.getParent());
            String content = "diatom.database.url=file-value\n";
            Files.write(installConfig, content.getBytes());

            new AppConfig();

            assertEquals("-D-value", System.getProperty("diatom.database.url"));
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 场景9：exportDatabaseProps - 空值跳过，不写入 System 属性
     */
    @Test
    public void testExportDatabaseProps_SkipsEmptyValues() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-test-db");
        try {
            System.setProperty("diatom.jar.dir", tempDir.toString());

            Path installConfig = tempDir.resolve(".diatom").resolve("application.properties");
            Files.createDirectories(installConfig.getParent());
            String content = "diatom.database.url=\n";
            Files.write(installConfig, content.getBytes());

            new AppConfig();

            assertNull(System.getProperty("diatom.database.url"));
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 辅助方法：清理 testExportDatabaseProps 可能写入的 System 属性
     */
    private void clearDatabaseSystemProps() {
        System.clearProperty("diatom.database.url");
        System.clearProperty("diatom.database.username");
        System.clearProperty("diatom.database.password");
        System.clearProperty("diatom.database.pool-size");
        System.clearProperty("diatom.database.hibernatedialect");
        System.clearProperty("diatom.database.driver");
    }

    /**
     * 辅助方法：递归删除目录
     */
    private void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
            }
        } catch (IOException e) {
            // ignore
        }
    }
}
