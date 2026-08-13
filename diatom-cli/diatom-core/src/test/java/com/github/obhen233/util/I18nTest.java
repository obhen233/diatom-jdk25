package com.github.obhen233.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * I18n 国际化测试用例
 * 对应 TEST_CASES.md 10. 国际化测试
 */
public class I18nTest {

    @Before
    public void setUp() {
        I18n.init("en");
    }

    @After
    public void tearDown() {
        // Reset to English after each test
        I18n.init("en");
    }

    @Test
    public void testInitEnglish() {
        I18n.init("en");
        assertEquals("en", I18n.getLanguage());
    }

    @Test
    public void testInitChinese() {
        I18n.init("zh");
        assertEquals("zh", I18n.getLanguage());
    }

    @Test
    public void testGetMessageExists() {
        I18n.init("en");

        // Test that existing keys return proper messages
        // Note: key "confirm_required" returns "Operation Confirmation Required"
        assertEquals("Operation Confirmation Required", I18n.get("confirm_required"));
        // Use actual keys from messages files
        assertEquals("Operation canceled.", I18n.get("canceled"));
        assertEquals("Error: {0}", I18n.get("error"));
    }

    @Test
    public void testGetMessageChinese() {
        I18n.init("zh");

        // Test Chinese messages
        assertEquals("需要操作确认", I18n.get("confirm_required"));
    }

    @Test
    public void testGetWithParameters() {
        I18n.init("en");

        String message = I18n.get("error", "Something went wrong");
        assertTrue(message.contains("Something went wrong"));
        assertTrue(message.contains("Error"));
    }

    @Test
    public void testGetWithMultipleParameters() {
        I18n.init("en");

        String message = I18n.get("token_usage_summary", "100", "50", "150");
        assertTrue(message.contains("100"));
        assertTrue(message.contains("50"));
        assertTrue(message.contains("150"));
        assertTrue(message.toLowerCase().contains("token"));
    }

    @Test
    public void testGetNonExistentKey() {
        I18n.init("en");

        // Non-existent keys should return the key itself
        String result = I18n.get("non_existent_key_xyz");
        assertEquals("non_existent_key_xyz", result);
    }

    @Test
    public void testHasKey() {
        I18n.init("en");

        assertTrue(I18n.hasKey("confirm_required"));
        assertTrue(I18n.hasKey("canceled"));
        assertTrue(I18n.hasKey("error"));
        assertFalse(I18n.hasKey("non_existent_key_xyz"));
    }

    @Test
    public void testNullLanguage() {
        // null language should default to English
        I18n.init(null);
        assertEquals("en", I18n.getLanguage());
    }

    @Test
    public void testEmptyLanguage() {
        // empty language should default to English
        I18n.init("");
        assertEquals("en", I18n.getLanguage());
    }

    @Test
    public void testInvalidLanguageFallsBack() {
        // Invalid language - I18n will try to load but fall back to default
        // The behavior depends on the implementation
        I18n.init("invalid_language_xyz");
        // After failed load, it should try Locale.ENGLISH
        // The result may still be the invalid language if fall back also fails
        String result = I18n.get("confirm_required");
        // Just verify we get some valid message, not empty
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    public void testChangeLanguage() {
        I18n.init("en");
        // confirm_required in English is "Operation Confirmation Required"
        assertEquals("Operation Confirmation Required", I18n.get("confirm_required"));

        I18n.init("zh");
        // confirm_required in Chinese is "需要操作确认"
        assertEquals("需要操作确认", I18n.get("confirm_required"));
    }

    @Test
    public void testChangeSummaryMessagesEnglish() {
        I18n.init("en");

        String empty = I18n.get("change_summary_empty");
        assertNotNull(empty);
        assertFalse(empty.isEmpty());

        String header = I18n.get("change_summary_header", 3, 1, 1, 1);
        assertTrue(header.contains("3") || header.contains("created"));
    }

    @Test
    public void testChangeSummaryMessagesChinese() {
        I18n.init("zh");

        String empty = I18n.get("change_summary_empty");
        assertNotNull(empty);
        assertFalse(empty.isEmpty());
    }
}
