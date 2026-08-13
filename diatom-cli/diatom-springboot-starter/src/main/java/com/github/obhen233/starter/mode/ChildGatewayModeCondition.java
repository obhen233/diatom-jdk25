package com.github.obhen233.starter.mode;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring {@link Condition}：匹配子 Gateway（Gateway 级联）模式。
 *
 * <p>当 {@code diatom.mode} 为 {@code "gateway:child"} 时匹配。
 * 用于 {@link com.github.obhen233.starter.gateway.child.ChildGatewayAutoConfiguration}
 * 的条件注册，使当前 Gateway 作为父 Gateway 的子节点（上游 Worker 语义）运行。
 *
 * <p>注意：{@code mode:subtype} 格式不能使用 {@code @ConditionalOnProperty}，
 * 必须用自定义 Condition（见 {@link ModeUtils} 注释约定）。
 */
public class ChildGatewayModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("diatom.mode", "standard");
        return "gateway:child".equals(mode);
    }
}
