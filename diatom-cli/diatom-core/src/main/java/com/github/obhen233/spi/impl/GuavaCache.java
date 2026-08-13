package com.github.obhen233.spi.impl;

import com.github.obhen233.spi.Cache;
import com.github.obhen233.spi.CacheFactory;
import com.google.common.cache.LoadingCache;

/**
 * Guava-backed cache implementation.
 * Wraps a Guava LoadingCache into the generic Cache interface.
 */
public class GuavaCache<K, V> implements Cache<K, V> {

    private final LoadingCache<K, V> delegate;

    public GuavaCache(LoadingCache<K, V> delegate) {
        this.delegate = delegate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(K key) {
        try {
            return delegate.get(key);
        } catch (Exception e) {
            return null;
        }
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
