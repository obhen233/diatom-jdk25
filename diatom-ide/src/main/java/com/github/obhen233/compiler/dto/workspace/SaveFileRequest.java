package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Save file request / 保存文件请求
 */
@Schema(description = "Save file request / 保存文件请求")
public record SaveFileRequest(
    @Schema(description = "File path relative to project / 相对于项目的文件路径", example = "src/main/java/com/example/Main.java", required = true) String path,
    @Schema(description = "File content / 文件内容", example = "public class Main {}") String content
) {}
