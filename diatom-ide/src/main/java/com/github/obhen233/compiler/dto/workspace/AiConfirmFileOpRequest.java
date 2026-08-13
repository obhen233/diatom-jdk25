package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI confirmation file operation request / AI确认文件操作请求
 */
@Schema(description = "AI confirmation file operation request / AI确认文件操作请求")
public record AiConfirmFileOpRequest(
    @Schema(description = "Project name / 项目名称", example = "myproject", required = true) String projectName,
    @Schema(description = "File path / 文件路径", example = "src/main/java/com/example/Main.java", required = true) String path,
    @Schema(description = "File content / 文件内容", example = "public class Main {}") String content
) {}
