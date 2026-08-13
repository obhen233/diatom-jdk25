package com.github.obhen233.quarkus.runtime.terminal;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * IDE Terminal 会话注册表（纯 Java，可单测）。
 *
 * <p>Phase 4 v1：进程内维护 WebSocket 终端会话的元信息（sessionId / open / status / createdAt / uri / remote），
 * 供 {@code TerminalResource} 的会话管理 REST 与 {@code TerminalWebSocket} 的生命周期回调共用。
 * 输入处理 {@link #onInput} 目前为「基础转发（回显 + 中断）」，完整 PTY 级联（WsTerminalIO + AsyncAgentExecutor）
 * 后续替换该方法内部实现即可。</p>
 */
@ApplicationScoped
public class TerminalSessionManager {

    /** 会话信息快照（record，不可变）。 */
    public record TerminalSessionInfo(String sessionId, boolean open, String status, long createdAt,
                                      String uri, String remoteAddress) {
    }

    private final ConcurrentMap<String, TerminalSessionInfo> sessions = new ConcurrentHashMap<>();

    /** 连接建立：登记会话。 */
    public void register(String sessionId, String uri, String remoteAddress) {
        sessions.put(sessionId, new TerminalSessionInfo(sessionId, true, "IDLE",
                System.currentTimeMillis(), uri == null ? "" : uri, remoteAddress == null ? "" : remoteAddress));
    }

    /** 连接关闭 / REST 删除：移除会话。 */
    public boolean closeSession(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    /** 更新会话状态（RUNNING / IDLE…）。 */
    public void updateStatus(String sessionId, String status) {
        sessions.computeIfPresent(sessionId, (id, info) ->
                new TerminalSessionInfo(info.sessionId(), info.open(), status, info.createdAt(),
                        info.uri(), info.remoteAddress()));
    }

    /** 查询单个会话。 */
    public Optional<TerminalSessionInfo> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** 全部会话（不可变快照，保持插入序）。 */
    public Map<String, TerminalSessionInfo> getSessions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sessions));
    }

    /** 当前在线会话数。 */
    public int activeCount() {
        return sessions.size();
    }

    /**
     * 处理一行终端输入。
     *
     * <p>Phase 4 v1：基础转发（回显 + {@code interrupt|/interrupt} 中断）；完整 PTY 级联
     * （AsyncAgentExecutor 提交）后续替换。</p>
     *
     * @param sessionId 会话 ID
     * @param input     客户端输入文本
     * @return 应回写客户端的响应文本
     */
    public String onInput(String sessionId, String input) {
        if (input == null || input.trim().isEmpty()) {
            updateStatus(sessionId, "IDLE");
            return "> ";
        }
        String cmd = input.trim();
        if ("interrupt".equalsIgnoreCase(cmd) || "/interrupt".equals(cmd)) {
            updateStatus(sessionId, "IDLE");
            return "\n[Interrupted]\n> ";
        }
        updateStatus(sessionId, "IDLE");
        return "\n" + input + "\n> ";
    }
}
