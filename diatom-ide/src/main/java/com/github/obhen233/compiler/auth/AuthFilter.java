package com.github.obhen233.compiler.auth;

import com.github.obhen233.compiler.i18n.I18n;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 认证过滤器。
 * 拦截除 /auth/**, 静态资源以外的所有请求，验证 X-Auth-Token。
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    @Autowired
    private SessionManager sessionManager;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String path = req.getRequestURI();

        // 放行: 认证接口、静态资源、WebSocket 握手、API文档、健康检查/指标探针
        // 注意: /actuator/health 与 /actuator/prometheus 供 Docker/K8s 探针与 Prometheus 抓取使用（无 token），
        //       其余 actuator 端点（env/heapdump 等）仍在认证保护下。
        if (path.startsWith("/auth/")
                || path.equals("/")
                || path.startsWith("/assets/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/actuator/prometheus")
                || path.endsWith(".html")
                || path.endsWith(".js")
                || path.endsWith(".css")
                || path.endsWith(".ttf")
                || path.endsWith(".woff")
                || path.endsWith(".woff2")
                || path.endsWith(".ico")
                || path.endsWith(".png")
                || path.endsWith(".svg")
                || path.startsWith("/java-lsp")
                || path.startsWith("/terminal-ws")
                || path.startsWith("/webjars/")) {
            chain.doFilter(request, response);
            return;
        }

        // 验证 token
        String token = req.getHeader("X-Auth-Token");
        if (token == null || token.isEmpty()) {
            // 也检查 query parameter（用于 SSE 等不方便加 header 的场景）
            token = req.getParameter("_token");
        }

        if (token != null && sessionManager.validateToken(token)) {
            chain.doFilter(request, response);
            return;
        }

        // 未认证
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"success\":false,\"message\":\"" + I18n.get("auth.session.timeout") + "\"}");
        return;
    }
}
