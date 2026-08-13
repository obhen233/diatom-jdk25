package com.github.obhen233.compiler.dto.core;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Execute core command request / 执行核心命令请求
 */
@Schema(description = "Execute core command request / 执行核心命令请求")
public record ExecuteCommandRequest(
    @Schema(description = "Command to execute / 要执行的命令", example = "help", required = true) String command
) {}
