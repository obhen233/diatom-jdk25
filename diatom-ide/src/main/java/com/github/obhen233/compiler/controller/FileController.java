package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.workspace.*;
import com.github.obhen233.compiler.service.ClasspathBuilder;
import com.github.obhen233.compiler.service.workspace.FileOperationService;
import com.github.obhen233.compiler.service.workspace.PackageService;
import com.github.obhen233.compiler.service.workspace.ProjectTreeService;
import com.github.obhen233.compiler.i18n.I18n;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/workspace")
@io.swagger.v3.oas.annotations.tags.Tag(name = "File / 文件操作", description = "File, directory and package CRUD operations / 文件、目录和包CRUD操作")
public class FileController {

    @Autowired
    private FileOperationService fileOperationService;

    @Autowired
    private PackageService packageService;

    @Autowired
    private ClasspathBuilder classpathBuilder;

    @Autowired
    private ProjectTreeService projectTreeService;

    // ==================== File/Directory/Package CRUD / 文件目录操作 ====================

    @GetMapping("/projects/{name}/file")
    @Operation(summary = "Read file / 读取文件", description = "Reads the content of a file in the project / 读取项目中文件的内容")
    public Map<String, Object> readFile(@PathVariable String name, @RequestParam String path) {
        return fileOperationService.readFile(name, path);
    }

    @PutMapping("/projects/{name}/file")
    @Operation(summary = "Save file / 保存文件", description = "Saves content to a file in the project / 保存内容到项目中的文件")
    public ApiResponse<Map<String, Object>> saveFile(@PathVariable String name, @RequestBody SaveFileRequest body) {
        try {
            return ApiResponse.ok(fileOperationService.saveFile(name, body.path(), body.content()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/projects/{name}/file")
    @Operation(summary = "Create file / 创建文件", description = "Creates a new file in the project / 在项目中创建新文件")
    public ApiResponse<Map<String, Object>> createFile(@PathVariable String name, @RequestBody CreateFileRequest body) {
        try {
            String parentPath = body.parentPath() != null ? body.parentPath() : "";
            return ApiResponse.ok(fileOperationService.createFile(name, parentPath, body.fileName()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/projects/{name}/directory")
    @Operation(summary = "Create directory / 创建目录", description = "Creates a new directory in the project / 在项目中创建新目录")
    public ApiResponse<Map<String, Object>> createDirectory(@PathVariable String name, @RequestBody CreateDirectoryRequest body) {
        try {
            String parentPath = body.parentPath() != null ? body.parentPath() : "";
            return ApiResponse.ok(fileOperationService.createDirectory(name, parentPath, body.dirName()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/projects/{name}/package")
    @Operation(summary = "Create package / 创建包", description = "Creates a new Java package / 创建新的Java包")
    public ApiResponse<Map<String, Object>> createPackage(@PathVariable String name, @RequestBody CreatePackageRequest body) {
        try {
            String parentPath = body.parentPath() != null ? body.parentPath() : "src";
            return ApiResponse.ok(packageService.createPackage(name, parentPath, body.packageName()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/projects/{name}/dir")
    public Map<String, Object> listDirectory(@PathVariable String name,
                                            @RequestParam String path,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size,
                                            @RequestParam(defaultValue = "name") String sort,
                                            @RequestParam(defaultValue = "asc") String order) {
        return projectTreeService.listDirectory(name, path, page, size, sort, order);
    }

    @PutMapping("/projects/{name}/rename")
    @Operation(summary = "Rename file or directory / 重命名文件或目录", description = "Renames a file or directory / 重命名文件或目录")
    public ApiResponse<Map<String, Object>> renameFileOrDir(@PathVariable String name, @RequestBody RenameFileRequest body) {
        try {
            return ApiResponse.ok(fileOperationService.renameFileOrDir(name, body.path(), body.newName()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @DeleteMapping("/projects/{name}/path")
    public Map<String, Object> deleteFileOrDir(@PathVariable String name, @RequestParam String path) {
        return fileOperationService.deleteFileOrDir(name, path);
    }

    /**
     * Compile a project using its build tool (mvn compile / gradle compileJava).
     * Returns compile output and success status.
     */
    @PostMapping("/projects/{name}/compile")
    @Operation(summary = "Compile project / 编译项目", description = "Compiles the project using Maven or Gradle / 使用Maven或Gradle编译项目")
    public Map<String, Object> compileProject(@PathVariable String name) {
        Map<String, Object> result = new HashMap<>();
        File projectDir = new File(Constants.workspacePath, name);
        if (!projectDir.exists()) {
            result.put("success", false);
            result.put("error", I18n.get("project.notFound"));
            return result;
        }

        String projectType = classpathBuilder.detectProjectType(projectDir);
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

        try {
            ProcessBuilder pb;
            if ("maven".equals(projectType)) {
                pb = new ProcessBuilder(isWin ? "mvn.cmd" : "mvn", "compile", "-DskipTests", "-q");
            } else if ("gradle".equals(projectType)) {
                pb = new ProcessBuilder(isWin ? "gradle.bat" : "gradle", "compileJava", "-q");
            } else {
                // Plain project — no build tool, skip
                result.put("success", true);
                result.put("type", "plain");
                result.put("output", "");
                return result;
            }

            pb.directory(projectDir);
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            result.put("success", exitCode == 0);
            result.put("type", projectType);
            result.put("output", output.toString().trim());
            result.put("exitCode", exitCode);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
