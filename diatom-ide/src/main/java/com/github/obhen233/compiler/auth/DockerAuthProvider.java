package com.github.obhen233.compiler.auth;

import com.github.obhen233.compiler.i18n.I18n;
import org.springframework.context.annotation.Conditional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Docker 环境认证提供者。
 * 当检测到在 Docker 容器中运行或 ide.docker.enabled=true 时使用此认证方式。
 *
 * 配置项：
 * - ide.docker.enabled: 启用方式，auto（自动检测）、true（强制启用）、false（强制禁用），默认 auto
 * - ide.docker.username: Docker 登录用户名（默认 appuser）
 * - ide.docker.password: Docker 登录密码
 * - ide.docker.password-hash: 密码的 SHA-256 哈希（Base64 编码），优先于明文密码
 *
 * 如果同时配置了 password 和 password-hash，优先使用 password-hash。
 */
@Component
@Conditional(DockerAuthProvider.DockerCondition.class)
public class DockerAuthProvider implements AuthProvider {

    /** 检测是否在 Docker 容器中运行 */
    private static final boolean IS_DOCKER = detectDocker();

    @Value("${ide.docker.enabled:auto}")
    private String enabledConfig;

    @Value("${ide.docker.username:appuser}")
    private String configuredUsername;

    @Value("${ide.docker.password:}")
    private String configuredPassword;

    @Value("${ide.docker.password-hash:}")
    private String passwordHash;

    private static boolean detectDocker() {
        // 检查 /.dockerenv 文件
        if (new File("/.dockerenv").exists()) {
            return true;
        }
        // 检查 /proc/1/cgroup 是否包含 docker 关键字
        try {
            File cgroup = new File("/proc/1/cgroup");
            if (cgroup.exists()) {
                String content = readFile(cgroup);
                if (content != null && content.toLowerCase().contains("docker")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String readFile(File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public AuthResult authenticate(String username, String password) {
        if (!isEnabled()) {
            return AuthResult.fail(I18n.get("auth.docker.notEnabled"));
        }

        if (username == null || username.trim().isEmpty()) {
            return AuthResult.fail(I18n.get("auth.docker.usernameEmpty"));
        }
        username = username.trim();

        // 验证用户名
        if (!username.equals(configuredUsername)) {
            return AuthResult.fail(I18n.get("auth.docker.usernameIncorrect"));
        }

        if (password == null) {
            password = "";
        }

        // 验证密码
        if (!verifyPassword(password)) {
            return AuthResult.fail(I18n.get("auth.docker.passwordIncorrect"));
        }

        return AuthResult.success(username);
    }

    private boolean verifyPassword(String password) {
        // 如果配置了密码哈希，使用哈希验证
        if (passwordHash != null && !passwordHash.isEmpty()) {
            String inputHash = sha256Base64(password);
            return passwordHash.equals(inputHash);
        }

        // 否则使用明文密码验证
        return configuredPassword.equals(password);
    }

    private String sha256Base64(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getAutoLoginUser() {
        // Docker 环境下需要密码，不支持自动登录
        return null;
    }

    @Override
    public String getProviderName() {
        return "docker-auth";
    }

    public boolean isEnabled() {
        // auto: 自动检测 Docker 环境
        // true: 强制启用
        // false: 强制禁用
        if ("true".equalsIgnoreCase(enabledConfig)) {
            return true;
        }
        if ("false".equalsIgnoreCase(enabledConfig)) {
            return false;
        }
        // auto 模式：根据是否在 Docker 环境自动决定
        return IS_DOCKER;
    }

    /** 仅用于测试：是否检测到 Docker 环境 */
    public static boolean isDockerEnvironment() {
        return IS_DOCKER;
    }

    /** Docker 认证条件 */
    public static class DockerCondition implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                             org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String enabled = context.getEnvironment().getProperty("ide.docker.enabled", "auto");
            if ("true".equalsIgnoreCase(enabled)) {
                return true;
            }
            if ("false".equalsIgnoreCase(enabled)) {
                return false;
            }
            // auto 模式：检测 Docker 环境
            return IS_DOCKER;
        }
    }
}
