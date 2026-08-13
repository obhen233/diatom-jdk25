package com.github.obhen233.compiler.dto.decompile;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JAR decompile request / JAR反编译请求
 */
@Schema(description = "JAR decompile request / JAR反编译请求")
public record JarRequest(
    @Schema(description = "JAR file path / JAR文件路径", example = "/path/to/lib.jar", required = true) String jarPath
) {}
