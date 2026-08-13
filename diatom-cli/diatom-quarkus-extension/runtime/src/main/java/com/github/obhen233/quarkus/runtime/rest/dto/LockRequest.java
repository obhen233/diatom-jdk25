package com.github.obhen233.quarkus.runtime.rest.dto;

/**
 * 锁操作请求 DTO：用于 acquire / release / renew 等锁操作。
 */
public record LockRequest(
        String resourceId,
        String workerId,
        String mode,       // READ / WRITE
        long leaseMs,
        long waitMs,
        String token,
        long additionalMs) {
}
