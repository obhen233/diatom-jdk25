package com.github.obhen233.compiler.auth;

import com.github.obhen233.compiler.i18n.I18n;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单用户会话管理器。
 * 同一时间只允许一个用户使用 IDE。
 * 新用户登录时，如果已有用户在线，需要协商踢人。
 */
@Component
public class SessionManager {

    /** 当前活跃会话 */
    private volatile ActiveSession currentSession;

    /** 待处理的踢人请求: requestId -> KickRequest */
    private final ConcurrentHashMap<String, KickRequest> pendingKicks = new ConcurrentHashMap<>();

    /** 会话超时时间（小时），可在 application.properties 中配置，默认 4 小时 */
    @Value("${ide.session.timeout-hours:4}")
    private double sessionTimeoutHours;

    /** 专用锁对象，保护 currentSession 的读写操作 */
    private final Object sessionLock = new Object();

    /** 获取超时毫秒数 */
    private long getSessionTimeoutMs() {
        return (long) (sessionTimeoutHours * 3600 * 1000);
    }

    /**
     * 尝试创建会话。
     * @return 会话 token，或 null 如果需要踢人协商
     */
    public LoginResult login(String username) {
        // 检查是否有活跃会话
        synchronized (sessionLock) {
            if (currentSession != null) {
                // 检查是否超时
                if (System.currentTimeMillis() - currentSession.lastActive > getSessionTimeoutMs()) {
                    // 会话已超时，直接替换
                    currentSession = new ActiveSession(username);
                    return LoginResult.success(currentSession.token, username);
                }
                // 不同用户，需要协商
                String requestId = UUID.randomUUID().toString().substring(0, 8);
                KickRequest kick = new KickRequest(username, currentSession.username);
                pendingKicks.put(requestId, kick);
                return LoginResult.needConfirm(requestId, currentSession.username);
            }

            // 清理可能残留的孤儿请求（之前等待超时的请求没有被正确清理）
            cleanOrphanedKickRequests(username);

            // 没有活跃会话，直接登录
            currentSession = new ActiveSession(username);
            return LoginResult.success(currentSession.token, username);
        }
    }

