package com.github.obhen233.core.gateway.registry;

/**
 * 注册中心事件
 */
public class RegistryEvent {
    private final String workerId;
    private final EventType type;
    private final WorkerInfo worker;

    public enum EventType {
        REGISTERED, DEREGISTERED, HEARTBEAT_TIMEOUT, STATUS_CHANGED
    }

    public RegistryEvent(String workerId, EventType type, WorkerInfo worker) {
        this.workerId = workerId;
        this.type = type;
        this.worker = worker;
    }

    public String getWorkerId() { return workerId; }
    public EventType getType() { return type; }
    public WorkerInfo getWorker() { return worker; }
}
