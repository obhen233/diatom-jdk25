package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Restore project request / 恢复项目请求
 */
@Schema(description = "Restore project request / 恢复项目请求")
public record RestoreProjectRequest(
    @Schema(description = "Directory name / 目录名称", example = "myproject", required = true) String dirName
) {}
