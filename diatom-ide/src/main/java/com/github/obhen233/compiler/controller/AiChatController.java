package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.workspace.*;
import com.github.obhen233.compiler.entity.IdeSetting;
import com.github.obhen233.compiler.repository.IdeSettingRepository;
import com.github.obhen233.compiler.service.AiChatService;
import com.github.obhen233.compiler.service.AgentManager;
import com.github.obhen233.compiler.service.AgentSession;
import com.github.obhen233.compiler.service.CoreCommandService;
import com.github.obhen233.compiler.service.IdeAiConfigService;
import com.github.obhen233.compiler.service.IdeStreamConsumer;
import com.github.obhen233.compiler.service.ProjectIndexService;
import com.github.obhen233.compiler.service.ClasspathBuilder;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.core.agent.ToolConfirmationException;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.database.HistoryManager;
import com.github.obhen233.starter.gateway.remote.ChatService;
import com.github.obhen233.starter.gateway.remote.ChatService.StreamHandler;
import com.github.obhen233.jdtls.JdtCoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CrossOrigin
@RestController
@RequestMapping("/workspace")
@io.swagger.v3.oas.annotations.tags.Tag(name = "AI Chat / AI对话", description = "AI-powered chat and code generation / AI驱动对话和代码生成")
public class AiChatController {

    @Autowired(required = false)
    private ProjectIndexService indexService;

    @Autowired
    private AiChatService aiChatService;

    @Autowired(required = false)
    private AgentManager agentManager;

    @Autowired(required = false)
    private IdeAiConfigService ideAiConfigService;

    @Autowired(required = false)
    private CoreCommandService coreCommandService;

    @Autowired
    private ClasspathBuilder classpathBuilder;

    @Autowired(required = false)
    private IdeSettingRepository settingRepo;

    @Autowired(required = false)
    private HistoryManager historyManager;

    private final ExecutorService aiExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ai-executor").factory());

    // ==================== AI Chat (SSE Streaming) / AI对话 (SSE流式) ====================

