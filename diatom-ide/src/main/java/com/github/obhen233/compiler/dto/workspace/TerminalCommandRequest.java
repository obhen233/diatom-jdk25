package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Terminal command request / 终端命令请求
 */
@Schema(description = "Terminal command request / 终端命令请求")
public record TerminalCommandRequest(
    @Schema(description = "Command to execute / 要执行的命令", example = "ls -la", required = true) String command,
    @Schema(description = "Project name / 项目名称", example = "myproject") String projectName,
    @Schema(description = "Current working directory / 当前工作目录", example = "/workspace/myproject") String cwd
) {}
