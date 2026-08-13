package com.github.obhen233.compiler.dto.vcs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Git push/pull request / Git推送/拉取请求
 */
@Schema(description = "Git push/pull request / Git推送/拉取请求")
public record GitPushPullRequest(
    @Schema(description = "Force push / 强制推送", example = "false") String force,
    @Schema(description = "Username / 用户名", example = "user") String username,
    @Schema(description = "Password or token / 密码或令牌", example = "token") String password
) {}
