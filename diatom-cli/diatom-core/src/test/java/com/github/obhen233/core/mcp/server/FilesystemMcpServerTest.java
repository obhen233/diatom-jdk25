package com.github.obhen233.core.mcp.server;

import com.github.obhen233.core.tool.AuthorizedPathManager;
import com.github.obhen233.core.tool.Tool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * FilesystemMcpServer 测试用例
 * 测试 delete_file 及其他文件系统操作
 */
public class FilesystemMcpServerTest {

    private Path tempDir;
    private FilesystemMcpServer server;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("diatom-fs-test");
        AuthorizedPathManager authManager = new AuthorizedPathManager();
        server = new FilesystemMcpServer(tempDir.toString(), authManager, false);
    }

    @After
    public void tearDown() throws Exception {
        deleteDirectory(tempDir);
    }

    // ==================== delete_file Tests ====================

    @Test
    public void testDeleteFile_Success() throws IOException {
        // 创建测试文件
        Path testFile = tempDir.resolve("test.txt");
        Files.write(testFile, "test content".getBytes());
        assertTrue("File should exist before delete", Files.exists(testFile));

        // 删除文件
        String result = server.callTool("delete_file", "{\"path\":\"test.txt\"}");

        // 验证
        assertTrue("Result should indicate success", result.contains("\"success\":true"));
        assertFalse("File should not exist after delete", Files.exists(testFile));
    }

    @Test
    public void testDeleteFile_DirectoryRejected() throws IOException {
        // 创建测试目录
        Path testDir = tempDir.resolve("testdir");
        Files.createDirectories(testDir);

        // 尝试删除目录
        String result = server.callTool("delete_file", "{\"path\":\"testdir\"}");

        // 验证：应该被拒绝
        assertTrue("Result should indicate error", result.contains("\"error\""));
        assertTrue("Error should mention directory", result.contains("directory"));
        assertTrue("Directory should still exist", Files.exists(testDir));
    }

    @Test
    public void testDeleteFile_SymbolicLinkRejected() throws IOException {
        // 创建目标文件和符号链接
        Path targetFile = tempDir.resolve("target.txt");
        Files.write(targetFile, "target content".getBytes());
        Path link = tempDir.resolve("link.txt");

        // 符号链接在 Windows 上可能需要管理员权限
        try {
            Files.createSymbolicLink(link, targetFile);
        } catch (Exception e) {
            // 创建符号链接失败（Windows 权限问题），跳过此测试
            System.out.println("SKIP: Cannot create symbolic link on this system: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        // 尝试删除符号链接
        String result = server.callTool("delete_file", "{\"path\":\"link.txt\"}");

        // 验证：应该被拒绝
        assertTrue("Result should indicate error", result.contains("\"error\""));
        assertTrue("Error should mention symbolic link", result.toLowerCase().contains("symbolic"));
        assertTrue("Original file should still exist", Files.exists(targetFile));
        assertTrue("Symbolic link should still exist", Files.exists(link));
    }

    @Test
    public void testDeleteFile_FileNotFound() throws IOException {
        // 尝试删除不存在的文件
        String result = server.callTool("delete_file", "{\"path\":\"nonexistent.txt\"}");

        // 验证：deleteIfExists 应该返回成功（幂等性）
        assertTrue("Result should indicate success for non-existent file",
            result.contains("\"success\":true"));
    }

    @Test
    public void testDeleteFile_OutsideWorkspace() throws IOException {
        // 尝试删除 workspace 外的文件
        // 使用绝对路径指向临时目录外的文件
        Path outsideFile = Files.createTempFile("outside", ".txt");
        try {
            String result = server.callTool("delete_file",
                "{\"path\":\"" + outsideFile.toString().replace("\\", "\\\\") + "\"}");

            // 验证：应该被拒绝（错误消息可能包含 Access denied 或 Path outside workspace）
            assertTrue("Result should indicate error", result.contains("\"error\""));
            assertTrue("Error should mention access or path: " + result,
                result.toLowerCase().contains("access") || result.toLowerCase().contains("outside"));
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    public void testDeleteFile_WithSubdirectory() throws IOException {
        // 创建子目录中的文件
        Path subdir = tempDir.resolve("subdir");
        Files.createDirectories(subdir);
        Path testFile = subdir.resolve("test.txt");
        Files.write(testFile, "test content".getBytes());

        // 删除子目录中的文件
        String result = server.callTool("delete_file", "{\"path\":\"subdir/test.txt\"}");

        // 验证
        assertTrue("Result should indicate success", result.contains("\"success\":true"));
        assertFalse("File should not exist after delete", Files.exists(testFile));
    }

    // ==================== list_tools Tests ====================

    @Test
    public void testListTools_ContainsDeleteFile() {
        Map<String, Tool> tools = server.listTools();

        assertNotNull("Tools should not be null", tools);
        assertTrue("Should have delete_file tool", tools.containsKey("delete_file"));
    }

    @Test
    public void testDeleteFile_ToolDescription() {
        Map<String, Tool> tools = server.listTools();

        assertTrue("delete_file should have description",
            tools.get("delete_file").getDescription().contains("Delete"));
    }

    // ==================== exists Tests ====================

    @Test
    public void testExists_File() throws IOException {
        Path testFile = tempDir.resolve("exists.txt");
        Files.write(testFile, "content".getBytes());

        String result = server.callTool("exists", "{\"path\":\"exists.txt\"}");

        assertTrue("Result should indicate file exists", result.contains("\"exists\":true"));
        assertTrue("Result should indicate isFile", result.contains("\"isFile\":true"));
    }

    @Test
    public void testExists_Directory() throws IOException {
        Path testDir = tempDir.resolve("testdir");
        Files.createDirectories(testDir);

        String result = server.callTool("exists", "{\"path\":\"testdir\"}");

        assertTrue("Result should indicate directory exists", result.contains("\"exists\":true"));
        assertTrue("Result should indicate isDirectory", result.contains("\"isDirectory\":true"));
    }

    @Test
    public void testExists_NotFound() throws IOException {
        String result = server.callTool("exists", "{\"path\":\"nonexistent.txt\"}");

        assertTrue("Result should indicate not exists", result.contains("\"exists\":false"));
        assertTrue("Result should indicate not a file", result.contains("\"isFile\":false"));
    }

    // ==================== read_file Tests ====================

    @Test
    public void testReadFile_Success() throws IOException {
        String content = "Hello, World!";
        Path testFile = tempDir.resolve("read.txt");
        Files.write(testFile, content.getBytes());

        String result = server.callTool("read_file", "{\"path\":\"read.txt\"}");

        assertTrue("Result should contain file content", result.contains("Hello, World"));
    }

    @Test
    public void testReadFile_NotFound() {
        String result = server.callTool("read_file", "{\"path\":\"nonexistent.txt\"}");

        assertTrue("Result should indicate error", result.contains("Error") && result.contains("not found"));
    }

    // ==================== create_directory Tests ====================

    @Test
    public void testCreateDirectory_Success() throws IOException {
        String result = server.callTool("create_directory", "{\"path\":\"newdir\"}");

        assertTrue("Result should indicate success", result.contains("\"success\":true"));
        assertTrue("Directory should be created", Files.exists(tempDir.resolve("newdir")));
    }

    @Test
    public void testCreateDirectory_Nested() throws IOException {
        String result = server.callTool("create_directory", "{\"path\":\"parent/child\"}");
        assertTrue("Result should indicate success", result.contains("\"success\":true"));
        assertTrue("Nested directory should be created", Files.exists(tempDir.resolve("parent/child")));
    }

    // ==================== write_file Tests ====================

    @Test
    public void testWriteFile_Success() throws IOException {
        String content = "New content";
        String result = server.callTool("write_file",
            "{\"path\":\"newfile.txt\",\"content\":\"New content\"}");

        assertTrue("Result should indicate success", result.contains("\"success\":true"));
        assertTrue("File should be created with content",
            Files.exists(tempDir.resolve("newfile.txt")));
    }

    @Test
    public void testWriteFile_Overwrite() throws IOException {
        Path testFile = tempDir.resolve("overwrite.txt");
        Files.write(testFile, "Old content".getBytes());

        String result = server.callTool("write_file",
            "{\"path\":\"overwrite.txt\",\"content\":\"New content\"}");
        assertTrue("Result should indicate success", result.contains("\"success\":true"));
        String newContent = new String(Files.readAllBytes(testFile));
        assertEquals("File should have new content", "New content", newContent);
    }

    // ==================== Helper Methods ====================

    private void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // ignore
                    }
                });
        } catch (IOException e) {
            // ignore
        }
    }
}
