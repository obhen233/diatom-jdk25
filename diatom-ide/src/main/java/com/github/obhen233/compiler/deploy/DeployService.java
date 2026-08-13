package com.github.obhen233.compiler.deploy;

import com.github.obhen233.spi.DeployCallback;
import com.github.obhen233.spi.DeployProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    private static final int MAX_BUFFER_EVENTS = 1000;
    private static final long MAX_BUFFER_BYTES = 512 * 1024; // 512KB
    private static final long SESSION_CLEANUP_MINUTES = 5;

    @Autowired(required = false)
    private DeployProvider deployProvider;

    private final ConcurrentHashMap<String, DeploySession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService deployExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("deploy-executor").factory());

    public interface DeployEventCallback {
        void onEvent(String event, String data);
    }

    /**
     * Start a deploy session for the given project.
     */
    public void startDeploy(String projectName, String profile, DeployEventCallback callback) {
        DeploySession session = new DeploySession(projectName, profile, callback);
        sessions.put(projectName, session);

        if (deployProvider == null) {
            callback.onEvent("error", "Deploy service not available");
            callback.onEvent("exit", "{\"code\":-1}");
            sessions.remove(projectName);
            return;
        }

        deployExecutor.submit(() -> {
            try {
                DeployCallback bufferedCallback = new BufferingDeployCallback(projectName, callback);
                if (profile != null && !profile.isEmpty()) {
                    deployProvider.execute(projectName, bufferedCallback, profile);
                } else {
                    deployProvider.execute(projectName, bufferedCallback);
                }
            } catch (Exception e) {
                log.error("Deploy error for project {}", projectName, e);
                callback.onEvent("error", "Deploy error: " + e.getMessage());
                callback.onEvent("exit", "{\"code\":-1}");
            }
        });
    }

    /**
     * Swap the output callback for a running deploy session (for SSE reconnect).
     */
    public void setCallback(String projectName, DeployEventCallback callback) {
        DeploySession session = sessions.get(projectName);
        if (session != null) {
            session.currentCallback = callback;
        }
    }

    /**
     * Get buffered events for replay during reconnect.
     */
    public List<BufferedEvent> getBuffer(String projectName) {
        DeploySession session = sessions.get(projectName);
        if (session != null) {
            synchronized (session.outputBuffer) {
                return new ArrayList<>(session.outputBuffer);
            }
        }
        return new ArrayList<>();
    }

    /**
     * Check if a deploy is currently running for the given project.
     */
    public boolean isRunning(String projectName) {
        DeploySession session = sessions.get(projectName);
        return session != null && session.running;
    }

    /**
     * Get all projects with running deploys.
     */
    public List<Map<String, Object>> getRunningDeploys() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, DeploySession> entry : sessions.entrySet()) {
            DeploySession session = entry.getValue();
            if (session.running) {
                Map<String, Object> info = new java.util.HashMap<>();
                info.put("projectName", session.projectName);
                info.put("startTime", session.startTime);
                info.put("profile", session.profile);
                result.add(info);
            }
        }
        return result;
    }

    /**
     * Stop a running deploy session.
     */
    public void stopDeploy(String projectName) {
        DeploySession session = sessions.get(projectName);
        if (session != null) {
            session.running = false;
            Future<?> future = session.future;
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
            sessions.remove(projectName);
        }
    }

    /**
     * Clean up stale (completed) sessions after 5 minutes.
     */
    @Scheduled(fixedRate = 60_000)
    public void cleanupStaleSessions() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(SESSION_CLEANUP_MINUTES);
        Iterator<Map.Entry<String, DeploySession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, DeploySession> entry = it.next();
            DeploySession session = entry.getValue();
            if (!session.running && session.endTime < cutoff) {
                it.remove();
                log.info("Cleaned up stale deploy session for project: {}", entry.getKey());
            }
        }
    }

    // ==================== Inner Classes ====================

    public static class BufferedEvent {
        public final String event;
        public final String data;
        public final long timestamp;

        public BufferedEvent(String event, String data) {
            this.event = event;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class DeploySession {
        final String projectName;
        final String profile;
        volatile DeployEventCallback currentCallback;
        volatile boolean running = true;
        final long startTime;
        volatile long endTime;
        final java.util.List<BufferedEvent> outputBuffer = new java.util.LinkedList<>();
        long bufferBytes;
        Future<?> future;

        DeploySession(String projectName, String profile, DeployEventCallback callback) {
            this.projectName = projectName;
            this.profile = profile;
            this.currentCallback = callback;
            this.startTime = System.currentTimeMillis();
            this.endTime = Long.MAX_VALUE;
        }

        void addToBuffer(String event, String data) {
            synchronized (outputBuffer) {
                if (outputBuffer.size() >= MAX_BUFFER_EVENTS || bufferBytes >= MAX_BUFFER_BYTES) {
                    BufferedEvent removed = outputBuffer.remove(0);
                    bufferBytes -= (removed.data != null ? removed.data.length() : 0);
                }
                BufferedEvent be = new BufferedEvent(event, data);
                outputBuffer.add(be);
                bufferBytes += (data != null ? data.length() : 0);
            }
        }
    }

    private class BufferingDeployCallback implements DeployCallback {
        private final String projectName;
        private final DeployEventCallback initialCallback;

        BufferingDeployCallback(String projectName, DeployEventCallback callback) {
            this.projectName = projectName;
            this.initialCallback = callback;
        }

        private void emit(String event, String data) {
            DeploySession session = sessions.get(projectName);
            if (session != null) {
                session.addToBuffer(event, data);
                DeployEventCallback cb = session.currentCallback;
                if (cb != null) {
                    try {
                        cb.onEvent(event, data);
                    } catch (Exception e) {
                        log.warn("Deploy callback error for {}: {}", projectName, e.getMessage());
                    }
                }
            }
        }

        @Override
        public void onOutput(String text) {
            emit("stdout", text);
        }

        @Override
        public void onProgress(String stepName, long current, long total, long speedBps) {
            String data = String.format("{\"stepName\":\"%s\",\"current\":%d,\"total\":%d,\"speedBps\":%d}",
                    stepName.replace("\"", "\\\""), current, total, speedBps);
            emit("scp_progress", data);
        }

        @Override
        public void onStepComplete(String stepName, boolean success) {
            // Not emitted as SSE event, but could be logged
        }

        @Override
        public void onPipelineComplete(boolean success) {
            emit("exit", "{\"code\":" + (success ? 0 : 1) + "}");
            DeploySession session = sessions.get(projectName);
            if (session != null) {
                session.running = false;
                session.endTime = System.currentTimeMillis();
            }
        }

        @Override
        public void onError(String message) {
            emit("error", message);
        }
    }
}
