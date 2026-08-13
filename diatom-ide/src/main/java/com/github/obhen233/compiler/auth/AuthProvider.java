package com.github.obhen233.compiler.auth;

/**
 * 认证提供者接口。
 * 默认实现使用操作系统用户验证，可通过实现此接口并注册为 Spring Bean 来替换。
 */
public interface AuthProvider {

    /**
     * 验证用户名和密码。
     * @return 认证结果
     */
    AuthResult authenticate(String username, String password);

    /**
     * 是否允许免密登录（如 Windows Administrator 无密码）。
     * @return 如果允许免密，返回免密用户名；否则返回 null
     */
    String getAutoLoginUser();

    /**
     * 获取提供者名称（用于日志和前端显示）。
     */
    default String getProviderName() {
        return "default";
    }
}
