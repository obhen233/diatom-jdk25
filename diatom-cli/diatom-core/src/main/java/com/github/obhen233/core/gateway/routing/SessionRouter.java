package com.github.obhen233.core.gateway.routing;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session Affinity 路由器
 * 同一 session 尽量打到同一 Worker
 */
public class SessionRouter {
    private static final int MAX_SESSION_AFFINITY = 10_000;

    private final Map<String, String> sessionAffinity = new ConcurrentHashMap<>();
    private final CapabilityRouter router;
    private final WorkerRegistry registry;

    public SessionRouter(CapabilityRouter router, WorkerRegistry registry) {
        this.router = router;
        this.registry = registry;
    }

    public WorkerInfo route(String sessionId, TaskRequirement requirement) {
        // Check existing affinity
        if (sessionId != null && sessionAffinity.containsKey(sessionId)) {
            WorkerInfo w = registry.getWorker(sessionAffinity.get(sessionId));
            if (w != null && w.isAvailable()) return w;
            // Worker not available, clear affinity
            sessionAffinity.remove(sessionId);
        }

        // Route normally
        WorkerInfo picked = router.route(requirement);
        if (picked != null && sessionId != null) {
            sessionAffinity.put(sessionId, picked.getWorkerId());
            // Evict oldest entry when max size exceeded
            if (sessionAffinity.size() > MAX_SESSION_AFFINITY) {
                String oldest = sessionAffinity.keySet().iterator().next();
                sessionAffinity.remove(oldest);
            }
        }
        return picked;
    }

    public void clearAffinity(String sessionId) {
        sessionAffinity.remove(sessionId);
    }

    public void handleWorkerFailure(String workerId) {
        sessionAffinity.values().removeIf(v -> v.equals(workerId));
    }
}
