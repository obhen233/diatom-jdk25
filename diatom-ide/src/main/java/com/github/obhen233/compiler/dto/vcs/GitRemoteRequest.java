package com.github.obhen233.compiler.dto.vcs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Git remote/set remote request / Git远程/设置远程请求
 */
@Schema(description = "Git remote/set remote request / Git远程/设置远程请求")
public record GitRemoteRequest(
    @Schema(description = "Remote URL / 远程URL", example = "https://github.com/user/repo.git", required = true) String url
) {}
