package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.quarkus.runtime.terminal.TerminalSessionManager;
import com.github.obhen233.quarkus.runtime.terminal.TerminalSessionManager.TerminalSessionInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IDE Terminal 会话管理 REST（{@code /api/ide/terminal}），镜像 starter {@code TerminalSessionController}。
 *
 * <p>Phase 4 v1：info / sessions / sessions{id}（GET + DELETE）读写 {@link TerminalSessionManager}
 * 会话注册表。完整 PTY 级联后续接入。</p>
 */
@ApplicationScoped
@Path("/api/ide/terminal")
public class TerminalResource {

    private final TerminalSessionManager sessionManager;

    public TerminalResource(TerminalSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /** WebSocket 连接信息。 */
    @GET
    @Path("/info")
    public Map<String, Object> getInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        int activeCount = sessionManager.activeCount();
        info.put("activeSessions", activeCount);
        info.put("status", activeCount > 0 ? "active" : "idle");
        info.put("sessionIds", sessionManager.getSessions().keySet());
        info.put("terminalPath", "/ide/terminal");
        info.put("protocol", "WebSocket");
        return info;
    }

    /** 列出全部活动终端会话。 */
    @GET
    @Path("/sessions")
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TerminalSessionInfo s : sessionManager.getSessions().values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("sessionId", s.sessionId());
            info.put("open", s.open());
            info.put("status", s.status());
            info.put("createdAt", s.createdAt());
            result.add(info);
        }
        return result;
    }

    /** 单个会话详情（不存在时返回 error 字段，兼容 starter 语义）。 */
    @GET
    @Path("/sessions/{id}")
    public Map<String, Object> getSession(@PathParam("id") String id) {
        Map<String, Object> info = new LinkedHashMap<>();
        sessionManager.getSession(id).ifPresentOrElse(s -> {
            info.put("sessionId", s.sessionId());
            info.put("open", s.open());
            info.put("status", s.status());
            info.put("uri", s.uri());
            info.put("remoteAddress", s.remoteAddress());
        }, () -> {
            info.put("error", "Session not found");
            info.put("sessionId", id);
        });
        return info;
    }

    /** 关闭指定终端会话。 */
    @DELETE
    @Path("/sessions/{id}")
    public Map<String, Object> closeSession(@PathParam("id") String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sessionManager.closeSession(id)) {
            result.put("success", true);
            result.put("sessionId", id);
            result.put("message", "Session closed");
        } else {
            result.put("error", "Session not found");
            result.put("sessionId", id);
        }
        return result;
    }
}
