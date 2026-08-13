package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Rename project request / 重命名项目请求
 */
@Schema(description = "Rename project request / 重命名项目请求")
public record RenameProjectRequest(
    @Schema(description = "New project name / 新项目名称", example = "newproject", required = true) String newName
) {}
