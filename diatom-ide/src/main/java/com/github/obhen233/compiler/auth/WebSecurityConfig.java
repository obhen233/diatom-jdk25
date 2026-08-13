package com.github.obhen233.compiler.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
public class WebSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // 允许 iframe（IDE Monaco Editor 需要）
        http.headers(headers -> headers.frameOptions(frame -> frame.disable()));

        // 禁用 CSRF：认证由自定义 AuthFilter 处理，不依赖 Spring Security CSRF 机制
        // CSRF token 主要用于防止浏览器自动提交的表单攻击，而本 IDE 使用 token-based 认证
        http.csrf(csrf -> csrf.disable());

        // 所有请求放行（认证由自定义 AuthFilter 处理）
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());

        return http.build();
    }
}
