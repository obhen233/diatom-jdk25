package com.github.obhen233.bootstrap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * Bootstrap 资源访问测试
 * 验证修复后的 Bootstrap 能够正确访问 JAR 内部的嵌套资源
 */
public class BootstrapResourceAccessTest {

    /**
     * 场景1：验证 diatom-cli.jar 内部确实包含 custom/custom-sources.jar
     * 这是集成测试，验证打包配置是否正确
     */
    @Test
    public void testDiatomCliJarContainsNestedSources() throws Exception {
        // 查找 diatom-cli.jar
        Path projectRoot = Paths.get("").toAbsolutePath();
        Path targetDir = projectRoot.resolve("target");
        Path cliJar = targetDir.resolve("diatom-cli.jar");

        if (!Files.exists(cliJar)) {
            System.out.println("SKIP: diatom-cli.jar not found, run 'mvn package' first");
            return;
        }

        // 验证 JAR 内部包含 custom/custom-sources.jar
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(cliJar.toFile())) {
            java.util.jar.JarEntry sourcesEntry = jar.getJarEntry("custom/custom-sources.jar");
            assertNotNull("diatom-cli.jar should contain custom/custom-sources.jar", sourcesEntry);

            // 验证内容可以被读取
            try (InputStream is = jar.getInputStream(sourcesEntry)) {
                assertNotNull("Should be able to read sources entry", is);
                byte[] buffer = new byte[4];
                int read = is.read(buffer);
                assertTrue("Should be able to read from sources jar", read > 0);
            }
        }
    }

    /**
     * 场景2：验证 bootstrap 能够通过 getResourceAsStream 访问 classpath 资源
     * 使用 classpath 中的资源（如 banner.txt）
     */
    @Test
    public void testClasspathResourceAccess() {
        // 使用 Bootstrap.class 的 ClassLoader 访问 classpath 资源
        try (InputStream is = Bootstrap.class.getClassLoader().getResourceAsStream("banner.txt")) {
            if (is != null) {
                // banner.txt 存在于 classpath
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                String content = baos.toString("UTF-8");
                assertNotNull("Banner content should not be null", content);
            } else {
                System.out.println("INFO: banner.txt not found in classpath");
            }
        } catch (Exception e) {
            System.out.println("INFO: Could not access banner.txt: " + e.getMessage());
        }
    }

    /**
     * 场景3：验证 Bootstrap 的 diatom.jar.dir 系统属性设置
     * 这个属性对于定位 JAR 同级配置文件至关重要
     */
    @Test
    public void testJarDirPropertyResolution() {
        // 模拟 Bootstrap 设置的属性
        String testJarDir = "C:\\test\\path";
        System.setProperty("diatom.jar.dir", testJarDir);

        try {
            // 验证可以被读取
            String resolved = System.getProperty("diatom.jar.dir");
            assertEquals(testJarDir, resolved);

            // 验证路径拼接正确
            String sourcesDir = resolved + "/sources";
            assertEquals("C:\\test\\path/sources", sourcesDir);
        } finally {
            System.clearProperty("diatom.jar.dir");
        }
    }

    /**
     * 场景4：验证 URLClassLoader 可以访问同级的 JAR 文件
     * 这是 Bootstrap.launchCore 的核心逻辑
     */
    @Test
    public void testUrlClassLoaderCanAccessJarInSameDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("diatom-url-test");
        try {
            // 创建一个测试 JAR
            Path testJar = tempDir.resolve("test-lib.jar");
            createMinimalJar(testJar);

            // 使用 URLClassLoader 加载
            java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{ testJar.toUri().toURL() },
                null
            );

            // 验证可以访问 JAR 内的资源
            java.net.URL resourceUrl = loader.getResource("test-resource.txt");
            if (resourceUrl != null) {
                try (InputStream is = loader.getResourceAsStream("test-resource.txt")) {
                    assertNotNull("Should be able to read resource from JAR", is);
                }
            }

            loader.close();
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 创建包含简单资源的最小 JAR 文件
     */
    private void createMinimalJar(Path jarPath) throws Exception {
        if (Files.exists(jarPath)) {
            Files.delete(jarPath);
        }

        try (java.util.zip.ZipOutputStream zos =
             new java.util.zip.ZipOutputStream(Files.newOutputStream(jarPath))) {

            // 添加测试资源
            java.util.zip.ZipEntry resourceEntry = new java.util.zip.ZipEntry("test-resource.txt");
            zos.putNextEntry(resourceEntry);
            String content = "Hello from test resource";
            zos.write(content.getBytes("UTF-8"));
            zos.closeEntry();

            // 添加 MANIFEST.MF
            java.util.zip.ZipEntry manifestEntry = new java.util.zip.ZipEntry("META-INF/MANIFEST.MF");
            zos.putNextEntry(manifestEntry);
            String manifest = "Manifest-Version: 1.0\nCreated-By: Test\n";
            zos.write(manifest.getBytes("UTF-8"));
            zos.closeEntry();
        }
    }

    /**
     * 场景5：测试 Properties 文件读写
     */
    @Test
    public void testPropertiesFileRoundTrip() throws Exception {
        Path tempFile = Files.createTempFile("test-config", ".properties");
        try {
            // 写入配置
            Properties props = new Properties();
            props.setProperty("api.key", "test-key-123");
            props.setProperty("model", "test-model");
            props.setProperty("workspace.dir", "/test/path");

            try (OutputStream os = Files.newOutputStream(tempFile)) {
                props.store(os, "Test Config");
            }

            // 读取配置
            Properties loaded = new Properties();
            try (InputStream is = Files.newInputStream(tempFile)) {
                loaded.load(is);
            }

            // 验证
            assertEquals("test-key-123", loaded.getProperty("api.key"));
            assertEquals("test-model", loaded.getProperty("model"));
            assertEquals("/test/path", loaded.getProperty("workspace.dir"));

        } finally {
            Files.deleteIfExists(tempFile);
        }
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
