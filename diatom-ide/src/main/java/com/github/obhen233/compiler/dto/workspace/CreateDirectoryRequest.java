package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Create directory request / 创建目录请求
 */
@Schema(description = "Create directory request / 创建目录请求")
public record CreateDirectoryRequest(
    @Schema(description = "Parent path / 父路径", example = "src/main/java") String parentPath,
    @Schema(description = "Directory name / 目录名称", example = "newdir", required = true) String dirName
) {}
