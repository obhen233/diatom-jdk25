package com.github.obhen233.compiler.auth;

import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.auth.LoginRequest;
import com.github.obhen233.compiler.dto.auth.RespondToKickRequest;
import com.github.obhen233.compiler.dto.auth.WaitForKickRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Authentication REST API / 认证 REST API
 */
@CrossOrigin
@RestController
@RequestMapping("/auth")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Authentication / 认证", description = "User authentication and session management / 用户认证和会话管理")
public class AuthController implements AutoCloseable {

    @Autowired
    private AuthProvider authProvider;

    @Autowired
    private SessionManager sessionManager;

    // === 速率限制 ===
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000; // 5 分钟
    private static final long CLEANUP_INTERVAL_MS = 10 * 60 * 1000; // 10 分钟清理一次过期条目

    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();

    // 定时清理过期条目
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "login-attempt-cleanup");
        t.setDaemon(true);
        return t;
    });

    {
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredAttempts, CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void cleanupExpiredAttempts() {
        long now = System.currentTimeMillis();
        long expiryThreshold = now - LOCKOUT_DURATION_MS * 2; // 超过 lockout 时长 2 倍则认为过期
        Iterator<Map.Entry<String, LoginAttempt>> it = loginAttempts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LoginAttempt> entry = it.next();
            LoginAttempt attempt = entry.getValue();
            if (attempt.lastAttemptTime < expiryThreshold && attempt.lockoutUntil < now) {
                it.remove();
            }
        }
    }

    /**
     * 关闭清理调度器，释放资源
     */
    @Override
    public void close() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class LoginAttempt {
        int count;
        long lastAttemptTime;
        long lockoutUntil;

        LoginAttempt() {
            this.count = 0;
            this.lastAttemptTime = System.currentTimeMillis();
            this.lockoutUntil = 0;
        }
    }

    /**
     * 检查是否可以免密登录。
     */
    @GetMapping("/check")
    @io.swagger.v3.oas.annotations.Operation(summary = "Check auto login / 检查自动登录", description = "Check if auto login is available / 检查是否可以免密登录")
    public ApiResponse<Map<String, Object>> check() {
        try {
            Map<String, Object> r = new HashMap<>();
            String autoUser = authProvider.getAutoLoginUser();
            if (autoUser != null) {
                // 免密登录
                SessionManager.LoginResult lr = sessionManager.login(autoUser);
                r.put("autoLogin", true);
                r.put("token", lr.token);
                r.put("username", autoUser);
            } else {
                r.put("autoLogin", false);
                r.put("currentUser", System.getProperty("user.name"));
                r.put("provider", authProvider.getProviderName());
            }
            return ApiResponse.ok(r);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * 登录（含速率限制）。
     */
    @PostMapping("/login")
    @io.swagger.v3.oas.annotations.Operation(summary = "Login / 登录", description = "Authenticate user and create session / 验证用户凭据并创建会话")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        String username = body.username();
        String password = body.password();

        // 速率限制检查
        String clientIp = getClientIp(request);
        LoginAttempt attempt = loginAttempts.computeIfAbsent(clientIp, k -> new LoginAttempt());

        long now = System.currentTimeMillis();
        if (attempt.lockoutUntil > now) {
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            long remainSec = (attempt.lockoutUntil - now) / 1000;
            r.put("message", I18n.get("auth.rateLimited", remainSec));
            return ApiResponse.fail(I18n.get("auth.rateLimited", remainSec));
        }

        // 超过窗口期则重置计数
        if (now - attempt.lastAttemptTime > LOCKOUT_DURATION_MS) {
            attempt.count = 0;
        }

        // 1. 验证凭据
        AuthResult authResult = authProvider.authenticate(username, password);
        if (!authResult.isSuccess()) {
            attempt.count++;
            attempt.lastAttemptTime = now;
            if (attempt.count >= MAX_ATTEMPTS) {
                attempt.lockoutUntil = now + LOCKOUT_DURATION_MS;
            }
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("message", authResult.getMessage());
            r.put("remainingAttempts", Math.max(0, MAX_ATTEMPTS - attempt.count));
            return ApiResponse.fail(authResult.getMessage());
        }

        // 登录成功，重置计数
        attempt.count = 0;
        attempt.lockoutUntil = 0;

        // 2. 尝试创建会话
        SessionManager.LoginResult lr = sessionManager.login(authResult.getUsername());
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("status", lr.status);
        r.put("token", lr.token);
        r.put("username", lr.username);
        r.put("requestId", lr.requestId);
        r.put("currentUser", lr.currentUser);
        if ("fail".equals(lr.status)) {
            r.put("success", false);
            r.put("message", lr.message);
        }
        return ApiResponse.ok(r);
    }

    private String getClientIp(HttpServletRequest request) {
        // 仅在有可信代理配置时信任 X-Forwarded-For
        // 在生产环境中，应该通过配置或网关来控制是否信任此头
        // 目前默认不信任 X-Forwarded-For，防止 IP 限速被绕过
        return request.getRemoteAddr();
    }

    /**
     * 等待踢人协商结果（新用户调用，长轮询）。
     */
    @PostMapping("/login/wait")
    @io.swagger.v3.oas.annotations.Operation(summary = "Wait for kick result / 等待踢人协商结果", description = "Wait for existing user to respond to kick request (long polling) / 等待现有用户响应踢人请求（长轮询）")
    public ApiResponse<Map<String, Object>> waitForKick(@Valid @RequestBody WaitForKickRequest body) {
        String requestId = body.requestId();
        if (requestId == null) return ApiResponse.fail(I18n.get("auth.kick.requestIdEmpty"));

        // 等待 10 秒
        SessionManager.LoginResult lr = sessionManager.waitForKickResult(requestId, 10);
        Map<String, Object> r = new HashMap<>();
        r.put("success", "success".equals(lr.status));
        r.put("status", lr.status);
        r.put("token", lr.token);
        r.put("username", lr.username);
        r.put("message", lr.message);
        return ApiResponse.ok(r);
    }

    /**
     * 已登录用户轮询是否有踢人请求。
     */
    @GetMapping("/kick/pending")
    @io.swagger.v3.oas.annotations.Operation(summary = "Get pending kick request / 获取待处理踢人请求", description = "Check if there is a pending kick request for current session / 检查当前会话是否有待处理的踢人请求")
    public ApiResponse<Map<String, Object>> getPendingKick(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        try {
            Map<String, Object> r = new HashMap<>();
            if (token == null) { r.put("hasPending", false); return ApiResponse.ok(r); }
            SessionManager.KickRequest kick = sessionManager.getPendingKick(token);
            if (kick == null) {
                r.put("hasPending", false);
            } else {
                r.put("hasPending", true);
                r.put("requestId", kick.requestId);
                r.put("newUser", kick.newUser);
            }
            return ApiResponse.ok(r);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * 已登录用户响应踢人请求。
     */
    @PostMapping("/kick/respond")
    @io.swagger.v3.oas.annotations.Operation(summary = "Respond to kick request / 响应踢人请求", description = "Approve or reject kick request / 批准或拒绝踢人请求")
    public ApiResponse<Map<String, Object>> respondToKick(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                              @Valid @RequestBody RespondToKickRequest body) {
        String requestId = body.requestId();
        Boolean approve = body.approve();
        if (requestId == null || approve == null) return ApiResponse.fail(I18n.get("auth.kick.paramIncomplete"));
        boolean ok = sessionManager.respondToKick(requestId, approve, token);
        Map<String, Object> r = new HashMap<>();
        r.put("success", ok);
        return ApiResponse.ok(r);
    }

    /**
     * 验证 token。
     */
    @GetMapping("/validate")
    @io.swagger.v3.oas.annotations.Operation(summary = "Validate token / 验证令牌", description = "Validate authentication token / 验证认证令牌")
    public ApiResponse<Map<String, Object>> validate(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        try {
            Map<String, Object> r = new HashMap<>();
            r.put("valid", sessionManager.validateToken(token));
            if (sessionManager.getCurrentSession() != null && sessionManager.validateToken(token)) {
                r.put("username", sessionManager.getCurrentSession().username);
            }
            return ApiResponse.ok(r);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * 登出。
     */
    @PostMapping("/logout")
    @io.swagger.v3.oas.annotations.Operation(summary = "Logout / 登出", description = "Invalidate session token / 作废会话令牌")
    public ApiResponse<Map<String, Object>> logout(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        try {
            if (token != null) sessionManager.logout(token);
            Map<String, Object> r = new HashMap<>();
            r.put("success", true);
            return ApiResponse.ok(r);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
