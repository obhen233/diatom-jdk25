package com.github.obhen233.router.local;

import java.util.Objects;

/**
 * A learned keyword entry with dynamic weight based on usage frequency.
 * <p>
 * Weight starts at 0.2 and grows with each reinforcement:
 * {@code weight = min(0.6, 0.2 + frequency * 0.02)}
 */
public class KeywordEntry {

    private final String text;
    private volatile int frequency;
    private volatile long firstLearned;
    private volatile long lastUpdated;

    /** For JSON deserialization. */
    @SuppressWarnings("unused")
    private KeywordEntry() {
        this.text = "";
        this.frequency = 0;
        this.firstLearned = 0;
        this.lastUpdated = 0;
    }

    public KeywordEntry(String text) {
        this(text, 1);
    }

    public KeywordEntry(String text, int initialFrequency) {
        this.text = Objects.requireNonNull(text, "keyword text must not be null");
        this.frequency = Math.max(1, initialFrequency);
        long now = System.currentTimeMillis();
        this.firstLearned = now;
        this.lastUpdated = now;
    }

    public KeywordEntry(String text, int frequency, long firstLearned, long lastUpdated) {
        this.text = text;
        this.frequency = frequency;
        this.firstLearned = firstLearned;
        this.lastUpdated = lastUpdated;
    }

    public String getText() {
        return text;
    }

    public int getFrequency() {
        return frequency;
    }

    public long getFirstLearned() {
        return firstLearned;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    /**
     * Current dynamic weight: starts at 0.2, grows to max 0.6.
     */
    public double getWeight() {
        return Math.min(0.6, 0.2 + frequency * 0.02);
    }

    /**
     * Increment usage frequency and update timestamp.
     */
    public void reinforce() {
        this.frequency++;
        this.lastUpdated = System.currentTimeMillis();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeywordEntry)) return false;
        KeywordEntry that = (KeywordEntry) o;
        return text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        return "KeywordEntry{text='" + text + "', weight=" + String.format("%.2f", getWeight())
                + ", freq=" + frequency + "}";
    }
}
