package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Run project request / 运行项目请求
 */
@Schema(description = "Run project request / 运行项目请求")
public record RunRequest(
    @Schema(description = "Project name / 项目名称", example = "myproject", required = true) String projectName,
    @Schema(description = "Main class name / 主类名称", example = "Main") String mainClass,
    @Schema(description = "JVM arguments / JVM参数", example = "-Xmx512m") String jvmArgs,
    @Schema(description = "Program arguments / 程序参数", example = "--server.port=8080") String programArgs
) {}
