package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI session action request / AI会话操作请求
 *
 * <p>合并原 {@code AiResetRequest}（仅 sessionId）与 {@code AiConfirmDecisionRequest}
 * （sessionId + decision），用于 AI 会话重置与确认决策两个端点。</p>
 */
@Schema(description = "AI session action request / AI会话操作请求")
public record AiActionRequest(
    @Schema(description = "Session ID / 会话ID", example = "abc123", required = true) String sessionId,
    @Schema(description = "Decision: y (yes), n (no), a (auto approve this session) / 决策：y(是), n(否), a(本次自动批准)", example = "y") String decision
) {}
