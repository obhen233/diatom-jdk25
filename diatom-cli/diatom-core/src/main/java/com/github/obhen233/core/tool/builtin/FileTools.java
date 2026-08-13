package com.github.obhen233.core.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.code.StructuredSourceModifier;
import com.github.obhen233.core.tool.annotation.ToolMethod;
import com.github.obhen233.core.tool.AuthorizedPathManager;
import com.github.obhen233.core.workspace.WorkspaceRegistry;
import com.github.obhen233.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.github.obhen233.util.JsonUtils;

public class FileTools {
    private static final Logger logger = LoggerFactory.getLogger(FileTools.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();
    private static final String NEWLINE = System.lineSeparator();

    /** 请求级 workspace 覆盖（ThreadLocal，Worker 模式使用） */
    private static final ThreadLocal<String> requestWorkspace = new ThreadLocal<>();

    /** 设置请求级 workspace（Worker handleChat 时调用） */
    public static void setRequestWorkspace(String ws) {
        if (ws != null) {
            requestWorkspace.set(ws);
        }
    }

    /** 清除请求级 workspace（请求结束时调用） */
    public static void clearRequestWorkspace() {
        requestWorkspace.remove();
    }

    /** 获取当前生效的 workspace：请求级优先，否则用构造时传入的值 */
    public String getEffectiveWorkspace() {
        String ws = requestWorkspace.get();
        return ws != null ? ws : workspaceDir;
    }

    /** 静态方式获取当前请求级 workspace（用于无需 FileTools 实例的场景，如 checkpoint 上报） */
    public static String getEffectiveWorkspaceStatic() {
        return requestWorkspace.get();
    }

    private final String workspaceDir;
    private final AuthorizedPathManager authManager;
    private final StructuredSourceModifier sourceModifier;
    private final WorkspaceRegistry workspaceRegistry;

    public FileTools() {
        this(PathUtils.getWorkingDir());
    }

    public FileTools(String workspaceDir) {
        this(workspaceDir, new AuthorizedPathManager());
    }

    public FileTools(String workspaceDir, AuthorizedPathManager authManager) {
        this(workspaceDir, authManager, null);
    }

    public FileTools(String workspaceDir, AuthorizedPathManager authManager, WorkspaceRegistry workspaceRegistry) {
        this.workspaceDir = workspaceDir;
        this.authManager = authManager;
        this.workspaceRegistry = workspaceRegistry;
        this.sourceModifier = new StructuredSourceModifier();
    }

    /**
     * Sanitize content that may have been copied from a read_file result.
     * read_file prepends a header line like "=== filename (lines X-Y of Z) ==="
     * and appends a truncation marker like "... (truncated at line X, total ~Y lines)".
     * If the model accidentally includes these in a write_file call, the written file
     * becomes invalid (e.g. Maven rejects a POM starting with '=').
     */
    private String sanitizeWriteContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        // Strip leading header line produced by read_file: "=== filename (lines X-Y of Z) ==="
        int firstNewline = content.indexOf('\n');
        if (firstNewline > 0) {
            String firstLine = content.substring(0, firstNewline).trim();
            if (firstLine.startsWith("=== ") && firstLine.contains("(lines ") && firstLine.endsWith(") ===")) {
                content = content.substring(firstNewline + 1);
            }
        }

        // Strip trailing truncation marker produced by read_file: "... (truncated at line X, total ~Y lines)"
        int lastNewline = content.lastIndexOf('\n');
        if (lastNewline >= 0) {
            String remainder = content.substring(lastNewline + 1);
            String beforeRemainder = content.substring(0, lastNewline);
            int prevNewline = beforeRemainder.lastIndexOf('\n');
            String lastContentLine = prevNewline >= 0 ? beforeRemainder.substring(prevNewline + 1) : beforeRemainder;
            if (lastContentLine.startsWith("... (truncated at line ") && lastContentLine.endsWith(" lines)")) {
                content = beforeRemainder.substring(0, prevNewline >= 0 ? prevNewline + 1 : 0);
            }
        }

        return content;
    }

