package com.github.obhen233.core.agent.context;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileReadTrackerTest {

    @Test
    public void testRecordAndCheckFile() throws IOException {
        FileReadTracker tracker = new FileReadTracker();
        
        // Create a temp file
        Path tempFile = Files.createTempFile("test", ".txt");
        String content = "Hello, World!";
        Files.write(tempFile, content.getBytes());
        
        String path = tempFile.toString();
        
        // Initially, file should not be tracked
        assertFalse("File should not be tracked initially", tracker.isFileReadAndUnchanged(path));
        
        // Record the file read
        tracker.recordFileRead(path, content);
        
        // Now, file should be tracked as unchanged
        assertTrue("File should be tracked as read", tracker.isFileReadAndUnchanged(path));
        
        // Get skip message
        String skipMessage = tracker.getSkipMessage(path);
        assertNotNull("Skip message should not be null", skipMessage);
        assertTrue("Skip message should contain path", skipMessage.contains(path));
        
        // Modify the file
        try {
            Thread.sleep(100); // Wait a bit to ensure modification time changes
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Files.write(tempFile, "Modified content".getBytes());
        
        // Now, file should be detected as changed
        assertFalse("File should be detected as changed after modification", tracker.isFileReadAndUnchanged(path));
        
        // Clean up
        Files.delete(tempFile);
    }

    @Test
    public void testInvalidateFile() throws IOException {
        FileReadTracker tracker = new FileReadTracker();
        
        // Create a temp file
        Path tempFile = Files.createTempFile("test", ".txt");
        String content = "Test content";
        Files.write(tempFile, content.getBytes());
        
        String path = tempFile.toString();
        
        // Record the file read
        tracker.recordFileRead(path, content);
        assertTrue("File should be tracked", tracker.isFileReadAndUnchanged(path));
        
        // Invalidate the file
        tracker.invalidateFile(path);
        
        // Now, file should not be tracked
        assertFalse("File should not be tracked after invalidation", tracker.isFileReadAndUnchanged(path));
        
        // Clean up
        Files.delete(tempFile);
    }

    @Test
    public void testInvalidateByPrefix() throws IOException {
        FileReadTracker tracker = new FileReadTracker();
        
        // Create temp files
        Path tempDir = Files.createTempDirectory("testdir");
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        
        Files.write(file1, "Content 1".getBytes());
        Files.write(file2, "Content 2".getBytes());
        
        // Record file reads
        tracker.recordFileRead(file1.toString(), "Content 1");
        tracker.recordFileRead(file2.toString(), "Content 2");
        
        assertTrue("File 1 should be tracked", tracker.isFileReadAndUnchanged(file1.toString()));
        assertTrue("File 2 should be tracked", tracker.isFileReadAndUnchanged(file2.toString()));
        
        // Invalidate by prefix
        tracker.invalidateByPrefix(tempDir.toString());
        
        // Both files should be invalidated
        assertFalse("File 1 should be invalidated", tracker.isFileReadAndUnchanged(file1.toString()));
        assertFalse("File 2 should be invalidated", tracker.isFileReadAndUnchanged(file2.toString()));
        
        // Clean up
        Files.delete(file1);
        Files.delete(file2);
        Files.delete(tempDir);
    }

    @Test
    public void testClear() throws IOException {
        FileReadTracker tracker = new FileReadTracker();
        
        // Create a temp file
        Path tempFile = Files.createTempFile("test", ".txt");
        String content = "Test content";
        Files.write(tempFile, content.getBytes());
        
        String path = tempFile.toString();
        
        // Record the file read
        tracker.recordFileRead(path, content);
        assertEquals("Should have 1 tracked file", 1, tracker.getTrackedFileCount());
        
        // Clear all
        tracker.clear();
        
        assertEquals("Should have 0 tracked files after clear", 0, tracker.getTrackedFileCount());
        
        // Clean up
        Files.delete(tempFile);
    }
}
