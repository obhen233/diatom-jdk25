package com.github.obhen233.quarkus.runtime.rest.dto;

/**
 * Worker 心跳指标 DTO（wire 协议与 starter/core 完全兼容）。
 */
public record MetricsPayload(double currentLoad, int activeTasks, boolean useSsl) {
}
