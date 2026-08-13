package com.github.obhen233.router.local;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * Tests for {@link KeywordStore} persistence.
 */
public class KeywordStoreTest {

    private Path tempFile;
    private KeywordStore store;

    @Before
    public void setUp() throws IOException {
        tempFile = Files.createTempFile("router-keywords-", ".json");
        store = new KeywordStore(tempFile);
    }

    @After
    public void tearDown() throws IOException {
        store.close();
        Files.deleteIfExists(tempFile);
        Files.deleteIfExists(tempFile.resolveSibling(tempFile.getFileName() + ".tmp"));
    }

    @Test
    public void testEmptyStore() {
        assertTrue("New store should be empty", store.allKeywords().isEmpty());
        assertEquals("Size should be 0", 0, store.size());
    }

    @Test
    public void testLearnNewKeyword() {
        store.learn("bubble sort");
        assertEquals("Should have 1 keyword", 1, store.size());
        KeywordEntry entry = store.get("bubble sort");
        assertNotNull("Entry should exist", entry);
        assertEquals("Frequency should be 1", 1, entry.getFrequency());
    }

    @Test
    public void testLearnIsCaseInsensitive() {
        store.learn("Sort");
        store.learn("sort");
        // Should be normalized to "sort"
        assertEquals("Should have 1 keyword (case-insensitive)", 1, store.size());
        KeywordEntry entry = store.get("sort");
        assertNotNull("Entry should exist", entry);
        assertEquals("Frequency should be 2", 2, entry.getFrequency());
    }

    @Test
    public void testReinforceExistingKeyword() {
        store.learn("sorting");
        store.learn("sorting");
        store.learn("sorting");

        assertEquals("Should have 1 keyword", 1, store.size());
        KeywordEntry entry = store.get("sorting");
        assertEquals("Frequency should be 3", 3, entry.getFrequency());
    }

    @Test
    public void testLearnNullText() {
        store.learn(null);
        store.learn("");
        store.learn("   ");
        assertTrue("Null/empty text should not be stored", store.allKeywords().isEmpty());
    }

    @Test
    public void testWeightCalculation() {
        // Frequency 1: weight = min(0.6, 0.2 + 1 * 0.02) = 0.22
        store.learn("keyword1");
        assertEquals(0.22, store.get("keyword1").getWeight(), 0.001);

        // Frequency 20: weight = min(0.6, 0.2 + 20 * 0.02) = 0.6
        store.learn("keyword2"); // 1st learn
        for (int i = 0; i < 19; i++) {
            store.learn("keyword2");
        }
        assertEquals(0.6, store.get("keyword2").getWeight(), 0.001);
    }

    @Test
    public void testSaveAndLoad() throws IOException {
        store.learn("token1");
        store.learn("token2");
        store.learn("token3");
        store.flush();

        // Read raw file to verify format
        String content = new String(Files.readAllBytes(tempFile), "UTF-8");
        assertTrue("JSON should contain token1", content.contains("token1"));
        assertTrue("JSON should contain token2", content.contains("token2"));
        assertTrue("JSON should contain token3", content.contains("token3"));

        // Create a new store from the same file
        KeywordStore loaded = new KeywordStore(tempFile);
        assertEquals("Loaded store should have 3 keywords", 3, loaded.size());
        assertNotNull("token1 should be loaded", loaded.get("token1"));
        assertNotNull("token2 should be loaded", loaded.get("token2"));
        assertNotNull("token3 should be loaded", loaded.get("token3"));
        loaded.close();

        // Clean up temp file created by second store
        // (the first store's file is cleaned in tearDown)
    }

    @Test
    public void testFlushThrottle() {
        store.learn("keyword");
        boolean first = store.save();
        // First save should succeed (not throttled)
        assertTrue("First save should not be throttled", first);

        // Immediate second save should be throttled
        boolean second = store.save();
        assertFalse("Second save within 30s should be throttled", second);
    }

    @Test
    public void testGetNullKeyword() {
        assertNull("Non-existent keyword should return null", store.get("nonexistent"));
    }

    @Test
    public void testContains() {
        assertFalse("Should not contain unlearned keyword", store.contains("unknown"));
        store.learn("known");
        assertTrue("Should contain learned keyword", store.contains("known"));
    }

    @Test
    public void testAllKeywordsReturnsCollection() {
        store.learn("a");
        store.learn("b");
        store.learn("c");

        Collection<KeywordEntry> all = store.allKeywords();
        assertEquals("Should have 3 keywords", 3, all.size());
    }

    @Test
    public void testLoadFromMissingFile() throws IOException {
        Path nonExistent = tempFile.resolveSibling("nonexistent.json");
        KeywordStore emptyStore = new KeywordStore(nonExistent);
        assertTrue("Store from missing file should be empty", emptyStore.allKeywords().isEmpty());
        emptyStore.close();
        Files.deleteIfExists(nonExistent);
    }
}