    @PostMapping("/ai/chat")
    @Operation(summary = "AI Chat with SSE streaming / AI对话 (SSE流式)", description = "Sends a prompt to the AI and receives streaming response via Server-Sent Events. " +
            "Events: think (streaming tokens), progress (model generating/completed), confirm (tool confirmation), done (final response), error. " +
            "发送提示词给AI，通过服务端推送事件接收流式响应。事件类型：think(流式令牌)、progress(模型生成中/完成)、confirm(工具确认)、done(最终响应)、error(错误)。")
    public SseEmitter aiChat(@RequestBody AiChatRequest body) {
        SseEmitter emitter = new SseEmitter(300_000L);

        String prompt = body.prompt();
        String projectName = body.projectName() != null ? body.projectName() : "";
        String activeFile = body.activeFile() != null ? body.activeFile() : "";
        String sessionId = body.sessionId() != null ? body.sessionId() : "";

        if (prompt == null || prompt.trim().isEmpty()) {
            sendSseError(emitter, I18n.get("ai.promptEmpty"));
            return emitter;
        }

        if (ideAiConfigService != null && !ideAiConfigService.isAiEnabled()) {
            sendSseError(emitter, I18n.get("ai.notEnabled"));
            return emitter;
        }

        if (ideAiConfigService == null || agentManager == null) {
            sendSseError(emitter, I18n.get("ai.notAvailable"));
            return emitter;
        }

        final String finalSessionId;
        if (sessionId.isEmpty()) {
            finalSessionId = AgentManager.generateSessionId();
        } else {
            finalSessionId = sessionId;
        }

        // Capture locale from HTTP request thread for use in executor thread
        // (LocaleContextHolder uses ThreadLocal and is NOT inherited by executors)
        final Locale requestLocale = LocaleContextHolder.getLocale();

        aiExecutor.execute(() -> {
            try {
                // Propagate locale to executor thread so I18n.get() and
                // resolveTemplate() use the correct locale
                if (requestLocale != null) {
                    LocaleContextHolder.setLocale(requestLocale);
                }

                if (ideAiConfigService != null) {
                    ideAiConfigService.syncConfig();
                }

                AgentSession session = agentManager.getOrCreate(finalSessionId);

                if (session.getAgent() != null) {
                    if (session.isAutoApprove()) {
                        session.getAgent().setAutoApproveWrite(true);
                    }

                    // Set workspace directory for proper resolution of project-prefixed file paths
                    String wsDir = Constants.workspacePath;
                    if (wsDir != null && !wsDir.isEmpty()) {
                        session.getAgent().setWorkspaceDir(wsDir);
                    }
                }

                StringBuilder contextBuilder = new StringBuilder();
                File wsRoot = new File(Constants.workspacePath);
                contextBuilder.append("Workspace: ").append(wsRoot.getAbsolutePath()).append("\n");
                contextBuilder.append("Projects:\n");
                File[] wsEntries = wsRoot.listFiles();
                if (wsEntries != null) {
                    for (File f : wsEntries) {
                        if (f.isDirectory() && !f.getName().startsWith(".")) {
                            contextBuilder.append("  - ").append(f.getName()).append("/\n");
                        }
                    }
                }
                if (projectName != null && !projectName.isEmpty()) {
                    File projectDir = new File(Constants.workspacePath, projectName);
                    if (projectDir.exists()) {
                        contextBuilder.append("\nActive Project: ").append(projectName).append("\n");
                        contextBuilder.append("Structure:\n");
                        collectAiFileTree(projectDir, projectDir, contextBuilder, 0, 3);
                        appendAiBuildFileContext(projectDir, contextBuilder);
                    }
                }

                // Include active file content if provided (format: "projectName:relativePath")
                if (activeFile != null && !activeFile.isEmpty() && activeFile.contains(":")) {
                    String[] parts = activeFile.split(":", 2);
                    if (parts.length == 2) {
                        String fileProject = parts[0];
                        String filePath = parts[1];
                        File projectDir = new File(Constants.workspacePath, fileProject);
                        File targetFile = new File(projectDir, filePath);
                        if (targetFile.exists() && targetFile.isFile() && targetFile.length() < 500_000) {
                            try {
                                String content = new String(java.nio.file.Files.readAllBytes(targetFile.toPath()), "UTF-8");
                                String truncatedContent = content.length() > 10000 ? content.substring(0, 10000) + "\n... (truncated)" : content;
                                contextBuilder.append("\n\n========== Currently Open File: ").append(filePath).append(" ==========\n");
                                contextBuilder.append(truncatedContent);
                                contextBuilder.append("\n========== End of File ==========\n");
                            } catch (Exception ignored) {
                                // ignore file read errors
                            }
                        }
                    }
                }

                // Check if prompt is a core command (help, config, tasks, etc.)
                // If so, execute it via CoreCommandService instead of sending to agent
                String coreOutput = null;
                if (coreCommandService != null) {
                    coreOutput = coreCommandService.executeCommand(prompt);
                }

                String userPrompt;
                if (contextBuilder.length() > 0) {
                    userPrompt = "## Project Context\n" + contextBuilder.toString() + "\n\n" + prompt;
                } else {
                    userPrompt = prompt;
                }

                String response;

                if (session.getAgent() == null) {
                    // ========== Remote mode: use ChatService chatStream() ==========
                    final StringBuilder responseBuilder = new StringBuilder();
                    final String[] remoteError = {null};

                    session.getChatService().chatStream(userPrompt, finalSessionId, null,
                            new StreamHandler() {
                                @Override
                                public void onToken(String content) {
                                    responseBuilder.append(content);
                                    try {
                                        emitter.send(SseEmitter.event().name("think").data(content));
                                    } catch (Exception ignored) {}
                                }

                                @Override
                                public void onComplete(String fullResponse) {
                                    if (fullResponse != null && !fullResponse.isEmpty()) {
                                        responseBuilder.setLength(0);
                                        responseBuilder.append(fullResponse);
                                    }
                                }

                                @Override
                                public void onError(String error) {
                                    remoteError[0] = error;
                                }
                            });

                    if (remoteError[0] != null) {
                        sendSseError(emitter, remoteError[0]);
                        return;
                    }
                    response = responseBuilder.toString();

                } else {
                    // ========== Local mode: use ReActAgent (existing flow) ==========
                    IdeStreamConsumer streamConsumer = new IdeStreamConsumer(emitter);
                    session.getAgent().setStreamingConsumer(streamConsumer);
                    session.getAgent().setSessionId(finalSessionId);

                    // Connect progress callback for API call status (generating/completed)
                    session.getAgent().setStatusCallback(status -> {
                        if ("generating".equals(status)) {
                            try {
                                emitter.send(SseEmitter.event().name("progress")
                                    .data("{\"tool\":\"model\",\"target\":\"\",\"status\":\"generating\"}"));
                            } catch (Exception ignored) {}
                        } else {
                            try {
                                emitter.send(SseEmitter.event().name("progress")
                                    .data("{\"tool\":\"model\",\"target\":\"\",\"status\":\"completed\"}"));
                            } catch (Exception ignored) {}
                        }
                    });

                    try {
                        // If core command was recognized, return its output directly
                        if (coreOutput != null) {
                            response = coreOutput;
                        } else {
                            response = runAgent(session, userPrompt);
                        }
                    } catch (ToolConfirmationException tce) {
                        Map<String, Object> confirmData = new HashMap<>();
                        confirmData.put("action", tce.getAction());
                        confirmData.put("tool", tce.getToolName());
                        confirmData.put("sessionId", finalSessionId);
                        emitter.send(SseEmitter.event().name("confirm").data(confirmData));

                        // Save conversation messages for proper resume after confirmation
                        session.saveMessages(tce.getMessages());

                        boolean confirmed = session.waitForConfirm(120_000);

                        if (confirmed) {
                            session.setAutoApprove(true);
                            session.getAgent().setStreamingConsumer(
                                    new IdeStreamConsumer(emitter));

                            // Restore saved messages so agent continues from where it left off
                            List<ChatMessage> savedMessages = session.getAndClearSavedMessages();
                            if (savedMessages != null) {
                                session.getAgent().setHistory(savedMessages);
                            }

                            // Retry loop — handle cascading ToolConfirmationExceptions
                            while (true) {
                                try {
                                    response = runAgent(session, userPrompt);
                                    break;
                                } catch (ToolConfirmationException tce2) {
                                    // Save messages again for the next cascade level
                                    session.saveMessages(tce2.getMessages());
                                    boolean confirmed2 = session.waitForConfirm(120_000);
                                    if (confirmed2) {
                                        session.setAutoApprove(true);
                                        session.getAgent().setStreamingConsumer(
                                                new IdeStreamConsumer(emitter));
                                        List<ChatMessage> saved2 = session.getAndClearSavedMessages();
                                        if (saved2 != null) {
                                            session.getAgent().setHistory(saved2);
                                        }
                                        // continue retry loop
                                    } else if (session.isConfirmRejected()) {
                                        List<ChatMessage> saved2 = session.getAndClearSavedMessages();
                                        if (saved2 != null) {
                                            saved2.add(new ChatMessage("user",
                                                "[The user declined to use the proposed tool. Please try a different approach or explain why the tool was needed.]"));
                                            session.getAgent().setHistory(saved2);
                                        }
                                    } else {
                                        emitter.send(SseEmitter.event().name("error")
                                                .data("{\"message\":\"Cancelled\"}"));
                                        emitter.complete();
                                        return;
                                    }
                                }
                            }
                        } else if (session.isConfirmRejected()) {
                            // User rejected the tool call (decision='n'):
                            // Inject rejection feedback and let the agent try a different approach.
                            List<ChatMessage> savedMessages = session.getAndClearSavedMessages();
                            if (savedMessages != null) {
                                savedMessages.add(new ChatMessage("user",
                                    "[The user declined to use the proposed tool. Please try a different approach or explain why the tool was needed.]"));
                                session.getAgent().setHistory(savedMessages);
                            }
                            session.getAgent().setStreamingConsumer(new IdeStreamConsumer(emitter));
                            // Retry — agent will see rejection and adapt
                            while (true) {
                                try {
                                    response = runAgent(session, userPrompt);
                                    break;
                                } catch (ToolConfirmationException tce2) {
                                    Map<String, Object> confirmData2 = new HashMap<>();
                                    confirmData2.put("action", tce2.getAction());
                                    confirmData2.put("tool", tce2.getToolName());
                                    confirmData2.put("sessionId", finalSessionId);
                                    emitter.send(SseEmitter.event().name("confirm").data(confirmData2));
                                    session.saveMessages(tce2.getMessages());
                                    boolean confirmed2 = session.waitForConfirm(120_000);
                                    if (confirmed2) {
                                        session.setAutoApprove(true);
                                        session.getAgent().setStreamingConsumer(new IdeStreamConsumer(emitter));
                                        List<ChatMessage> saved2 = session.getAndClearSavedMessages();
                                        if (saved2 != null) {
                                            session.getAgent().setHistory(saved2);
                                        }
                                    } else {
                                        emitter.send(SseEmitter.event().name("error")
                                                .data("{\"message\":\"Cancelled\"}"));
                                        emitter.complete();
                                        return;
                                    }
                                }
                            }
                        } else {
                            emitter.send(SseEmitter.event().name("error")
                                    .data("{\"message\":\"Cancelled\"}"));
                            emitter.complete();
                            return;
                        }
                    }
                }

                Map<String, Object> doneData = new HashMap<>();
                // Resolve {{key:param}} templates via IDE I18n fallback chain
                response = I18n.resolveTemplate(response);
                doneData.put("content", response);
                doneData.put("sessionId", finalSessionId);

                // Save command to history for project-based navigation
                if (historyManager != null) {
                    historyManager.saveCommand(prompt, finalSessionId, projectName);
                }

                emitter.send(SseEmitter.event().name("done").data(doneData));
                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"message\":\"" + e.getMessage() + "\"}"));
                } catch (Exception ignored) {}
                try {
                    emitter.complete();
                } catch (Exception ignored) {}
            } finally {
                LocaleContextHolder.resetLocaleContext();
            }
        });

        return emitter;
    }

    @PostMapping("/ai/confirm-decision")
    @Operation(summary = "AI confirmation decision / AI确认决策", description = "Submit user's confirmation decision (y/n/a) for a pending tool execution in AI chat / 提交用户对AI聊天中待处理工具执行的确认决策(y/n/a)")
    public ApiResponse<Map<String, Object>> aiConfirmDecision(@RequestBody AiActionRequest body) {
        String sessionId = body.sessionId();
        String decision = body.decision();

        if (sessionId == null || decision == null) {
            return ApiResponse.fail("Missing sessionId or decision");
        }

        if (agentManager == null) {
            return ApiResponse.fail("Agent manager not available");
        }

        AgentSession session = agentManager.getOrCreate(sessionId);
        session.supplyConfirmDecision(decision);

        return ApiResponse.ok();
    }

    @PostMapping("/ai/confirm")
    @Operation(summary = "AI confirm file operation / AI确认文件操作", description = "Confirm file content for AI write operation / 确认AI写操作的文件内容")
    public ApiResponse<Map<String, Object>> aiConfirmFileOp(@RequestBody AiConfirmFileOpRequest body) {
        if (!"true".equalsIgnoreCase(getAiSetting("aiEnabled"))) {
            return ApiResponse.fail(I18n.get("ai.notEnabled"));
        }
        String projectName = body.projectName();
        String path = body.path();
        String content = body.content();
        if (projectName == null || path == null || content == null) return ApiResponse.fail(I18n.get("common.requestFailed"));

        AiChatService.ConfirmResult result = aiChatService.confirmOp(projectName, path, content);

        if (!result.isSuccess()) {
            return ApiResponse.fail(result.getMessage());
        }

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("path", path);
        return ApiResponse.ok(responseData);
    }

    @PostMapping("/ai/reset")
    @Operation(summary = "Reset AI session / 重置AI会话", description = "Resets the AI conversation history for a session / 重置某个会话的AI对话历史")
    public ApiResponse<Map<String, Object>> aiReset(@RequestBody AiActionRequest body) {
        String sessionId = body.sessionId();
        if (sessionId != null && agentManager != null) {
            agentManager.reset(sessionId);
        }
        return ApiResponse.ok();
    }

    @GetMapping("/ai/history")
    @Operation(summary = "Get command history / 获取命令历史", description = "Returns recent terminal commands, optionally filtered by project / 返回最近的终端命令历史，支持按项目过滤")
    @Parameters({
        @Parameter(name = "limit", description = "Maximum number of history entries / 最大历史条目数", example = "50"),
        @Parameter(name = "projectName", description = "Filter by project name / 按项目名称过滤", example = "myproject")
    })
    public Map<String, Object> aiHistory(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String projectName) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        if (historyManager != null) {
            List<String> commands = historyManager.getRecentCommands(limit, projectName);
            result.put("history", commands);
        } else {
            result.put("history", Collections.emptyList());
        }
        return result;
    }

    // ==================== Helper Methods ====================

    private String getAiSetting(String key) {
        return settingRepo.findById(key).map(IdeSetting::getValue).orElse("");
    }

    private void collectAiFileTree(File root, File dir, StringBuilder sb, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
        String indent = "";
        for (int i = 0; i < depth; i++) indent += "  ";
        int count = 0;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") || name.equals("target") || name.equals("build") || name.equals("node_modules")) continue;
            if (count++ > 50) { sb.append(indent).append("  ...\n"); break; }
            if (f.isDirectory()) { sb.append(indent).append(name).append("/\n"); collectAiFileTree(root, f, sb, depth + 1, maxDepth); }
            else sb.append(indent).append("  ").append(name).append("\n");
        }
    }

    private void appendAiBuildFileContext(File projectDir, StringBuilder sb) {
        File pom = new File(projectDir, "pom.xml");
        if (pom.exists()) { sb.append("pom.xml:\n```xml\n").append(readAiFileSafe(pom, 4000)).append("\n```\n\n"); }
        File gradle = new File(projectDir, "build.gradle");
        if (gradle.exists()) { sb.append("build.gradle:\n```groovy\n").append(readAiFileSafe(gradle, 4000)).append("\n```\n\n"); }
    }

    private String readAiFileSafe(File f, int maxLen) {
        try {
            String c = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            return c.length() > maxLen ? c.substring(0, maxLen) + "\n" + I18n.get("terminal.outputTruncated") : c;
        } catch (Exception e) { return I18n.get("terminal.readFailed"); }
    }

    /**
     * 在共享 ReActAgent 实例上串行执行 {@code run()}。
     *
     * <p>worker 模式下，同一 Agent 同时被 {@code WorkerRestController}（/worker/v1/chat，
     * 收到 Gateway 下发的任务）和 IDE 本地 AI 通道使用，而 ReActAgent 非线程安全。
     * 统一在 agent 实例上 synchronized，与 starter 的 WorkerRestController 共享同一监视器。</p>
     */
    private static String runAgent(AgentSession session, String userPrompt) {
        synchronized (session.getAgent()) {
            return session.getAgent().run(userPrompt);
        }
    }

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
