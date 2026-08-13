package com.github.obhen233.core.gateway.collaboration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 锁 / 租约 / 排队数据结构。
 *
 * 用于 ResourceLockManager 管理分布式锁状态：
 * - WRITE 锁：排他，只有一个持有者
 * - READ 锁：共享，多个 worker 可同时持有
 */
public class LockInfo {

    public enum LockMode {
        READ,
        WRITE
    }

    private final String resourceId;
    private final String holderWorkerId;
    private final String token;
    private final LockMode mode;
    private final long acquiredAt;
    private final long leaseDurationMs;
    private final Set<String> sharedReadHolders = ConcurrentHashMap.newKeySet();

    public LockInfo(String resourceId, String holderWorkerId, String token,
                    LockMode mode, long acquiredAt, long leaseDurationMs) {
        this.resourceId = resourceId;
        this.holderWorkerId = holderWorkerId;
        this.token = token;
        this.mode = mode;
        this.acquiredAt = acquiredAt;
        this.leaseDurationMs = leaseDurationMs;
        if (mode == LockMode.READ) {
            this.sharedReadHolders.add(holderWorkerId);
        }
    }

    /**
     * 检查该锁是否已过期。
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > acquiredAt + leaseDurationMs;
    }

    /**
     * 检查请求的模式是否与当前锁冲突。
     * WRITE 与任何锁冲突（排他）。
     * READ 与 WRITE 冲突，但与 READ 兼容（共享）。
     */
    public boolean conflictsWith(LockMode requestedMode) {
        if (this.mode == LockMode.WRITE) {
            return true; // WRITE 与任何请求冲突
        }
        // this.mode == READ: 与 WRITE 请求冲突，但与 READ 兼容
        return requestedMode == LockMode.WRITE;
    }

    /**
     * 获取该锁剩余的租约时间（毫秒）。
     */
    public long getRemainingLeaseMs() {
        long elapsed = System.currentTimeMillis() - acquiredAt;
        return Math.max(0, leaseDurationMs - elapsed);
    }

    // === Getters ===

    public String getResourceId() { return resourceId; }
    public String getHolderWorkerId() { return holderWorkerId; }
    public String getToken() { return token; }
    public LockMode getMode() { return mode; }
    public long getAcquiredAt() { return acquiredAt; }
    public long getLeaseDurationMs() { return leaseDurationMs; }
    public long getExpiresAt() { return acquiredAt + leaseDurationMs; }
    public Set<String> getSharedReadHolders() { return sharedReadHolders; }

    @Override
    public String toString() {
        return mode + " lock on " + resourceId + " by " + holderWorkerId
                + " (token: " + token + ")";
    }

    /**
     * 锁请求，用于等待队列。
     */
    public static class LockRequest {
        private final String resourceId;
        private final String workerId;
        private final LockMode mode;
        private final long leaseMs;
        private final long requestTime;

        public LockRequest(String resourceId, String workerId,
                           LockMode mode, long leaseMs) {
            this.resourceId = resourceId;
            this.workerId = workerId;
            this.mode = mode;
            this.leaseMs = leaseMs;
            this.requestTime = System.currentTimeMillis();
        }

        public String getResourceId() { return resourceId; }
        public String getWorkerId() { return workerId; }
        public LockMode getMode() { return mode; }
        public long getLeaseMs() { return leaseMs; }
        public long getRequestTime() { return requestTime; }
    }
}
