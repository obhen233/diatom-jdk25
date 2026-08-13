package com.github.obhen233.compiler.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Login request / 登录请求
 */
@Schema(description = "Login request / 登录请求")
public record LoginRequest(
    @NotBlank(message = "用户名不能为空")
    @Schema(description = "Username / 用户名", example = "admin", required = true) String username,
    @Schema(description = "Password / 密码", example = "password", required = true) String password
) {}
