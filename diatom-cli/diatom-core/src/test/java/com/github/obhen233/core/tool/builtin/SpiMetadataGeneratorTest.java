package com.github.obhen233.core.tool.builtin;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for SpiMetadataGenerator
 */
public class SpiMetadataGeneratorTest {

    @Test
    public void testGenerateMetadataContainsCoreVersion() throws Exception {
        String json = SpiMetadataGenerator.generateSpiMetadata("1.0.0");

        assertNotNull(json);
        assertTrue(json.contains("\"coreVersion\""));
        assertTrue(json.contains("1.0.0"));
    }

    @Test
    public void testGenerateMetadataContainsSpiInterfaces() throws Exception {
        String json = SpiMetadataGenerator.generateSpiMetadata("1.0.0");

        assertNotNull(json);
        // Should contain known SPI interfaces
        assertTrue(json.contains("com.github.obhen233.spi.ToolRegistrar"));
        assertTrue(json.contains("com.github.obhen233.spi.UpgradePolicy"));
    }

    @Test
    public void testGenerateMetadataContainsMethods() throws Exception {
        String json = SpiMetadataGenerator.generateSpiMetadata("1.0.0");

        assertNotNull(json);
        // ToolRegistrar should have registerTools method
        assertTrue(json.contains("registerTools"));
        // UpgradePolicy should have shouldUpgrade method
        assertTrue(json.contains("shouldUpgrade"));
    }

    @Test
    public void testGenerateMetadataContainsTimestamp() throws Exception {
        String json = SpiMetadataGenerator.generateSpiMetadata("1.0.0");

        assertNotNull(json);
        assertTrue(json.contains("\"generatedAt\""));
    }

    @Test
    public void testGenerateMetadataIsValidJson() throws Exception {
        String json = SpiMetadataGenerator.generateSpiMetadata("1.0.0");

        assertNotNull(json);
        // Should be valid JSON (parseable)
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);

        assertNotNull(node);
        assertEquals("1.0.0", node.get("coreVersion").asText());
        assertTrue(node.has("interfaces"));
        assertTrue(node.has("generatedAt"));
    }

    @Test
    public void testGenerateMetadataContainsCustomToolApi() throws Exception {
        String json = SpiMetadataGenerator.generateSpiMetadata("1.0.0");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);

        assertTrue(node.has("customToolApi"));
        String customToolApi = node.get("customToolApi").toString();
        assertTrue(customToolApi.contains("ToolRegistry"));
        assertTrue(customToolApi.contains("scanObject"));
        assertTrue(customToolApi.contains("ToolMethod"));
    }

    @Test
    public void testGenerateMetadataInterfaceHasRequiredFields() throws Exception {
        String json = SpiMetadataGenerator.generateSpiMetadata("1.0.0");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);

        com.fasterxml.jackson.databind.JsonNode interfaces = node.get("interfaces");
        assertTrue(interfaces.isArray());
        assertTrue(interfaces.size() > 0);

        // Check first interface has required fields
        com.fasterxml.jackson.databind.JsonNode firstInterface = interfaces.get(0);
        assertTrue(firstInterface.has("name"));
        assertTrue(firstInterface.has("simpleName"));
        assertTrue(firstInterface.has("methods"));
    }

    @Test
    public void testGenerateMetadataMethodHasRequiredFields() throws Exception {
        String json = SpiMetadataGenerator.generateSpiMetadata("1.0.0");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);

        com.fasterxml.jackson.databind.JsonNode interfaces = node.get("interfaces");
        for (com.fasterxml.jackson.databind.JsonNode iface : interfaces) {
            if (iface.get("methods").size() > 0) {
                com.fasterxml.jackson.databind.JsonNode method = iface.get("methods").get(0);
                assertTrue(method.has("name"));
                assertTrue(method.has("returnType"));
                assertTrue(method.has("parameters"));
                break;
            }
        }
    }

    @Test
    public void testGenerateMetadataDifferentVersions() throws Exception {
        String json1 = SpiMetadataGenerator.generateSpiMetadata("1.0.0");
        String json2 = SpiMetadataGenerator.generateSpiMetadata("2.0.0");

        assertNotNull(json1);
        assertNotNull(json2);
        assertTrue(json1.contains("1.0.0"));
        assertTrue(json2.contains("2.0.0"));
    }
}
