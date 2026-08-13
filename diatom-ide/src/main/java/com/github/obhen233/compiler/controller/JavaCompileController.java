package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.CompileResult;
import com.github.obhen233.compiler.i18n.I18n;


import com.github.obhen233.compiler.service.JavaCompileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * JAVA编译器controller / Java Compiler Controller
 * 按项目编译运行
 */
@CrossOrigin
@RestController
@Tag(name = "Java Compiler / Java编译器", description = "Java compilation and execution using ECJ / 使用ECJ进行Java编译和运行")
public class JavaCompileController {

    private static final Logger logger = LoggerFactory.getLogger(JavaCompileController.class);

    @Resource
    private JavaCompileService javaCompileService;

    /**
     * 编译并运行（按项目编译）
     */
    @ResponseBody
    @RequestMapping(value = "compile")
    @Operation(summary = "Compile and run Java project / 编译并运行Java项目", description = "Compiles the project using ECJ (Eclipse Compiler) and executes the specified main class / 使用ECJ(Eclipse编译器)编译项目并执行指定的主类")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Compilation and execution result wrapped in ApiResponse / 编译执行结果(包装在ApiResponse中)")
    @Parameters({
        @Parameter(name = "projectName", description = "Project name / 项目名称", required = true, example = "myproject"),
        @Parameter(name = "mainClass", description = "Main class name / 主类名", example = "Main"),
        @Parameter(name = "jdkVersion", description = "JDK version (5-25) / JDK版本", example = "25"),
        @Parameter(name = "executeTimeLimit", description = "Execution time limit in milliseconds / 执行时间限制(毫秒)"),
        @Parameter(name = "executeArgs", description = "Program arguments (space-separated) / 程序参数(空格分隔)", example = "arg1 arg2"),
        @Parameter(name = "jvmArgs", description = "JVM arguments / JVM参数", example = "-Xmx256m")
    })
    public ApiResponse<CompileResult> compile(
            @RequestParam(value = "projectName") String projectName,
            @RequestParam(value = "mainClass", required = false, defaultValue = "Main") String mainClass,
            @RequestParam(value = "jdkVersion", required = false, defaultValue = "25") Integer jdkVersion,
            @RequestParam(value = "executeTimeLimit", required = false) Long executeTimeLimit,
            @RequestParam(value = "executeArgs", required = false) String executeArgs,
            @RequestParam(value = "jvmArgs", required = false) String jvmArgs) {
        try {
            if (!StringUtils.hasText(projectName)) {
                return ApiResponse.fail(I18n.get("project.nameEmpty"));
            }
            Class clazz = javaCompileService.compile(projectName, jdkVersion, mainClass);

            // 执行
            String[] args = getInputArgs(executeArgs);
            if (executeTimeLimit != null && executeTimeLimit <= 0) {
                return ApiResponse.fail(I18n.get("compile.timeLimitTooSmall"));
            }
            if (null == executeTimeLimit && null == args) {
                return javaCompileService.executeMainMethod(clazz);
            } else if (null == executeTimeLimit) {
                return javaCompileService.executeMainMethod(clazz, args);
            } else if (null == args) {
                return javaCompileService.executeMainMethod(clazz, executeTimeLimit);
            } else {
                return javaCompileService.executeMainMethod(clazz, executeTimeLimit, args);
            }
        } catch (Exception e) {
            logger.error("Compile/run failed for project: {}", projectName, e);
            return ApiResponse.fail(I18n.get("compile.error", e.getMessage()));
        }
    }

    private String[] getInputArgs(String executeArgsStr) {
        if (!StringUtils.hasText(executeArgsStr)) {
            return null;
        } else {
            return executeArgsStr.split(" ");
        }
    }

    /**
     * 扫描项目中所有包含 main 方法的类
     */
    @GetMapping("/detect-main-classes")
    @Operation(summary = "Detect main classes / 检测主类", description = "Scans the project for classes containing a main method / 扫描项目中包含main方法的类")
    @Parameters({
        @Parameter(name = "projectName", description = "Project name / 项目名称", required = true, example = "myproject")
    })
    public java.util.Map<String, Object> detectMainClasses(@RequestParam String projectName) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        java.util.List<String> mainClasses = new java.util.ArrayList<>();
        String workspacePath = Constants.workspacePath;
        if (workspacePath == null || projectName == null) {
            result.put("success", false);
            result.put("message", I18n.get("common.paramInvalid"));
            return result;
        }
        java.io.File projectDir = new java.io.File(workspacePath, projectName);
        if (!projectDir.exists()) {
            result.put("success", false);
            result.put("message", I18n.get("project.notFound"));
            return result;
        }
        // 扫描所有 .java 文件，查找包含 public static void main 的类
        scanMainClasses(projectDir, projectDir, mainClasses);
        result.put("success", true);
        result.put("mainClasses", mainClasses);
        return result;
    }

    private void scanMainClasses(java.io.File baseDir, java.io.File dir, java.util.List<String> result) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                scanMainClasses(baseDir, f, result);
            } else if (f.getName().endsWith(".java")) {
                try {
                    String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                    // 检查是否包含 main 方法
                    if (content.matches("(?s).*public\\s+static\\s+void\\s+main\\s*\\(\\s*String\\s*\\[\\s*\\].*")) {
                        // 提取包名和类名
                        String pkg = "";
                        java.util.regex.Matcher pm = java.util.regex.Pattern
                            .compile("^\\s*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE)
                            .matcher(content);
                        if (pm.find()) pkg = pm.group(1);
                        String className = f.getName().replace(".java", "");
                        String fullName = pkg.isEmpty() ? className : pkg + "." + className;
                        result.add(fullName);
                    }
                } catch (Exception e) {
                    logger.debug("Failed to scan file for main class: {}", f.getPath());
                }
            }
        }
    }
}
