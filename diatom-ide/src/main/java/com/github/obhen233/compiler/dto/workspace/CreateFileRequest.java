package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Create file/directory request / 创建文件/目录请求
 */
@Schema(description = "Create file/directory request / 创建文件/目录请求")
public record CreateFileRequest(
    @Schema(description = "Parent path relative to project / 相对于项目的父路径", example = "src/main/java/com/example") String parentPath,
    @Schema(description = "File or directory name / 文件或目录名称", example = "Utils.java", required = true) String fileName
) {}
