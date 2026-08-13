package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.search.NavigationRequest;
import com.github.obhen233.compiler.service.ProjectIndexService;
import com.github.obhen233.compiler.i18n.I18n;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目搜索 API / Search API
 */
@CrossOrigin
@RestController
@RequestMapping("/workspace")
@Tag(name = "Search / 搜索", description = "Project search and code navigation / 项目搜索和代码导航")
public class SearchController {

    @Autowired
    private ProjectIndexService indexService;

    /**
     * 搜索项目中的文件和符号
     * GET /workspace/projects/{name}/search?q=keyword&type=all&max=30
     */
    @GetMapping("/projects/{name}/search")
    @Operation(summary = "Search in project / 项目内搜索", description = "Searches files and symbols within a project / 在项目内搜索文件和符号")
    public ApiResponse<List<ProjectIndexService.SearchResult>> search(@PathVariable String name,
                                      @RequestParam("q") String query,
                                      @RequestParam(value = "type", defaultValue = "all") String type,
                                      @RequestParam(value = "max", defaultValue = "30") int max,
                                      @RequestParam(value = "ext", defaultValue = "") String ext) {
        try {
            List<ProjectIndexService.SearchResult> results = indexService.search(name, query, type, max, ext);
            return ApiResponse.ok(String.valueOf(results.size()), results);
        } catch (Exception e) {
            return ApiResponse.fail(I18n.get("search.failed", e.getMessage()));
        }
    }

    /**
     * 手动重建项目索引
     * POST /workspace/projects/{name}/reindex
     */
    @PostMapping("/projects/{name}/reindex")
    @Operation(summary = "Rebuild project index / 重建项目索引", description = "Manually rebuild the search index for a project / 手动重建项目的搜索索引")
    public ApiResponse<Void> reindex(@PathVariable String name) {
        try {
            indexService.rebuildIndex(name);
            return ApiResponse.ok();
        } catch (Exception e) {
            return ApiResponse.fail(I18n.get("search.reindexFailed", e.getMessage()));
        }
    }

    // ==================== 代码导航 API / Code Navigation API ====================

    /**
     * Go to Definition / 跳转到定义
     * POST /workspace/projects/{name}/navigate/definition
     */
    @PostMapping("/projects/{name}/navigate/definition")
    @Operation(summary = "Go to definition / 跳转到定义", description = "Finds the definition of a symbol at the given location / 查找给定位置的符号定义")
    public ApiResponse<List<ProjectIndexService.NavigationResult>> goToDefinition(@PathVariable String name, @RequestBody NavigationRequest body) {
        try {
            List<ProjectIndexService.NavigationResult> locations = indexService.findDefinition(name, body.filePath(), body.line(), body.column());
            return ApiResponse.ok(locations);
        } catch (Exception e) {
            return ApiResponse.fail(I18n.get("search.goToDefinitionFailed", e.getMessage()));
        }
    }

    /**
     * Go to Implementation / 跳转到实现
     * POST /workspace/projects/{name}/navigate/implementations
     */
    @PostMapping("/projects/{name}/navigate/implementations")
    @Operation(summary = "Go to implementation / 跳转到实现", description = "Finds all implementations of a method or interface / 查找方法或接口的所有实现")
    public ApiResponse<List<ProjectIndexService.NavigationResult>> goToImplementation(@PathVariable String name, @RequestBody NavigationRequest body) {
        try {
            List<ProjectIndexService.NavigationResult> locations = indexService.findImplementations(name, body.filePath(), body.line(), body.column());
            return ApiResponse.ok(locations);
        } catch (Exception e) {
            return ApiResponse.fail(I18n.get("search.goToImplementationFailed", e.getMessage()));
        }
    }

    /**
     * Find All Reference / 查找所有引用
     * POST /workspace/projects/{name}/navigate/references
     */
    @PostMapping("/projects/{name}/navigate/references")
    @Operation(summary = "Find references / 查找引用", description = "Finds all references to a symbol / 查找符号的所有引用")
    public ApiResponse<List<ProjectIndexService.NavigationResult>> findReferences(@PathVariable String name, @RequestBody NavigationRequest body) {
        try {
            List<ProjectIndexService.NavigationResult> locations = indexService.findReferences(name, body.filePath(), body.line(), body.column());
            return ApiResponse.ok(locations);
        } catch (Exception e) {
            return ApiResponse.fail(I18n.get("search.findReferencesFailed", e.getMessage()));
        }
    }
}
