package com.github.obhen233.starter.gateway.dto;

/**
 * Worker 心跳指标 DTO。
 */
public record MetricsPayload(double currentLoad, int activeTasks, boolean useSsl) {
}
