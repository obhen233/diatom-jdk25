package com.github.obhen233.compiler.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.compiler.dto.ToolProgress;
import com.github.obhen233.core.agent.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes AI tool execution progress to WebSocket clients.
 *
 * <p>Tracks per-session progress state and pushes updates via the terminal WebSocket
 * connection. Progress messages are sent in real-time as MCP tools are called.
 *
 * <p>Message format sent to client:
 * <pre>
 * {
 *   "type": "progress",
 *   "data": {
 *     "sessionId": "abc123",
 *     "current": { "tool": "read_file", "target": "src/Main.java", "status": "reading" },
 *     "history": [
 *       { "tool": "search_files", "target": "*.java", "status": "completed", "timestamp": 1718092799000 }
 *     ]
 *   }
 * }
 * </pre>
 *
 * @see ToolProgress
 */
@Component
public class ProgressPublisher {

    private static final Logger logger = LoggerFactory.getLogger(ProgressPublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Thread-local storage for current AI session ID */
    private static final ThreadLocal<String> currentSessionContext = new ThreadLocal<>();

    /** sessionId -> current ongoing progress */
    private final Map<String, ToolProgress> currentProgress = new ConcurrentHashMap<>();

    /** sessionId -> list of completed progress entries */
    private final Map<String, List<ToolProgress>> sessionHistory = new ConcurrentHashMap<>();

    /** sessionId -> WebSocket session */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * Set the current AI session context for the calling thread.
     * Call this when starting AI processing in handleAi().
     * Call {@link #clearContext()} when AI processing completes.
     *
     * @param sessionId terminal session ID (WebSocket session ID)
     */
    public void setContext(String sessionId) {
        currentSessionContext.set(sessionId);
    }

    /**
     * Clear the current AI session context.
     * Call this when AI processing completes or is cancelled.
     */
    public void clearContext() {
        currentSessionContext.remove();
    }

    /**
     * Get the current session ID from context.
     * Falls back to ToolExecutor's thread-local if not set directly (cross-thread MCP calls).
     */
    private String getCurrentSessionId() {
        String id = currentSessionContext.get();
        if (id == null) {
            // MCP tool calls run on ToolExecutor's thread pool — use propagated context
            id = ToolExecutor.CURRENT_SESSION.get();
        }
        return id;
    }

    /**
     * Register a WebSocket session for progress updates.
     *
     * @param sessionId terminal session ID (wsSession.getId())
     * @param session WebSocket session
     */
    public void registerSession(String sessionId, WebSocketSession session) {
        sessions.put(sessionId, session);
    }

    /**
     * Unregister WebSocket session and clear associated progress state.
     *
     * @param sessionId terminal session ID
     */
    public void unregisterSession(String sessionId) {
        sessions.remove(sessionId);
        currentProgress.remove(sessionId);
        sessionHistory.remove(sessionId);
    }

    /**
     * Publish progress update to connected client.
     *
     * <p>If status is "completed", the current progress is moved to history.
     * Otherwise, it becomes the current active progress.
     *
     * <p>Uses the current session context from ThreadLocal if sessionId is not provided.
     *
     * @param progress tool progress
     * @param sessionId terminal session ID (optional, uses context if null)
     */
    public void publish(ToolProgress progress, String sessionId) {
        if (progress == null) return;

        if (sessionId == null) {
            sessionId = getCurrentSessionId();
        }
        if (sessionId == null) {
            logger.debug("No sessionId available for progress publish, skipping");
            return;
        }

        if ("completed".equals(progress.getStatus())) {
            ToolProgress current = currentProgress.remove(sessionId);
            if (current != null) {
                sessionHistory.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(current);
            }
        } else {
            currentProgress.put(sessionId, progress);
        }

        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            sendProgressMessage(session, sessionId);
        }
    }

    /**
     * Publish progress update using current session context.
     */
    public void publish(ToolProgress progress) {
        publish(progress, null);
    }

    /**
     * Publish a real-time file change notification to the frontend.
     * <p>
     * Sent whenever the AI agent writes, modifies, or deletes a file.
     * The frontend displays this as a diff panel so the user can see
     * exactly what lines changed.
     *
     * @param sessionId the AI session ID
     * @param filePath  the changed file path (relative to workspace)
     * @param operation CREATE, MODIFY, or DELETE
     * @param diffText  unified diff text of the change
     * @param category  file category for filtering (PROJECT_SOURCE, HELPER_SCRIPT, etc.)
     */
    public void publishFileChange(String sessionId, String filePath,
                                  String operation, String diffText, String category) {
        if (sessionId == null) return;

        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) return;

        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "file_change");

            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", sessionId);
            data.put("filePath", filePath);
            data.put("operation", operation);
            data.put("diff", diffText);
            data.put("category", category != null ? category : "HELPER_SCRIPT");

            msg.put("data", data);

            String json = JSON.writeValueAsString(msg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to send file_change message: {}", e.getMessage());
        }
    }

    /**
     * Mark AI task as done and clear all progress state.
     *
     * @param sessionId terminal session ID
     */
    public void markDone(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            sendDoneMessage(session, sessionId);
        }
        currentProgress.remove(sessionId);
        sessionHistory.remove(sessionId);
    }

    private void sendProgressMessage(WebSocketSession session, String sessionId) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "progress");

            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", sessionId);

            ToolProgress current = currentProgress.get(sessionId);
            if (current != null) {
                data.put("current", toMap(current));
            } else {
                data.put("current", null);
            }

            List<ToolProgress> history = sessionHistory.get(sessionId);
            if (history != null && !history.isEmpty()) {
                List<Map<String, Object>> historyMaps = new ArrayList<>();
                for (ToolProgress h : history) {
                    historyMaps.add(toMap(h));
                }
                data.put("history", historyMaps);
            } else {
                data.put("history", Collections.emptyList());
            }

            msg.put("data", data);

            String json = JSON.writeValueAsString(msg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to send progress message: {}", e.getMessage());
        }
    }

    private void sendDoneMessage(WebSocketSession session, String sessionId) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "progress");

            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", sessionId);
            data.put("done", true);

            msg.put("data", data);

            String json = JSON.writeValueAsString(msg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to send done message: {}", e.getMessage());
        }
    }

    private Map<String, Object> toMap(ToolProgress p) {
        Map<String, Object> map = new HashMap<>();
        map.put("tool", p.getTool());
        map.put("target", p.getTarget());
        map.put("status", p.getStatus());
        map.put("timestamp", p.getTimestamp());
        return map;
    }

    /**
     * Get current progress for a session.
     */
    public ToolProgress getCurrentProgress(String sessionId) {
        return currentProgress.get(sessionId);
    }

    /**
     * Get history for a session.
     */
    public List<ToolProgress> getHistory(String sessionId) {
        return sessionHistory.getOrDefault(sessionId, Collections.emptyList());
    }

    /**
     * Send a stdout message to the WebSocket session identified by sessionId.
     * Used by MCP tools (e.g., deploy) to stream real-time output during long-running operations.
     * Falls back to {@link ToolExecutor#CURRENT_SESSION} if sessionId is null.
     *
     * @param sessionId the AI session ID (may be null to use ThreadLocal context)
     * @param text      the text to send as stdout
     */
    public void sendStdout(String sessionId, String text) {
        if (text == null || text.isEmpty()) return;
        if (sessionId == null) {
            sessionId = getCurrentSessionId();
        }
        if (sessionId == null) return;

        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) return;

        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "stdout");
            msg.put("text", text);
            String json = JSON.writeValueAsString(msg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to send stdout message: {}", e.getMessage());
        }
    }
}
