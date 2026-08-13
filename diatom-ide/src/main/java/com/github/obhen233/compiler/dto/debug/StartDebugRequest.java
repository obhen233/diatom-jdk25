package com.github.obhen233.compiler.dto.debug;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Start debug session request / 启动调试会话请求
 */
@Schema(description = "Start debug session request / 启动调试会话请求")
public record StartDebugRequest(
    @Schema(description = "Project name / 项目名称", example = "myproject") String projectName,
    @Schema(description = "Launch mode: MAIN_CLASS, SPRING_BOOT, GRADLE, GRADLE_BOOT, MAVEN / 启动模式", example = "MAIN_CLASS") String launchMode,
    @Schema(description = "Main class name / 主类名称", example = "Main") String mainClass,
    @Schema(description = "Spring Boot main class / Spring Boot主类", example = "com.example.Application") String springBootMainClass,
    @Schema(description = "Gradle task name / Gradle任务名称", example = "bootRun") String gradleTask,
    @Schema(description = "JVM arguments / JVM参数", example = "-Xmx512m") String jvmArgs,
    @Schema(description = "Program arguments / 程序参数", example = "--server.port=8080") String programArgs,
    @Schema(description = "Auto compile before debug / 调试前自动编译", example = "true") String autoCompile,
    @Schema(description = "Suspend debugger at start / 启动时挂起调试器", example = "true") String suspend,
    @Schema(description = "Attach port for existing process / 附加到已有进程的端口", example = "5005") String attachPort
) {}
