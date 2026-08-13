package com.github.obhen233.spi;

/**
 * Listener for cluster coordination events.
 *
 * <p>Registered via {@link ClusterCoordinator#addListener(ClusterEventListener)}
 * to receive notifications when workers join or leave the cluster.</p>
 */
@FunctionalInterface
public interface ClusterEventListener {

    /**
     * Called when a cluster event occurs.
     *
     * @param event the event
     */
    void onEvent(ClusterEvent event);

    /**
     * Cluster event types.
     */
    enum EventType {
        WORKER_ADDED,
        WORKER_REMOVED,
        WORKER_UPDATED
    }

    /**
     * A cluster event.
     */
    class ClusterEvent {
        private final EventType type;
        private final String workerId;
        private final ClusterCoordinator.WorkerEntry entry;

        public ClusterEvent(EventType type, String workerId, ClusterCoordinator.WorkerEntry entry) {
            this.type = type;
            this.workerId = workerId;
            this.entry = entry;
        }

        public EventType getType() { return type; }
        public String getWorkerId() { return workerId; }
        public ClusterCoordinator.WorkerEntry getEntry() { return entry; }
    }
}
