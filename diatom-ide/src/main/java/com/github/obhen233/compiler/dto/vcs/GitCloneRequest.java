package com.github.obhen233.compiler.dto.vcs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Git clone request / Git克隆请求
 */
@Schema(description = "Git clone request / Git克隆请求")
public record GitCloneRequest(
    @Schema(description = "Repository URL / 仓库URL", example = "https://github.com/user/repo.git", required = true) String url,
    @Schema(description = "Username / 用户名", example = "user") String username,
    @Schema(description = "Password / 密码", example = "password") String password
) {}
