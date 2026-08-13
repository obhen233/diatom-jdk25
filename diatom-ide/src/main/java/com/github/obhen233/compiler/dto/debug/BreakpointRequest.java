package com.github.obhen233.compiler.dto.debug;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Manage breakpoint request / 断点管理请求
 */
@Schema(description = "Manage breakpoint request / 断点管理请求")
public record BreakpointRequest(
    @NotBlank(message = "action 不能为空")
    @Schema(description = "Action: set or remove / 操作：set或remove", example = "set", required = true) String action,
    @Schema(description = "Full class name / 完整类名", example = "com.example.Main") String className,
    @Schema(description = "File name / 文件名", example = "Main.java") String fileName,
    @Schema(description = "File path / 文件路径", example = "/src/main/java/com/example/Main.java") String filePath,
    @Schema(description = "Breakpoint ID / 断点ID", example = "bp-1") String id,
    @Schema(description = "Line number / 行号", example = "42") String lineNumber
) {}
