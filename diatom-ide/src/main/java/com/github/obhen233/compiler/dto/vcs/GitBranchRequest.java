package com.github.obhen233.compiler.dto.vcs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Git merge/checkout request / Git合并/检出请求
 */
@Schema(description = "Git merge/checkout request / Git合并/检出请求")
public record GitBranchRequest(
    @Schema(description = "Branch name / 分支名称", example = "feature-branch", required = true) String branch,
    @Schema(description = "Create new branch / 创建新分支", example = "false") Boolean create
) {}
