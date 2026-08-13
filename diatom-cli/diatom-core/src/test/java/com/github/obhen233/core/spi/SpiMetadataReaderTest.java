package com.github.obhen233.core.spi;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.*;

/**
 * Tests for SpiMetadataReader
 */
public class SpiMetadataReaderTest {

    @Test
    public void testParseValidMetadata() throws Exception {
        String json = createValidMetadataJson();

        Path tempJar = Files.createTempFile("test-spi", ".jar");
        try {
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tempJar))) {
                Manifest manifest = new Manifest();
                JarEntry entry = new JarEntry("META-INF/diatom-spi.json");
                jos.putNextEntry(entry);
                jos.write(json.getBytes(StandardCharsets.UTF_8));
            }

            SpiMetadataReader reader = SpiMetadataReader.loadFromJar(tempJar);

            assertEquals("1.0.0", reader.getCoreVersion());
            assertEquals(1, reader.getSpiInterfaceNames().size());
            assertTrue(reader.getSpiInterfaceNames().contains("com.github.obhen233.spi.ToolRegistrar"));

            SpiMetadataReader.SpiInterface iface = reader.getSpiInterface("com.github.obhen233.spi.ToolRegistrar");
            assertNotNull(iface);
            assertEquals("ToolRegistrar", iface.simpleName);
            assertEquals(1, iface.methods.size());

            SpiMetadataReader.SpiMethod method = iface.methods.get(0);
            assertEquals("registerTools", method.name);
            assertEquals("void", method.returnType);
            assertEquals(1, method.parameters.size());
            assertEquals("ToolRegistry", method.parameters.get(0).simpleType);

        } finally {
            Files.deleteIfExists(tempJar);
        }
    }

    @Test
    public void testCustomToolApiSurvivesParse() throws Exception {
        String json = "{\"coreVersion\":\"1.0.0\",\"interfaces\":[],\"customToolApi\":{\"toolRegistry\":{\"class\":\"ToolRegistry\",\"methods\":[\"void scanObject(Object obj)\"]},\"toolMethodAnnotation\":{\"class\":\"ToolMethod\"}},\"services\":{}}";
        Path jar = createTestJar("custom-tool-api", json);

        try {
            SpiMetadataReader reader = SpiMetadataReader.loadFromJar(jar);
            Map<String, Object> customToolApi = reader.getMetadata().customToolApi;

            assertNotNull(customToolApi);
            assertTrue(customToolApi.containsKey("toolRegistry"));
            assertTrue(customToolApi.toString().contains("scanObject"));
            assertTrue(customToolApi.toString().contains("ToolMethod"));
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    @Test
    public void testCompareNoChanges() throws Exception {
        String json1 = createMetadataJson("1.0.0", "com.github.obhen233.spi.ToolRegistrar");
        String json2 = createMetadataJson("1.0.1", "com.github.obhen233.spi.ToolRegistrar");

        Path jar1 = createTestJar("test1", json1);
        Path jar2 = createTestJar("test2", json2);

        try {
            SpiMetadataReader reader1 = SpiMetadataReader.loadFromJar(jar1);
            SpiMetadataReader reader2 = SpiMetadataReader.loadFromJar(jar2);

            SpiMetadataReader.SpiDiff diff = SpiMetadataReader.compare(reader1, reader2);

            assertFalse(diff.hasChanges());
            assertEquals("1.0.0", diff.oldVersion);
            assertEquals("1.0.1", diff.newVersion);
            assertTrue(diff.addedInterfaces.isEmpty());
            assertTrue(diff.removedInterfaces.isEmpty());
        } finally {
            Files.deleteIfExists(jar1);
            Files.deleteIfExists(jar2);
        }
    }

    @Test
    public void testCompareAddedInterface() throws Exception {
        String json1 = "{\"coreVersion\":\"1.0.0\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"methods\":[]}],\"services\":{}}";
        String json2 = "{\"coreVersion\":\"1.0.1\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"methods\":[]},{\"name\":\"com.github.obhen233.spi.NewInterface\",\"methods\":[]}],\"services\":{}}";

        Path jar1 = createTestJar("test1", json1);
        Path jar2 = createTestJar("test2", json2);

        try {
            SpiMetadataReader reader1 = SpiMetadataReader.loadFromJar(jar1);
            SpiMetadataReader reader2 = SpiMetadataReader.loadFromJar(jar2);

            SpiMetadataReader.SpiDiff diff = SpiMetadataReader.compare(reader1, reader2);

            assertTrue(diff.hasChanges());
            assertEquals(1, diff.addedInterfaces.size());
            assertEquals("com.github.obhen233.spi.NewInterface", diff.addedInterfaces.get(0));
            assertTrue(diff.removedInterfaces.isEmpty());
        } finally {
            Files.deleteIfExists(jar1);
            Files.deleteIfExists(jar2);
        }
    }

    @Test
    public void testCompareRemovedInterface() throws Exception {
        String json1 = "{\"coreVersion\":\"1.0.0\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"methods\":[]},{\"name\":\"com.github.obhen233.spi.OldInterface\",\"methods\":[]}],\"services\":{}}";
        String json2 = "{\"coreVersion\":\"1.0.1\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"methods\":[]}],\"services\":{}}";

        Path jar1 = createTestJar("test1", json1);
        Path jar2 = createTestJar("test2", json2);

        try {
            SpiMetadataReader reader1 = SpiMetadataReader.loadFromJar(jar1);
            SpiMetadataReader reader2 = SpiMetadataReader.loadFromJar(jar2);

            SpiMetadataReader.SpiDiff diff = SpiMetadataReader.compare(reader1, reader2);

            assertTrue(diff.hasChanges());
            assertTrue(diff.addedInterfaces.isEmpty());
            assertEquals(1, diff.removedInterfaces.size());
            assertEquals("com.github.obhen233.spi.OldInterface", diff.removedInterfaces.get(0));
        } finally {
            Files.deleteIfExists(jar1);
            Files.deleteIfExists(jar2);
        }
    }

    @Test
    public void testCompareAddedMethod() throws Exception {
        String json1 = "{\"coreVersion\":\"1.0.0\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"methods\":[{\"name\":\"registerTools\",\"returnType\":\"void\",\"parameters\":[]}]}],\"services\":{}}";
        String json2 = "{\"coreVersion\":\"1.0.1\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"methods\":[{\"name\":\"registerTools\",\"returnType\":\"void\",\"parameters\":[]},{\"name\":\"unregisterTools\",\"returnType\":\"void\",\"parameters\":[]}]}],\"services\":{}}";

        Path jar1 = createTestJar("test1", json1);
        Path jar2 = createTestJar("test2", json2);

        try {
            SpiMetadataReader reader1 = SpiMetadataReader.loadFromJar(jar1);
            SpiMetadataReader reader2 = SpiMetadataReader.loadFromJar(jar2);

            SpiMetadataReader.SpiDiff diff = SpiMetadataReader.compare(reader1, reader2);

            assertTrue(diff.hasChanges());
            assertEquals(1, diff.addedMethods.size());
            assertEquals("com.github.obhen233.spi.ToolRegistrar.unregisterTools", diff.addedMethods.get(0));
            assertTrue(diff.removedMethods.isEmpty());
        } finally {
            Files.deleteIfExists(jar1);
            Files.deleteIfExists(jar2);
        }
    }

    @Test
    public void testCompareRemovedMethod() throws Exception {
        String json1 = "{\"coreVersion\":\"1.0.0\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"methods\":[{\"name\":\"registerTools\",\"returnType\":\"void\",\"parameters\":[]},{\"name\":\"oldMethod\",\"returnType\":\"void\",\"parameters\":[]}]}],\"services\":{}}";
        String json2 = "{\"coreVersion\":\"1.0.1\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"methods\":[{\"name\":\"registerTools\",\"returnType\":\"void\",\"parameters\":[]}]}],\"services\":{}}";

        Path jar1 = createTestJar("test1", json1);
        Path jar2 = createTestJar("test2", json2);

        try {
            SpiMetadataReader reader1 = SpiMetadataReader.loadFromJar(jar1);
            SpiMetadataReader reader2 = SpiMetadataReader.loadFromJar(jar2);

            SpiMetadataReader.SpiDiff diff = SpiMetadataReader.compare(reader1, reader2);

            assertTrue(diff.hasChanges());
            assertTrue(diff.addedMethods.isEmpty());
            assertEquals(1, diff.removedMethods.size());
            assertEquals("com.github.obhen233.spi.ToolRegistrar.oldMethod", diff.removedMethods.get(0));
        } finally {
            Files.deleteIfExists(jar1);
            Files.deleteIfExists(jar2);
        }
    }

    @Test
    public void testToMarkdownReport() throws Exception {
        String json1 = "{\"coreVersion\":\"1.0.0\",\"interfaces\":[],\"services\":{}}";
        String json2 = "{\"coreVersion\":\"1.0.1\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.NewInterface\",\"methods\":[{\"name\":\"newMethod\",\"returnType\":\"void\",\"parameters\":[]}]}],\"services\":{}}";

        Path jar1 = createTestJar("test1", json1);
        Path jar2 = createTestJar("test2", json2);

        try {
            SpiMetadataReader reader1 = SpiMetadataReader.loadFromJar(jar1);
            SpiMetadataReader reader2 = SpiMetadataReader.loadFromJar(jar2);

            SpiMetadataReader.SpiDiff diff = SpiMetadataReader.compare(reader1, reader2);

            String report = diff.toMarkdown();

            assertTrue(report.contains("## SPI Compatibility Report"));
            assertTrue(report.contains("**Old Version**: 1.0.0"));
            assertTrue(report.contains("**New Version**: 1.0.1"));
            assertTrue(report.contains("### Added Interfaces"));
            assertTrue(report.contains("com.github.obhen233.spi.NewInterface"));
            assertTrue(report.contains("### Added Methods"));
            assertTrue(report.contains("newMethod"));
        } finally {
            Files.deleteIfExists(jar1);
            Files.deleteIfExists(jar2);
        }
    }

    @Test
    public void testToMarkdownNoChanges() throws Exception {
        String json = "{\"coreVersion\":\"1.0.0\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"methods\":[]}],\"services\":{}}";

        Path jar = createTestJar("test", json);

        try {
            SpiMetadataReader reader = SpiMetadataReader.loadFromJar(jar);
            SpiMetadataReader.SpiDiff diff = SpiMetadataReader.compare(reader, reader);

            String report = diff.toMarkdown();

            assertTrue(report.contains("No SPI changes detected"));
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    @Test(expected = java.io.IOException.class)
    public void testLoadFromNonExistentJar() throws Exception {
        Path nonExistent = Files.createTempFile("nonexistent", ".jar");
        Files.delete(nonExistent);

        SpiMetadataReader.loadFromJar(nonExistent);
    }

    private String createValidMetadataJson() {
        return "{\"coreVersion\":\"1.0.0\",\"generatedAt\":\"2026-05-19T00:00:00Z\",\"interfaces\":[{\"name\":\"com.github.obhen233.spi.ToolRegistrar\",\"simpleName\":\"ToolRegistrar\",\"description\":\"SPI interface for registering tool components\",\"methods\":[{\"name\":\"registerTools\",\"returnType\":\"void\",\"parameters\":[{\"type\":\"com.github.obhen233.core.tool.ToolRegistry\",\"simpleType\":\"ToolRegistry\"}]}]}],\"services\":{}}";
    }

    private String createMetadataJson(String version, String interfaceName) {
        return "{\"coreVersion\":\"" + version + "\",\"interfaces\":[{\"name\":\"" + interfaceName + "\",\"methods\":[{\"name\":\"registerTools\",\"returnType\":\"void\",\"parameters\":[]}]}],\"services\":{}}";
    }

    private Path createTestJar(String prefix, String json) throws Exception {
        Path tempJar = Files.createTempFile(prefix, ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tempJar))) {
            JarEntry entry = new JarEntry("META-INF/diatom-spi.json");
            jos.putNextEntry(entry);
            jos.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return tempJar;
    }
}
