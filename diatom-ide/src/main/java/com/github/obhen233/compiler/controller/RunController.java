package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.workspace.RunRequest;
import com.github.obhen233.compiler.dto.workspace.StopRunRequest;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.compiler.service.ClasspathBuilder;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@CrossOrigin
@RestController
@RequestMapping("/workspace")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Run / 运行", description = "Project execution operations / 项目运行操作")
public class RunController {

    @Autowired
    private ClasspathBuilder classpathBuilder;

    // 支持多项目并发运行：每个项目独立追踪运行进程
    private final ConcurrentHashMap<String, Process> runningProcesses = new ConcurrentHashMap<>();

    @PostMapping("/run")
    @Operation(summary = "Run project / 运行项目", description = "Compiles and runs a Java project, streaming output via SSE. " +
            "Supports Maven/Gradle projects with automatic classpath resolution. / 编译并运行Java项目，通过SSE流式输出。支持Maven/Gradle项目自动解析类路径。")
    public SseEmitter run(@RequestBody RunRequest body) {
        String projectName = body.projectName();
        String mainClass = body.mainClass() != null ? body.mainClass() : "Main";
        String jvmArgs = body.jvmArgs() != null ? body.jvmArgs() : "";
        String programArgs = body.programArgs() != null ? body.programArgs() : "";

        SseEmitter emitter = new SseEmitter(600_000L);

        if (projectName == null || projectName.isEmpty()) {
            sendSseError(emitter, I18n.get("project.nameEmpty"));
            return emitter;
        }

        File projectDir = new File(Constants.workspacePath, projectName);
        if (!projectDir.exists()) {
            sendSseError(emitter, I18n.get("project.notFound"));
            return emitter;
        }

        new Thread(() -> {
            try {
                String projectType = classpathBuilder.detectProjectType(projectDir);
                boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
                String javaCmd = classpathBuilder.getConfiguredJavaHome() + File.separator + "bin" + File.separator + (isWin ? "java.exe" : "java");
                if (!new File(javaCmd).exists()) javaCmd = isWin ? "java.exe" : "java";

                String classpath = classpathBuilder.buildClasspath(projectDir, projectType);

                // If classpath is empty and this is a Maven project, try to resolve dependencies first
                if (classpath.isEmpty() && "maven".equals(projectType)) {
                    sendSseEvent(emitter, "line", "Resolving Maven dependencies...");
                    String resolved = classpathBuilder.resolveMavenClasspath(projectName);
                    if (resolved != null && !resolved.isEmpty()) {
                        classpath = resolved;
                    }
                }

                if (classpath.isEmpty()) {
                    sendSseEvent(emitter, "error", I18n.get("run.noArtifact"));
                    sendSseEvent(emitter, "done", "{\"exitCode\":-1}");
                    emitter.complete();
                    return;
                }

                List<String> command = new ArrayList<>();
                command.add(javaCmd);
                if (jvmArgs != null && !jvmArgs.trim().isEmpty()) {
                    for (String arg : jvmArgs.trim().split("\\s+")) {
                        if (!arg.isEmpty()) command.add(arg);
                    }
                }
                command.add("-cp");
                command.add(classpath);
                command.add(mainClass);
                if (programArgs != null && !programArgs.trim().isEmpty()) {
                    for (String arg : programArgs.trim().split("\\s+")) {
                        if (!arg.isEmpty()) command.add(arg);
                    }
                }

                // Show truncated classpath for display (first 100 chars + "...")
                String displayCp = classpath.length() > 100 ? classpath.substring(0, 100) + "..." : classpath;
                sendSseEvent(emitter, "line", "> " + javaCmd + " -cp " + displayCp + " " + mainClass
                        + (programArgs != null && !programArgs.trim().isEmpty() ? " " + programArgs.trim() : ""));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(projectDir);
                pb.redirectErrorStream(true);
                pb.environment().put("JAVA_HOME", classpathBuilder.getConfiguredJavaHome());
                pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");

                Process process = pb.start();
                runningProcesses.put(projectName, process);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sendSseEvent(emitter, "line", line);
                    }
                }

                int exitCode = process.waitFor();
                runningProcesses.remove(projectName);
                sendSseEvent(emitter, "done", "{\"exitCode\":" + exitCode + "}");
                emitter.complete();
            } catch (Exception e) {
                runningProcesses.remove(projectName);
                try {
                    sendSseEvent(emitter, "error", I18n.get("run.failed", e.getMessage()));
                    sendSseEvent(emitter, "done", "{\"exitCode\":-1}");
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        }, "run-" + projectName).start();

        return emitter;
    }

    @PostMapping("/run/stop")
    @Operation(summary = "Stop running project / 停止运行项目", description = "Forcefully stops a running Java process for the specified project / 强制停止指定项目的运行中Java进程")
    public ApiResponse<Map<String, Object>> stopRun(@RequestBody StopRunRequest body) {
        String projectName = body.projectName();
        if (projectName == null || projectName.isEmpty()) {
            return ApiResponse.fail(I18n.get("project.nameEmpty"));
        }
        Process p = runningProcesses.get(projectName);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            runningProcesses.remove(projectName);
            return ApiResponse.ok();
        }
        return ApiResponse.fail(I18n.get("common.noRunningProcess"));
    }

    @GetMapping("/run/status")
    @Operation(summary = "Get run status / 获取运行状态", description = "Returns the running status of all projects or a specific project / 返回所有项目或指定项目的运行状态")
    public Map<String, Object> runStatus(@RequestParam(required = false) String projectName) {
        Map<String, Object> r = new HashMap<>();
        if (projectName != null && !projectName.isEmpty()) {
            Process p = runningProcesses.get(projectName);
            r.put("success", true);
            r.put("running", p != null && p.isAlive());
            r.put("projectName", projectName);
        } else {
            // 返回所有运行中的项目
            List<String> runningProjects = new ArrayList<>();
            runningProcesses.forEach((name, p) -> {
                if (p.isAlive()) runningProjects.add(name);
            });
            r.put("success", true);
            r.put("runningProjects", runningProjects);
            r.put("runningCount", runningProjects.size());
        }
        return r;
    }

    // ==================== Helper Methods ====================

    private void sendSseError(SseEmitter emitter, String msg) {
        try {
            sendSseEvent(emitter, "error", msg);
            sendSseEvent(emitter, "done", "{\"exitCode\":-1}");
            emitter.complete();
        } catch (Exception ignored) {}
    }

    private void sendSseEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception ignored) {}
    }
}
