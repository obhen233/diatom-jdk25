package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.compiler.decompile.DecompileService;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.decompile.DecompileClassRequest;
import com.github.obhen233.compiler.dto.decompile.JarInfoRequest;
import com.github.obhen233.compiler.dto.decompile.JarRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/workspace/decompile")
@Tag(name = "Decompile / 反编译", description = "Java class and JAR decompilation / Java类和JAR反编译")
public class DecompileController {

    @Autowired
    private DecompileService decompileService;

    /**
     * List all .class entries in a jar file
     */
    @PostMapping("/list-classes")
    @Operation(summary = "List classes in JAR / 列出JAR中的类", description = "Lists all class entries inside a JAR file / 列出JAR文件中的所有类条目")
    public ApiResponse<List<String>> listClasses(@RequestBody JarRequest body) {
        try {
            String jarPath = body.jarPath();
            if (jarPath == null || jarPath.trim().isEmpty()) {
                return ApiResponse.fail(I18n.get("common.paramInvalid"));
            }
            List<String> classes = decompileService.listClasses(jarPath);
            return ApiResponse.ok(classes);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * Decompile a single class from a jar file
     */
    @PostMapping("/class")
    @Operation(summary = "Decompile single class / 反编译单个类", description = "Decompiles a single class from a JAR file / 从JAR文件中反编译单个类")
    public ApiResponse<String> decompileClass(@RequestBody DecompileClassRequest body) {
        try {
            String jarPath = body.jarPath();
            String className = body.className();
            if (jarPath == null || className == null || jarPath.trim().isEmpty() || className.trim().isEmpty()) {
                return ApiResponse.fail(I18n.get("common.paramInvalid"));
            }
            String source = decompileService.decompileClass(jarPath, className);
            return ApiResponse.ok(source);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * Decompile all classes in a jar
     */
    @PostMapping("/jar")
    @Operation(summary = "Decompile entire JAR / 反编译整个JAR", description = "Decompiles all classes in a JAR file / 反编译JAR中的所有类")
    public ApiResponse<String> decompileJar(@RequestBody JarRequest body) {
        try {
            String jarPath = body.jarPath();
            if (jarPath == null || jarPath.trim().isEmpty()) {
                return ApiResponse.fail(I18n.get("common.paramInvalid"));
            }
            String source = decompileService.decompileJar(jarPath);
            return ApiResponse.ok(source);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * Get jar info (path, name, size)
     */
    @PostMapping("/jar-info")
    @Operation(summary = "Get JAR info / 获取JAR信息", description = "Returns JAR file information (path, name, size) / 返回JAR文件信息（路径、名称、大小）")
    public ApiResponse<Map<String, Object>> jarInfo(@RequestBody JarInfoRequest body) {
        try {
            String workspacePath = Constants.workspacePath;
            String projectName = body.projectName();
            String jarName = body.jarName();
            if (projectName == null || jarName == null) {
                return ApiResponse.fail(I18n.get("common.paramInvalid"));
            }
            // Look in project's lib/ directory
            File libDir = new File(workspacePath, projectName + "/lib");
            File jarFile = new File(libDir, jarName);
            if (!jarFile.exists()) {
                // Try full path
                jarFile = new File(jarName);
                if (!jarFile.exists()) {
                    return ApiResponse.fail(I18n.get("decompile.jarNotFound", jarName));
                }
            }
            java.util.Map<String, Object> info = new java.util.HashMap<>();
            info.put("jarPath", jarFile.getAbsolutePath());
            info.put("jarName", jarFile.getName());
            info.put("size", jarFile.length());
            return ApiResponse.ok(info);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
