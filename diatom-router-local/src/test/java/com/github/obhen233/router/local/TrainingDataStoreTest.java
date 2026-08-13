package com.github.obhen233.router.local;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class TrainingDataStoreTest {

    private Path tempFile;
    private TrainingDataStore store;

    @Before
    public void setUp() throws IOException {
        tempFile = Files.createTempFile("router-training-", ".json");
        store = new TrainingDataStore(tempFile);
    }

    @After
    public void tearDown() throws IOException {
        store.close();
        Files.deleteIfExists(tempFile);
        Files.deleteIfExists(tempFile.resolveSibling(tempFile.getFileName() + ".tmp"));
    }

    @Test
    public void testEmptyStore() {
        assertEquals(0, store.size());
    }

    @Test
    public void testAddEntry() {
        store.add("修复登录bug", "bug_fix");
        assertEquals(1, store.size());
    }

    @Test
    public void testAddMultipleEntries() {
        store.add("修复登录bug", "bug_fix");
        store.add("实现排序功能", "feature");
        store.add("写单元测试", "testing");
        assertEquals(3, store.size());
    }

    @Test
    public void testSaveAndLoad() {
        store.add("修复登录bug", "bug_fix");
        store.add("实现排序功能", "feature");
        store.flush();

        // Create a new store pointing to the same file and verify data loads
        TrainingDataStore loaded = new TrainingDataStore(tempFile);
        assertEquals(2, loaded.size());
        loaded.close();
    }

    @Test
    public void testAllEntries() {
        store.add("message1", "cat1");
        store.add("message2", "cat2");
        assertEquals(2, store.allEntries().size());
    }

    @Test
    public void testFlushThrottle() {
        store.add("msg", "cat");
        assertTrue("First save should succeed", store.flush());
        // Subsequent save should be throttled
        assertFalse("Immediate second save should be throttled", store.save());
    }

    @Test
    public void testClosePreventsWrites() {
        store.close();
        store.add("should", "not_appear");
        assertEquals(0, store.size());
    }

    @Test
    public void testConcurrentAdd() {
        // Simple sanity: add from multiple conceptual "threads"
        for (int i = 0; i < 100; i++) {
            store.add("message" + i, "cat" + (i % 5));
        }
        assertEquals(100, store.size());
    }
}
