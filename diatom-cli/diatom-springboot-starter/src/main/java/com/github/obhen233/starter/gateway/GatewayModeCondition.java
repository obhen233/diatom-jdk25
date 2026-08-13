package com.github.obhen233.starter.gateway;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring {@link Condition}：匹配 Gateway 模式。
 *
 * <p>当 {@code diatom.mode} 为 {@code "gateway"} 或以 {@code "gateway:"} 开头时匹配。
 * 用于 {@link DiatomGatewayAutoConfiguration} 的条件注册，
 * 支持子类型模式如 {@code gateway:nacos}、{@code gateway:eureka}。
 *
 * <p>使用方式：
 * <pre>
 * &#64;Conditional(GatewayModeCondition.class)
 * </pre>
 */
public class GatewayModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("diatom.mode", "standard");
        return "gateway".equals(mode) || mode.startsWith("gateway:");
    }
}
