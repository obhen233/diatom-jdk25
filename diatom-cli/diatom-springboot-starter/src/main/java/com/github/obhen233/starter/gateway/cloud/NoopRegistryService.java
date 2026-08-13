package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.WorkerInfo;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 无操作注册中心服务（默认）。
 *
 * <p>当 {@code diatom.mode=gateway}（无子类型）时使用。
 * Worker 直接通过 HTTP 注册到 Gateway，无需注册中心。
 */
public class NoopRegistryService implements RegistryService {

    @Override
    public void registerGateway(String serviceId, String host, int port, Map<String, String> metadata) {
        // no-op: direct HTTP registration mode
    }

    @Override
    public void deregisterGateway() {
        // no-op
    }

    @Override
    public List<WorkerInfo> discoverWorkers(String serviceFilter) {
        return Collections.emptyList();
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public void destroy() {
        // no-op
    }
}
