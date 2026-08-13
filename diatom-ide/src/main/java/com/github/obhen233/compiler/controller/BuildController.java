package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.workspace.BuildRequest;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.jdtls.SimpleTextDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/workspace")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Build / 构建", description = "Project build operations / 项目构建操作")
public class BuildController {

    @Autowired(required = false)
    private com.github.obhen233.compiler.repository.IdeSettingRepository settingRepo;

    // ==================== Build Tool ====================

    @PostMapping("/build")
    @Operation(summary = "Build project / 构建项目", description = "Builds a project using Maven or Gradle via SSE streaming / 通过SSE流式传输使用Maven或Gradle构建项目")
    public SseEmitter build(@RequestBody BuildRequest body) {
        String projectName = body.projectName();
        String buildTool = body.buildTool();
        String goal = body.goal();

        SseEmitter emitter = new SseEmitter(300_000L);

        if (projectName == null || buildTool == null || goal == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data(I18n.get("common.paramIncomplete")));
                emitter.send(SseEmitter.event().name("done").data("{\"exitCode\":-1}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        File projectDir = new File(Constants.workspacePath, projectName);
        if (!projectDir.exists()) {
            try {
                emitter.send(SseEmitter.event().name("error").data(I18n.get("project.notFound")));
                emitter.send(SseEmitter.event().name("done").data("{\"exitCode\":-1}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        new Thread(() -> {
            try {
                List<String> command = new ArrayList<>();
                if ("maven".equals(buildTool)) {
                    String mvnCmd = findMavenCommand(projectDir);
                    command.add(mvnCmd);
                    for (String part : goal.split("\\s+")) {
                        if (!part.isEmpty()) command.add(part);
                    }
                    command.add("-B");
                    String mavenSettings = getConfiguredMavenSettings();
                    if (mavenSettings != null && !mavenSettings.trim().isEmpty()) {
                        command.add("-s");
                        command.add(mavenSettings.trim());
                    }
                    String mavenRepo = getConfiguredMavenLocalRepo();
                    if (mavenRepo != null && !mavenRepo.trim().isEmpty()) {
                        command.add("-Dmaven.repo.local=" + mavenRepo.trim());
                    }
                } else if ("gradle".equals(buildTool)) {
                    String gradleCmd = findGradleCommand(projectDir);
                    command.add(gradleCmd);
                    for (String part : goal.split("\\s+")) {
                        if (!part.isEmpty()) command.add(part);
                    }
                } else {
                    emitter.send(SseEmitter.event().name("error").data(I18n.get("build.unsupportedTool", buildTool)));
                    emitter.send(SseEmitter.event().name("done").data("{\"exitCode\":-1}"));
                    emitter.complete();
                    return;
                }

                emitter.send(SseEmitter.event().name("line").data("> " + String.join(" ", command)));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(projectDir);
                pb.redirectErrorStream(true);
                pb.environment().put("JAVA_HOME", getConfiguredJavaHome());
                String mavenHome = getSetting("mavenHome");
                if (mavenHome != null && !mavenHome.trim().isEmpty()) {
                    pb.environment().put("M2_HOME", mavenHome.trim());
                    pb.environment().put("MAVEN_HOME", mavenHome.trim());
                }
                String existingMavenOpts = pb.environment().getOrDefault("MAVEN_OPTS", "");
                if (!existingMavenOpts.contains("-Dfile.encoding")) {
                    pb.environment().put("MAVEN_OPTS", (existingMavenOpts + " -Dfile.encoding=UTF-8").trim());
                }
                String existingGradleOpts = pb.environment().getOrDefault("GRADLE_OPTS", "");
                if (!existingGradleOpts.contains("-Dfile.encoding")) {
                    pb.environment().put("GRADLE_OPTS", (existingGradleOpts + " -Dfile.encoding=UTF-8").trim());
                }
                pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");

                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        emitter.send(SseEmitter.event().name("line").data(line));
                    }
                }
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    try {
                        SimpleTextDocumentService.invalidateMavenClasspathCache(projectName);
                        SimpleTextDocumentService.invalidateGradleClasspathCache(projectName);
                    } catch (Exception ignored) {}
                }
                emitter.send(SseEmitter.event().name("done").data("{\"exitCode\":" + exitCode + "}"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(I18n.get("build.execFailed", e.getMessage())));
                    emitter.send(SseEmitter.event().name("done").data("{\"exitCode\":-1}"));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        }, "build-" + projectName).start();

        return emitter;
    }

    @PostMapping("/projects/{name}/clean-build")
    @Operation(summary = "Clean build artifacts / 清理构建产物", description = "Deletes build directories (target, build, out) / 删除构建目录(target, build, out)")
    public ApiResponse<Map<String, Object>> cleanBuild(@PathVariable String name) {
        try {
            File projectDir = new File(Constants.workspacePath, name);
            if (!projectDir.exists()) return ApiResponse.fail(I18n.get("project.notFound"));

            List<String> deleted = new ArrayList<>();
            File targetClasses = new File(projectDir, "target" + File.separator + "classes");
            if (targetClasses.exists()) {
                deleteRecursive(targetClasses);
                deleted.add("target/classes");
            }
            File targetDir = new File(projectDir, "target");
            if (targetDir.exists()) {
                deleteRecursive(targetDir);
                deleted.add("target");
            }
            File buildDir = new File(projectDir, "build");
            if (buildDir.exists()) {
                deleteRecursive(buildDir);
                deleted.add("build");
            }
            File outDir = new File(projectDir, "out");
            if (outDir.exists()) {
                deleteRecursive(outDir);
                deleted.add("out");
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("deleted", deleted);
            return ApiResponse.ok(resultData);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    private String findMavenCommand(File projectDir) {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        File mvnw = new File(projectDir, isWin ? "mvnw.cmd" : "mvnw");
        if (mvnw.exists()) return mvnw.getAbsolutePath();
        String mavenHome = getSetting("mavenHome");
        if (mavenHome != null && !mavenHome.trim().isEmpty()) {
            File mvnBin = new File(mavenHome, "bin" + File.separator + (isWin ? "mvn.cmd" : "mvn"));
            if (mvnBin.exists()) return mvnBin.getAbsolutePath();
            File mvnBin2 = new File(mavenHome, "bin" + File.separator + "mvn");
            if (mvnBin2.exists()) return mvnBin2.getAbsolutePath();
        }
        return isWin ? "mvn.cmd" : "mvn";
    }

    private String findGradleCommand(File projectDir) {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        File gradlew = new File(projectDir, isWin ? "gradlew.bat" : "gradlew");
        if (gradlew.exists()) return gradlew.getAbsolutePath();
        String gradleHome = getSetting("gradleUserHome");
        if (gradleHome != null && !gradleHome.trim().isEmpty()) {
            File gradleBin = new File(gradleHome, "bin" + File.separator + (isWin ? "gradle.bat" : "gradle"));
            if (gradleBin.exists()) return gradleBin.getAbsolutePath();
        }
        return isWin ? "gradle.bat" : "gradle";
    }

    private String getConfiguredJavaHome() {
        String javaHome = getSetting("javaHome");
        if (javaHome != null && !javaHome.trim().isEmpty()) return javaHome.trim();
        return System.getProperty("java.home");
    }

    private String getConfiguredMavenSettings() {
        return getSetting("mavenUserSettings");
    }

    private String getConfiguredMavenLocalRepo() {
        return getSetting("mavenLocalRepository");
    }

    private String getSetting(String key) {
        if (settingRepo != null) {
            String val = settingRepo.findById(key).map(com.github.obhen233.compiler.entity.IdeSetting::getValue).orElse(null);
            if (val != null && !val.trim().isEmpty()) return val.trim();
        }
        switch (key) {
            case "javaHome":
                String jh = System.getProperty("java.home");
                if (jh != null && !jh.isEmpty()) return jh;
                return System.getenv("JAVA_HOME");
            case "mavenHome":
                String m2 = System.getenv("M2_HOME");
                if (m2 != null && !m2.isEmpty()) return m2;
                return System.getenv("MAVEN_HOME");
            case "gradleUserHome":
                return System.getenv("GRADLE_USER_HOME");
            default:
                return null;
        }
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return file.delete();
    }
}
