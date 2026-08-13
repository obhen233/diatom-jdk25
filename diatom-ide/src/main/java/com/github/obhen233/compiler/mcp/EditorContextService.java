package com.github.obhen233.compiler.mcp;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the current editor state synced from the frontend Monaco editor.
 * Consumed by EditorMcpServer to provide AI with cursor context, open tabs, etc.
 */
@Service
public class EditorContextService {

    private final ThreadLocal<EditorState> currentState = ThreadLocal.withInitial(EditorState::new);

    /**
     * Update editor state from frontend sync.
     */
    public void updateFromFrontend(EditorState state) {
        if (state != null) {
            currentState.set(state);
        }
    }

    /**
     * Get current editor state for the current thread.
     */
    public EditorState getCurrentState() {
        return currentState.get();
    }

    /**
     * Get cursor context: lines before and after cursor.
     */
    public CursorContext getCursorContext(int beforeLines, int afterLines) {
        EditorState state = currentState.get();
        CursorContext ctx = new CursorContext();
        ctx.filePath = state.filePath;
        ctx.projectName = state.projectName;
        ctx.cursorLine = state.cursorLine;
        ctx.enclosingMethod = state.enclosingMethod;

        if (state.fileContent != null && !state.fileContent.isEmpty()) {
            String[] lines = state.fileContent.split("\n", -1);
            int cursor = state.cursorLine - 1; // 0-indexed

            int beforeStart = Math.max(0, cursor - beforeLines);
            int afterEnd = Math.min(lines.length, cursor + afterLines);

            StringBuilder before = new StringBuilder();
            for (int i = beforeStart; i < cursor; i++) {
                before.append(lines[i]).append("\n");
            }
            ctx.beforeCursor = before.toString();

            StringBuilder after = new StringBuilder();
            for (int i = cursor; i < afterEnd; i++) {
                after.append(lines[i]).append(i < afterEnd - 1 ? "\n" : "");
            }
            ctx.afterCursor = after.toString();
        }

        return ctx;
    }

    /**
     * Get selected text context.
     */
    public SelectedContext getSelectedContext() {
        EditorState state = currentState.get();
        SelectedContext ctx = new SelectedContext();
        ctx.filePath = state.filePath;
        ctx.projectName = state.projectName;
        ctx.selectedText = state.selectedText;
        ctx.selectionStartLine = state.selectionStartLine;
        ctx.selectionEndLine = state.selectionEndLine;
        return ctx;
    }

    // === Data classes ===

    public static class EditorState {
        public String filePath = "";
        public String projectName = "";
        public String language = "";
        public int cursorLine = 1;
        public int cursorColumn = 1;
        public String fileContent = "";
        public String selectedText = "";
        public int selectionStartLine = 0;
        public int selectionEndLine = 0;
        public String enclosingMethod = "";
        public List<String> openTabs = new ArrayList<>();
    }

    public static class CursorContext {
        public String filePath = "";
        public String projectName = "";
        public int cursorLine = 1;
        public String beforeCursor = "";
        public String afterCursor = "";
        public String enclosingMethod = "";
    }

    public static class SelectedContext {
        public String filePath = "";
        public String projectName = "";
        public String selectedText = "";
        public int selectionStartLine = 0;
        public int selectionEndLine = 0;
    }
}
