package com.github.obhen233.compiler.service;

import com.github.obhen233.starter.gateway.remote.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages AI conversation sessions within the IDE.
 *
 * Uses a shared ChatService singleton (from starter autoconfiguration),
 * wraps it with session-scoped state (auto-approve, confirmation latches).
 *
 * In local mode, the ChatService wraps a ReActAgent running in-process.
 * In remote mode, the ChatService communicates with a remote Gateway.
 *
 * Sessions are identified by a client-generated sessionId (from the X-Session-Id header).
 */
@Service
public class AgentManager {

    private static final Logger logger = LoggerFactory.getLogger(AgentManager.class);

    private final ChatService chatService;
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public AgentManager(ChatService chatService) {
        this.chatService = chatService;
        logger.info("AgentManager initialized with ChatService: {}", chatService);
    }

    /**
     * Get or create a session for the given sessionId.
     */
    public AgentSession getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> {
            logger.info("Creating new AI session: {}", id);
            return new AgentSession(id, chatService);
        });
    }

    /**
     * Reset a session: clear conversation history and disable auto-approve.
     */
    public void reset(String sessionId) {
        AgentSession session = sessions.get(sessionId);
        if (session != null) {
            session.reset();
            logger.info("Reset AI session: {}", sessionId);
        }
    }

    /**
     * Destroy a session completely.
     */
    public void destroy(String sessionId) {
        AgentSession removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.reset();
            logger.info("Destroyed AI session: {}", sessionId);
        }
    }

    /**
     * Generate a new unique session ID.
     */
    public static String generateSessionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Get current active session count.
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}
