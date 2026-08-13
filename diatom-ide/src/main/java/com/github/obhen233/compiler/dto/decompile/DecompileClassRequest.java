package com.github.obhen233.compiler.dto.decompile;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Decompile class request / 反编译类请求
 */
@Schema(description = "Decompile class request / 反编译类请求")
public record DecompileClassRequest(
    @Schema(description = "JAR file path / JAR文件路径", example = "/path/to/lib.jar", required = true) String jarPath,
    @Schema(description = "Full class name / 完整类名", example = "com.example.Main", required = true) String className
) {}
