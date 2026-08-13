package com.github.obhen233.compiler.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Wait for kick request / 等待踢人协商结果请求
 */
@Schema(description = "Wait for kick request / 等待踢人协商结果请求")
public record WaitForKickRequest(
    @NotBlank(message = "请求ID不能为空")
    @Schema(description = "Request ID / 请求ID", example = "req-123", required = true) String requestId
) {}
