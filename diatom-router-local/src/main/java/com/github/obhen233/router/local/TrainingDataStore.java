package com.github.obhen233.router.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistence layer for LLM feedback training data.
 * <p>
 * Accumulates {@code (message, category, timestamp)} pairs from LLM
 * classification results. Data is persisted as a JSON array and used
 * for future SVM model retraining.
 * <p>
 * Append-only: new entries are added at the end. Writes are throttled
 * to at most once per 30 seconds.
 */
public class TrainingDataStore {

    private static final Logger logger = LoggerFactory.getLogger(TrainingDataStore.class);

    private static final long SAVE_THROTTLE_MS = 30_000L;

    private final Path storePath;
    private final List<TrainingEntry> entries;
    private final AtomicLong lastSaveTime = new AtomicLong(0);
    private volatile boolean closed = false;

    public TrainingDataStore(Path storePath) {
        this.storePath = storePath;
        this.entries = new CopyOnWriteArrayList<>();
        load();
    }

    /**
     * Add a training sample from LLM feedback.
     */
    public void add(String message, String category) {
        if (closed) return;
        entries.add(new TrainingEntry(message, category, System.currentTimeMillis()));
    }

    /**
     * Total number of accumulated training samples.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Get all training entries (unmodifiable).
     */
    public List<TrainingEntry> allEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Attempt a throttled save.
     */
    public boolean save() {
        if (closed) return false;
        long now = System.currentTimeMillis();
        long last = lastSaveTime.get();
        if (now - last < SAVE_THROTTLE_MS) {
            return false;
        }
        return flush();
    }

    /**
     * Force an immediate save, ignoring throttle.
     */
    public synchronized boolean flush() {
        if (closed) return false;
        try {
            Files.createDirectories(storePath.getParent());
            String json = toJson();

            Path tmpPath = storePath.resolveSibling(storePath.getFileName() + ".tmp");
            Files.write(tmpPath, Collections.singletonList(json), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            try (FileChannel channel = FileChannel.open(tmpPath, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock != null) {
                    Files.move(tmpPath, storePath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(tmpPath, storePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            lastSaveTime.set(System.currentTimeMillis());
            logger.debug("Saved {} training entries to {}", entries.size(), storePath);
            return true;
        } catch (IOException e) {
            logger.warn("Failed to save training data to {}: {}", storePath, e.getMessage());
            return false;
        }
    }

    /**
     * Close the store.
     */
    public void close() {
        closed = true;
        flush();
    }

    // ========== Internal ==========

    private void load() {
        if (!Files.exists(storePath)) {
            logger.debug("No training data found at {}, starting fresh", storePath);
            return;
        }
        try {
            String content = new String(Files.readAllBytes(storePath), "UTF-8").trim();
            if (content.isEmpty() || content.equals("[]")) {
                return;
            }
            parseJson(content);
            logger.info("Loaded {} training entries from {}", entries.size(), storePath);
        } catch (IOException e) {
            logger.warn("Failed to load training data from {}: {}", storePath, e.getMessage());
        }
    }

    // ========== JSON serialization ==========

    private String toJson() {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < entries.size(); i++) {
            TrainingEntry e = entries.get(i);
            sb.append("  {");
            sb.append("\"message\":\"").append(escapeJson(e.getMessage())).append("\"");
            sb.append(",\"category\":\"").append(escapeJson(e.getCategory())).append("\"");
            sb.append(",\"timestamp\":").append(e.getTimestamp());
            sb.append("}");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    private void parseJson(String json) {
        try {
            String compact = json.replaceAll("\\s+", "");
            if (compact.length() <= 2) return;
            String inner = compact.substring(1, compact.length() - 1);

            int depth = 0;
            int start = -1;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        String objStr = inner.substring(start, i + 1);
                        TrainingEntry entry = parseEntry(objStr);
                        if (entry != null) {
                            entries.add(entry);
                        }
                        start = -1;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse training data JSON: {}", e.getMessage());
        }
    }

    private TrainingEntry parseEntry(String json) {
        try {
            String msg = extractString(json, "message");
            String cat = extractString(json, "category");
            long ts = extractLong(json, "timestamp", System.currentTimeMillis());
            if (msg == null || cat == null) return null;
            return new TrainingEntry(msg, cat, ts);
        } catch (Exception e) {
            logger.warn("Failed to parse training entry: {}", e.getMessage());
            return null;
        }
    }

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    private static long extractLong(String json, String key, long defaultVal) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return defaultVal;
        start += search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ========== Entry model ==========

    public static class TrainingEntry {
        private final String message;
        private final String category;
        private final long timestamp;

        public TrainingEntry(String message, String category, long timestamp) {
            this.message = message;
            this.category = category;
            this.timestamp = timestamp;
        }

        public String getMessage() { return message; }
        public String getCategory() { return category; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return "TrainingEntry{category='" + category + "', message='" +
                    (message.length() > 30 ? message.substring(0, 30) + "..." : message) + "'}";
        }
    }
}
