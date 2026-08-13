package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.vcs.*;
import com.github.obhen233.compiler.service.VcsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Version Control REST API (Git + SVN) / 版本控制 REST API (Git + SVN)
 */
@CrossOrigin
@RestController
@RequestMapping("/workspace/projects/{name}/vcs")
@Tag(name = "Version Control / 版本控制", description = "Git and SVN version control operations / Git和SVN版本控制操作")
public class VcsController {

    @Autowired
    private VcsService vcsService;

    // ==================== Git ====================

    @GetMapping("/git/status")
    @Operation(summary = "Git status / Git状态", description = "Returns the Git status of the project / 返回项目的Git状态")
    public ApiResponse<Map<String, Object>> gitStatus(@PathVariable String name) {
        try {
            return ApiResponse.ok(vcsService.gitStatus(name));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/init")
    @Operation(summary = "Git init / Git初始化", description = "Initialize a Git repository in the project / 在项目中初始化Git仓库")
    public ApiResponse<Map<String, Object>> gitInit(@PathVariable String name) {
        try {
            return ApiResponse.ok(vcsService.gitInit(name));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/clone")
    @Operation(summary = "Git clone / Git克隆", description = "Clone a remote Git repository / 克隆远程Git仓库")
    public ApiResponse<Map<String, Object>> gitClone(@PathVariable String name, @RequestBody GitCloneRequest body) {
        try {
            return ApiResponse.ok(vcsService.gitClone(name, body.url(),
                    body.username() != null ? body.username() : "",
                    body.password() != null ? body.password() : ""));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/add")
    @Operation(summary = "Git add / Git添加", description = "Stage files for commit / 暂存文件以提交")
    public ApiResponse<Map<String, Object>> gitAdd(@PathVariable String name, @RequestBody(required = false) GitPathsRequest body) {
        try {
            return ApiResponse.ok(vcsService.gitAdd(name, body != null ? body.paths() : null));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/unstage")
    @Operation(summary = "Git unstage / Git取消暂存", description = "Unstage files / 取消暂存文件")
    public ApiResponse<Map<String, Object>> gitUnstage(@PathVariable String name, @RequestBody(required = false) GitPathsRequest body) {
        try {
            return ApiResponse.ok(vcsService.gitUnstage(name, body != null ? body.paths() : null));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/discard")
    @Operation(summary = "Git discard / Git放弃更改", description = "Discard changes in working directory / 放弃工作目录中的更改")
    public ApiResponse<Map<String, Object>> gitDiscard(@PathVariable String name, @RequestBody(required = false) GitPathsRequest body) {
        try {
            return ApiResponse.ok(vcsService.gitDiscard(name, body != null ? body.paths() : null));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/git/file-diff")
    @Operation(summary = "Git file diff / Git文件差异", description = "Get diff for a specific file. Returns diff, oldContent (HEAD), newContent (working tree) / 获取特定文件的差异，返回 diff、oldContent（HEAD版本）、newContent（当前工作区版本）")
    public ApiResponse<Map<String, Object>> gitFileDiff(@PathVariable String name, @RequestParam String file) {
        try {
            return ApiResponse.ok(vcsService.gitFileDiff(name, file));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/commit")
    @Operation(summary = "Git commit / Git提交", description = "Commit staged changes / 提交暂存的更改")
    public ApiResponse<Map<String, Object>> gitCommit(@PathVariable String name, @RequestBody GitCommitRequest body) {
        try {
            return ApiResponse.ok(vcsService.gitCommit(name, body.message(),
                    body.addAll() != null ? body.addAll() : false));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/push")
    @Operation(summary = "Git push / Git推送", description = "Push commits to remote repository / 将提交推送到远程仓库")
    public ApiResponse<Map<String, Object>> gitPush(@PathVariable String name, @RequestBody(required = false) GitPushPullRequest body) {
        try {
            boolean force = body != null && "true".equals(body.force());
            String username = body != null && body.username() != null ? body.username() : "";
            String password = body != null && body.password() != null ? body.password() : "";
            return ApiResponse.ok(vcsService.gitPush(name, username, password, force));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/pull")
    @Operation(summary = "Git pull / Git拉取", description = "Pull changes from remote repository / 从远程仓库拉取更改")
    public ApiResponse<Map<String, Object>> gitPull(@PathVariable String name, @RequestBody(required = false) GitPushPullRequest body) {
        try {
            String username = body != null && body.username() != null ? body.username() : "";
            String password = body != null && body.password() != null ? body.password() : "";
            return ApiResponse.ok(vcsService.gitPull(name, username, password));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/merge")
    @Operation(summary = "Git merge / Git合并", description = "Merge a branch into current branch / 将分支合并到当前分支")
    public ApiResponse<Map<String, Object>> gitMerge(@PathVariable String name, @RequestBody GitBranchRequest body) {
        try {
            return ApiResponse.ok(vcsService.gitMerge(name, body.branch()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/checkout")
    @Operation(summary = "Git checkout / Git检出", description = "Checkout a branch or file / 检出分支或文件")
    public ApiResponse<Map<String, Object>> gitCheckout(@PathVariable String name, @RequestBody GitBranchRequest body) {
        try {
            return ApiResponse.ok(vcsService.gitCheckout(name, body.branch(),
                    body.create() != null ? body.create() : false));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/cherry-pick")
    @Operation(summary = "Git cherry-pick / Git摘取", description = "Cherry-pick a commit onto current branch / 将提交摘取到当前分支")
    public ApiResponse<Map<String, Object>> gitCherryPick(@PathVariable String name, @RequestBody GitCherryPickRequest body) {
        try {
            return ApiResponse.ok(vcsService.gitCherryPick(name, body.commitId()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/git/log")
    @Operation(summary = "Git log / Git日志", description = "Get commit log / 获取提交日志")
    public ApiResponse<Map<String, Object>> gitLog(@PathVariable String name,
                                      @RequestParam(defaultValue = "50") int max,
                                      @RequestParam(defaultValue = "0") int skip) {
        try {
            return ApiResponse.ok(vcsService.gitLog(name, max, skip));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/git/diff")
    @Operation(summary = "Git diff / Git差异", description = "Get diff between commits / 获取提交之间的差异")
    public ApiResponse<Map<String, Object>> gitDiff(@PathVariable String name,
                                       @RequestParam(required = false) String oldRef,
                                       @RequestParam(required = false) String newRef) {
        try {
            return ApiResponse.ok(vcsService.gitDiff(name, oldRef, newRef));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/git/show")
    @Operation(summary = "Git show / Git显示", description = "Show file at specific commit / 显示特定提交的文件")
    public ApiResponse<Map<String, Object>> gitShowFile(@PathVariable String name,
                                           @RequestParam String commitId,
                                           @RequestParam String path) {
        try {
            return ApiResponse.ok(vcsService.gitShowFile(name, commitId, path));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/git/remote")
    @Operation(summary = "Git set remote / Git设置远程", description = "Set remote URL for repository / 设置仓库的远程URL")
    public ApiResponse<Map<String, Object>> gitSetRemote(@PathVariable String name, @RequestBody GitRemoteRequest body) {
        try {
            return ApiResponse.ok(vcsService.gitSetRemote(name, body.url()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ==================== SVN ====================

    @GetMapping("/svn/status")
    @Operation(summary = "SVN status / SVN状态", description = "Returns the SVN status of the project / 返回项目的SVN状态")
    public ApiResponse<Map<String, Object>> svnStatus(@PathVariable String name) {
        try {
            return ApiResponse.ok(vcsService.svnStatus(name));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/svn/file-diff")
    @Operation(summary = "SVN file diff / SVN文件差异", description = "Get diff for a specific SVN file. Returns diff, oldContent (BASE), newContent (working copy) / 获取特定SVN文件的差异，返回 diff、oldContent（BASE版本）、newContent（当前工作副本）")
    public ApiResponse<Map<String, Object>> svnFileDiff(@PathVariable String name, @RequestParam String file) {
        try {
            return ApiResponse.ok(vcsService.svnFileDiff(name, file));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/svn/add")
    @Operation(summary = "SVN add / SVN添加", description = "Add files to SVN / 添加文件到SVN")
    public ApiResponse<Map<String, Object>> svnAdd(@PathVariable String name, @RequestBody(required = false) SvnPathsRequest body) {
        try {
            return ApiResponse.ok(vcsService.svnAdd(name, body != null ? body.paths() : null));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/svn/checkout")
    @Operation(summary = "SVN checkout / SVN检出", description = "Checkout a remote SVN repository / 检出远程SVN仓库")
    public ApiResponse<Map<String, Object>> svnCheckout(@PathVariable String name, @RequestBody SvnCheckoutRequest body) {
        try {
            return ApiResponse.ok(vcsService.svnCheckout(name, body.url(),
                    body.username() != null ? body.username() : "",
                    body.password() != null ? body.password() : ""));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/svn/commit")
    @Operation(summary = "SVN commit / SVN提交", description = "Commit changes to SVN / 提交更改到SVN")
    public ApiResponse<Map<String, Object>> svnCommit(@PathVariable String name, @RequestBody SvnCommitRequest body) {
        try {
            return ApiResponse.ok(vcsService.svnCommit(name, body.message()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/svn/update")
    @Operation(summary = "SVN update / SVN更新", description = "Update SVN working copy / 更新SVN工作副本")
    public ApiResponse<Map<String, Object>> svnUpdate(@PathVariable String name) {
        try {
            return ApiResponse.ok(vcsService.svnUpdate(name));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/svn/log")
    @Operation(summary = "SVN log / SVN日志", description = "Get SVN commit log / 获取SVN提交日志")
    public ApiResponse<Map<String, Object>> svnLog(@PathVariable String name,
                                      @RequestParam(defaultValue = "50") int limit) {
        try {
            return ApiResponse.ok(vcsService.svnLog(name, limit));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/svn/diff")
    @Operation(summary = "SVN diff / SVN差异", description = "Get SVN diff / 获取SVN差异")
    public ApiResponse<Map<String, Object>> svnDiff(@PathVariable String name) {
        try {
            return ApiResponse.ok(vcsService.svnDiff(name));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
