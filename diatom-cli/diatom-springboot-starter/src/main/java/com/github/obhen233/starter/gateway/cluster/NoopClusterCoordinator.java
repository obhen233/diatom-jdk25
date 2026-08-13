package com.github.obhen233.starter.gateway.cluster;

import com.github.obhen233.spi.ClusterCoordinator;
import com.github.obhen233.spi.ClusterEventListener;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 空操作 {@link ClusterCoordinator}。
 *
 * <p>当 {@code diatom.gateway.ha.enabled=true} 但 Hazelcast 初始化失败（或没有可用实现）时，
 * 作为兜底 Bean 返回，保证 {@code ClusterCoordinator} Bean 永不为 null。
 * {@link #isActive()} 恒为 false，使 {@link com.github.obhen233.core.gateway.registry.ClusteredWorkerRegistry}
 * 自动降级为纯本地注册表（register/heartbeat 不同步、availableWorkers 仅本地）。
 */
public class NoopClusterCoordinator implements ClusterCoordinator {

    @Override
    public String getName() { return "noop"; }

    @Override
    public void init(Map<String, String> config) { }

    @Override
    public void shutdown() { }

    @Override
    public void putWorker(String key, WorkerEntry entry, int ttlSeconds) { }

    @Override
    public WorkerEntry getWorker(String key) { return null; }

    @Override
    public Collection<WorkerEntry> getAllWorkers() { return Collections.emptyList(); }

    @Override
    public void removeWorker(String key) { }

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) { return false; }

    @Override
    public void unlock(String key) { }

    @Override
    public void addListener(ClusterEventListener listener) { }

    @Override
    public void removeListener(ClusterEventListener listener) { }

    @Override
    public void store(String namespace, String key, String value, int ttlSeconds) { }

    @Override
    public String retrieve(String namespace, String key) { return null; }

    @Override
    public void remove(String namespace, String key) { }

    @Override
    public Collection<String> keys(String namespace, String prefix) { return Collections.emptyList(); }

    @Override
    public boolean isActive() { return false; }
}
