package com.github.obhen233.compiler.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.compiler.mcp.EditorContextService;
import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP server providing editor context to the AI.
 *
 * Tools:
 *   get_active_file     - Current file path, language, project
 *   get_cursor_context  - Lines before/after cursor (token-saving)
 *   get_open_tabs       - All open editor tabs
 *   get_selected_text   - Currently selected text range
 *   editor_notify       - Notify editor to refresh a file
 */
@Component
public class EditorMcpServer implements McpServer {

    private static final Logger logger = LoggerFactory.getLogger(EditorMcpServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private EditorContextService editorContext;

    @Override
    public String getName() {
        return "editor";
    }

    @Override
    public String getDescription() {
        return "Provides editor context: active file, cursor position, open tabs. Use this to understand what the user is editing.";
    }

    @Override
    public Map<String, Tool> listTools() {
        Map<String, Tool> tools = new LinkedHashMap<>();

        Tool activeFile = new Tool(
                "get_active_file",
                "Get the currently active file path, language and project name. Call this first to understand what the user is working on.",
                "{}"
        );
        activeFile.setReadOnly(true);
        tools.put("get_active_file", activeFile);

        Tool cursorContext = new Tool(
                "get_cursor_context",
                "[RECOMMENDED] Get lines before and after the cursor position. Use this instead of read_file to save tokens. Parameters: beforeLines (int, default 20), afterLines (int, default 20). Returns the surrounding code context.",
                "{\"type\":\"object\",\"properties\":{\"beforeLines\":{\"type\":\"integer\",\"default\":20},\"afterLines\":{\"type\":\"integer\",\"default\":20}}}"
        );
        cursorContext.setReadOnly(true);
        tools.put("get_cursor_context", cursorContext);

        Tool openTabs = new Tool(
                "get_open_tabs",
                "Get list of all currently open editor tabs with their file paths.",
                "{}"
        );
        openTabs.setReadOnly(true);
        tools.put("get_open_tabs", openTabs);

        Tool selectedText = new Tool(
                "get_selected_text",
                "Get the currently selected text in the editor and its line range.",
                "{}"
        );
        selectedText.setReadOnly(true);
        tools.put("get_selected_text", selectedText);

        Tool editorNotify = new Tool(
                "editor_notify",
                "Notify the editor. type=refresh: reload a file in the editor (use after modifying code). type=message: show a message. Parameters: type (required), filePath (for refresh), text (for message).",
                "{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\",\"enum\":[\"refresh\",\"message\"]},\"filePath\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}},\"required\":[\"type\"]}"
        );
        editorNotify.setReadOnly(true);
        tools.put("editor_notify", editorNotify);

        return tools;
    }

    @Override
    public String callTool(String name, String args) {
        try {
            switch (name) {
                case "get_active_file":
                    return callGetActiveFile();
                case "get_cursor_context":
                    return callGetCursorContext(args);
                case "get_open_tabs":
                    return callGetOpenTabs();
                case "get_selected_text":
                    return callGetSelectedText();
                case "editor_notify":
                    return callEditorNotify(args);
                default:
                    return "{\"error\":\"Unknown tool: " + name + "\"}";
            }
        } catch (Exception e) {
            logger.error("Error calling tool {}", name, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String callGetActiveFile() {
        EditorContextService.EditorState state = editorContext.getCurrentState();
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("filePath", state.filePath);
            result.put("language", state.language);
            result.put("projectName", state.projectName);
            result.put("cursorLine", state.cursorLine);
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String callGetCursorContext(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        int beforeLines = params.containsKey("beforeLines") ? ((Number) params.get("beforeLines")).intValue() : 20;
        int afterLines = params.containsKey("afterLines") ? ((Number) params.get("afterLines")).intValue() : 20;

        EditorContextService.CursorContext ctx = editorContext.getCursorContext(beforeLines, afterLines);

        Map<String, Object> result = new HashMap<>();
        result.put("filePath", ctx.filePath);
        result.put("projectName", ctx.projectName);
        result.put("cursorLine", ctx.cursorLine);
        result.put("enclosingMethod", ctx.enclosingMethod);
        result.put("beforeCursor", ctx.beforeCursor);
        result.put("afterCursor", ctx.afterCursor);
        return JSON.writeValueAsString(result);
    }

    private String callGetOpenTabs() throws Exception {
        EditorContextService.EditorState state = editorContext.getCurrentState();
        Map<String, Object> result = new HashMap<>();
        result.put("tabs", state.openTabs);
        return JSON.writeValueAsString(result);
    }

    private String callGetSelectedText() throws Exception {
        EditorContextService.SelectedContext ctx = editorContext.getSelectedContext();
        Map<String, Object> result = new HashMap<>();
        result.put("filePath", ctx.filePath);
        result.put("projectName", ctx.projectName);
        result.put("selectedText", ctx.selectedText);
        result.put("startLine", ctx.selectionStartLine);
        result.put("endLine", ctx.selectionEndLine);
        return JSON.writeValueAsString(result);
    }

    private String callEditorNotify(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        String type = toString(params.get("type"));
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("type", type);
        logger.info("Editor notify: type={}, filePath={}", type, params.get("filePath"));
        return JSON.writeValueAsString(result);
    }

    private String toString(Object o) {
        return o == null ? "" : o.toString();
    }
}
