package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.core.ExecuteCommandRequest;
import com.github.obhen233.compiler.service.CoreCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for executing core commands in IDE mode.
 * Provides endpoints for core commands like help, config, tasks, etc.
 */
@CrossOrigin
@RestController
@RequestMapping("/core")
@Tag(name = "Core Commands / 核心命令", description = "IDE core commands execution / IDE核心命令执行")
public class CoreCommandController {

    @Autowired
    private CoreCommandService coreCommandService;

    /**
     * Execute a core command line.
     *
     * POST /core/execute
     * Body: { "command": "config set streaming.enabled true" }
     * Response: { "success": true, "output": "...", "error": null }
     */
    @PostMapping("/execute")
    @Operation(summary = "Execute core command / 执行核心命令", description = "Executes an IDE core command (help, config, tasks, snapshot, context, history, deploy, mcp, skills, auth) / 执行IDE核心命令（help, config, tasks, snapshot, context, history, deploy, mcp, skills, auth）")
    public ApiResponse<String> executeCommand(@RequestBody ExecuteCommandRequest body) {
        String command = body.command();

        if (command == null || command.trim().isEmpty()) {
            return ApiResponse.fail("Command is required");
        }

        try {
            String output = coreCommandService.executeCommand(command);
            if (output == null) {
                return ApiResponse.fail("Command not recognized");
            } else {
                return ApiResponse.ok(output);
            }
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * Get core help text.
     *
     * GET /core/help?lang=zh
     */
    @GetMapping("/help")
    @Operation(summary = "Get core commands help / 获取核心命令帮助", description = "Returns help text for all core commands / 返回所有核心命令的帮助文本")
    public ApiResponse<String> getCoreHelp(@RequestParam(defaultValue = "zh") String lang) {
        try {
            String help = coreCommandService.getCoreHelp(lang);
            return ApiResponse.ok(help);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
