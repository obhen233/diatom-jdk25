package com.github.obhen233.compiler.dto.search;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Code navigation request / 代码导航请求
 */
@Schema(description = "Code navigation request / 代码导航请求")
public record NavigationRequest(
    @Schema(description = "File path / 文件路径", example = "src/main/java/com/example/Main.java", required = true) String filePath,
    @Schema(description = "Line number / 行号", example = "10", required = true) Integer line,
    @Schema(description = "Column number / 列号", example = "1", required = true) Integer column
) {}
