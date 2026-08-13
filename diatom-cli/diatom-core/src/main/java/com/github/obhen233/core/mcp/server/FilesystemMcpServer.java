package com.github.obhen233.core.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.obhen233.core.mcp.McpServer;
import com.github.obhen233.core.tool.AuthorizedPathManager;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.util.JsonUtils;
import com.github.obhen233.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class FilesystemMcpServer implements McpServer {
    private static final Logger logger = LoggerFactory.getLogger(FilesystemMcpServer.class);
    private final ObjectMapper mapper = JsonUtils.getMapper();

    private final String workspaceDir;
    private final AuthorizedPathManager authManager;
    private final boolean allowExternal;
    private final BufferedReader reader;
    private final PrintWriter writer;

    public FilesystemMcpServer(String workspaceDir) {
        this(workspaceDir, null, false);
    }

    public FilesystemMcpServer(String workspaceDir, AuthorizedPathManager authManager) {
        this(workspaceDir, authManager, false);
    }

    public FilesystemMcpServer(String workspaceDir, AuthorizedPathManager authManager, boolean allowExternal) {
        this.workspaceDir = workspaceDir;
        this.authManager = authManager;
        this.allowExternal = allowExternal;
        this.reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(System.out, true);
    }

    @Override
    public String getName() {
        return "filesystem";
    }

    @Override
    public String getDescription() {
        return "Filesystem server providing resource access to local files";
    }

    @Override
    public Map<String, Resource> listResources() {
        Map<String, Resource> resources = new HashMap<>();
        Path workspace = Paths.get(workspaceDir);

        try {
            if (Files.exists(workspace) && Files.isDirectory(workspace)) {
                listResourcesRecursive(workspace, workspace, resources);
            }
        } catch (IOException e) {
            logger.error("Error listing resources", e);
        }

        return resources;
    }

    private void listResourcesRecursive(Path base, Path current, Map<String, Resource> resources) throws IOException {
        try (Stream<Path> list = Files.list(current)) {
            list.forEach(p -> {
                String uri = base.relativize(p).toString().replace('\\', '/');
                String mimeType = Files.isDirectory(p) ? "inode/directory" : getMimeType(p);
                Resource resource = new Resource(
                    "file://" + uri,
                    p.getFileName().toString(),
                    mimeType,
                    Files.isDirectory(p) ? "Directory" : "File: " + p
                );
                resources.put(uri, resource);

                if (Files.isDirectory(p) && !p.getFileName().toString().startsWith(".")) {
                    try {
                        listResourcesRecursive(base, p, resources);
                    } catch (IOException e) {
                        logger.warn("Cannot access: {}", p);
                    }
                }
            });
        }
    }

    @Override
    public String readResource(String uri) {
        try {
            if (uri.startsWith("file://")) {
                String path = uri.substring(7);
                return readFileContent(path, 1, 1000);
            }
            return "{\"error\": \"Unsupported URI scheme\"}";
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String getMimeType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".java")) return "text/x-java";
        if (name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".ts")) return "text/typescript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "text/yaml";
        if (name.endsWith(".sql")) return "text/x-sql";
        if (name.endsWith(".sh")) return "application/x-sh";
        if (name.endsWith(".bat") || name.endsWith(".cmd")) return "application/x-batch";
        if (name.endsWith(".properties")) return "text/properties";
        if (name.endsWith(".jar")) return "application/java-archive";
        if (name.endsWith(".class")) return "application/java-vm";
        return "application/octet-stream";
    }

    private Path resolvePath(String path) {
        Path p = Paths.get(path);
        if (!p.isAbsolute()) {
            p = Paths.get(workspaceDir, path).normalize();
        }
        Path workspace = Paths.get(workspaceDir).normalize();

        // If allowExternal is true, allow any path
        if (allowExternal) {
            return p;
        }

        // Check if path is within workspace
        if (p.startsWith(workspace)) {
            return p;
        }

        // Check if path is authorized
        if (authManager != null && authManager.isAuthorized(p.toString())) {
            return p;
        }

        throw new RuntimeException("Access denied: Path outside workspace");
    }

    private String readFile(JsonNode arguments) throws IOException {
        String path = arguments.has("path") ? arguments.get("path").asText() : "";
        int offset = arguments.has("offset") ? arguments.get("offset").asInt(1) : 1;
        int limit = arguments.has("limit") ? arguments.get("limit").asInt(1000) : 1000;
        return readFileContent(path, offset, limit);
    }

    private String readFileContent(String path, int offset, int limit) throws IOException {
        Path filePath = resolvePath(path);
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return "Error: File not found: " + path;
        }

        // Count total lines
        long totalLines;
        try (BufferedReader counter = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            totalLines = 0;
            while (counter.readLine() != null) {
                totalLines++;
            }
        }

        int startLine = Math.max(1, offset);
        int maxLimit = Math.min(limit, 5000);
        String filename = filePath.getFileName().toString();
        int endLine = (int) Math.min(totalLines, startLine + maxLimit - 1);

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(filename).append(" (lines ").append(startLine).append("-").append(endLine).append(" of ").append(totalLines).append(") ===\n");

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (lineNum < startLine) continue;
                if (lineNum > startLine + maxLimit - 1) {
                    sb.append("... (truncated, ").append(totalLines - endLine).append(" more lines)");
                    break;
                }
                sb.append(String.format("%4d", lineNum)).append(": ").append(line).append("\n");
            }
        }

        return sb.toString();
    }

    public void start() {
        logger.info("Filesystem MCP Server started with workspace: {}", workspaceDir);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                handleRequest(line);
            }
        } catch (IOException e) {
            logger.error("Server error", e);
        }
    }

    private void handleRequest(String request) {
        try {
            JsonNode json = mapper.readTree(request);
            String method = json.get("method").asText();
            int id = json.has("id") ? json.get("id").asInt() : 0;

            JsonNode params = json.has("params") ? json.get("params") : mapper.createObjectNode();

            ObjectNode result = mapper.createObjectNode();

            switch (method) {
                case "initialize":
                    result = handleInitialize();
                    break;
                case "tools/list":
                    result = handleListTools();
                    break;
                case "tools/call":
                    result = handleCallTool(params);
                    break;
                case "resources/list":
                    result = handleListResources();
                    break;
                case "resources/read":
                    result = handleReadResource(params);
                    break;
                case "prompts/list":
                    result = handleListPrompts();
                    break;
                default:
                    result.put("error", "Unknown method: " + method);
            }

            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("result", result);
            response.put("id", id);
            writer.println(mapper.writeValueAsString(response));

        } catch (Exception e) {
            logger.error("Error handling request", e);
            try {
                ObjectNode error = mapper.createObjectNode();
                error.put("error", e.getMessage());
                ObjectNode response = mapper.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("error", error);
                writer.println(mapper.writeValueAsString(response));
            } catch (Exception ex) {
                logger.error("Failed to write error response", ex);
            }
        }
    }

    private ObjectNode handleInitialize() {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.put("tools", true);
        capabilities.put("resources", true);
        capabilities.put("prompts", true);
        result.set("capabilities", capabilities);
        result.put("protocolVersion", "2024-11-05");
        return result;
    }

    @Override
    public Map<String, Tool> listTools() {
        Map<String, Tool> tools = new HashMap<>();
        Tool readFile = new Tool("read_file", "[MCP Filesystem] Read the contents of a file. All lines are prefixed with line numbers. Large files auto-truncate at 1000 lines. Use offset+limit to paginate, or search first then read specific sections.",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\", \"description\": \"Relative or absolute file path\"}, \"offset\": {\"type\": \"integer\", \"description\": \"Starting line number (1-based, default: 1)\"}, \"limit\": {\"type\": \"integer\", \"description\": \"Maximum lines to read (default: 1000, max: 5000)\"}}, \"required\": [\"path\"]}");
        readFile.setReadOnly(true);
        readFile.setRequiresConfirmation(false);
        readFile.setRiskLevel("none");
        tools.put("read_file", readFile);

        Tool writeFile = new Tool("write_file", "[MCP Filesystem] Write content to a file",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"content\": {\"type\": \"string\"}}}");
        writeFile.setReadOnly(false);
        writeFile.setRequiresConfirmation(true);
        writeFile.setRiskLevel("medium");
        writeFile.setConfirmationTemplate("tool_confirm_write_file");
        tools.put("write_file", writeFile);

        Tool listDir = new Tool("list_directory", "[MCP Filesystem] List files in a directory",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}");
        listDir.setReadOnly(true);
        listDir.setRequiresConfirmation(false);
        listDir.setRiskLevel("none");
        tools.put("list_directory", listDir);

        Tool createDir = new Tool("create_directory", "[MCP Filesystem] Create a directory",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}");
        createDir.setReadOnly(false);
        createDir.setRequiresConfirmation(true);
        createDir.setRiskLevel("medium");
        createDir.setConfirmationTemplate("tool_confirm_create_directory");
        tools.put("create_directory", createDir);

        Tool deleteFile = new Tool("delete_file", "[MCP Filesystem] Delete a file",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}");
        deleteFile.setReadOnly(false);
        deleteFile.setRequiresConfirmation(true);
        deleteFile.setRiskLevel("medium");
        deleteFile.setConfirmationTemplate("tool_confirm_delete_file");
        tools.put("delete_file", deleteFile);

        Tool exists = new Tool("exists", "[MCP Filesystem] Check if a path exists",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}");
        exists.setReadOnly(true);
        exists.setRequiresConfirmation(false);
        exists.setRiskLevel("none");
        tools.put("exists", exists);

        Tool searchFiles = new Tool("search_files", "[MCP Filesystem] Search for files matching pattern(s). " +
            "Batch multiple patterns with | separator (e.g. \"*.java|*.xml|*.jsp\")",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"pattern\": {\"type\": \"string\", \"description\": \"Glob pattern. Batch multiple with | separator, e.g. '*.java|*.xml'\"}}}");
        searchFiles.setReadOnly(true);
        searchFiles.setRequiresConfirmation(false);
        searchFiles.setRiskLevel("none");
        tools.put("search_files", searchFiles);

        return tools;
    }

    private ObjectNode handleListTools() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = mapper.createArrayNode();
        tools.add(createToolObject("read_file", "Read the contents of a file. All lines are prefixed with line numbers. Large files auto-truncate at 1000 lines. Use offset+limit to paginate, or search first then read specific sections.",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\", \"description\": \"Relative or absolute file path\"}, \"offset\": {\"type\": \"integer\", \"description\": \"Starting line number (1-based, default: 1)\"}, \"limit\": {\"type\": \"integer\", \"description\": \"Maximum lines to read (default: 1000, max: 5000)\"}}, \"required\": [\"path\"]}"));
        tools.add(createToolObject("write_file", "Write content to a file (creates or overwrites)",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"content\": {\"type\": \"string\"}}}"));
        tools.add(createToolObject("list_directory", "List files and directories in a path",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\", \"description\": \"Directory path\"}}}"));
        tools.add(createToolObject("create_directory", "Create a directory",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}"));
        tools.add(createToolObject("delete_file", "Delete a file",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}"));
        tools.add(createToolObject("exists", "Check if a file or directory exists",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}"));
        tools.add(createToolObject("search_files", "Search for files matching pattern(s). Batch multiple with | separator (e.g. \"*.java|*.xml|*.jsp\")",
            "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}, \"pattern\": {\"type\": \"string\", \"description\": \"Glob pattern. Batch multiple with | separator\"}}}"));
        result.set("tools", tools);
        return result;
    }

    private ObjectNode createToolObject(String name, String description, String schema) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", "[MCP Filesystem] " + description);
        try {
            JsonNode inputSchema = mapper.readTree(schema);
            tool.set("inputSchema", inputSchema);
        } catch (Exception e) {
            tool.set("inputSchema", mapper.createObjectNode());
        }
        return tool;
    }

    @Override
    public String callTool(String toolName, String args) {
        try {
            JsonNode arguments = mapper.readTree(args);
            switch (toolName) {
                case "read_file":
                    return readFile(arguments);
                case "write_file":
                    return writeFile(arguments.get("path").asText(), arguments.get("content").asText());
                case "list_directory":
                    return listDirectory(arguments.has("path") ? arguments.get("path").asText() : "");
                case "create_directory":
                    return createDirectory(arguments.get("path").asText());
                case "delete_file":
                    return deleteFile(arguments.get("path").asText());
                case "exists":
                    return exists(arguments.get("path").asText());
                case "search_files":
                    return searchFiles(arguments.has("path") ? arguments.get("path").asText() : "",
                                       arguments.get("pattern").asText());
                default: {
                    ObjectNode error = mapper.createObjectNode();
                    error.put("error", "Unknown tool: " + toolName);
                    return mapper.writeValueAsString(error);
                }
            }
        } catch (Exception e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("error", "Unknown tool error: " + e.getMessage());
            try {
                return mapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"error\":\"Internal error\"}";
            }
        }
    }

    private ObjectNode handleListResources() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode resources = mapper.createArrayNode();
        Map<String, Resource> resourceMap = listResources();
        if (resourceMap != null) {
            for (Map.Entry<String, Resource> entry : resourceMap.entrySet()) {
                ObjectNode res = mapper.createObjectNode();
                Resource r = entry.getValue();
                res.put("uri", r.getUri());
                res.put("name", r.getName());
                res.put("mimeType", r.getMimeType());
                res.put("description", r.getDescription());
                resources.add(res);
            }
        }
        result.set("resources", resources);
        return result;
    }

    private ObjectNode handleReadResource(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        String uri = params.has("uri") ? params.get("uri").asText() : "";
        String content = readResource(uri);
        result.put("contents", content);
        return result;
    }

    @Override
    public Map<String, Prompt> listPrompts() {
        return null;
    }

    private ObjectNode handleListPrompts() {
        ObjectNode result = mapper.createObjectNode();
        result.set("prompts", mapper.createArrayNode());
        return result;
    }

    private ObjectNode handleCallTool(JsonNode params) {
        String toolName = params.has("name") ? params.get("name").asText() : "";
        JsonNode args = params.has("arguments") ? params.get("arguments") : mapper.createObjectNode();

        String result = callTool(toolName, args.toString());

        ObjectNode resultObj = mapper.createObjectNode();
        ObjectNode content = mapper.createObjectNode();
        content.put("type", "text");
        content.put("text", result);
        ArrayNode contents = mapper.createArrayNode();
        contents.add(content);
        resultObj.set("content", contents);
        return resultObj;
    }

    private String writeFile(String path, String content) throws IOException {
        // Sanitize content: strip read_file header "=== filename (lines X-Y of Z) ==="
        // and truncation marker "... (truncated at line X, total ~Y lines)"
        // that the model may accidentally include when copying from read_file output.
        String sanitized = sanitizeReadFileHeader(content);

        Path filePath = resolvePath(path);
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, sanitized.getBytes());
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("path", filePath.toString());
        return mapper.writeValueAsString(result);
    }

    /**
     * Strip read_file header/footer markers from content.
     * Models sometimes copy the "=== filename (lines X-Y of Z) ===" header
     * and "... (truncated...)" footer from read_file output into write_file calls.
     */
    private String sanitizeReadFileHeader(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // Strip leading header: "=== filename (lines X-Y of Z) ==="
        int firstNewline = content.indexOf('\n');
        if (firstNewline > 0) {
            String firstLine = content.substring(0, firstNewline).trim();
            if (firstLine.startsWith("=== ") && firstLine.contains("(lines ") && firstLine.endsWith(") ===")) {
                content = content.substring(firstNewline + 1);
            }
        }
        // Strip trailing truncation: "... (truncated at line X, total ~Y lines)"
        int lastNewline = content.lastIndexOf('\n');
        if (lastNewline >= 0) {
            String lastLine = content.substring(lastNewline + 1).trim();
            if (lastLine.startsWith("... (truncated at line ") && lastLine.contains("lines)")) {
                content = content.substring(0, lastNewline);
            }
        }
        return content;
    }

    private String listDirectory(String path) throws IOException {
        Path dirPath = resolvePath(path.isEmpty() ? "." : path);
        if (!Files.exists(dirPath)) {
            ObjectNode error = mapper.createObjectNode();
            error.put("error", "Directory not found: " + path);
            return mapper.writeValueAsString(error);
        }
        if (!Files.isDirectory(dirPath)) {
            ObjectNode error = mapper.createObjectNode();
            error.put("error", "Not a directory: " + path);
            return mapper.writeValueAsString(error);
        }

        ArrayNode entries = mapper.createArrayNode();
        try (Stream<Path> list = Files.list(dirPath)) {
            list.forEach(p -> {
                ObjectNode entry = mapper.createObjectNode();
                entry.put("name", p.getFileName().toString());
                entry.put("type", Files.isDirectory(p) ? "directory" : "file");
                entry.put("path", p.toString());
                try {
                    entry.put("size", Files.size(p));
                } catch (IOException e) {
                    entry.put("size", -1);
                }
                entries.add(entry);
            });
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("entries", entries);
        result.put("path", dirPath.toString());
        return mapper.writeValueAsString(result);
    }

    private String createDirectory(String path) throws IOException {
        Path dirPath = resolvePath(path);
        Files.createDirectories(dirPath);
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("path", dirPath.toString());
        return mapper.writeValueAsString(result);
    }

    private String deleteFile(String path) throws IOException {
        Path filePath = resolvePath(path);

        // 1. 禁止删除目录
        if (Files.isDirectory(filePath)) {
            ObjectNode error = mapper.createObjectNode();
            error.put("error", "Cannot delete directory: " + path + ". Use delete_directory instead.");
            return mapper.writeValueAsString(error);
        }

        // 2. 禁止删除符号链接（防止通过符号链接跨目录攻击）
        if (Files.isSymbolicLink(filePath)) {
            ObjectNode error = mapper.createObjectNode();
            error.put("error", "Cannot delete symbolic link: " + path);
            return mapper.writeValueAsString(error);
        }

        Files.deleteIfExists(filePath);
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("path", filePath.toString());
        return mapper.writeValueAsString(result);
    }

    private String exists(String path) {
        Path filePath = resolvePath(path);
        boolean isFile = Files.exists(filePath) && Files.isRegularFile(filePath);
        boolean isDir = Files.exists(filePath) && Files.isDirectory(filePath);
        ObjectNode result = mapper.createObjectNode();
        result.put("exists", isFile || isDir);
        result.put("isFile", isFile);
        result.put("isDirectory", isDir);
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"Failed to serialize\"}";
        }
    }

    private String searchFiles(String path, String pattern) throws IOException {
        Path searchPath = resolvePath(path.isEmpty() ? "." : path);
        java.util.List<String> matches = new java.util.concurrent.CopyOnWriteArrayList<>();

        if (!Files.exists(searchPath)) {
            ObjectNode error = mapper.createObjectNode();
            error.put("error", "Path not found: " + path);
            return mapper.writeValueAsString(error);
        }

        // 分割多模式（用 | 分隔）
        String[] patterns = pattern.split("\\|");
        Set<String> seen = ConcurrentHashMap.newKeySet();

        for (String singlePattern : patterns) {
            String trimmed = singlePattern.trim();
            if (trimmed.isEmpty()) continue;

            String regex = trimmed.replace("*", ".*").replace("?", ".");
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("regex:" + regex);

            try {
                Files.walk(searchPath, 10)
                    .parallel()
                    .filter(p -> {
                        try {
                            return Files.isRegularFile(p) && matcher.matches(p.getFileName())
                                && seen.add(p.toString());
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .forEach(p -> matches.add(p.toString()));
            } catch (Exception e) {
                ObjectNode error = mapper.createObjectNode();
                error.put("error", "Search failed for pattern '" + trimmed + "': " + e.getMessage());
                return mapper.writeValueAsString(error);
            }
        }

        ObjectNode result = mapper.createObjectNode();
        ArrayNode files = mapper.createArrayNode();
        matches.forEach(files::add);
        result.set("matches", files);
        result.put("count", matches.size());
        return mapper.writeValueAsString(result);
    }

    public static void main(String[] args) {
        String workspace = args.length > 0 ? args[0] : PathUtils.getWorkingDir();
        FilesystemMcpServer server = new FilesystemMcpServer(workspace);
        server.start();
    }
}