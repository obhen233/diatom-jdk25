package com.github.obhen233.adapter.worker.model;

/**
 * Payload for {@code PUT /gateway/v1/workers/{workerId}/heartbeat} request.
 *
 * @param workerId    the worker id
 * @param currentLoad current load
 * @param activeTasks active task count
 * @param timestamp   heartbeat timestamp
 * @param status      worker status ("ACTIVE"/"SHUTTING_DOWN")
 * @param useSsl      null = no change, true/false = explicit
 */
public record HeartbeatPayload(
        String workerId,
        double currentLoad,
        int activeTasks,
        long timestamp,
        String status,
        Boolean useSsl) {}
