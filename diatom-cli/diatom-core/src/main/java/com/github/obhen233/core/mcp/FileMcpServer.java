package com.github.obhen233.core.mcp;

import com.github.obhen233.core.tool.Tool;

import java.util.Map;

/**
 * SPI interface for file operations that can be overridden by IDE implementations.
 * Provides project-aware file operations for multi-project workspaces.
 *
 * If not overridden, the core's default file operations will be used.
 */
public interface FileMcpServer extends McpServer {

    // ========== File Operations ==========

    /**
     * Read the contents of a file.
     */
    default String readFile(String path) {
        return "{\"error\": \"read_file not implemented\"}";
    }

    /**
     * Write content to a file (creates or overwrites).
     */
    default String writeFile(String path, String content) {
        return "{\"error\": \"write_file not implemented\"}";
    }

    /**
     * List files and directories in a path.
     */
    default String listDirectory(String path) {
        return "{\"error\": \"list_directory not implemented\"}";
    }

    /**
     * Create a directory.
     */
    default String createDirectory(String path) {
        return "{\"error\": \"create_directory not implemented\"}";
    }

    /**
     * Delete a file.
     */
    default String deleteFile(String path) {
        return "{\"error\": \"delete_file not implemented\"}";
    }

    /**
     * Check if a file or directory exists.
     */
    default String exists(String path) {
        return "{\"error\": \"exists not implemented\"}";
    }

    /**
     * Search for files matching a pattern.
     */
    default String searchFiles(String path, String pattern) {
        return "{\"error\": \"search_files not implemented\"}";
    }

    /**
     * Replace a specific string in a file.
     * IDE implementations should use project context to resolve paths correctly.
     */
    default String replaceInFile(String path, String oldStr, String newStr) {
        return "{\"error\": \"replace_in_file not implemented\"}";
    }

    // ========== Project Context ==========

    /**
     * Get the current project name for file operations.
     * Return null or empty if no project is active.
     */
    default String getCurrentProjectName() {
        return null;
    }

    // ========== Tool Registration ==========

    /**
     * Override to provide custom tool definitions.
     * If not overridden, core's default tools will be used.
     */
    @Override
    default Map<String, Tool> listTools() {
        return null;
    }

    /**
     * Override to handle tool calls.
     * If not overridden, individual methods will be called directly.
     */
    @Override
    default String callTool(String name, String args) {
        return "{\"error\": \"callTool not implemented, use individual methods\"}";
    }
}
