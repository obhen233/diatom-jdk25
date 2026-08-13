package com.github.obhen233.core.agent.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks files that have been read to avoid redundant content being sent to LLM.
 * Uses file modification time and size for change detection.
 * 
 * When a cached file is re-requested, returns a useful summary instead of just
 * "already read" to prevent AI from getting stuck in a loop.
 */
public class FileReadTracker {
    private static final Logger logger = LoggerFactory.getLogger(FileReadTracker.class);

    // Track read files: path -> FileReadRecord
    private final Map<String, FileReadRecord> readFiles = new ConcurrentHashMap<>();

    // Maximum size of tracked files (prevent memory issues)
    private static final int MAX_TRACKED_FILES = 1000;
    
    // Maximum lines to include in summary
    private static final int SUMMARY_LINES = 20;

    /**
     * Record representing a file that has been read
     */
    public static class FileReadRecord {
        private final String path;
        private final long size;
        private final FileTime lastModifiedTime;
        private final long readTimestamp;
        private final int contentHash;
        private final String contentSummary;  // First N lines for quick reference
        
        public FileReadRecord(String path, long size, FileTime lastModifiedTime, int contentHash, String contentSummary) {
            this.path = path;
            this.size = size;
            this.lastModifiedTime = lastModifiedTime;
            this.readTimestamp = System.currentTimeMillis();
            this.contentHash = contentHash;
            this.contentSummary = contentSummary;
        }

        public String getPath() { return path; }
        public long getSize() { return size; }
        public FileTime getLastModifiedTime() { return lastModifiedTime; }
        public long getReadTimestamp() { return readTimestamp; }
        public int getContentHash() { return contentHash; }
        public String getContentSummary() { return contentSummary; }
    }

    /**
     * Check if a file has been read and not modified since
     * @param path File path
     * @return true if file was read and not modified, false otherwise
     */
    public boolean isFileReadAndUnchanged(String path) {
        FileReadRecord record = readFiles.get(normalizePath(path));
        if (record == null) {
            return false;
        }

        try {
            Path filePath = Paths.get(path);
            if (!Files.exists(filePath)) {
                // File no longer exists, remove from tracking
                readFiles.remove(normalizePath(path));
                return false;
            }

            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            
            // Check if file has been modified
            boolean sizeChanged = attrs.size() != record.getSize();
            boolean timeChanged = attrs.lastModifiedTime().compareTo(record.getLastModifiedTime()) != 0;
            
            if (sizeChanged || timeChanged) {
                logger.debug("File {} has been modified (size: {} -> {}, time: {} -> {}), will re-read",
                    path, record.getSize(), attrs.size(), 
                    record.getLastModifiedTime(), attrs.lastModifiedTime());
                readFiles.remove(normalizePath(path));
                return false;
            }

            logger.debug("File {} already read and unchanged, returning cached summary", path);
            return true;
        } catch (IOException e) {
            logger.debug("Error checking file status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Record a file as read
     * @param path File path
     * @param content File content (for hash calculation and summary)
     */
    public void recordFileRead(String path, String content) {
        if (readFiles.size() >= MAX_TRACKED_FILES) {
            // Remove oldest entries (simple LRU-like behavior)
            cleanupOldEntries();
        }

        try {
            Path filePath = Paths.get(path);
            if (!Files.exists(filePath)) {
                return;
            }

            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            int contentHash = content != null ? content.hashCode() : 0;
            String summary = generateContentSummary(content);
            
            FileReadRecord record = new FileReadRecord(
                normalizePath(path),
                attrs.size(),
                attrs.lastModifiedTime(),
                contentHash,
                summary
            );
            
            readFiles.put(normalizePath(path), record);
            logger.debug("Recorded file read: {} (size: {}, hash: {})", path, attrs.size(), contentHash);
        } catch (IOException e) {
            logger.debug("Error recording file read: {}", e.getMessage());
        }
    }

    /**
     * Remove a file from tracking (e.g., when file is modified by agent)
     * @param path File path
     */
    public void invalidateFile(String path) {
        readFiles.remove(normalizePath(path));
        logger.debug("Invalidated file tracking: {}", path);
    }

    /**
     * Invalidate all files matching a prefix (e.g., when directory is modified)
     * @param pathPrefix Path prefix to match
     */
    public void invalidateByPrefix(String pathPrefix) {
        String normalizedPrefix = normalizePath(pathPrefix);
        readFiles.keySet().removeIf(key -> key.startsWith(normalizedPrefix));
        logger.debug("Invalidated files matching prefix: {}", pathPrefix);
    }

    /**
     * Get all tracked file paths
     * @return Set of tracked file paths
     */
    public Set<String> getTrackedFiles() {
        return readFiles.keySet();
    }

    /**
     * Get the number of tracked files
     * @return Number of tracked files
     */
    public int getTrackedFileCount() {
        return readFiles.size();
    }

    /**
     * Clear all tracked files
     */
    public void clear() {
        readFiles.clear();
        logger.debug("Cleared all file tracking");
    }

    /**
     * Get a summary of tracked files for debugging
     * @return Summary string
     */
    public String getSummary() {
        return String.format("FileReadTracker: %d files tracked", readFiles.size());
    }

    /**
     * Generate a message with cached file summary.
     * This provides useful context instead of just "already read".
     * @param path File path
     * @return Message with file summary or null if file not tracked
     */
    public String getSkipMessage(String path) {
        FileReadRecord record = readFiles.get(normalizePath(path));
        if (record == null) {
            return null;
        }
        
        StringBuilder msg = new StringBuilder();
        msg.append("[CACHED FILE - 已缓存文件]\n");
        msg.append("Path: ").append(path).append("\n");
        msg.append("Size: ").append(record.getSize()).append(" bytes\n");
        msg.append("Status: Content unchanged since last read\n\n");
        
        if (record.getContentSummary() != null && !record.getContentSummary().isEmpty()) {
            msg.append("=== Content Summary (内容摘要) ===\n");
            msg.append(record.getContentSummary());
            msg.append("\n\n");
        }
        
        msg.append("[提示: 文件内容已缓存，如需完整内容请指定行号范围]\n");
        msg.append("[Tip: File content cached. Specify line range if you need full content]\n");
        
        return msg.toString();
    }
    
    /**
     * Generate a brief content summary for quick reference.
     * Includes first N lines and key metadata.
     */
    private String generateContentSummary(String content) {
        if (content == null || content.isEmpty()) {
            return "(empty file)";
        }
        
        String[] lines = content.split("\n", SUMMARY_LINES + 1);
        StringBuilder summary = new StringBuilder();
        
        int lineCount = Math.min(lines.length, SUMMARY_LINES);
        for (int i = 0; i < lineCount; i++) {
            summary.append(String.format("%4d: %s\n", i + 1, lines[i]));
        }
        
        if (lines.length > SUMMARY_LINES) {
            summary.append("... (").append(lines.length - SUMMARY_LINES).append(" more lines)\n");
        }
        
        return summary.toString();
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        // Normalize path for consistent key lookup
        return path.replace("\\", "/").toLowerCase();
    }

    private void cleanupOldEntries() {
        // Remove entries older than 30 minutes
        long cutoff = System.currentTimeMillis() - (30 * 60 * 1000);
        readFiles.entrySet().removeIf(entry -> 
            entry.getValue().getReadTimestamp() < cutoff
        );
        logger.debug("Cleaned up old file tracking entries, {} files remaining", readFiles.size());
    }
}