    @ToolMethod(name = "read_file",
                description = "[SCENE: file-read] Read the contents of a file. Use for: reading source code, config files, logs. For directory structure, prefer get_source_tree. " +
                    "Supports offset (starting line, 1-based) and limit (max lines) for reading large files in segments. " +
                    "When reading the first 1000 lines shows '(truncated)', call again with offset=1001 to continue.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, " +
                    "\"offset\": {\"type\": \"integer\", \"description\": \"Starting line number (1-based, default 1)\"}, " +
                    "\"limit\": {\"type\": \"integer\", \"description\": \"Max lines to read (default 1000, max 5000)\"}}, " +
                    "\"required\": [\"path\"]}",
                readOnly = true,
                checkWorkspaceBoundary = true)
    public String readFile(String argsJson) throws IOException {
        // Parse path from args - handle both JSON object and plain string
        String path = parsePathArg(argsJson);
        int offset = 1;
        int limit = 1000;

        // Try to parse offset/limit from JSON args
        if (argsJson != null && argsJson.trim().startsWith("{")) {
            try {
                JsonNode obj = mapper.readTree(argsJson);
                if (obj.has("offset")) {
                    offset = obj.get("offset").asInt(1);
                    if (offset < 1) offset = 1;
                }
                if (obj.has("limit")) {
                    limit = obj.get("limit").asInt(1000);
                    if (limit < 1) limit = 1;
                    if (limit > 5000) limit = 5000;
                }
            } catch (Exception e) {
                // ignore, use defaults
            }
        }

        Path filePath = validatePath(path);

        // Reject binary archive/compiled files — reading them returns garbage and wastes context.
        // The model should use get_source_tree, list_files, or search_files instead.
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".jar") || fileName.endsWith(".zip")
            || fileName.endsWith(".war") || fileName.endsWith(".class")
            || fileName.endsWith(".gz") || fileName.endsWith(".tar")) {
            return "ERROR: Cannot read binary/archive file '" + fileName
                + "'. This is a compiled or archived binary, not a text file. "
                + "Use get_source_tree to explore the project structure, "
                + "list_files to see directory contents, or search_files to find source files.";
        }

        return readFileContent(filePath, limit, offset);
    }

    @ToolMethod(name = "write_file",
                description = "[SCENE: file-write] Write content to a file (overwrites existing). USE FOR: any file in the workspace directory including Java source files. The path is relative to the workspace root. For self-updating diatom-cli itself, use write_source_file instead. For large files (>10KB), prefer write_file_chunk.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"content\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                checkWorkspaceBoundary = true,
                riskLevel = "medium",
                confirmationTemplate = "tool_confirm_write_file",
                riskDescriptionTemplate = "tool_dangerous_write")
    public String writeFile(String argsJson) throws IOException {
        JsonNode obj = parseJsonArgs(argsJson);
        String path = obj.get("path").asText();
        String content = sanitizeWriteContent(obj.get("content").asText());
        Path filePath = validatePath(path);
        Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
        return "File written successfully: " + path;
    }
    
    @ToolMethod(name = "write_file_chunk",
                description = "[SCENE: file-write-chunk] Write content to a file using chunk mode. Use for LARGE files (>10KB). For mode='create', the full content is written in one call (always returns COMPLETE). For mode='append'/'prepend', track chunks with chunk_index and set is_final=true on the last chunk. The path is relative to workspace root.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"content\": {\"type\": \"string\"}, \"mode\": {\"type\": \"string\"}, \"chunk_index\": {\"type\": \"integer\"}, \"is_final\": {\"type\": \"boolean\"}}}",
                requiresConfirmation = true,
                checkWorkspaceBoundary = true,
                riskLevel = "medium",
                confirmationTemplate = "tool_confirm_write_file",
                riskDescriptionTemplate = "tool_dangerous_write")
    public String writeFileChunk(String argsJson) throws IOException {
        JsonNode obj = parseJsonArgs(argsJson);
        String path = obj.get("path").asText();
        String content = sanitizeWriteContent(obj.get("content").asText());
        String mode = obj.has("mode") ? obj.get("mode").asText() : "create";
        int chunkIndex = obj.has("chunk_index") ? obj.get("chunk_index").asInt() : 0;
        boolean isFinal = obj.has("is_final") && obj.get("is_final").asBoolean();
        
        Path filePath = validatePath(path);
        
        switch (mode.toLowerCase()) {
            case "create":
                // Create new file or overwrite
                Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
                break;
            case "append":
                // Append to existing file
                if (Files.exists(filePath)) {
                    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8), 
                        java.nio.file.StandardOpenOption.APPEND);
                } else {
                    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
                }
                break;
            case "prepend":
                // Prepend to existing file
                if (Files.exists(filePath)) {
                    String existing = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                    Files.write(filePath, (content + existing).getBytes(StandardCharsets.UTF_8));
                } else {
                    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
                }
                break;
            default:
                throw new IOException("Unknown mode: " + mode + ". Use 'create', 'append', or 'prepend'.");
        }
        
        String status;
        if ("create".equalsIgnoreCase(mode)) {
            // mode=create always writes the complete content provided in this call.
            // If more content needs to be appended, use mode=append instead.
            status = "COMPLETE";
        } else {
            status = isFinal ? "COMPLETE" : "chunk " + chunkIndex;
        }
        return String.format("File chunk written: %s (%s, %d bytes)", path, status, content.length());
    }
    
    @ToolMethod(name = "write_file_via_temp",
                description = "[SCENE: file-write-large] Write large file via temporary file. Use for VERY large files (>50KB) where JSON transfer is slow. Steps: 1) Creates temp file with content, 2) Moves to final location. The path is relative to workspace root.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"content\": {\"type\": \"string\"}, \"temp_name\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                checkWorkspaceBoundary = true,
                riskLevel = "medium",
                confirmationTemplate = "tool_confirm_write_file",
                riskDescriptionTemplate = "tool_dangerous_write")
    public String writeFileViaTemp(String argsJson) throws IOException {
        JsonNode obj = parseJsonArgs(argsJson);
        String path = obj.get("path").asText();
        String content = sanitizeWriteContent(obj.get("content").asText());
        String tempName = obj.has("temp_name") ? obj.get("temp_name").asText() : "diatom_temp_" + System.currentTimeMillis();
        
        Path targetPath = validatePath(path);
        
        // Create temp file in system temp directory
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path tempFile = tempDir.resolve(tempName);
        
        // Write to temp file first
        Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8));
        
        // Move to final location
        Files.move(tempFile, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        
        return String.format("File written via temp: %s (%d bytes)", path, content.length());
    }

    @ToolMethod(name = "replace_in_file",
                description = "[SCENE: tiny-edit] Replace a specific string in a file. Use ONLY for: single-line fixes, typo corrections, value updates. FOR larger changes, use edit_file or write_file instead.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"old_str\": {\"type\": \"string\"}, \"new_str\": {\"type\": \"string\"}}}",
                requiresConfirmation = true,
                checkWorkspaceBoundary = true,
                riskLevel = "medium",
                confirmationTemplate = "tool_confirm_replace_file",
                riskDescriptionTemplate = "tool_dangerous_write")
    public String replaceInFile(String argsJson) throws IOException {
        JsonNode obj = parseJsonArgs(argsJson);
        String path = obj.get("path").asText();
        String oldStr = obj.get("old_str").asText();
        String newStr = obj.get("new_str").asText();

        // Validate that old_str is not empty
        if (oldStr == null || oldStr.isEmpty()) {
            return "Error: old_str cannot be empty. Please provide the exact text to replace.";
        }

        Path filePath = validatePath(path);
        String content = readFileContent(filePath, Integer.MAX_VALUE);

        // Try structured modification first (handles line ending differences)
        try {
            // Use structured modifier which handles line ending normalization
            String result = sourceModifier.basicReplace(path, oldStr, newStr, getEffectiveWorkspace());
            logger.info("Structured replace successful for: {}", path);
            return result;
        } catch (Exception e) {
            logger.debug("Structured replace failed, trying simple replace: {}", e.getMessage());
        }

        // Fallback to simple string replace
        if (!content.contains(oldStr)) {
            // Provide a helpful error with context about nearby text
            String hint = findNearbyMatch(content, oldStr);
            if (hint != null) {
                return "Error: String not found. " + hint;
            }
            return "Error: String not found in file. Use edit_file instead (it works by line numbers, not string matching).";
        }

        content = content.replace(oldStr, newStr);
        // Strip any read_file header that might have been included in the content
        content = sanitizeWriteContent(content);
        Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
        return "Replacement done in: " + path;
    }

    @ToolMethod(name = "edit_file",
                description = "[SCENE: edit-file] Replace a range of lines in a file by line number. " +
                    "PREFERRED over replace_in_file for any multi-line change. " +
                    "The old_lines range is replaced with new_content. " +
                    "Use read_file first to see the line numbers, then call this with the exact line range to replace. " +
                    "Example: replace lines 45-60 with new code.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, " +
                    "\"start_line\": {\"type\": \"integer\", \"description\": \"First line to replace (1-based, inclusive)\"}, " +
                    "\"end_line\": {\"type\": \"integer\", \"description\": \"Last line to replace (1-based, inclusive)\"}, " +
                    "\"new_content\": {\"type\": \"string\", \"description\": \"New content to replace the line range with\"}}, " +
                    "\"required\": [\"path\", \"start_line\", \"end_line\", \"new_content\"]}",
                requiresConfirmation = true,
                checkWorkspaceBoundary = true,
                riskLevel = "medium",
                confirmationTemplate = "tool_confirm_replace_file",
                riskDescriptionTemplate = "tool_dangerous_write")
    public String editFileLines(String argsJson) throws IOException {
        JsonNode obj = parseJsonArgs(argsJson);
        String path = obj.get("path").asText();
        int startLine = obj.get("start_line").asInt();
        int endLine = obj.get("end_line").asInt();
        String newContent = sanitizeWriteContent(obj.get("new_content").asText());

        if (startLine < 1 || endLine < startLine) {
            return "Error: Invalid line range. start_line must be >= 1 and end_line >= start_line.";
        }

        Path filePath = validatePath(path);
        List<String> allLines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        int fileLineCount = allLines.size();

        if (startLine > fileLineCount) {
            return "Error: start_line (" + startLine + ") exceeds file length (" + fileLineCount + " lines).";
        }
        if (endLine > fileLineCount) {
            endLine = fileLineCount;
        }

        // Build new file content: lines before start + new_content + lines after end
        StringBuilder sb = new StringBuilder();
        // Lines 1 to startLine-1 (0-based: 0 to startLine-2)
        for (int i = 0; i < startLine - 1 && i < allLines.size(); i++) {
            sb.append(allLines.get(i)).append("\n");
        }
        // New content
        sb.append(newContent);
        // Ensure new content ends with newline
        if (!newContent.endsWith("\n")) {
            sb.append("\n");
        }
        // Lines after endLine
        for (int i = endLine; i < allLines.size(); i++) {
            sb.append(allLines.get(i));
            if (i < allLines.size() - 1) {
                sb.append("\n");
            }
        }

        Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));
        return String.format("Lines %d-%d replaced in: %s (%d lines replaced, %d total lines -> %d total lines)",
            startLine, endLine, path, (endLine - startLine + 1),
            fileLineCount, countLines(sb.toString()));
    }

    /**
     * Count lines in a string.
     */
    private int countLines(String content) {
        if (content == null || content.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') count++;
        }
        return count;
    }

    /**
     * Find nearby match for a failed replace_in_file string match.
     */
    private String findNearbyMatch(String content, String oldStr) {
        if (content == null || oldStr == null) return null;
        String searchStr = oldStr.length() > 50 ? oldStr.substring(0, 50) : oldStr;
        int idx = content.indexOf(searchStr);
        if (idx >= 0) {
            // Count line number where the partial match was found
            int lineNum = 1;
            for (int i = 0; i < idx && i < content.length(); i++) {
                if (content.charAt(i) == '\n') lineNum++;
            }
            return "Partial match found near line " + lineNum
                + ". The exact text may differ in whitespace/indentation. "
                + "Try using edit_file instead (specify line " + lineNum + ").";
        }
        return null;
    }

    @ToolMethod(name = "list_files",
                description = "[SCENE: dir-list] List files in a directory. " +
                    "★★★ DO NOT USE for project overview - use get_source_tree instead! ★★★ " +
                    "Use ONLY when you need specific directory details NOT available in get_source_tree. " +
                    "This tool counts against exploration budget.",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}",
                readOnly = true,
                checkWorkspaceBoundary = true)
    public String listFiles(String argsJson) throws IOException {
        logger.debug("list_files called with args: {}", argsJson);
        
        // Parse path from args - handle both JSON object and plain string
        String path = parsePathArg(argsJson);
        if (path == null || path.isEmpty()) {
            path = ".";
        }
        
        logger.debug("list_files path resolved to: {}", path);
        Path dirPath = validatePath(path.isEmpty() ? "." : path);
        logger.debug("list_files validated path: {}", dirPath);
        StringBuilder sb = new StringBuilder();
        try (java.util.stream.Stream<Path> list = Files.list(dirPath)) {
            java.util.List<Path> entries = list.collect(java.util.stream.Collectors.toList());
            logger.debug("list_files found {} entries", entries.size());
            for (Path p : entries) {
                sb.append(p.getFileName()).append(NEWLINE);
            }
        }
        String result = sb.toString();
        logger.debug("list_files result length: {}", result.length());
        return result;
    }
    
    /**
     * Parse path argument from JSON or plain string.
     * Handles Windows paths with unescaped backslashes.
     */
    private String parsePathArg(String argsJson) {
        if (argsJson == null || argsJson.trim().isEmpty()) {
            return ".";
        }
        
        String trimmed = argsJson.trim();
        
        // Check if it's a JSON object
        if (trimmed.startsWith("{")) {
            try {
                JsonNode obj = mapper.readTree(argsJson);
                if (obj.has("path")) {
                    return obj.get("path").asText();
                }
                return ".";
            } catch (Exception e) {
                // JSON parse failed, try to extract path manually
                logger.debug("Failed to parse JSON, attempting manual extraction: {}", argsJson);
            }
        }
        
        // Plain string: the path itself (could be Windows path like D:\path)
        // No need to escape backslashes for non-JSON input
        return trimmed;
    }
    
    /**
     * Parse JSON arguments with Windows path backslash handling.
     * Windows paths like D:\path\to\file need backslashes escaped as \\
     */
    private JsonNode parseJsonArgs(String argsJson) throws IOException {
        if (argsJson == null || argsJson.trim().isEmpty()) {
            throw new IOException("Empty arguments");
        }
        
        String json = argsJson.trim();
        
        // Try parsing as-is first
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            // If parsing failed and we're on Windows, try to fix unescaped backslashes
            // Windows paths like "D:\path\file" appear in JSON as "D:\\path\\file"
            // But sometimes they come in as "D:\path\file" which is invalid JSON
            if (File.separatorChar == '\\') {
                // Try to fix common Windows path issues in JSON strings
                // This handles cases where ToolRegistry passes paths with single backslashes
                String fixed = fixWindowsPathInJson(json);
                try {
                    return mapper.readTree(fixed);
                } catch (Exception e2) {
                    logger.warn("Failed to parse JSON even after Windows path fix: {}", argsJson);
                    throw new IOException("Invalid JSON arguments: " + argsJson, e);
                }
            }
            throw new IOException("Invalid JSON arguments: " + argsJson, e);
        }
    }
    
    /**
     * Fix Windows paths in JSON by escaping unescaped backslashes in string values.
     * Example: {"path": "D:\test\file"} -> {"path": "D:\\test\\file"}
     */
    private String fixWindowsPathInJson(String json) {
        // Simple approach: find string values and escape backslashes
        // This regex finds: "key": "value with backslashes"
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                // Found start of a string
                int start = i;
                i++;
                boolean foundBackslash = false;
                StringBuilder stringValue = new StringBuilder();
                
                while (i < json.length()) {
                    char sc = json.charAt(i);
                    if (sc == '\\' && i + 1 < json.length()) {
                        char next = json.charAt(i + 1);
                        if (next == '"' || next == '\\' || next == '/' || next == 'n' || next == 'r' || next == 't') {
                            // Already escaped, keep as-is
                            stringValue.append(sc);
                            i++;
                            stringValue.append(next);
                            i++;
                        } else {
                            // Unescaped backslash (like in D:\path), escape it
                            stringValue.append("\\\\");
                            i++;
                            foundBackslash = true;
                        }
                    } else if (sc == '"') {
                        // End of string
                        i++;
                        break;
                    } else {
                        stringValue.append(sc);
                        i++;
                    }
                }
                
                result.append('"').append(stringValue).append('"');
            } else {
                result.append(c);
                i++;
            }
        }
        
        return result.toString();
    }

    /**
     * Cleanup temporary files generated during task execution.
     * Model reports which files/dirs were created, this tool deletes them.
     */
    @ToolMethod(name = "cleanup_workspace",
                description = "Delete temporary files/directories created during task execution. Model should report what was created. Example: cleanup_workspace(['target/', 'helper.java', 'script.sh']).",
                parametersSchema = "{\"type\": \"object\", \"properties\": {\"files\": {\"type\": \"array\", \"items\": {\"type\": \"string\"}, \"description\": \"List of file/directory names to delete (relative to workspace root)\"}}}",
                requiresConfirmation = true,
                checkWorkspaceBoundary = true,
                riskLevel = "high",
                confirmationTemplate = "tool_confirm_delete_file",
                riskDescriptionTemplate = "tool_dangerous_delete")
    public String cleanupWorkspace(String argsJson) throws IOException {
        JsonNode obj = parseJsonArgs(argsJson);
        JsonNode filesNode = obj.get("files");

        if (filesNode == null || !filesNode.isArray()) {
            return "Error: 'files' parameter must be an array of file/directory names";
        }

        Path workspace = Paths.get(getEffectiveWorkspace()).toAbsolutePath().normalize();
        int[] stats = {0, 0};
        long[] bytesFreed = {0};
        StringBuilder report = new StringBuilder();

        for (JsonNode node : filesNode) {
            String fileName = node.asText();
            Path target = workspace.resolve(fileName).normalize();

            // Security check: ensure target is within workspace
            if (!target.startsWith(workspace)) {
                report.append("Skipped (outside workspace): ").append(fileName).append("\n");
                continue;
            }

            if (!Files.exists(target)) {
                report.append("Not found: ").append(fileName).append("\n");
                continue;
            }

            try {
                long size = Files.isDirectory(target) ? folderSize(target) : Files.size(target);
                deleteRecursively(target);
                bytesFreed[0] += size;

                if (Files.isDirectory(target)) {
                    stats[1]++;
                } else {
                    stats[0]++;
                }
                report.append("Deleted: ").append(fileName).append("\n");
            } catch (IOException e) {
                report.append("Failed to delete: ").append(fileName).append(" - ").append(e.getMessage()).append("\n");
            }
        }

        String sizeStr = formatBytes(bytesFreed[0]);
        report.append(String.format("Total: %d files, %d directories deleted, %s freed",
                stats[0], stats[1], sizeStr));
        return report.toString();
    }

    private long folderSize(Path folder) {
        try {
            return Files.walk(folder)
                .filter(Files::isRegularFile)
                .mapToLong(p -> {
                    try { return Files.size(p); } catch (IOException e) { return 0; }
                })
                .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (java.util.stream.Stream<Path> s = Files.list(path)) {
                s.forEach(p -> {
                    try {
                        deleteRecursively(p);
                    } catch (IOException e) {
                        logger.debug("Failed to delete: {}", p);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    protected Path validatePath(String path) throws IOException {
        String effectiveWs = getEffectiveWorkspace();
        Path filePath;

        if (Paths.get(path).isAbsolute()) {
            filePath = Paths.get(path).toAbsolutePath().normalize();
        } else {
            filePath = Paths.get(effectiveWs, path).toAbsolutePath().normalize();
        }

        Path workspace = Paths.get(effectiveWs).toAbsolutePath().normalize();

        // Check if path is within workspace
        if (filePath.startsWith(workspace)) {
            return filePath;
        }

        // Check if path is in authorized paths
        if (authManager != null && authManager.isAuthorized(filePath.toString())) {
            return filePath;
        }

        // Check if path is in a registered workspace -- auto-authorize if so
        if (workspaceRegistry != null && workspaceRegistry.isInAnyWorkspace(filePath.toString())) {
            if (authManager != null) {
                authManager.authorize(filePath.toString());
            }
            return filePath;
        }

        throw new UnauthorizedPathException("Access denied: Path outside workspace [" + workspace + "]: " + path, filePath.toString());
    }

    private String readFileContent(Path filePath, int maxLines) throws IOException {
        return readFileContent(filePath, maxLines, 1);
    }

    private String readFileContent(Path filePath, int maxLines, int startLine) throws IOException {
        StringBuilder sb = new StringBuilder();
        long totalLines = 0;
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;

            // Skip to start line
            int lineNum = 1;
            while (lineNum < startLine && reader.readLine() != null) {
                lineNum++;
            }

            // Read content
            while ((line = reader.readLine()) != null) {
                totalLines = lineNum; // track last line read
                if (count < maxLines) {
                    sb.append(line).append("\n");
                    count++;
                }
                lineNum++;
            }
        }

        // Build result with metadata
        StringBuilder result = new StringBuilder();
        result.append("=== ").append(filePath.getFileName()).append(" (lines ")
              .append(startLine).append("-").append(startLine + count - 1)
              .append(" of ").append(totalLines).append(") ===").append("\n");
        result.append(sb);

        if (count > 0 && count >= maxLines) {
            result.append("\n... (truncated at line ").append(startLine + count - 1)
                  .append(", total ~").append(totalLines).append(" lines)")
                  .append("\n");
        }

        if (sb.length() == 0 && startLine > 1) {
            result.append("(offset ").append(startLine).append(" is beyond end of file)")
                  .append("\n");
        }

        return result.toString();
    }

    public static class UnauthorizedPathException extends IOException {
        private final String requestedPath;

        public UnauthorizedPathException(String message, String requestedPath) {
            super(message);
            this.requestedPath = requestedPath;
        }

        public String getRequestedPath() {
            return requestedPath;
        }
    }
}