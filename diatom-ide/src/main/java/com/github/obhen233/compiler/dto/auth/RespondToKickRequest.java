package com.github.obhen233.compiler.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Respond to kick request / 响应踢人请求
 */
@Schema(description = "Respond to kick request / 响应踢人请求")
public record RespondToKickRequest(
    @NotBlank(message = "请求ID不能为空")
    @Schema(description = "Request ID / 请求ID", example = "req-123", required = true) String requestId,
    @NotNull(message = "approve 不能为空")
    @Schema(description = "Approve kick request / 批准踢人请求", example = "true", required = true) Boolean approve
) {}
