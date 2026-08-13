package com.github.obhen233.compiler.dto.vcs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Git commit request / Git提交请求
 */
@Schema(description = "Git commit request / Git提交请求")
public record GitCommitRequest(
    @Schema(description = "Commit message / 提交消息", example = "Initial commit", required = true) String message,
    @Schema(description = "Stage all changes before commit / 提交前暂存所有更改", example = "false") Boolean addAll
) {}
