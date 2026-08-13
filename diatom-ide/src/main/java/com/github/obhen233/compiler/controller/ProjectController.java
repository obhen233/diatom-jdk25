package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.workspace.*;
import com.github.obhen233.compiler.repository.IdeSettingRepository;
import com.github.obhen233.compiler.service.ClasspathBuilder;
import com.github.obhen233.compiler.service.workspace.ProjectManagementService;
import com.github.obhen233.compiler.service.workspace.ProjectTreeService;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.jdtls.JdtCoreService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.*;

@CrossOrigin
@RestController
@RequestMapping("/workspace")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Project / 项目管理", description = "Project CRUD, file tree, library management / 项目CRUD、文件树、库管理")
public class ProjectController {

    @Autowired
    private ProjectManagementService projectManagementService;

    @Autowired
    private ProjectTreeService projectTreeService;

    @Autowired
    private ClasspathBuilder classpathBuilder;

    @Autowired(required = false)
    private IdeSettingRepository settingRepo;

    // ==================== Project Management / 项目管理 ====================

    @GetMapping("/projects")
    @Operation(summary = "List all projects / 列出所有项目", description = "Returns a list of all projects in the workspace / 返回工作空间中的所有项目")
    public Map<String, Object> listProjects() {
        return projectManagementService.listProjects();
    }

