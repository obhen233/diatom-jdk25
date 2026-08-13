package com.github.obhen233.compiler.dto.vcs;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Git file paths request / Git文件路径请求
 */
@Schema(description = "Git file paths request / Git文件路径请求")
public record GitPathsRequest(
    @Schema(description = "File paths to operate on / 要操作的文件路径", example = "[\"src/Main.java\"]") List<String> paths
) {}
