package com.github.obhen233.adapter.worker.model;

import java.util.List;
import java.util.Map;

/**
 * Payload for {@code POST /gateway/v1/workers} registration request.
 * Aligned with diatom-core RegistrationRequest format.
 *
 * @param workerId       unique worker id
 * @param host           worker host
 * @param port           worker HTTP port
 * @param model          agent model
 * @param traits         agent characteristics
 * @param capabilities   capability routing scores
 * @param group          worker group
 * @param maxConcurrency max concurrent tasks
 * @param workspace      workspace directory
 * @param currentLoad    current load
 * @param activeTasks    active task count
 * @param lastHeartbeat  last heartbeat timestamp
 * @param tier           worker tier
 * @param agentVersion   agent version
 * @param useSsl         whether the worker uses SSL
 * @param requestCert    whether the worker requests a certificate from the Gateway
 */
public record RegistrationPayload(
        String workerId,
        String host,
        int port,
        String model,
        List<String> traits,
        Map<String, Double> capabilities,
        String group,
        int maxConcurrency,
        String workspace,
        double currentLoad,
        int activeTasks,
        long lastHeartbeat,
        String tier,
        String agentVersion,
        boolean useSsl,
        boolean requestCert) {}
