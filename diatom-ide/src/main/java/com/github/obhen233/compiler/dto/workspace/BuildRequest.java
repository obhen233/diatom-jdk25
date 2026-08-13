package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Build project request / 构建项目请求
 */
@Schema(description = "Build project request / 构建项目请求")
public record BuildRequest(
    @Schema(description = "Project name / 项目名称", example = "myproject", required = true) String projectName,
    @Schema(description = "Build tool: maven, gradle / 构建工具", example = "maven") String buildTool,
    @Schema(description = "Build goal / 构建目标", example = "compile") String goal
) {}
