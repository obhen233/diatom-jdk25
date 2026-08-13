package com.github.obhen233.compiler.auth;

/**
 * 认证结果。
 */
public class AuthResult {
    private final boolean success;
    private final String username;
    private final String message;

    private AuthResult(boolean success, String username, String message) {
        this.success = success;
        this.username = username;
        this.message = message;
    }

    public static AuthResult success(String username) {
        return new AuthResult(true, username, null);
    }

    public static AuthResult fail(String message) {
        return new AuthResult(false, null, message);
    }

    public boolean isSuccess() { return success; }
    public String getUsername() { return username; }
    public String getMessage() { return message; }
}
