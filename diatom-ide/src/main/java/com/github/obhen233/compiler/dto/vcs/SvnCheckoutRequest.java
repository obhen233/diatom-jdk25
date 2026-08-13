package com.github.obhen233.compiler.dto.vcs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * SVN checkout request / SVN检出请求
 */
@Schema(description = "SVN checkout request / SVN检出请求")
public record SvnCheckoutRequest(
    @Schema(description = "SVN repository URL / SVN仓库URL", example = "https://svn.example.com/repo", required = true) String url,
    @Schema(description = "Username / 用户名", example = "user") String username,
    @Schema(description = "Password / 密码", example = "password") String password
) {}
