package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Rename file or directory request / 重命名文件或目录请求
 */
@Schema(description = "Rename file or directory request / 重命名文件或目录请求")
public record RenameFileRequest(
    @Schema(description = "File or directory path / 文件或目录路径", example = "src/main/java/com/example/Main.java", required = true) String path,
    @Schema(description = "New name / 新名称", example = "NewMain.java", required = true) String newName
) {}
