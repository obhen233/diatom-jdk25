package com.github.obhen233.compiler.service;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.starter.gateway.remote.ChatService;
import com.github.obhen233.starter.gateway.remote.LocalChatService;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Represents a single AI conversation session within the IDE.
 *
 * Each session wraps a shared ChatService singleton with session-scoped state:
 * - Whether file operations are auto-approved
 * - The conversation history
 * - Confirmation latch for blocking agent execution during user confirmation
 *
 * In local mode, the underlying ReActAgent is accessible via {@link #getAgent()}.
 * In remote mode, session delegates to {@link ChatService} which communicates
 * with a remote Gateway.
 */
public class AgentSession {

    private final String sessionId;
    private final ChatService chatService;
    private final ReActAgent localAgent;
    private final Instant createdAt;

    /** The project this session belongs to */
    private String projectName;

    /** Whether write operations are auto-approved for this session */
    private boolean autoApprove;

    /** Latch for pausing agent execution waiting for user confirmation */
    private volatile CountDownLatch confirmLatch;
    private volatile boolean confirmDecision; // true = yes, false = no

    /** Whether the user rejected a specific tool call (decision='n') */
    private volatile boolean confirmRejected = false;

    /** Saved messages for resuming after ToolConfirmationException */
    private volatile List<ChatMessage> savedMessages;

    public AgentSession(String sessionId, ChatService chatService) {
        this.sessionId = sessionId;
        this.chatService = chatService;
        this.createdAt = Instant.now();
        this.autoApprove = false;

        // Extract local ReActAgent reference if available
        if (chatService instanceof LocalChatService localChatService) {
            this.localAgent = localChatService.getAgent();
        } else {
            this.localAgent = null;
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the underlying ReActAgent in local mode, or null in remote mode.
     */
    public ReActAgent getAgent() {
        return localAgent;
    }

    /**
     * Returns the ChatService (works in both local and remote modes).
     */
    public ChatService getChatService() {
        return chatService;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public boolean isAutoApprove() {
        return autoApprove;
    }

    public void setAutoApprove(boolean autoApprove) {
        this.autoApprove = autoApprove;
        if (localAgent != null) {
            localAgent.setAutoApproveWrite(autoApprove);
        }
    }

    /**
     * Block the agent thread until the user makes a confirmation decision.
     * Returns true if confirmed, false if denied.
     */
    public boolean waitForConfirm(long timeoutMs) throws InterruptedException {
        this.confirmLatch = new CountDownLatch(1);
        this.confirmRejected = false;
        boolean countedDown = confirmLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        this.confirmLatch = null;
        return countedDown && confirmDecision;
    }

    /**
     * Supply a raw boolean confirmation decision (used internally by cancel).
     */
    public void supplyConfirm(boolean decision) {
        this.confirmDecision = decision;
        this.confirmRejected = false;
        CountDownLatch latch = this.confirmLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * Supply a confirmation decision based on the user's choice string.
     * "y" = confirm one operation
     * "a" = confirm AND auto-approve future operations in this session
     * "n" = reject this specific tool call (let the agent try a different approach)
     */
    public void supplyConfirmDecision(String decision) {
        boolean isApproved = "y".equalsIgnoreCase(decision) || "a".equalsIgnoreCase(decision);
        this.confirmDecision = isApproved;
        this.confirmRejected = "n".equalsIgnoreCase(decision);
        if (isApproved && "a".equalsIgnoreCase(decision)) {
            setAutoApprove(true);
        }
        CountDownLatch latch = this.confirmLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * Whether the user rejected the tool call (decision='n').
     * The caller should inject a rejection message into the agent's history
     * and let the agent continue rather than cancelling the whole task.
     */
    public boolean isConfirmRejected() {
        return confirmRejected;
    }

    /**
     * Save messages for resuming after ToolConfirmationException.
     */
    public void saveMessages(List<ChatMessage> messages) {
        this.savedMessages = messages;
    }

    /**
     * Get and clear saved messages.
     */
    public List<ChatMessage> getAndClearSavedMessages() {
        List<ChatMessage> result = this.savedMessages;
        this.savedMessages = null;
        return result;
    }

    /**
     * Reset session state: clear history and disable auto-approve.
     */
    public void reset() {
        if (localAgent != null) {
            localAgent.clearHistory();
            localAgent.setAutoApproveWrite(false);
        }
        autoApprove = false;
        savedMessages = null;
    }
}
