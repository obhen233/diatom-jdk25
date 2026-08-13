package com.github.obhen233.compiler.dto.decompile;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JAR info request / JAR信息请求
 */
@Schema(description = "JAR info request / JAR信息请求")
public record JarInfoRequest(
    @Schema(description = "Project name / 项目名称", example = "myproject", required = true) String projectName,
    @Schema(description = "JAR file name / JAR文件名", example = "lib.jar", required = true) String jarName
) {}
