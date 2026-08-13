package com.github.obhen233.compiler.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.dto.ToolProgress;
import com.github.obhen233.compiler.mcp.EditorContextService;
import com.github.obhen233.compiler.service.workspace.FileOperationService;
import com.github.obhen233.core.mcp.FileMcpServer;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.jdtls.SimpleTextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Project-aware FileMcpServer implementation.
 * Provides project-aware file operations for multi-project workspaces.
 * All file operations use project context to resolve paths correctly.
 */
@Component
public class ProjectFileMcpServer implements FileMcpServer {

    private static final Logger logger = LoggerFactory.getLogger(ProjectFileMcpServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private EditorContextService editorContext;

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired(required = false)
    private ProgressPublisher progressPublisher;

    @Autowired(required = false)
    private SimpleTextDocumentService textDocumentService;

    @Override
    public String getName() {
        return "project-file";
    }

    @Override
    public String getDescription() {
        return "Project-aware file operations for multi-project workspaces.";
    }

    @Override
    public Map<String, Tool> listTools() {
        Map<String, Tool> tools = new LinkedHashMap<>();

        // Read file
        Tool readFile = new Tool(
                "read_file",
                "[OVERRIDE BUILT-IN] Read the contents of a file. Project-aware implementation.",
                "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\", \"description\": \"Project-relative file path\"}}}"
        );
        readFile.setReadOnly(true);
        readFile.setRequiresConfirmation(false);
        readFile.setRiskLevel("none");
        tools.put("read_file", readFile);

        // Write file
        Tool writeFile = new Tool(
                "write_file",
                "[OVERRIDE BUILT-IN] Write content to a file. Project-aware implementation.",
                "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"content\": {\"type\": \"string\"}}}"
        );
        writeFile.setReadOnly(false);
        writeFile.setRequiresConfirmation(true);
        writeFile.setRiskLevel("medium");
        writeFile.setConfirmationTemplate("tool_confirm_write_file");
        tools.put("write_file", writeFile);

        // List directory
        Tool listDir = new Tool(
                "list_directory",
                "[OVERRIDE BUILT-IN] List files in a directory. Project-aware implementation.",
                "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\", \"description\": \"Directory path\"}}}"
        );
        listDir.setReadOnly(true);
        listDir.setRequiresConfirmation(false);
        listDir.setRiskLevel("none");
        tools.put("list_directory", listDir);

        // Create directory
        Tool createDir = new Tool(
                "create_directory",
                "[OVERRIDE BUILT-IN] Create a directory. Project-aware implementation.",
                "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}"
        );
        createDir.setReadOnly(false);
        createDir.setRequiresConfirmation(true);
        createDir.setRiskLevel("medium");
        createDir.setConfirmationTemplate("tool_confirm_create_directory");
        tools.put("create_directory", createDir);

        // Delete file
        Tool deleteFile = new Tool(
                "delete_file",
                "[OVERRIDE BUILT-IN] Delete a file. Project-aware implementation.",
                "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}"
        );
        deleteFile.setReadOnly(false);
        deleteFile.setRequiresConfirmation(true);
        deleteFile.setRiskLevel("medium");
        deleteFile.setConfirmationTemplate("tool_confirm_delete_file");
        tools.put("delete_file", deleteFile);

        // Exists
        Tool exists = new Tool(
                "exists",
                "[OVERRIDE BUILT-IN] Check if a path exists. Project-aware implementation.",
                "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}"
        );
        exists.setReadOnly(true);
        exists.setRequiresConfirmation(false);
        exists.setRiskLevel("none");
        tools.put("exists", exists);

        // Search files
        Tool searchFiles = new Tool(
                "search_files",
                "[OVERRIDE BUILT-IN] Search for files matching a pattern. Project-aware implementation.",
                "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"pattern\": {\"type\": \"string\"}}}"
        );
        searchFiles.setReadOnly(true);
        searchFiles.setRequiresConfirmation(false);
        searchFiles.setRiskLevel("none");
        tools.put("search_files", searchFiles);

        // Replace in file
        Tool replaceInFile = new Tool(
                "replace_in_file",
                "[OVERRIDE BUILT-IN] Replace a specific string in a file. Project-aware implementation.",
                "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"old_str\": {\"type\": \"string\"}, \"new_str\": {\"type\": \"string\"}}, \"required\": [\"path\", \"old_str\", \"new_str\"]}"
        );
        replaceInFile.setReadOnly(false);
        replaceInFile.setRequiresConfirmation(true);
        replaceInFile.setRiskLevel("medium");
        replaceInFile.setConfirmationTemplate("tool_confirm_replace_file");
        tools.put("replace_in_file", replaceInFile);

        return tools;
    }

    @Override
    public String callTool(String name, String args) {
        try {
            switch (name) {
                case "read_file":
                    return doReadFile(args);
                case "write_file":
                    return doWriteFile(args);
                case "list_directory":
                    return doListDirectory(args);
                case "create_directory":
                    return doCreateDirectory(args);
                case "delete_file":
                    return doDeleteFile(args);
                case "exists":
                    return doExists(args);
                case "search_files":
                    return doSearchFiles(args);
                case "replace_in_file":
                    return doReplaceInFile(args);
                default:
                    return null; // Let other servers handle
            }
        } catch (Exception e) {
            logger.error("Error calling tool {}", name, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ========== Interface method implementations ==========

    @Override
    public String readFile(String path) {
        return doReadFilePath(path);
    }

    @Override
    public String writeFile(String path, String content) {
        return doWriteFilePath(path, content);
    }

    @Override
    public String listDirectory(String path) {
        return doListDirectoryPath(path);
    }

    @Override
    public String createDirectory(String path) {
        return doCreateDirectoryPath(path);
    }

    @Override
    public String deleteFile(String path) {
        return doDeleteFilePath(path);
    }

    @Override
    public String exists(String path) {
        return doExistsPath(path);
    }

    @Override
    public String searchFiles(String path, String pattern) {
        String projectName = getCurrentProjectName();
        return doSearchFilesPath(path, pattern, projectName);
    }

    @Override
    public String replaceInFile(String path, String oldStr, String newStr) {
        return doReplaceInFilePath(path, oldStr, newStr);
    }

    @Override
    public String getCurrentProjectName() {
        EditorContextService.EditorState state = editorContext.getCurrentState();
        return state != null ? state.projectName : null;
    }

    // ========== JSON args parsing methods ==========

    private String doReadFile(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        String path = toString(params.get("path"));
        return doReadFilePath(path);
    }

    private String doWriteFile(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        String path = toString(params.get("path"));
        String content = toString(params.get("content"));
        return doWriteFilePath(path, content);
    }

    private String doListDirectory(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        String path = toString(params.get("path"));
        return doListDirectoryPath(path);
    }

    private String doCreateDirectory(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        String path = toString(params.get("path"));
        return doCreateDirectoryPath(path);
    }

    private String doDeleteFile(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        String path = toString(params.get("path"));
        return doDeleteFilePath(path);
    }

    private String doExists(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        String path = toString(params.get("path"));
        return doExistsPath(path);
    }

    private String doSearchFiles(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        String path = toString(params.get("path"));
        String pattern = toString(params.get("pattern"));
        String projectName = toString(params.get("projectName"));
        return doSearchFilesPath(path, pattern, projectName);
    }

    private String doReplaceInFile(String args) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = JSON.readValue(args, Map.class);
        String path = toString(params.get("path"));
        String oldStr = toString(params.get("old_str"));
        String newStr = toString(params.get("new_str"));
        return doReplaceInFilePath(path, oldStr, newStr);
    }

    // ========== Path-based implementation methods ==========

    private String doReadFilePath(String relativePath) {
        if (relativePath.isEmpty()) {
            return "{\"error\":\"path is required\"}";
        }
        if (isAbsolutePath(relativePath)) {
            return absolutePathError(relativePath);
        }

        if (progressPublisher != null) {
            progressPublisher.publish(ToolProgress.reading("read_file", relativePath));
        }

        File targetFile = resolveProjectFile(relativePath);
        if (targetFile == null || !targetFile.exists()) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("read_file", relativePath));
            }
            return "{\"error\":\"File not found: " + relativePath + "\"}";
        }
        try {
            String content = new String(Files.readAllBytes(targetFile.toPath()), "UTF-8");
            Map<String, Object> result = new HashMap<>();
            result.put("content", content);
            result.put("path", relativePath);
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("read_file", relativePath));
            }
            return toJson(result);
        } catch (IOException e) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("read_file", relativePath));
            }
            return "{\"error\":\"Failed to read file: " + e.getMessage() + "\"}";
        }
    }

    private String doWriteFilePath(String relativePath, String content) {
        if (relativePath.isEmpty()) {
            return "{\"error\":\"path is required\"}";
        }
        if (isAbsolutePath(relativePath)) {
            return absolutePathError(relativePath);
        }

        if (progressPublisher != null) {
            progressPublisher.publish(ToolProgress.writing("write_file", relativePath));
        }

        String projectName = resolveProjectName(relativePath);
        if (projectName == null || projectName.isEmpty()) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("write_file", relativePath));
            }
            return "{\"error\":\"Cannot determine project for: " + relativePath + "\"}";
        }

        String cleanPath = normalizeRelativePath(relativePath, projectName);
        File targetFile = fileOperationService.resolveProjectFile(projectName, cleanPath);
        if (targetFile == null) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("write_file", relativePath));
            }
            return "{\"error\":\"Cannot resolve path: " + relativePath + "\"}";
        }
        try {
            // 使用 FileOperationService.saveFile 写入（触发 ProjectIndexService 索引更新）
            Map<String, Object> saveResult = fileOperationService.saveFile(projectName, cleanPath, content);
            if (!Boolean.TRUE.equals(saveResult.get("success"))) {
                if (progressPublisher != null) {
                    progressPublisher.publish(ToolProgress.completed("write_file", relativePath));
                }
                return "{\"error\":\"Failed to write file: " + saveResult.get("message") + "\"}";
            }
            logger.info("write_file: {} ({})", relativePath, targetFile.getAbsolutePath());

            // 使 LSP 缓存失效（openDocuments + 项目类索引）
            if (textDocumentService != null) {
                textDocumentService.invalidateDocumentByFilePath(targetFile.getAbsolutePath());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", relativePath);
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("write_file", relativePath));
            }
            return toJson(result);
        } catch (Exception e) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("write_file", relativePath));
            }
            logger.error("write_file failed: {}", relativePath, e);
            return "{\"error\":\"Failed to write file: " + e.getMessage() + "\"}";
        }
    }

    private String doListDirectoryPath(String relativePath) {
        final String effectivePath = relativePath.isEmpty() ? "" : relativePath;
        if (isAbsolutePath(effectivePath)) {
            return absolutePathError(effectivePath);
        }
        File targetDir = resolveProjectFile(effectivePath);
        if (targetDir == null || !targetDir.exists()) {
            return "{\"error\":\"Directory not found: " + effectivePath + "\"}";
        }
        if (!targetDir.isDirectory()) {
            return "{\"error\":\"Not a directory: " + effectivePath + "\"}";
        }
        try (Stream<Path> list = Files.list(targetDir.toPath())) {
            ObjectNode result = JSON.createObjectNode();
            ArrayNode entries = JSON.createArrayNode();
            list.forEach(p -> {
                ObjectNode entry = JSON.createObjectNode();
                entry.put("name", p.getFileName().toString());
                entry.put("type", Files.isDirectory(p) ? "directory" : "file");
                entry.put("path", effectivePath.isEmpty() ? p.getFileName().toString() : effectivePath + "/" + p.getFileName().toString());
                try {
                    entry.put("size", Files.size(p));
                } catch (IOException e) {
                    entry.put("size", -1);
                }
                entries.add(entry);
            });
            result.set("entries", entries);
            result.put("path", effectivePath);
            return JSON.writeValueAsString(result);
        } catch (IOException e) {
            return "{\"error\":\"Failed to list directory: " + e.getMessage() + "\"}";
        }
    }

    private String doCreateDirectoryPath(String relativePath) {
        if (relativePath.isEmpty()) {
            return "{\"error\":\"path is required\"}";
        }
        if (isAbsolutePath(relativePath)) {
            return absolutePathError(relativePath);
        }
        File targetDir = resolveProjectFile(relativePath);
        if (targetDir == null) {
            return "{\"error\":\"Cannot resolve path: " + relativePath + "\"}";
        }
        try {
            Files.createDirectories(targetDir.toPath());
            logger.info("create_directory: {} ({})", relativePath, targetDir.getAbsolutePath());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", relativePath);
            return toJson(result);
        } catch (IOException e) {
            return "{\"error\":\"Failed to create directory: " + e.getMessage() + "\"}";
        }
    }

    private String doDeleteFilePath(String relativePath) {
        if (relativePath.isEmpty()) {
            return "{\"error\":\"path is required\"}";
        }
        if (isAbsolutePath(relativePath)) {
            return absolutePathError(relativePath);
        }
        File targetFile = resolveProjectFile(relativePath);
        if (targetFile == null || !targetFile.exists()) {
            return "{\"error\":\"File not found: " + relativePath + "\"}";
        }
        try {
            Files.deleteIfExists(targetFile.toPath());
            logger.info("delete_file: {} ({})", relativePath, targetFile.getAbsolutePath());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", relativePath);
            return toJson(result);
        } catch (IOException e) {
            return "{\"error\":\"Failed to delete file: " + e.getMessage() + "\"}";
        }
    }

    private String doExistsPath(String relativePath) {
        if (isAbsolutePath(relativePath)) {
            return absolutePathError(relativePath);
        }
        File targetFile = resolveProjectFile(relativePath);
        boolean isFile = targetFile != null && Files.exists(targetFile.toPath()) && Files.isRegularFile(targetFile.toPath());
        boolean isDir = targetFile != null && Files.exists(targetFile.toPath()) && Files.isDirectory(targetFile.toPath());
        Map<String, Object> result = new HashMap<>();
        result.put("exists", isFile || isDir);
        result.put("isFile", isFile);
        result.put("isDirectory", isDir);
        return toJson(result);
    }

    private String doSearchFilesPath(String relativePath, String pattern, String projectName) {
        final String effectivePath = relativePath.isEmpty() ? "" : relativePath;
        if (pattern.isEmpty()) {
            return "{\"error\":\"pattern is required\"}";
        }
        if (isAbsolutePath(effectivePath)) {
            return absolutePathError(effectivePath);
        }

        if (progressPublisher != null) {
            progressPublisher.publish(ToolProgress.reading("search_files", pattern));
        }

        // 确保有项目名
        if (projectName == null || projectName.isEmpty()) {
            projectName = getCurrentProjectName();
        }
        if (projectName == null || projectName.isEmpty()) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("search_files", pattern));
            }
            return "{\"error\":\"projectName is required\"}";
        }

        File searchDir = resolveProjectFile(effectivePath);
        if (searchDir == null || !searchDir.exists()) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("search_files", pattern));
            }
            return "{\"error\":\"Path not found: " + effectivePath + "\"}";
        }
        java.util.List<String> matches = new java.util.ArrayList<>();
        String regex = pattern.replace("*", ".*").replace("?", ".");
        try {
            String finalProjectName = projectName;
            Files.walk(searchDir.toPath()).forEach(p -> {
                if (p.getFileName().toString().matches(regex)) {
                    String projectRelative = getProjectRelativePath(p.toString(), finalProjectName);
                    matches.add(projectRelative);
                }
            });
        } catch (IOException e) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("search_files", pattern));
            }
            return "{\"error\":\"Failed to search files: " + e.getMessage() + "\"}";
        }
        ObjectNode result = JSON.createObjectNode();
        ArrayNode files = JSON.createArrayNode();
        matches.forEach(files::add);
        result.set("matches", files);
        result.put("count", matches.size());
        if (progressPublisher != null) {
            progressPublisher.publish(ToolProgress.completed("search_files", pattern));
        }
        return toJson(result);
    }

    private String doReplaceInFilePath(String relativePath, String oldStr, String newStr) {
        if (relativePath.isEmpty() || oldStr.isEmpty()) {
            return "{\"error\":\"path and old_str are required\"}";
        }
        if (isAbsolutePath(relativePath)) {
            return absolutePathError(relativePath);
        }

        if (progressPublisher != null) {
            progressPublisher.publish(ToolProgress.writing("replace_in_file", relativePath));
        }

        String projectName = resolveProjectName(relativePath);
        if (projectName == null || projectName.isEmpty()) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("replace_in_file", relativePath));
            }
            return "{\"error\":\"Cannot determine project for: " + relativePath + "\"}";
        }

        String cleanPath = normalizeRelativePath(relativePath, projectName);
        File targetFile = fileOperationService.resolveProjectFile(projectName, cleanPath);
        if (targetFile == null || !targetFile.exists()) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("replace_in_file", relativePath));
            }
            return "{\"error\":\"File not found: " + relativePath + "\"}";
        }
        String content;
        try {
            content = new String(Files.readAllBytes(targetFile.toPath()), "UTF-8");
        } catch (IOException e) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("replace_in_file", relativePath));
            }
            return "{\"error\":\"Failed to read file: " + e.getMessage() + "\"}";
        }
        if (!content.contains(oldStr)) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("replace_in_file", relativePath));
            }
            return "{\"error\":\"String not found in file: " + oldStr.substring(0, Math.min(50, oldStr.length())) + "...\"}";
        }
        String newContent = content.replace(oldStr, newStr);
        try {
            // 使用 FileOperationService.saveFile 写入（触发 ProjectIndexService 索引更新）
            Map<String, Object> saveResult = fileOperationService.saveFile(projectName, cleanPath, newContent);
            if (!Boolean.TRUE.equals(saveResult.get("success"))) {
                if (progressPublisher != null) {
                    progressPublisher.publish(ToolProgress.completed("replace_in_file", relativePath));
                }
                return "{\"error\":\"Failed to write file: " + saveResult.get("message") + "\"}";
            }
            logger.info("replace_in_file: {} ({})", relativePath, targetFile.getAbsolutePath());

            // 使 LSP 缓存失效（openDocuments + 项目类索引）
            if (textDocumentService != null) {
                textDocumentService.invalidateDocumentByFilePath(targetFile.getAbsolutePath());
            }

            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("replace_in_file", relativePath));
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", relativePath);
            result.put("file", targetFile.getAbsolutePath());
            return toJson(result);
        } catch (Exception e) {
            if (progressPublisher != null) {
                progressPublisher.publish(ToolProgress.completed("replace_in_file", relativePath));
            }
            logger.error("replace_in_file failed: {}", relativePath, e);
            return "{\"error\":\"Failed to write file: " + e.getMessage() + "\"}";
        }
    }

    // ========== Path resolution ==========

    // 解析路径对应的项目名
    private String resolveProjectName(String relativePath) {
        String projectName = getCurrentProjectName();
        if (projectName == null || projectName.isEmpty()) {
            projectName = inferProjectFromPath(relativePath);
        }
        return projectName;
    }

    // 检查路径是否为绝对路径
    private boolean isAbsolutePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return new File(path).isAbsolute();
    }

    // 返回绝对路径错误的统一方法
    private String absolutePathError(String path) {
        // 从绝对路径中提取项目后的相对路径部分作为示例
        String relativeHint = "";
        if (path != null && path.contains("/")) {
            String afterWorkspace = path.substring(path.indexOf("/") + 1);
            if (afterWorkspace.contains("/")) {
                relativeHint = afterWorkspace.substring(afterWorkspace.indexOf("/") + 1);
            }
        }
        String hint = relativeHint.isEmpty() ? "src/main/java" : relativeHint;
        return "{\"error\":\"Absolute path not allowed. Use relative path from project root, e.g. '" + hint + "'. Received absolute path: '" + path + "'\"}";
    }

    // 标准化路径：去掉项目名前缀，转换成纯粹的相对路径
    private String normalizeRelativePath(String path, String projectName) {
        if (path == null || path.isEmpty() || projectName == null || projectName.isEmpty()) {
            return path;
        }
        // 去掉 path 开头的项目名/ 或 项目名\ 前缀
        String[] prefixes = {projectName + "/", projectName + "\\"};
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                return path.substring(prefix.length());
            }
        }
        return path;
    }

    private File resolveProjectFile(String relativePath) {
        // 如果是绝对路径，返回 null（调用方会返回错误）
        if (isAbsolutePath(relativePath)) {
            logger.warn("Absolute path received, expected relative path: {}", relativePath);
            return null;
        }

        String projectName = resolveProjectName(relativePath);
        if (projectName == null || projectName.isEmpty()) {
            return null;
        }

        // 标准化路径：去掉项目名前缀（如 "SQLExecutor/src/..." -> "src/..."）
        String cleanPath = normalizeRelativePath(relativePath, projectName);

        return fileOperationService.resolveProjectFile(projectName, cleanPath);
    }

    private String inferProjectFromPath(String relativePath) {
        String workspacePath = Constants.workspacePath;
        File wsDir = new File(workspacePath);
        if (wsDir.exists() && wsDir.isDirectory()) {
            File[] projects = wsDir.listFiles(File::isDirectory);
            if (projects != null) {
                for (File proj : projects) {
                    String projName = proj.getName();
                    if (!projName.startsWith(".") && relativePath.startsWith(projName + "/")) {
                        return projName;
                    }
                }
            }
        }
        return null;
    }

    private String getProjectRelativePath(String absolutePath, String projectName) {
        if (projectName == null || projectName.isEmpty()) {
            throw new IllegalArgumentException("projectName is required for getProjectRelativePath");
        }
        // 使用项目名构建前缀，查找并提取相对路径
        String[] prefixes = {projectName + File.separator, projectName + "/"};
        for (String prefix : prefixes) {
            int idx = absolutePath.lastIndexOf(prefix);
            if (idx >= 0) {
                return absolutePath.substring(idx + prefix.length());
            }
        }
        throw new IllegalArgumentException("Cannot find project '" + projectName + "' in path: " + absolutePath);
    }

    private String toString(Object o) {
        return o == null ? "" : o.toString();
    }

    private String toJson(Object obj) {
        try {
            return JSON.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
