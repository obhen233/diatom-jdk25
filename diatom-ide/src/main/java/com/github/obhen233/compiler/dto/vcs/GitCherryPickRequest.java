package com.github.obhen233.compiler.dto.vcs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Git cherry-pick request / Git摘取提交请求
 */
@Schema(description = "Git cherry-pick request / Git摘取提交请求")
public record GitCherryPickRequest(
    @Schema(description = "Commit ID to cherry-pick / 要摘取的提交ID", example = "abc123def456", required = true) String commitId
) {}
