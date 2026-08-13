package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.editor.SyncEditorStateRequest;
import com.github.obhen233.compiler.mcp.EditorContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for frontend Monaco Editor state synchronization.
 * Frontend calls this periodically to share current editor context with the AI.
 */
@RestController
@RequestMapping("/ide/editor")
@Tag(name = "Editor / 编辑器", description = "Monaco Editor state synchronization / Monaco编辑器状态同步")
public class EditorController {

    @Autowired
    private EditorContextService editorContext;

    /**
     * Sync editor state from frontend.
     * Called ~1/sec by the frontend to share current file, cursor, selection.
     */
    @PostMapping("/state")
    @Operation(summary = "Sync editor state / 同步编辑器状态", description = "Synchronizes editor state from frontend (file, cursor, selection) to backend for AI context / 将编辑器状态（文件，光标、选区）从前端同步到后端供AI使用")
    public ApiResponse<Void> syncEditorState(@RequestBody SyncEditorStateRequest body) {
        EditorContextService.EditorState state = new EditorContextService.EditorState();
        state.filePath = body.filePath();
        state.projectName = body.projectName();
        state.language = body.language();
        state.cursorLine = body.cursorLine() != null ? body.cursorLine() : 1;
        state.cursorColumn = body.cursorColumn() != null ? body.cursorColumn() : 1;
        state.fileContent = body.fileContent();
        state.selectedText = body.selectedText();
        state.selectionStartLine = body.selectionStartLine() != null ? body.selectionStartLine() : 0;
        state.selectionEndLine = body.selectionEndLine() != null ? body.selectionEndLine() : 0;
        state.enclosingMethod = body.enclosingMethod();
        state.openTabs = body.openTabs();

        editorContext.updateFromFrontend(state);

        return ApiResponse.ok();
    }

    /**
     * Get current editor state (for debugging).
     */
    @GetMapping("/state")
    @Operation(summary = "Get editor state / 获取编辑器状态", description = "Returns the current editor state for debugging purposes / 返回当前编辑器状态用于调试")
    public EditorContextService.EditorState getEditorState() {
        return editorContext.getCurrentState();
    }

    /**
     * Notify frontend to refresh a file in the editor.
     * Called by EditorMcpServer after AI modifies files.
     */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh file in editor / 刷新编辑器中的文件", description = "Notifies frontend to refresh a file after AI modification / 通知前端在AI修改后刷新文件")
    public ApiResponse<String> refreshFile(@RequestBody SyncEditorStateRequest body) {
        // This is a placeholder - actual notification to frontend
        // would use SSE or WebSocket. The frontend can poll or use SSE.
        return ApiResponse.ok(body.filePath());
    }
}
