package com.github.obhen233.spi.impl;

import com.github.obhen233.spi.Cache;
import com.github.obhen233.spi.CacheFactory;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;

import java.util.concurrent.TimeUnit;

/**
 * Default CacheFactory implementation backed by Guava.
 * Used when no custom CacheFactory is provided via SPI.
 */
public class GuavaCacheFactory implements CacheFactory {

    @Override
    public <K, V> Cache<K, V> create(String name, CacheConfig config) {
        CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder()
                .maximumSize(config.getMaxSize())
                .expireAfterWrite(config.getExpireAfterWriteMillis(), TimeUnit.MILLISECONDS);

        com.github.obhen233.spi.CacheFactory.CacheLoader<K, V> loader = config.getLoader();
        if (loader != null) {
            com.google.common.cache.CacheLoader<K, V> guavaLoader =
                    com.google.common.cache.CacheLoader.from(key -> {
                        try {
                            return loader.load(key);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            LoadingCache<K, V> guavaCache = builder.build(guavaLoader);
            return new GuavaCache<>(guavaCache);
        } else {
            com.google.common.cache.Cache<K, V> simpleCache = builder.build();
            return new SimpleGuavaCache<>(simpleCache);
        }
    }

    /**
     * Guava cache wrapper for caches without a loader.
     */
    private static class SimpleGuavaCache<K, V> implements Cache<K, V> {
        private final com.google.common.cache.Cache<K, V> delegate;

        SimpleGuavaCache(com.google.common.cache.Cache<K, V> delegate) {
            this.delegate = delegate;
        }

        @Override
        @SuppressWarnings("unchecked")
        public V get(K key) {
            V value = delegate.getIfPresent(key);
            return value;
        }

        @Override
        public void put(K key, V value) {
            delegate.put(key, value);
        }

        @Override
        public void invalidate(K key) {
            delegate.invalidate(key);
        }

        @Override
        public void invalidateAll() {
            delegate.invalidateAll();
        }

        @Override
        public long size() {
            return delegate.size();
        }
    }
}
