package com.github.obhen233.starter.gateway.dto;

/**
 * Worker 注册请求 DTO。
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
