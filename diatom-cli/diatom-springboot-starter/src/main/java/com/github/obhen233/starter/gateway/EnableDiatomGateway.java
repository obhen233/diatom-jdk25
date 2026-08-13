package com.github.obhen233.starter.gateway;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * 启用 Diatom Gateway 嵌入式模式
 *
 * 在 Spring Boot 应用的主类上添加此注解即可启用 Gateway 功能:
 * <pre>
 * &#64;SpringBootApplication
 * &#64;EnableDiatomGateway
 * public class MyApplication { ... }
 * </pre>
 *
 * 等效于在 application.properties 中配置:
 * diatom.gateway.enabled=true
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(DiatomGatewayAutoConfiguration.class)
public @interface EnableDiatomGateway {
}
