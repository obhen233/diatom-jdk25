package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Stop run request / 停止运行请求
 */
@Schema(description = "Stop run request / 停止运行请求")
public record StopRunRequest(
    @Schema(description = "Project name / 项目名称", example = "myproject", required = true) String projectName
) {}
