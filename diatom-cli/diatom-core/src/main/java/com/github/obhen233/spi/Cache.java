package com.github.obhen233.spi;

/**
 * Generic cache interface abstraction.
 * Can be backed by Guava, Caffeine, ConcurrentHashMap, or any other implementation.
 */
public interface Cache<K, V> {

    /**
     * Get a value from the cache.
     * @param key the key to look up
     * @return the cached value, or null if not present
     */
    V get(K key);

    /**
     * Put a value into the cache.
     * @param key the key
     * @param value the value
     */
    void put(K key, V value);

    /**
     * Invalidate a specific key.
     * @param key the key to remove
     */
    void invalidate(K key);

    /**
     * Invalidate all entries in the cache.
     */
    void invalidateAll();

    /**
     * Invalidate multiple keys at once.
     * @param keys the keys to invalidate
     */
    default void invalidateAll(Iterable<? extends K> keys) {
        for (K key : keys) {
            invalidate(key);
        }
    }

    /**
     * Get the approximate number of entries in the cache.
     * @return size estimate
     */
    long size();
}
