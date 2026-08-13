package com.github.obhen233.compiler.i18n;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 语言拦截器。
 * 从请求头中获取 Accept-Language 并设置到 LocaleContextHolder。
 * 前端需要在请求头中携带 Accept-Language。
 *
 * 如果前端没有发送 Accept-Language，可以发送 X-Lang header（优先）：
 * - X-Lang: zh -> 中文
 * - X-Lang: en -> 英文
 */
@Component
public class LocaleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 优先使用 X-Lang header
        String lang = request.getHeader("X-Lang");
        if (lang != null && !lang.isEmpty()) {
            if ("zh".equalsIgnoreCase(lang)) {
                LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            } else {
                LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
            }
            return true;
        }

        // 否则使用 Accept-Language header（Spring 会自动解析）
        // AcceptHeaderLocaleResolver 会自动处理，这里不需要额外操作
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清理 LocaleContextHolder
        LocaleContextHolder.resetLocaleContext();
    }
}
