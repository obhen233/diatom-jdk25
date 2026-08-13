package com.github.obhen233.compiler.auth;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.io.File;

/**
 * 条件：不在 Docker 容器中运行。
 */
public class NotDockerCondition implements Condition {

    private static final boolean IS_DOCKER = detectDocker();

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
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // 如果 ide.docker.enabled=true，强制不使用 OsAuthProvider
        String dockerEnabled = context.getEnvironment().getProperty("ide.docker.enabled", "auto");
        if ("true".equalsIgnoreCase(dockerEnabled)) {
            return false;
        }
        if ("false".equalsIgnoreCase(dockerEnabled)) {
            return true;
        }
        // auto 模式：根据是否在 Docker 环境决定
        return !IS_DOCKER;
    }
}