    @PostMapping("/projects")
    @Operation(summary = "Create new project / 创建新项目", description = "Creates a new Java project with specified configuration / 使用指定配置创建新的Java项目")
    public ApiResponse<Map<String, Object>> createProject(@RequestBody CreateProjectRequest body) {
        try {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("name", body.name());
            map.put("jdkVersion", body.jdkVersion());
            map.put("buildTool", body.buildTool());
            map.put("dependencies", body.dependencies());
            map.put("options", body.options());
            return ApiResponse.ok(projectManagementService.createProject(map));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PutMapping("/projects/{name}")
    @Operation(summary = "Rename project / 重命名项目", description = "Renames an existing project / 重命名现有项目")
    public ApiResponse<Map<String, Object>> renameProject(@PathVariable String name, @RequestBody RenameProjectRequest body) {
        try {
            return ApiResponse.ok(projectManagementService.renameProject(name, body.newName()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @DeleteMapping("/projects/{name}")
    @Operation(summary = "Delete project / 删除项目", description = "Deletes a project. Mode can be 'remove' (delete files) or 'archive' (soft delete) / 删除项目，模式可为'remove'（删除文件）或'archive'（软删除）")
    public Map<String, Object> deleteProject(@PathVariable String name,
                                             @RequestParam(defaultValue = "remove") String mode) {
        return projectManagementService.deleteProject(name, mode);
    }

    @GetMapping("/projects-removed")
    @Operation(summary = "List removed projects / 列出已删除项目", description = "Returns list of soft-deleted projects that can be restored / 返回可恢复的软删除项目列表")
    public Map<String, Object> listRemovedProjects() {
        return projectManagementService.listRemovedProjects();
    }

    @PostMapping("/projects-restore")
    @Operation(summary = "Restore deleted project / 恢复已删除项目", description = "Restores a previously soft-deleted project / 恢复之前软删除的项目")
    public ApiResponse<Map<String, Object>> restoreProject(@RequestBody RestoreProjectRequest body) {
        try {
            return ApiResponse.ok(projectManagementService.restoreProject(body.dirName()));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ==================== Project Tree ====================

    @GetMapping("/projects/{name}/tree")
    public Map<String, Object> getProjectTree(@PathVariable String name,
                                              @RequestParam(defaultValue = "-1") int depth,
                                              @RequestParam(required = false) String path) {
        return projectTreeService.getProjectTree(name, depth, path);
    }

    // ==================== Library Management ====================

    @GetMapping("/projects/{name}/libs")
    @Operation(summary = "List project libraries / 列出项目库", description = "Lists JAR files and dependencies in the project / 列出项目中的JAR文件和依赖")
    public ApiResponse<Map<String, Object>> listLibs(@PathVariable String name) {
        try {
            File projectDir = new File(Constants.workspacePath, name);
            if (!projectDir.exists()) return ApiResponse.fail(I18n.get("project.notFound"));
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            String projectType = classpathBuilder.detectProjectType(projectDir);
            result.put("projectType", projectType);

            List<Map<String, String>> jars = new ArrayList<>();
            File libDir = new File(projectDir, "lib");
            if (libDir.exists() && libDir.isDirectory()) {
                File[] jarFiles = libDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
                if (jarFiles != null) {
                    Arrays.sort(jarFiles, Comparator.comparing(File::getName));
                    for (File jar : jarFiles) {
                        Map<String, String> j = new HashMap<>();
                        j.put("name", jar.getName());
                        j.put("size", formatSize(jar.length()));
                        j.put("source", "lib");
                        jars.add(j);
                    }
                }
            }
            result.put("jars", jars);

            if ("maven".equals(projectType)) {
                result.put("dependencies", parseMavenDependencies(new File(projectDir, "pom.xml")));
            }
            if ("gradle".equals(projectType)) {
                result.put("dependencies", parseGradleDependencies(new File(projectDir, "build.gradle")));
            }
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/projects/{name}/libs")
    @Operation(summary = "Upload JAR library / 上传JAR库", description = "Uploads a JAR file to the project lib directory / 上传JAR文件到项目lib目录")
    public ApiResponse<Map<String, Object>> uploadJar(@PathVariable String name,
                                         @RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) return ApiResponse.fail(I18n.get("file.empty"));
            String originalName = file.getOriginalFilename();
            if (originalName == null || !originalName.toLowerCase().endsWith(".jar")) {
                return ApiResponse.fail(I18n.get("file.onlyJarAllowed"));
            }
            File projectDir = new File(Constants.workspacePath, name);
            if (!projectDir.exists()) return ApiResponse.fail(I18n.get("project.notFound"));
            File libDir = new File(projectDir, "lib");
            if (!libDir.exists()) libDir.mkdirs();
            File target = new File(libDir, originalName);
            file.transferTo(target);
            return ApiResponse.ok();
        } catch (Exception e) {
            return ApiResponse.fail(I18n.get("file.uploadFail", e.getMessage()));
        }
    }

    @DeleteMapping("/projects/{name}/libs/{jarName}")
    @Operation(summary = "Delete JAR library / 删除JAR库", description = "Deletes a JAR file from the project lib directory / 从项目lib目录中删除JAR文件")
    public ApiResponse<Map<String, Object>> deleteJar(@PathVariable String name, @PathVariable String jarName) {
        try {
            File jar = new File(Constants.workspacePath + File.separator + name + File.separator + "lib", jarName);
            if (!jar.exists()) return ApiResponse.fail(I18n.get("file.jarNotFound"));
            return jar.delete() ? ApiResponse.ok() : ApiResponse.fail(I18n.get("file.deleteFail"));
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ==================== Code Generation ====================

    @PostMapping("/projects/{name}/generate-overrides")
    @Operation(summary = "Generate method overrides / 生成方法覆盖", description = "Generates method override stubs from source code / 从源代码生成方法覆盖存根")
    public ApiResponse<Map<String, Object>> generateOverrides(@PathVariable String name, @RequestBody java.util.Map<String, String> body) {
        try {
            String source = body.get("source");
            if (source == null || source.trim().isEmpty()) return ApiResponse.fail(I18n.get("common.sourceEmpty"));

            String[] classpath = classpathBuilder.buildClasspathArray(name);

            JdtCoreService jdtCoreService = new JdtCoreService();
            List<JdtCoreService.MethodStub> stubs = jdtCoreService.generateMethodStubs(source, classpath);

            List<Map<String, Object>> methods = new ArrayList<>();
            for (JdtCoreService.MethodStub stub : stubs) {
                Map<String, Object> m = new HashMap<>();
                m.put("code", stub.getCode());
                m.put("context", stub.getContext());
                m.put("insertOffset", stub.getInsertOffset());
                methods.add(m);
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("methods", methods);
            return ApiResponse.ok(resultData);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ==================== Utility Methods ====================

    private List<Map<String, String>> parseMavenDependencies(File pomFile) {
        List<Map<String, String>> deps = new ArrayList<>();
        if (!pomFile.exists()) return deps;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(pomFile.toPath()), "UTF-8");
            java.util.regex.Pattern depPattern = java.util.regex.Pattern.compile(
                "<dependency>\\s*" +
                "<groupId>([^<]+)</groupId>\\s*" +
                "<artifactId>([^<]+)</artifactId>\\s*" +
                "(?:<version>([^<]*)</version>\\s*)?" +
                "(?:<scope>([^<]*)</scope>\\s*)?" +
                "</dependency>", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = depPattern.matcher(content);
            while (m.find()) {
                Map<String, String> dep = new HashMap<>();
                dep.put("groupId", m.group(1).trim());
                dep.put("artifactId", m.group(2).trim());
                dep.put("version", m.group(3) != null ? m.group(3).trim() : "");
                dep.put("scope", m.group(4) != null ? m.group(4).trim() : "compile");
                deps.add(dep);
            }
        } catch (Exception e) {
            // ignore parse errors
        }
        return deps;
    }

    private List<Map<String, String>> parseGradleDependencies(File gradleFile) {
        List<Map<String, String>> deps = new ArrayList<>();
        if (!gradleFile.exists()) return deps;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(gradleFile.toPath()), "UTF-8");
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(implementation|compile|api|testImplementation|testCompile|runtimeOnly|compileOnly)" +
                "\\s+['\"]([^'\"]+)['\"]");
            java.util.regex.Matcher m = p.matcher(content);
            while (m.find()) {
                String scope = m.group(1);
                String coord = m.group(2);
                String[] parts = coord.split(":");
                Map<String, String> dep = new HashMap<>();
                dep.put("groupId", parts.length > 0 ? parts[0] : "");
                dep.put("artifactId", parts.length > 1 ? parts[1] : "");
                dep.put("version", parts.length > 2 ? parts[2] : "");
                dep.put("scope", scope);
                deps.add(dep);
            }
        } catch (Exception e) {
            // ignore
        }
        return deps;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
