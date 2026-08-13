package com.github.obhen233.router.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Persistence layer for learned keywords.
 * <p>
 * Stores keywords in {@code .diatom/router-keywords.json} (relative to the
 * gateway working directory) using a {@link ConcurrentHashMap} keyed by
 * normalized keyword text.
 * Writes are throttled to at most once per 30 seconds to avoid excessive I/O.
 */
public class KeywordStore {

    private static final Logger logger = LoggerFactory.getLogger(KeywordStore.class);

    private static final long SAVE_THROTTLE_MS = 30_000L;

    private final Path storePath;
    private final Map<String, KeywordEntry> keywords;
    private final AtomicLong lastSaveTime = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Create a KeywordStore with the default path {@code .diatom/router-keywords.json}.
     */
    public KeywordStore() {
        this(Paths.get(".diatom", "router-keywords.json"));
    }

    /**
     * Create a KeywordStore with a custom path.
     */
    public KeywordStore(Path storePath) {
        this.storePath = storePath;
        this.keywords = new ConcurrentHashMap<>();
        load();
    }

    /**
     * Get all learned keywords.
     */
    public Collection<KeywordEntry> allKeywords() {
        return keywords.values();
    }

    /**
     * Get keywords for a specific category.
     */
    public List<KeywordEntry> getKeywordsForCategory(String categoryId) {
        // Keywords are stored globally; filtering by category prefix can be added later.
        // For now, all learned keywords apply to all categories.
        return Collections.unmodifiableList(new ArrayList<>(keywords.values()));
    }

    /**
     * Get a keyword entry by text, or {@code null} if not found.
     */
    public KeywordEntry get(String text) {
        return keywords.get(normalize(text));
    }

    /**
     * Learn a new keyword or reinforce an existing one (default frequency=1).
     */
    public void learn(String text) {
        learn(text, 1);
    }

    /**
     * Learn a new keyword or reinforce an existing one with a given initial frequency.
     * <p>
     * Higher initial frequency gives higher starting weight.
     * Used by LLM feedback learning (initialFrequency=3) to distinguish from
     * partial-match learning (initialFrequency=1).
     *
     * @param text             the keyword text
     * @param initialFrequency frequency to add (1 = partial match, 3 = LLM confirmed)
     */
    public void learn(String text, int initialFrequency) {
        if (text == null || text.trim().isEmpty() || initialFrequency < 1) {
            return;
        }
        String normalized = normalize(text);
        KeywordEntry existing = keywords.get(normalized);
        if (existing != null) {
            for (int i = 0; i < initialFrequency; i++) {
                existing.reinforce();
            }
        } else {
            keywords.put(normalized, new KeywordEntry(normalized, initialFrequency));
        }
    }

    /**
     * Check if a keyword exists in the store.
     */
    public boolean contains(String normalizedText) {
        return keywords.containsKey(normalizedText);
    }

    /**
     * Total number of learned keywords.
     */
    public int size() {
        return keywords.size();
    }

