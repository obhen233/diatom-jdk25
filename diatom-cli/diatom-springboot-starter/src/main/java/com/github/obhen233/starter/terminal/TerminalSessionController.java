package com.github.obhen233.starter.terminal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for managing WebSocket terminal sessions.
 * Provides Swagger-documented endpoints for session management.
 */
@RestController
@RequestMapping("/api/ide/terminal")
@Tag(name = "IDE Terminal", description = "WebSocket terminal session management")
public class TerminalSessionController {

    private final TerminalWebSocketHandler handler;

    public TerminalSessionController(TerminalWebSocketHandler handler) {
        this.handler = handler;
    }

    @GetMapping("/info")
    @Operation(summary = "Get WebSocket connection information")
    public Map<String, Object> getInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        int activeCount = handler.getSessions().size();
        info.put("activeSessions", activeCount);
        info.put("status", activeCount > 0 ? "active" : "idle");
        info.put("sessionIds", handler.getSessions().keySet());
        info.put("terminalPath", "/ide/terminal");
        info.put("protocol", "WebSocket");
        return info;
    }

    @GetMapping("/sessions")
    @Operation(summary = "List all active terminal sessions")
    public List<Map<String, Object>> listSessions() {
        return handler.getSessions().values().stream()
                .map(s -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("sessionId", s.wsSession.getId());
                    info.put("open", s.wsSession.isOpen());
                    info.put("status", s.wsTerminalIO.getCurrentStatus().name());
                    info.put("createdAt", s.wsSession.getAttributes().get("createdAt"));
                    return info;
                })
                .collect(Collectors.toList());
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Get details of a specific terminal session")
    public Map<String, Object> getSession(
            @Parameter(description = "WebSocket session ID") @PathVariable String id) {
        TerminalWebSocketHandler.TerminalSession session = handler.getSession(id);
        if (session == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Session not found");
            error.put("sessionId", id);
            return error;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sessionId", session.wsSession.getId());
        info.put("open", session.wsSession.isOpen());
        info.put("status", session.wsTerminalIO.getCurrentStatus().name());
        info.put("uri", session.wsSession.getUri() != null ? session.wsSession.getUri().toString() : "");
        info.put("remoteAddress", session.wsSession.getRemoteAddress() != null
                ? session.wsSession.getRemoteAddress().toString() : "");
        return info;
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "Close a specific terminal session")
    public Map<String, Object> closeSession(
            @Parameter(description = "WebSocket session ID") @PathVariable String id) {
        TerminalWebSocketHandler.TerminalSession session = handler.getSession(id);
        if (session == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Session not found");
            error.put("sessionId", id);
            return error;
        }
        session.executor.shutdown();
        session.wsTerminalIO.stop();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("sessionId", id);
        result.put("message", "Session closed");
        return result;
    }
}
