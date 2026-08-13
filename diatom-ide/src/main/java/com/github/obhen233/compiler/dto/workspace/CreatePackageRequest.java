package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Create package request / 创建包请求
 */
@Schema(description = "Create package request / 创建包请求")
public record CreatePackageRequest(
    @Schema(description = "Parent path / 父路径", example = "src/main/java") String parentPath,
    @Schema(description = "Package name / 包名", example = "com.example.newpackage", required = true) String packageName
) {}
