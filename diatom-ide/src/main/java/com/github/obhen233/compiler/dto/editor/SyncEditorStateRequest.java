package com.github.obhen233.compiler.dto.editor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Sync editor state request / 同步编辑器状态请求
 */
@Schema(description = "Sync editor state request / 同步编辑器状态请求")
public record SyncEditorStateRequest(
    @Schema(description = "File path / 文件路径", example = "src/main/java/com/example/Main.java") String filePath,
    @Schema(description = "Project name / 项目名称", example = "myproject") String projectName,
    @Schema(description = "Language ID / 语言ID", example = "java") String language,
    @Schema(description = "Cursor line / 光标行", example = "1") Integer cursorLine,
    @Schema(description = "Cursor column / 光标列", example = "1") Integer cursorColumn,
    @Schema(description = "File content / 文件内容", example = "public class Main {}") String fileContent,
    @Schema(description = "Selected text / 选中文本", example = "Main") String selectedText,
    @Schema(description = "Selection start line / 选择开始行", example = "1") Integer selectionStartLine,
    @Schema(description = "Selection end line / 选择结束行", example = "1") Integer selectionEndLine,
    @Schema(description = "Enclosing method / 包含方法", example = "public static void main") String enclosingMethod,
    @Schema(description = "Open tabs / 打开的标签", example = "[\"Main.java\", \"Utils.java\"]") List<String> openTabs
) {}
