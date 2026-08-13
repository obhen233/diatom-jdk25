package com.github.obhen233.quarkus.runtime.rest.dto;

/**
 * Worker 注册请求 DTO（wire 协议与 starter/core 完全兼容）。
 */
public record WorkerRegisterRequest(
        String workerId,
        String host,
        int port,
        String model,
        String group,
        String workspace,
        String gatewayProfile,
        double currentLoad,
        int activeTasks,
        int maxConcurrency,
        String tier,
        boolean useSsl) {
}
