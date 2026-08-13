package com.github.obhen233.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * JsonUtils 测试用例
 */
public class JsonUtilsTest {

    @Test
    public void testToJsonSimple() {
        Map<String, String> obj = new HashMap<>();
        obj.put("key", "value");

        String json = JsonUtils.toJson(obj);
        assertTrue(json.contains("key"));
        assertTrue(json.contains("value"));
    }

    @Test
    public void testToJsonList() {
        List<String> list = Arrays.asList("a", "b", "c");
        String json = JsonUtils.toJson(list);
        assertTrue(json.contains("a"));
        assertTrue(json.contains("b"));
        assertTrue(json.contains("c"));
    }

    @Test
    public void testFromJson() {
        String json = "{\"name\":\"test\",\"value\":123}";
        Map result = JsonUtils.fromJson(json, Map.class);
        assertEquals("test", result.get("name"));
        assertEquals(123, result.get("value"));
    }

    @Test
    public void testEscapeString() {
        String input = "hello \"world\"";
        String escaped = JsonUtils.escapeString(input);
        assertTrue(escaped.startsWith("\""));
        assertTrue(escaped.endsWith("\""));
        assertTrue(escaped.contains("\\\""));
    }

    @Test
    public void testEscapeStringNewlines() {
        String input = "line1\nline2";
        String escaped = JsonUtils.escapeString(input);
        assertTrue(escaped.contains("\\n"));
    }

    @Test
    public void testRoundTrip() {
        Map<String, Object> original = new HashMap<>();
        original.put("name", "test");
        original.put("count", 42);

        String json = JsonUtils.toJson(original);
        Map<String, Object> parsed = JsonUtils.fromJson(json, Map.class);

        assertEquals(original.get("name"), parsed.get("name"));
        assertEquals(original.get("count"), parsed.get("count"));
    }

    @Test(expected = RuntimeException.class)
    public void testFromJsonInvalid() {
        JsonUtils.fromJson("invalid json {", Map.class);
    }
}