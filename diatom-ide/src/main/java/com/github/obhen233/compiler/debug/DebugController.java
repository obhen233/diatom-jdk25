package com.github.obhen233.compiler.debug;

import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.debug.AttachRequest;
import com.github.obhen233.compiler.dto.debug.BreakpointRequest;
import com.github.obhen233.compiler.dto.debug.StartDebugRequest;
import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.compiler.debug.model.DebugBreakpoint;
import com.github.obhen233.compiler.entity.DebugConfiguration;
import com.github.obhen233.compiler.debug.model.DebugStackFrame;
import com.github.obhen233.compiler.debug.model.DebugVariable;
import com.github.obhen233.compiler.repository.DebugConfigurationRepository;
import com.github.obhen233.compiler.service.ClasspathBuilder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Debug controller providing REST + SSE endpoints for the Java debugger.
 * <p>
 * Uses JDWP (JDK Debug Wire Protocol) to attach to a forked JVM process
 * and control breakpoints, stepping, and variable inspection.
 * </p>
 */
@CrossOrigin
@RestController
@RequestMapping("/workspace/debug")
@Tag(name = "Debug / 调试", description = "Java debugger with JDWP / Java调试器，支持断点、步进、变量检查")
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    @Autowired
    private DebugService debugService;

    @Autowired
    private ClasspathBuilder classpathBuilder;

    @Autowired
    private DebugConfigurationRepository configRepository;

    @Autowired
    private DebugSseManager sseManager;

    /**
     * Start a debug session. Launches the target project's main class in a
     * separate JVM with JDWP enabled, then attaches via JDI.
     * <p>
     * Returns an {@link SseEmitter} that streams debug events:
     * <ul>
     *   <li>{@code line} -- stdout/stderr output from the debugee</li>
     *   <li>{@code suspended} -- execution paused at a breakpoint or step</li>
     *   <li>{@code resumed} -- execution continued</li>
     *   <li>{@code breakpoint} -- breakpoint hit notification</li>
     *   <li>{@code sessionEnd} -- debugee terminated</li>
     *   <li>{@code error} -- an error occurred</li>
     *   <li>{@code done} -- session complete (always sent last)</li>
     * </ul>
     *
     * @param body JSON body containing {@code projectName}, {@code mainClass},
     *             {@code jvmArgs}, and {@code programArgs}
     * @return SseEmitter for streaming debug events
     */
    @PostMapping("/start")
    @Operation(summary = "Start debug session / 启动调试会话", description = "Starts a debug session with JDWP. Returns SSE stream with events: line, suspended, resumed, breakpoint, sessionEnd, error, done / 启动JDWP调试会话。返回SSE流，包含事件：line, suspended, resumed, breakpoint, sessionEnd, error, done")
    public SseEmitter startDebug(@RequestBody StartDebugRequest body) {
        SseEmitter emitter = sseManager.createEmitter(); // 10 min timeout

        String projectName = body.projectName();
        String launchMode = body.launchMode() != null ? body.launchMode() : "MAIN_CLASS";
        String mainClass = body.mainClass() != null ? body.mainClass() : "Main";
        String springBootMainClass = body.springBootMainClass() != null ? body.springBootMainClass() : "";
        String gradleTask = body.gradleTask() != null ? body.gradleTask() : "";
        String jvmArgs = body.jvmArgs() != null ? body.jvmArgs() : "";
        String programArgs = body.programArgs() != null ? body.programArgs() : "";
        boolean autoCompile = "true".equalsIgnoreCase(body.autoCompile());
        boolean suspend = !"false".equalsIgnoreCase(body.suspend());
        String attachPortStr = body.attachPort();

        if (projectName == null || projectName.isEmpty()) {
            sseManager.sendError(emitter, I18n.get("project.nameEmpty"));
            return emitter;
        }

        File projectDir = new File(Constants.workspacePath, projectName);
        if (!projectDir.exists()) {
            sseManager.sendError(emitter, I18n.get("project.notFound"));
            return emitter;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String projectType = classpathBuilder.detectProjectType(projectDir);

                    // Auto-compile if requested
                    if (autoCompile) {
                        sseManager.sendEvent(emitter, "line", "Auto-compiling project...");
                        boolean compileSuccess = debugService.compileProject(projectDir, projectType,
                            sseManager.createEventCallback(emitter));
                        if (!compileSuccess) {
                            sseManager.sendEvent(emitter, "error", I18n.get("buildFailed"));
                            sseManager.sendEvent(emitter, "done", "{\"exitCode\":-1}");
                            emitter.complete();
                            return;
                        }
                    }

                    String javaHome = classpathBuilder.getConfiguredJavaHome();

                    // Determine the main class to use based on launch mode
                    String effectiveMainClass = mainClass;
                    if ("SPRING_BOOT".equals(launchMode) && !springBootMainClass.isEmpty()) {
                        effectiveMainClass = springBootMainClass;
                    }

                    // Create SSE callback
                    DebugService.SseEventCallback callback = sseManager.createEventCallback(emitter);

                    // Route to appropriate launch method based on launch mode
                    if ("GRADLE".equals(launchMode)) {
                        debugService.startWithGradle(projectDir, effectiveMainClass, jvmArgs, programArgs, javaHome, suspend, callback);
                    } else if ("GRADLE_BOOT".equals(launchMode)) {
                        debugService.startWithGradleBoot(projectDir, effectiveMainClass, jvmArgs, programArgs, javaHome, suspend, callback);
                    } else if ("MAVEN".equals(launchMode)) {
                        debugService.startWithMaven(projectDir, effectiveMainClass, jvmArgs, programArgs, javaHome, suspend, callback);
                    } else if ("SPRING_BOOT".equals(launchMode)) {
                        debugService.startWithMavenSpringBoot(projectDir, effectiveMainClass, jvmArgs, programArgs, javaHome, suspend, callback);
                    } else {
                        // MAIN_CLASS mode - build classpath and launch directly
                        String classpath = classpathBuilder.buildClasspath(projectDir, projectType);

                        if (classpath.isEmpty() && "maven".equals(projectType)) {
                            sseManager.sendEvent(emitter, "line", "Resolving Maven dependencies...");
                            String resolved = classpathBuilder.resolveMavenClasspath(projectName);
                            if (resolved != null && !resolved.isEmpty()) {
                                classpath = resolved;
                            }
                        }

                        if (classpath.isEmpty()) {
                            sseManager.sendEvent(emitter, "error", I18n.get("run.noArtifact"));
                            sseManager.sendEvent(emitter, "done", "{\"exitCode\":-1}");
                            emitter.complete();
                            return;
                        }

                        debugService.start(projectDir, effectiveMainClass, classpath, jvmArgs, programArgs, javaHome, suspend, callback);
                    }

                    // Keep the emitter alive while the debug session is running.
                    // Send a named heartbeat event every second to wake up the frontend
                    // reader.read(), ensuring any partially-buffered events get flushed.
                    // Using a named event (not comment) guarantees Spring produces valid
                    // SSE output regardless of framework version.
                    sseManager.keepAliveWhileRunning(emitter, debugService::isRunning);
                } catch (Exception e) {
                    try {
                        String errorMsg = I18n.get("debug.startFailed") + ": " + e.getMessage();
                        log.error("Debug start failed: {}", errorMsg, e);
                        sseManager.sendEvent(emitter, "error", errorMsg);
                        sseManager.sendEvent(emitter, "done", "{\"exitCode\":-1}");
                        emitter.complete();
                    } catch (Exception ignored) {
                        emitter.completeWithError(e);
                    }
                } finally {
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                        // Already completed
                    }
                }
            }
        }, "debug-start").start();

        return emitter;
    }

    /**
     * Stop the current debug session and terminate the debugee process.
     *
     * @return status map with success flag and message
     */
    @PostMapping("/stop")
    @Operation(summary = "Stop debug session / 停止调试会话", description = "Stops the current debug session and terminates the debugee process / 停止当前调试会话并终止被调试进程")
    public ApiResponse<String> stopDebug() {
        debugService.stop();
        return ApiResponse.ok(I18n.get("debug.stopped"));
    }

    /**
     * Manage breakpoints (set or remove).
     * <p>
     * Request body:
     * <pre>
     * { "action": "set"|"remove", "className": "com.example.Main",
     *   "fileName": "Main.java", "lineNumber": 42, "id": "bp-1" }
     * </pre>
     *
     * @param body breakpoint management request
     * @return result map; on success, includes the breakpoint {@code id}
     */
    @PostMapping("/breakpoint")
    @Operation(summary = "Manage breakpoints / 管理断点", description = "Set or remove a breakpoint / 设置或移除断点")
    public ApiResponse<String> manageBreakpoint(@Valid @RequestBody BreakpointRequest body) {
        String action = body.action(); // "set" or "remove"
        String className = body.className();
        String fileName = body.fileName();
        String filePath = body.filePath();
        String id = body.id();

        if (action == null) {
            return ApiResponse.fail(I18n.get("common.paramInvalid"));
        }

        if ("set".equals(action)) {
            int lineNumber;
            try {
                lineNumber = Integer.parseInt(body.lineNumber());
            } catch (NumberFormatException e) {
                return ApiResponse.fail(I18n.get("common.paramInvalid"));
            }
            DebugBreakpoint bp = new DebugBreakpoint(null, className, fileName, filePath, lineNumber);
            debugService.setBreakpoint(bp);
            return ApiResponse.ok(bp.getId());
        } else if ("remove".equals(action)) {
            if (id != null) {
                debugService.removeBreakpoint(id);
            }
            return ApiResponse.ok();
        }

        return ApiResponse.fail(I18n.get("common.paramInvalid"));
    }

    /**
     * List all current breakpoints with file path information.
     *
     * @return result map with {@code breakpoints} list
     */
    @GetMapping("/breakpoints")
    @Operation(summary = "List breakpoints / 列出断点", description = "Lists all current breakpoints with file path information / 列出所有当前断点及其文件路径信息")
    public ApiResponse<Map<String, Object>> getBreakpoints() {
        Collection<DebugBreakpoint> bps = debugService.getBreakpoints();
        List<Map<String, Object>> list = new ArrayList<>();
        for (DebugBreakpoint bp : bps) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", bp.getId());
            item.put("className", bp.getClassName());
            item.put("fileName", bp.getFileName());
            item.put("filePath", bp.getFilePath());
            item.put("lineNumber", bp.getLineNumber());
            item.put("enabled", bp.isEnabled());
            list.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("breakpoints", list);
        return ApiResponse.ok(result);
    }

    /**
     * Step over the current line in the suspended thread.
     *
     * @return result map
     */
    @PostMapping("/stepOver")
    @Operation(summary = "Step over / 步过", description = "Step over the current line in the suspended thread / 在挂起线程中步过当前行")
    public ApiResponse<String> stepOver() {
        long threadId = debugService.getSuspendedThreadId();
        if (threadId < 0) {
            return ApiResponse.fail(I18n.get("debug.noSuspendedThread"));
        }
        debugService.stepOver(threadId);
        return ApiResponse.ok();
    }

    /**
     * Step into the method call on the current line.
     *
     * @return result map
     */
    @PostMapping("/stepInto")
    @Operation(summary = "Step into / 步入", description = "Step into the method call on the current line / 步入当前行的方法调用")
    public ApiResponse<String> stepInto() {
        long threadId = debugService.getSuspendedThreadId();
        if (threadId < 0) {
            return ApiResponse.fail(I18n.get("debug.noSuspendedThread"));
        }
        debugService.stepInto(threadId);
        return ApiResponse.ok();
    }

    /**
     * Step out of the current method back to the caller.
     *
     * @return result map
     */
    @PostMapping("/stepOut")
    @Operation(summary = "Step out / 步出", description = "Step out of the current method back to the caller / 从当前方法步出返回调用者")
    public ApiResponse<String> stepOut() {
        long threadId = debugService.getSuspendedThreadId();
        if (threadId < 0) {
            return ApiResponse.fail(I18n.get("debug.noSuspendedThread"));
        }
        debugService.stepOut(threadId);
        return ApiResponse.ok();
    }

    /**
     * Resume execution of the suspended debuggee.
     *
     * @return result map
     */
    @PostMapping("/continue")
    @Operation(summary = "Continue execution / 继续执行", description = "Resume execution of the suspended debuggee / 恢复挂起被调试进程的执行")
    public ApiResponse<String> continueDebug() {
        debugService.resume();
        return ApiResponse.ok();
    }

    /**
     * Get the call stack frames for a suspended thread.
     *
     * @param threadId the thread ID (defaults to the currently suspended thread)
     * @return result map with a {@code frames} list
     */
    @GetMapping("/stackFrames")
    @Operation(summary = "Get stack frames / 获取堆栈帧", description = "Get the call stack frames for a suspended thread / 获取挂起线程的调用堆栈帧")
    public ApiResponse<Map<String, Object>> getStackFrames(@RequestParam(defaultValue = "-1") long threadId) {
        long effectiveThreadId = threadId;
        if (effectiveThreadId < 0) {
            effectiveThreadId = debugService.getSuspendedThreadId();
        }
        List<DebugStackFrame> frames = debugService.getStackFrames(effectiveThreadId);
        Map<String, Object> result = new HashMap<>();
        result.put("frames", frames);
        return ApiResponse.ok(result);
    }

    /**
     * Get local variables visible in the given stack frame.
     *
     * @param threadId the thread ID (defaults to the currently suspended thread)
     * @param frameId  the stack frame index (0 = topmost)
     * @return result map with a {@code variables} list
     */
    @GetMapping("/variables")
    @Operation(summary = "Get variables / 获取变量", description = "Get local variables visible in the given stack frame / 获取给定堆栈帧中的局部变量")
    public ApiResponse<Map<String, Object>> getVariables(@RequestParam(defaultValue = "-1") long threadId,
                                             @RequestParam(defaultValue = "0") int frameId) {
        long effectiveThreadId = threadId;
        if (effectiveThreadId < 0) {
            effectiveThreadId = debugService.getSuspendedThreadId();
        }
        List<DebugVariable> variables = debugService.getVariables(effectiveThreadId, frameId);
        Map<String, Object> result = new HashMap<>();
        result.put("variables", variables);
        return ApiResponse.ok(result);
    }

    /**
     * Get the current debug session state.
     *
     * @return result map with {@code state} (enum name) and {@code running} flag
     */
    @GetMapping("/status")
    @Operation(summary = "Get debug status / 获取调试状态", description = "Get the current debug session state / 获取当前调试会话状态")
    public ApiResponse<Map<String, Object>> getStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("state", debugService.getState().name());
        result.put("running", debugService.isRunning());
        return ApiResponse.ok(result);
    }

    /**
     * Reconnect to a running debug session after browser close/refresh.
     * Creates a new SSE stream and sends the current breakpoints list.
     *
     * @return SseEmitter for streaming debug events
     */
    @PostMapping("/reconnect")
    @Operation(summary = "Reconnect to debug session / 重连调试会话", description = "Reconnect to a running debug session after browser close/refresh / 浏览器关闭/刷新后重连到运行中的调试会话")
    public SseEmitter reconnectDebug() {
        SseEmitter emitter = sseManager.createEmitter(); // 10 min timeout

        if (!debugService.isRunning()) {
            sseManager.sendError(emitter, I18n.get("debug.notRunning"));
            return emitter;
        }

        // Send current breakpoints as first event
        Collection<DebugBreakpoint> bps = debugService.getBreakpoints();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DebugBreakpoint bp : bps) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"id\":\"").append(sseManager.escapeJson(bp.getId())).append("\",");
            sb.append("\"className\":\"").append(sseManager.escapeJson(bp.getClassName())).append("\",");
            sb.append("\"fileName\":\"").append(sseManager.escapeJson(bp.getFileName())).append("\",");
            sb.append("\"filePath\":\"").append(sseManager.escapeJson(bp.getFilePath())).append("\",");
            sb.append("\"lineNumber\":").append(bp.getLineNumber()).append(",");
            sb.append("\"enabled\":").append(bp.isEnabled());
            sb.append("}");
        }
        sb.append("]");
        sseManager.sendEvent(emitter, "breakpointsList", sb.toString());

        // Swap the event callback to this new emitter
        debugService.setEventCallback(sseManager.createEventCallback(emitter));

        // Keep the emitter alive while the debug session is running, then complete
        new Thread(() -> {
            sseManager.keepAliveWhileRunning(emitter, debugService::isRunning);
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }, "debug-reconnect").start();

        return emitter;
    }

    // ==================== Debug Configuration CRUD ====================

    /**
     * List all saved debug configurations.
     */
    @GetMapping("/configs")
    @Operation(summary = "List debug configurations / 列出调试配置", description = "Lists all saved debug configurations / 列出所有已保存的调试配置")
    public ApiResponse<Map<String, Object>> listConfigs() {
        List<DebugConfiguration> configs = configRepository.findAllByOrderByNameAsc();
        Map<String, Object> result = new HashMap<>();
        result.put("configs", configs);
        return ApiResponse.ok(result);
    }

    /**
     * Get a specific debug configuration.
     */
    @GetMapping("/configs/{id}")
    @Operation(summary = "Get debug configuration / 获取调试配置", description = "Get a specific debug configuration by ID / 根据ID获取特定调试配置")
    public ApiResponse<Map<String, Object>> getConfig(@PathVariable String id) {
        return configRepository.findById(id)
            .map(config -> {
                Map<String, Object> r = new HashMap<>();
                r.put("config", config);
                return ApiResponse.ok(r);
            })
            .orElse(ApiResponse.fail(I18n.get("common.notFound")));
    }

    /**
     * Create a new debug configuration.
     */
    @PostMapping("/configs")
    @Operation(summary = "Create debug configuration / 创建调试配置", description = "Creates a new debug configuration / 创建新的调试配置")
    public ApiResponse<Map<String, Object>> createConfig(@RequestBody DebugConfiguration config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId(java.util.UUID.randomUUID().toString());
        }
        DebugConfiguration saved = configRepository.save(config);
        Map<String, Object> result = new HashMap<>();
        result.put("config", saved);
        return ApiResponse.ok(result);
    }

    /**
     * Update an existing debug configuration.
     */
    @PutMapping("/configs/{id}")
    @Operation(summary = "Update debug configuration / 更新调试配置", description = "Updates an existing debug configuration / 更新已存在的调试配置")
    public ApiResponse<Map<String, Object>> updateConfig(@PathVariable String id, @RequestBody DebugConfiguration config) {
        if (!configRepository.existsById(id)) {
            return ApiResponse.fail(I18n.get("common.notFound"));
        }
        config.setId(id);
        DebugConfiguration saved = configRepository.save(config);
        Map<String, Object> result = new HashMap<>();
        result.put("config", saved);
        return ApiResponse.ok(result);
    }

    /**
     * Delete a debug configuration.
     */
    @DeleteMapping("/configs/{id}")
    @Operation(summary = "Delete debug configuration / 删除调试配置", description = "Deletes a debug configuration / 删除调试配置")
    public ApiResponse<String> deleteConfig(@PathVariable String id) {
        if (!configRepository.existsById(id)) {
            return ApiResponse.fail(I18n.get("common.notFound"));
        }
        configRepository.deleteById(id);
        return ApiResponse.ok();
    }

    /**
     * Start a debug session using a saved configuration.
     */
    @PostMapping("/startWithConfig")
    @Operation(summary = "Start debug with config / 使用配置启动调试", description = "Start a debug session using a saved configuration / 使用已保存的调试配置启动调试会话")
    public SseEmitter startDebugWithConfig(@RequestBody StartDebugRequest body) {
        String configId = body.projectName(); // Using projectName field to pass configId for this endpoint
        if (configId == null || configId.isEmpty()) {
            SseEmitter emitter = sseManager.createInfiniteEmitter();
            sseManager.sendError(emitter, I18n.get("common.paramInvalid"));
            return emitter;
        }

        DebugConfiguration config = configRepository.findById(configId).orElse(null);
        if (config == null) {
            SseEmitter emitter = sseManager.createInfiniteEmitter();
            sseManager.sendError(emitter, I18n.get("common.notFound"));
            return emitter;
        }

        // Convert config to StartDebugRequest and delegate
        StartDebugRequest req = new StartDebugRequest(
                config.getProjectName(),
                config.getLaunchMode(),
                config.getMainClass() != null ? config.getMainClass() : "Main",
                config.getSpringBootMainClass() != null ? config.getSpringBootMainClass() : "",
                config.getGradleTask() != null ? config.getGradleTask() : "",
                config.getJvmArgs() != null ? config.getJvmArgs() : "",
                config.getProgramArgs() != null ? config.getProgramArgs() : "",
                String.valueOf(config.isAutoCompile()),
                String.valueOf(config.isSuspend()),
                config.getAttachPort() != null ? String.valueOf(config.getAttachPort()) : null
        );

        return startDebug(req);
    }

    /**
     * List running JVM processes that can be attached to.
     */
    @GetMapping("/runningProcesses")
    @Operation(summary = "List running JVM processes / 列出运行中的JVM进程", description = "Lists running JVM processes that can be attached to / 列出可以附加的运行中JVM进程")
    public ApiResponse<Map<String, Object>> getRunningProcesses() {
        List<Map<String, Object>> processes = debugService.listRunningJvms();
        Map<String, Object> result = new HashMap<>();
        result.put("processes", processes);
        return ApiResponse.ok(result);
    }

    /**
     * Attach to a running JVM process.
     */
    @PostMapping("/attach")
    @Operation(summary = "Attach to JVM process / 附加到JVM进程", description = "Attach to a running JVM process via JDWP / 通过JDWP附加到运行中的JVM进程")
    public SseEmitter attachToProcess(@RequestBody AttachRequest body) {
        SseEmitter emitter = sseManager.createEmitter();

        String portStr = body.port();
        String classpath = body.classpath();

        if (portStr == null || portStr.isEmpty()) {
            sseManager.sendError(emitter, I18n.get("debug.attachPortRequired"));
            return emitter;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            sseManager.sendError(emitter, I18n.get("common.paramInvalid"));
            return emitter;
        }

        final int debugPort = port;
        final String cp = classpath != null ? classpath : "";

        new Thread(() -> {
            try {
                debugService.attach(debugPort, cp, sseManager.createEventCallback(emitter));

                sseManager.keepAliveWhileRunning(emitter, debugService::isRunning);
            } catch (Exception e) {
                try {
                    sseManager.sendEvent(emitter, "error", I18n.get("debug.attachFailed") + ": " + e.getMessage());
                    sseManager.sendEvent(emitter, "done", "{\"exitCode\":-1}");
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            } finally {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // Already completed
                }
            }
        }, "debug-attach").start();

        return emitter;
    }

}