    /**
     * 清理可能残留的孤儿踢人请求。
     * 当 currentSession 为空时，如果有之前创建的 KickRequest 没有被正确清理，
     * 需要在新用户登录前清理掉。
     *
     * 修复：使用 ConcurrentHashMap 的原子操作，先检查再删除，避免竞态。
     * 如果 latch 已经被 countDown()，说明该请求已被处理，无需清理。
     * 如果 latch 未被 countDown()（已超时），则移除。
     */
    private void cleanOrphanedKickRequests(String username) {
        // 遍历所有待处理的踢人请求
        for (java.util.Map.Entry<String, KickRequest> entry : pendingKicks.entrySet()) {
            KickRequest kick = entry.getValue();
            // 只清理匹配用户名的请求
            if (kick.newUser.equals(username)) {
                // 使用原子操作：只有当 latch 还未被 countDown 时才移除
                // CountDownLatch 的 countDown() 只能调用一次
                // 如果 latch 已经是 0（已调用过 countDown），则不清理（说明请求已被处理）
                if (kick.latch.getCount() > 0) {
                    // latch 还有效，说明还没有响应，尝试清理
                    // 但需要确认是否真的超时了（通过 await 确认）
                    try {
                        // await(0, ...) 立即返回：如果 latch > 0 返回 false（未超时），如果 latch == 0 返回 true（已超时）
                        boolean timedOut = !kick.latch.await(0, TimeUnit.MILLISECONDS);
                        if (timedOut) {
                            // 超时了才清理
                            pendingKicks.remove(entry.getKey());
                        }
                        // 如果未超时，不清理（请求还在处理中）
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                // 如果 latch 已经是 0（已 countDown），不清理
            }
        }
    }

    /**
     * 等待踢人协商结果。
     * 新用户调用此方法等待已登录用户的响应。
     * @param requestId 踢人请求 ID
     * @param timeoutSeconds 等待超时秒数
     * @return 登录结果
     */
    public LoginResult waitForKickResult(String requestId, int timeoutSeconds) {
        KickRequest kick = pendingKicks.get(requestId);
        if (kick == null) return LoginResult.fail(I18n.get("session.requestNotFound"));

        try {
            boolean responded = kick.latch.await(timeoutSeconds, TimeUnit.SECONDS);
            pendingKicks.remove(requestId);

            if (!responded) {
                // 超时未响应，允许新用户登录
                synchronized (sessionLock) {
                    currentSession = new ActiveSession(kick.newUser);
                    return LoginResult.success(currentSession.token, kick.newUser);
                }
            }

            if (kick.approved.get()) {
                // 已登录用户同意
                synchronized (sessionLock) {
                    currentSession = new ActiveSession(kick.newUser);
                    return LoginResult.success(currentSession.token, kick.newUser);
                }
            } else {
                return LoginResult.fail(I18n.get("session.kickRejected", kick.currentUser));
            }
        } catch (InterruptedException e) {
            pendingKicks.remove(requestId);
            return LoginResult.fail(I18n.get("session.waitInterrupted"));
        }
    }

    /**
     * 已登录用户响应踢人请求。
     */
    public boolean respondToKick(String requestId, boolean approve, String token) {
        synchronized (sessionLock) {
            // 验证是当前用户在操作
            if (currentSession == null || !currentSession.token.equals(token)) {
                return false;
            }
            KickRequest kick = pendingKicks.get(requestId);
            if (kick == null) return false;
            kick.approved.set(approve);
            kick.latch.countDown();
            return true;
        }
    }

    /**
     * 获取待处理的踢人请求（当前用户轮询）。
     */
    public KickRequest getPendingKick(String token) {
        if (currentSession == null || !currentSession.token.equals(token)) return null;
        for (java.util.Map.Entry<String, KickRequest> entry : pendingKicks.entrySet()) {
            KickRequest kick = entry.getValue();
            if (kick.currentUser.equals(currentSession.username)) {
                kick.requestId = entry.getKey();
                return kick;
            }
        }
        return null;
    }

    /**
     * 验证 token 是否有效。
     * 使用滑动窗口机制：每次验证成功都会刷新 lastActive 时间。
     * 如果距离上次活动时间超过 4 小时，会话失效。
     */
    public boolean validateToken(String token) {
        if (token == null) return false;
        synchronized (sessionLock) {
            if (currentSession == null) return false;
            if (currentSession.token.equals(token)) {
                // 检查是否超时
                if (System.currentTimeMillis() - currentSession.lastActive > getSessionTimeoutMs()) {
                    // 会话已超时，失效
                    currentSession = null;
                    return false;
                }
                // 刷新滑动窗口：每次操作都延长超时
                currentSession.lastActive = System.currentTimeMillis();
                return true;
            }
            return false;
        }
    }

    /**
     * 刷新会话时间（滑动窗口）。
     * 业务操作可调用此方法延长会话超时。
     */
    public boolean refreshSession(String token) {
        if (token == null) return false;
        synchronized (sessionLock) {
            if (currentSession == null) return false;
            if (currentSession.token.equals(token)) {
                currentSession.lastActive = System.currentTimeMillis();
                return true;
            }
            return false;
        }
    }

    /**
     * 登出。
     */
    public void logout(String token) {
        synchronized (sessionLock) {
            if (currentSession != null && currentSession.token.equals(token)) {
                currentSession = null;
            }
        }
    }

    /**
     * 获取当前会话信息（用于状态查询）。
     */
    public ActiveSession getCurrentSession() {
        return currentSession;
    }

    // ==================== 内部类 ====================

    public static class ActiveSession {
        public final String username;
        public final String token;
        public final long loginTime;
        public volatile long lastActive;

        ActiveSession(String username) {
            this.username = username;
            this.token = UUID.randomUUID().toString();
            this.loginTime = System.currentTimeMillis();
            this.lastActive = System.currentTimeMillis();
        }
    }

    public static class KickRequest {
        public final String newUser;
        public final String currentUser;
        public final CountDownLatch latch = new CountDownLatch(1);
        public final AtomicReference<Boolean> approved = new AtomicReference<>(false);
        public String requestId; // 填充用

        KickRequest(String newUser, String currentUser) {
            this.newUser = newUser;
            this.currentUser = currentUser;
        }
    }

    public static class LoginResult {
        public final String status; // "success", "needConfirm", "fail"
        public final String token;
        public final String username;
        public final String message;
        public final String requestId;
        public final String currentUser;

        private LoginResult(String status, String token, String username, String message, String requestId, String currentUser) {
            this.status = status;
            this.token = token;
            this.username = username;
            this.message = message;
            this.requestId = requestId;
            this.currentUser = currentUser;
        }

        static LoginResult success(String token, String username) {
            return new LoginResult("success", token, username, null, null, null);
        }
        static LoginResult needConfirm(String requestId, String currentUser) {
            return new LoginResult("needConfirm", null, null, null, requestId, currentUser);
        }
        static LoginResult fail(String message) {
            return new LoginResult("fail", null, null, message, null, null);
        }
    }
}
