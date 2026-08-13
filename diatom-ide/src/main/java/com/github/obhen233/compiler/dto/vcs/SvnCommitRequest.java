package com.github.obhen233.compiler.dto.vcs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * SVN commit request / SVN提交请求
 */
@Schema(description = "SVN commit request / SVN提交请求")
public record SvnCommitRequest(
    @Schema(description = "Commit message / 提交消息", example = "Updated files", required = true) String message
) {}
