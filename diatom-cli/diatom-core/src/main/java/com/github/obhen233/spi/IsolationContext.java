package com.github.obhen233.spi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隔离上下文的句柄。
 * 包含策略执行过程中需要清理的所有资源。
 * 由 prepareEnvironment() 创建，传递给 collectChanges() 和 cleanup()。
 */
public class IsolationContext {

    private final String strategyType;
    private final Map<String, Path> sandboxPaths = new ConcurrentHashMap<>();
    private final Map<String, LockToken> heldLocks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> subTaskResources = new ConcurrentHashMap<>();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public IsolationContext(String strategyType) {
        this.strategyType = strategyType;
    }

    public String getStrategyType() {
        return strategyType;
    }

    public void addSandboxPath(String subTaskId, Path sandboxPath) {
        sandboxPaths.put(subTaskId, sandboxPath);
    }

    public Path getSandboxPath(String subTaskId) {
        return sandboxPaths.get(subTaskId);
    }

    public Map<String, Path> getSandboxPaths() {
        return new HashMap<>(sandboxPaths);
    }

    public void addLock(String resourceId, LockToken token) {
        heldLocks.put(resourceId, token);
    }

    public LockToken getLock(String resourceId) {
        return heldLocks.get(resourceId);
    }

    public Map<String, LockToken> getHeldLocks() {
        return new HashMap<>(heldLocks);
    }

    public void addSubTaskResource(String subTaskId, List<String> resources) {
        subTaskResources.put(subTaskId, new ArrayList<>(resources));
    }

    public List<String> getSubTaskResources(String subTaskId) {
        return subTaskResources.get(subTaskId);
    }

    public Map<String, List<String>> getAllSubTaskResources() {
        return new HashMap<>(subTaskResources);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 锁令牌，由 prepareEnvironment() 产生，用于释放锁。
     */
    public static class LockToken {
        private final String token;
        private final String resourceId;
        private final String workerId;
        private final String mode; // "READ" or "WRITE"
        private final long leaseExpiresAt;

        public LockToken(String token, String resourceId, String workerId,
                         String mode, long leaseExpiresAt) {
            this.token = token;
            this.resourceId = resourceId;
            this.workerId = workerId;
            this.mode = mode;
            this.leaseExpiresAt = leaseExpiresAt;
        }

        public String getToken() { return token; }
        public String getResourceId() { return resourceId; }
        public String getWorkerId() { return workerId; }
        public String getMode() { return mode; }
        public long getLeaseExpiresAt() { return leaseExpiresAt; }
        public boolean isExpired() {
            return System.currentTimeMillis() > leaseExpiresAt;
        }
        public long getExpiresAt() { return leaseExpiresAt; }
    }

    /**
     * Lock mode enum (READ / WRITE).
     * Used by GatewayLockHandler and ResourceLockManager.
     */
    public enum LockMode {
        READ, WRITE
    }

    /**
     * Lock information holder, returned by ResourceLockManager for lock status queries.
     */
    public static class LockInfo {
        private final String resourceId;
        private final String holderWorkerId;
        private final String mode;
        private final long expiresAt;

        public LockInfo(String resourceId, String holderWorkerId, String mode, long expiresAt) {
            this.resourceId = resourceId;
            this.holderWorkerId = holderWorkerId;
            this.mode = mode;
            this.expiresAt = expiresAt;
        }

        public String getResourceId() { return resourceId; }
        public String getHolderWorkerId() { return holderWorkerId; }
        public String getMode() { return mode; }
        public long getExpiresAt() { return expiresAt; }
    }
}
