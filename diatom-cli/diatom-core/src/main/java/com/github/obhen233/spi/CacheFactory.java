package com.github.obhen233.spi;

/**
 * Factory for creating named cache instances.
 * Custom implementations can provide their own caching backend
 * (e.g., Caffeine, Redis) via SPI.
 */
public interface CacheFactory {

    /**
     * Create a named cache with the given configuration.
     * @param name logical name of the cache (for debugging/monitoring)
     * @param config configuration options
     * @param <K> key type
     * @param <V> value type
     * @return a new cache instance
     */
    <K, V> Cache<K, V> create(String name, CacheConfig config);

    /**
     * Configuration for a cache instance.
     */
    class CacheConfig {
        private int maxSize = 100;
        private long expireAfterWriteMillis = 300_000; // 5 minutes default
        private CacheLoader<?, ?> loader;

        public int getMaxSize() { return maxSize; }
        public CacheConfig setMaxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public long getExpireAfterWriteMillis() { return expireAfterWriteMillis; }
        public CacheConfig setExpireAfterWriteMillis(long millis) {
            this.expireAfterWriteMillis = millis;
            return this;
        }

        @SuppressWarnings("unchecked")
        public <K, V> CacheLoader<K, V> getLoader() { return (CacheLoader<K, V>) loader; }
        @SuppressWarnings("unchecked")
        public <K, V> CacheConfig setLoader(CacheLoader<K, V> loader) {
            this.loader = loader;
            return this;
        }
    }

    /**
     * Cache loader function (similar to Guava's CacheLoader).
     * @param <K> key type
     * @param <V> value type
     */
    @FunctionalInterface
    interface CacheLoader<K, V> {
        V load(K key) throws Exception;
    }
}