    /**
     * Attempt a throttled save. Returns true if saved, false if throttled.
     */
    public boolean save() {
        if (closed.get()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long last = lastSaveTime.get();
        if (now - last < SAVE_THROTTLE_MS) {
            // Within throttle window — skip to avoid excessive I/O
            return false;
        }
        return flush();
    }

    /**
     * Force an immediate save, ignoring throttle.
     */
    public synchronized boolean flush() {
        if (closed.get()) {
            return false;
        }
        try {
            Files.createDirectories(storePath.getParent());

            // Build JSON manually without Gson/Jackson dependency
            String json = toJson();

            // Atomic write: write to temp file then rename
            Path tmpPath = storePath.resolveSibling(storePath.getFileName() + ".tmp");
            Files.write(tmpPath, Collections.singletonList(json), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            // Acquire file lock before rename
            try (FileChannel channel = FileChannel.open(tmpPath, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock != null) {
                    Files.move(tmpPath, storePath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    logger.warn("Could not acquire file lock, saving without atomic move");
                    Files.move(tmpPath, storePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            lastSaveTime.set(System.currentTimeMillis());
            logger.debug("Saved {} keywords to {}", keywords.size(), storePath);
            return true;
        } catch (IOException e) {
            logger.warn("Failed to save keywords to {}: {}", storePath, e.getMessage());
            return false;
        }
    }

    /**
     * Close the store and flush pending writes.
     */
    public void close() {
        closed.set(true);
        flush();
    }

    // ========== Internal ==========

    private void load() {
        if (!Files.exists(storePath)) {
            logger.debug("No keyword store found at {}, starting fresh", storePath);
            return;
        }
        try {
            String content = new String(Files.readAllBytes(storePath), "UTF-8").trim();
            if (content.isEmpty() || content.equals("{}")) {
                return;
            }
            parseJson(content);
            logger.info("Loaded {} learned keywords from {}", keywords.size(), storePath);
        } catch (IOException e) {
            logger.warn("Failed to load keywords from {}: {}", storePath, e.getMessage());
        }
    }

    private static String normalize(String text) {
        return text.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // ========== Minimal JSON serialization (no external deps) ==========

    private String toJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        // Sort by key for deterministic output
        List<Map.Entry<String, KeywordEntry>> sorted = keywords.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toList());
        for (Map.Entry<String, KeywordEntry> e : sorted) {
            if (!first) sb.append(",");
            first = false;
            KeywordEntry k = e.getValue();
            sb.append("\n  \"").append(escapeJson(e.getKey())).append("\":{")
                    .append("\"text\":\"").append(escapeJson(k.getText())).append("\"")
                    .append(",\"frequency\":").append(k.getFrequency())
                    .append(",\"firstLearned\":").append(k.getFirstLearned())
                    .append(",\"lastUpdated\":").append(k.getLastUpdated())
                    .append("}");
        }
        sb.append("\n}");
        return sb.toString();
    }

    private void parseJson(String json) {
        // Minimal JSON parser for the known format
        // Expected: {"keyword":{"text":"...","frequency":N,"firstLearned":L,"lastUpdated":L},...}
        try {
            // Remove whitespace/newlines for simpler parsing
            String compact = json.replaceAll("\\s+", "");
            if (compact.length() <= 2) return;

            // Extract top-level objects between { }
            String inner = compact.substring(1, compact.length() - 1);

            int depth = 0;
            int start = -1;
            String currentKey = null;

            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '{') {
                    if (depth == 0) {
                        start = i;
                    }
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        String objStr = inner.substring(start, i + 1);
                        if (currentKey != null) {
                            parseKeywordObject(currentKey, objStr);
                        }
                        currentKey = null;
                        start = -1;
                    }
                } else if (c == '"' && depth == 0) {
                    // Parse key
                    int end = inner.indexOf('"', i + 1);
                    if (end > i) {
                        currentKey = inner.substring(i + 1, end);
                        currentKey = currentKey.replace("\\\"", "\"").replace("\\\\", "\\");
                        i = end;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse keyword JSON: {}", e.getMessage());
        }
    }

    private void parseKeywordObject(String key, String objJson) {
        try {
            String inner = objJson;
            if (inner.startsWith("{")) inner = inner.substring(1);
            if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);

            String text = key;
            int frequency = 1;
            long firstLearned = System.currentTimeMillis();
            long lastUpdated = System.currentTimeMillis();

            // Parse fields
            int idx;
            if ((idx = inner.indexOf("\"frequency\":")) >= 0) {
                int start = idx + "\"frequency\":".length();
                int end = findNumberEnd(inner, start);
                if (end > start) {
                    frequency = Integer.parseInt(inner.substring(start, end));
                }
            }
            if ((idx = inner.indexOf("\"firstLearned\":")) >= 0) {
                int start = idx + "\"firstLearned\":".length();
                int end = findNumberEnd(inner, start);
                if (end > start) {
                    firstLearned = Long.parseLong(inner.substring(start, end));
                }
            }
            if ((idx = inner.indexOf("\"lastUpdated\":")) >= 0) {
                int start = idx + "\"lastUpdated\":".length();
                int end = findNumberEnd(inner, start);
                if (end > start) {
                    lastUpdated = Long.parseLong(inner.substring(start, end));
                }
            }

            keywords.put(key, new KeywordEntry(text, frequency, firstLearned, lastUpdated));
        } catch (Exception e) {
            logger.warn("Failed to parse keyword object '{}': {}", key, e.getMessage());
        }
    }

    private static int findNumberEnd(String s, int start) {
        int i = start;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-')) {
            i++;
        }
        return i;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
