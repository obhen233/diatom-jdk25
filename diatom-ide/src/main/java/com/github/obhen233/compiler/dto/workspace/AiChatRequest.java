package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI chat request / AI聊天请求
 */
@Schema(description = "AI chat request / AI聊天请求")
public record AiChatRequest(
    @Schema(description = "Prompt / 提示词", example = "Explain this code", required = true) String prompt,
    @Schema(description = "Project name / 项目名称", example = "myproject") String projectName,
    @Schema(description = "Active file path / 当前文件路径", example = "src/main/java/com/example/Main.java") String activeFile,
    @Schema(description = "Session ID for continuing conversation / 会话ID，用于继续对话", example = "abc123") String sessionId
) {}
