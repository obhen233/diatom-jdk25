package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.compiler.mcp.EditorContextService;
import com.github.obhen233.compiler.mcp.ProgressPublisher;
import com.github.obhen233.compiler.service.AgentManager;
import com.github.obhen233.compiler.service.AgentSession;
import com.github.obhen233.compiler.service.CoreCommandService;
import com.github.obhen233.compiler.service.IdeAiConfigService;
import com.github.obhen233.compiler.service.VcsService;
import com.github.obhen233.core.agent.ToolConfirmationException;
import com.github.obhen233.core.agent.tool.ToolExecutor;
import com.github.obhen233.core.context.ProjectIndexer;
import com.github.obhen233.core.database.HistoryManager;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.starter.gateway.remote.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * AI 聊天 WebSocket 处理器。
 *
 * <p>从 {@link TerminalWebSocketHandler} 中拆出的 AI 相关逻辑：接收 AI 提示词、
 * 流式返回思考 token、处理工具确认（confirm）、取消（cancel）、重置（reset）、
 * 运行中会话查询（query_active_ai）以及用户反馈（ai_feedback）。
 * 消息发送统一委托给 {@link WebSocketMessenger}。</p>
 */
@Component
public class AiChatWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(AiChatWebSocketHandler.class);

    @Autowired(required = false)
    private AgentManager agentManager;

    @Autowired(required = false)
    private IdeAiConfigService ideAiConfigService;

    @Autowired(required = false)
    private CoreCommandService coreCommandService;

    @Autowired(required = false)
    private EditorContextService editorContext;

    @Autowired(required = false)
    private ProgressPublisher progressPublisher;

    @Autowired(required = false)
    private HistoryManager historyManager;

    @Autowired(required = false)
    private ProjectIndexer projectIndexer;

    @Autowired(required = false)
    private VcsService vcsService;

    // 虚拟线程执行器：AI 任务多为 I/O 密集，虚拟线程可大幅降低线程切换开销
    private final ExecutorService aiExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** Per-session AI task Future tracking */
    private final ConcurrentHashMap<String, Future<?>> aiTaskFutures = new ConcurrentHashMap<>();

    /** Tracks active AI sessions by sessionId */
    private final ConcurrentHashMap<String, ActiveAiInfo> activeAiSessions = new ConcurrentHashMap<>();
    /** Tracks active AI sessions by projectName */
    private final ConcurrentHashMap<String, ActiveAiInfo> activeAiSessionsByProject = new ConcurrentHashMap<>();

    /**
     * Holds information about an active AI session.
     */
    private static class ActiveAiInfo {
        final String sessionId;
        final String projectName;
        final Future<?> taskFuture;

        ActiveAiInfo(String sessionId, String projectName, Future<?> taskFuture) {
            this.sessionId = sessionId;
            this.projectName = projectName;
            this.taskFuture = taskFuture;
        }
    }

    /**
     * 处理 AI 聊天请求。WebSocket 线程不经过 LocaleInterceptor，因此先设置 locale，
     * 后续 I18n.get() 依赖 LocaleContextHolder。
     */
    public void handleAi(WebSocketSession wsSession, Map<String, Object> msg) {
        // Set locale FIRST — WebSocket threads don't go through LocaleInterceptor,
        // and subsequent I18n.get() calls depend on LocaleContextHolder being set.
        String lang = editorContext != null ? editorContext.getCurrentState().language : "";
        Locale locale = "zh".equals(lang) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        LocaleContextHolder.setLocale(locale);

        String prompt = (String) msg.get("prompt");
        String projectName = (String) msg.get("projectName");
        String sessionId = (String) msg.get("sessionId");

        if (prompt == null || prompt.trim().isEmpty()) {
            WebSocketMessenger.sendWsJson(wsSession,
                WebSocketMessenger.createMessage("error", "message", I18n.get("ai.promptEmpty")));
            return;
        }
        // Check core commands FIRST — bypass all AI setup for direct commands
        if (coreCommandService != null) {
            setProjectDirProperty(projectName);
            String coreOutput = coreCommandService.executeCommand(prompt);
            if (coreOutput != null) {
                Map<String, Object> doneData = new HashMap<>();
                doneData.put("type", "done");
                doneData.put("content", coreOutput);
                doneData.put("sessionId", sessionId != null ? sessionId : "");
                WebSocketMessenger.sendWsJson(wsSession, doneData);
                return;
            }
        }

        // Only reach here for actual AI prompts
        if (ideAiConfigService != null && !ideAiConfigService.isAiEnabled()) {
            WebSocketMessenger.sendWsJson(wsSession,
                WebSocketMessenger.createMessage("error", "message", I18n.get("ai.notEnabled")));
            return;
        }

        if (agentManager == null) {
            WebSocketMessenger.sendWsJson(wsSession,
                WebSocketMessenger.createMessage("error", "message", I18n.get("ai.notAvailable")));
            return;
        }

        final String finalSessionId;
        if (sessionId == null || sessionId.isEmpty()) {
            finalSessionId = AgentManager.generateSessionId();
        } else {
            finalSessionId = sessionId;
        }

        final String finalPrompt = prompt;
        final String finalProjectName = projectName != null ? projectName : "";

        // Cancel any existing AI task for this session
        Future<?> existingTask = aiTaskFutures.get(finalSessionId);
        if (existingTask != null && !existingTask.isDone()) {
            existingTask.cancel(true);
        }

        // Submit new AI task and track its Future
        final Future<?> aiTask = aiExecutor.submit(() -> {
            try {
                // Sync CLI I18n to the current IDE language for each AI request,
                // so that token_usage_summary etc. are rendered in the correct locale.
                // This also covers runtime language switching.
                String currentLang = editorContext != null ? editorContext.getCurrentState().language : "";
                if (currentLang.isEmpty()) currentLang = "en";
                com.github.obhen233.util.I18n.reload(currentLang);
                // Sync IDE LocaleContextHolder for IDE I18n.get() calls during AI context
                // building (e.g. readAiFileSafe), so they use the user's language.
                LocaleContextHolder.setLocale(locale);
                if (progressPublisher != null) {
                    progressPublisher.setContext(finalSessionId);
                    // Register wsSession under the AI sessionId too, so ProgressPublisher
                    // can find the WebSocket session when publishing by AI sessionId
                    progressPublisher.registerSession(finalSessionId, wsSession);
                }
                ToolExecutor.CURRENT_SESSION.set(finalSessionId);
                executeAiChat(wsSession, finalPrompt, finalProjectName, finalSessionId);
            } catch (Exception e) {
                logger.error("AI chat error", e);
                WebSocketMessenger.sendWsJson(wsSession,
                    WebSocketMessenger.createMessage("error", "message", e.getMessage()));
            } finally {
                aiTaskFutures.remove(finalSessionId);
                // Remove from active session maps only if we're the current entry
                ActiveAiInfo finished = activeAiSessions.remove(finalSessionId);
                if (finished != null && finished.projectName != null && !finished.projectName.isEmpty()) {
                    activeAiSessionsByProject.remove(finished.projectName, finished);
                }
                if (progressPublisher != null) {
                    progressPublisher.markDone(finalSessionId);
                    progressPublisher.clearContext();
                }
                ToolExecutor.CURRENT_SESSION.remove();
            }
        });
        aiTaskFutures.put(finalSessionId, aiTask);

        // Track in active session maps (overwrites any previous entry for this sessionId/projectName)
        ActiveAiInfo info = new ActiveAiInfo(finalSessionId, finalProjectName, aiTask);
        activeAiSessions.put(finalSessionId, info);
        if (finalProjectName != null && !finalProjectName.isEmpty()) {
            activeAiSessionsByProject.put(finalProjectName, info);
        }
    }

    private void executeAiChat(WebSocketSession wsSession, String prompt,
                               String projectName, String sessionId) throws Exception {
        // NOTE: Core commands are already handled in handleAi() before reaching here
        if (ideAiConfigService != null) {
            ideAiConfigService.syncConfig();
        }

        AgentSession session = agentManager.getOrCreate(sessionId);
        session.setProjectName(projectName);

        // Set workspace directory for proper resolution of project-prefixed file paths
        // e.g. "SQLExecutor/src/main/java/X.java" resolves to Constants.workspacePath/SQLExecutor/...
        String wsDir = Constants.workspacePath;

        // Update EditorContextService so MCP tools can get the correct projectName
        if (projectName != null && !projectName.isEmpty()) {
            EditorContextService.EditorState state = new EditorContextService.EditorState();
            state.projectName = projectName;
            editorContext.updateFromFrontend(state);
        }

        // Build workspace context — list all projects in the workspace
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

        // Add detailed context for the currently active project
        if (projectName != null && !projectName.isEmpty()) {
            File projectDir = new File(Constants.workspacePath, projectName);
            if (projectDir.exists()) {
                contextBuilder.append("\nActive Project: ").append(projectName).append("\n");
                contextBuilder.append("Structure:\n");
                collectAiFileTree(projectDir, projectDir, contextBuilder, 0, 3);
                appendAiBuildFileContext(projectDir, contextBuilder);

                // Point ProjectIndexer to the active project (used by CLI-compat code)
                if (projectIndexer != null) {
                    projectIndexer.setProjectDir(projectDir.getAbsolutePath());
                }
            }
        }

        String userPrompt;
        if (contextBuilder.length() > 0) {
            userPrompt = "## Project Context\n" + contextBuilder + "\n\n" + prompt;
        } else {
            userPrompt = prompt;
        }

        // Remote mode (gateway/adapter/api/gateway:child): the ChatService is not a
        // LocalChatService, so there is no local ReActAgent. Route the prompt through
        // ChatService.chatStream() and mirror the local flow's WebSocket protocol.
        if (session.getAgent() == null) {
            streamViaRemoteChat(wsSession, session, userPrompt, sessionId);
            return;
        }

        // Local mode — agent-dependent setup
        if (wsDir != null && !wsDir.isEmpty()) {
            session.getAgent().setWorkspaceDir(wsDir);
        }
        if (session.isAutoApprove()) {
            session.getAgent().setAutoApproveWrite(true);
        }

        // Create WebSocket stream consumer
        WebSocketStreamConsumer streamConsumer = new WebSocketStreamConsumer(wsSession, sessionId);
        session.getAgent().setStreamingConsumer(streamConsumer);

        // Connect progress callback for API call status
        // Note: "generating" is intentionally NOT published — it would override the
        // more useful tool-level progress (e.g. "writing: path/to/file") with an
        // empty target. The terminal already shows "thinking" messages during generation.
        session.getAgent().setStatusCallback(status -> {
            // no-op: tool progress is published by ProjectFileMcpServer
        });

        // Register FileChangeListener for real-time diff streaming
        if (progressPublisher != null) {
            session.getAgent().setFileChangeListener((filePath, oldContent, newContent, operation, category) -> {
                String diffText = computeFileDiff(filePath, oldContent, newContent);
                String catName = category != null ? category.name() : "HELPER_SCRIPT";

                // Plan B: git tracking enhancement
                // If file is already tracked in git, upgrade to PROJECT_SOURCE
                // (overrides core's path-based classification for committed files)
                if (vcsService != null && !"PROJECT_SOURCE".equals(catName) && filePath != null) {
                    int slashIdx = filePath.indexOf('/');
                    if (slashIdx > 0) {
                        String projName = filePath.substring(0, slashIdx);
                        String relPath = filePath.substring(slashIdx + 1);
                        if (vcsService.isFileTrackedInGit(projName, relPath)) {
                            catName = "PROJECT_SOURCE";
                        }
                    }
                }

                progressPublisher.publishFileChange(sessionId, filePath, operation, diffText, catName);
            });
        }

        // Skip core's ProjectIndexer context — IDE already provides per-project context above
        session.getAgent().setIncludeProjectContext(false);

        String response;
        try {
            response = runAgent(session, userPrompt);
        } catch (ToolConfirmationException tce) {
            String assistantText = getPendingAssistantText(tce.getMessages());
            Map<String, Object> confirmData = new HashMap<>();
            if (assistantText != null) confirmData.put("assistantText", assistantText);
            confirmData.put("type", "confirm");
            confirmData.put("action", tce.getAction());
            confirmData.put("tool", tce.getToolName());
            confirmData.put("readableName", tce.getReadableName() != null ? tce.getReadableName() : tce.getToolName());
            confirmData.put("operationDescription", tce.getOperationDescription() != null ? tce.getOperationDescription() : tce.getAction());
            confirmData.put("sessionId", sessionId);
            WebSocketMessenger.sendWsJson(wsSession, confirmData);

            // Save messages for resuming after confirmation
            if (tce.getMessages() != null) {
                session.saveMessages(tce.getMessages());
            }

            boolean confirmed = session.waitForConfirm(120_000);

            if (confirmed) {
                session.setAutoApprove(true);
                session.getAgent().setStreamingConsumer(new WebSocketStreamConsumer(wsSession, sessionId));

                List<ChatMessage> savedMessages = session.getAndClearSavedMessages();
                if (savedMessages != null) {
                    logger.info("Restoring {} messages from ToolConfirmationException", savedMessages.size());
                    session.getAgent().setHistory(savedMessages);
                } else {
                    logger.info("No saved messages to restore");
                }

                // Retry loop — handle cascading ToolConfirmationExceptions
                while (true) {
                    try {
                        response = runAgent(session, userPrompt);
                        break;  // Success
                    } catch (ToolConfirmationException tce2) {
                        String assistantText2 = getPendingAssistantText(tce2.getMessages());
                        Map<String, Object> confirmData2 = new HashMap<>();
                        if (assistantText2 != null) confirmData2.put("assistantText", assistantText2);
                        confirmData2.put("type", "confirm");
                        confirmData2.put("action", tce2.getAction());
                        confirmData2.put("tool", tce2.getToolName());
                        confirmData2.put("readableName", tce2.getReadableName() != null ? tce2.getReadableName() : tce2.getToolName());
                        confirmData2.put("operationDescription", tce2.getOperationDescription() != null ? tce2.getOperationDescription() : tce2.getAction());
                        confirmData2.put("sessionId", sessionId);
                        WebSocketMessenger.sendWsJson(wsSession, confirmData2);

                        if (tce2.getMessages() != null) {
                            session.saveMessages(tce2.getMessages());
                        }

                        boolean confirmed2 = session.waitForConfirm(120_000);
                        if (confirmed2) {
                            List<ChatMessage> savedMessages2 = session.getAndClearSavedMessages();
                            if (savedMessages2 != null) {
                                session.getAgent().setHistory(savedMessages2);
                            }
                        } else if (session.isConfirmRejected()) {
                            List<ChatMessage> savedMessages2 = session.getAndClearSavedMessages();
                            if (savedMessages2 != null) {
                                savedMessages2.add(new ChatMessage("user",
                                    "[The user declined to use the proposed tool. Please try a different approach or explain why the tool was needed.]"));
                                session.getAgent().setHistory(savedMessages2);
                            }
                        } else {
                            session.getAndClearSavedMessages();
                            WebSocketMessenger.sendWsJson(wsSession,
                                WebSocketMessenger.createMessage("error", "message", "Cancelled"));
                            return;
                        }
                    }
                }
            } else if (session.isConfirmRejected()) {
                // User rejected the tool call (decision='n'):
                // Inject rejection feedback into agent history and let it try a different approach.
                List<ChatMessage> savedMessages = session.getAndClearSavedMessages();
                if (savedMessages != null) {
                    savedMessages.add(new ChatMessage("user",
                        "[The user declined to use the proposed tool. Please try a different approach or explain why the tool was needed.]"));
                    session.getAgent().setHistory(savedMessages);
                }
                session.getAgent().setStreamingConsumer(new WebSocketStreamConsumer(wsSession, sessionId));
                // Retry — agent will see rejection and adapt
                while (true) {
                    try {
                        response = runAgent(session, userPrompt);
                        break;
                    } catch (ToolConfirmationException tce2) {
                        String assistantText2 = getPendingAssistantText(tce2.getMessages());
                        Map<String, Object> confirmData2 = new HashMap<>();
                        if (assistantText2 != null) confirmData2.put("assistantText", assistantText2);
                        confirmData2.put("type", "confirm");
                        confirmData2.put("action", tce2.getAction());
                        confirmData2.put("tool", tce2.getToolName());
                        confirmData2.put("readableName", tce2.getReadableName() != null ? tce2.getReadableName() : tce2.getToolName());
                        confirmData2.put("operationDescription", tce2.getOperationDescription() != null ? tce2.getOperationDescription() : tce2.getAction());
                        confirmData2.put("sessionId", sessionId);
                        WebSocketMessenger.sendWsJson(wsSession, confirmData2);

                        if (tce2.getMessages() != null) {
                            session.saveMessages(tce2.getMessages());
                        }

                        boolean confirmed2 = session.waitForConfirm(120_000);
                        if (confirmed2) {
                            List<ChatMessage> savedMessages2 = session.getAndClearSavedMessages();
                            if (savedMessages2 != null) {
                                session.getAgent().setHistory(savedMessages2);
                            }
                        } else {
                            session.getAndClearSavedMessages();
                            WebSocketMessenger.sendWsJson(wsSession,
                                WebSocketMessenger.createMessage("error", "message", "Cancelled"));
                            return;
                        }
                    }
                }
            } else {
                session.getAndClearSavedMessages(); // Clear saved messages on cancel
                WebSocketMessenger.sendWsJson(wsSession,
                    WebSocketMessenger.createMessage("error", "message", "Cancelled"));
                return;
            }
        }

        // Save command to history for project-based navigation
        if (historyManager != null) {
            historyManager.saveCommand(prompt, sessionId, projectName);
        }

        // Resolve {{key:param}} templates from the agent response using IDE I18n.
        // This ensures keys like token_usage_summary are rendered in the correct
        // locale via the IDE I18n → CoreI18nAutoConfiguration fallback chain.
        String lang = editorContext != null ? editorContext.getCurrentState().language : "";
        Locale locale = "zh".equals(lang) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        LocaleContextHolder.setLocale(locale);
        try {
            response = I18n.resolveTemplate(response);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }

        Map<String, Object> doneData = new HashMap<>();
        doneData.put("type", "done");
        doneData.put("content", response);
        doneData.put("sessionId", sessionId);
        WebSocketMessenger.sendWsJson(wsSession, doneData);
    }

    /**
     * Remote AI mode (gateway/adapter/api/gateway:child): route the prompt through the
     * starter's {@link ChatService} instead of a local ReActAgent.
     *
     * <p>Streams tokens as "think" events and finalizes with a "done" event, matching the
     * local flow's WebSocket protocol so the frontend stays agnostic to the running mode.
     * The workspace/project context has already been baked into {@code userPrompt} by
     * {@link #executeAiChat}.</p>
     */
    private void streamViaRemoteChat(WebSocketSession wsSession, AgentSession session,
                                     String userPrompt, String sessionId) {
        final WebSocketStreamConsumer streamConsumer = new WebSocketStreamConsumer(wsSession, sessionId);
        final StringBuilder responseBuilder = new StringBuilder();
        try {
            session.getChatService().chatStream(userPrompt, sessionId, null,
                    new ChatService.StreamHandler() {
                        @Override
                        public void onToken(String content) {
                            if (content != null) {
                                responseBuilder.append(content);
                                streamConsumer.sendWsMessage("think", content);
                            }
                        }

                        @Override
                        public void onComplete(String fullResponse) {
                            if (fullResponse != null && !fullResponse.isEmpty()) {
                                responseBuilder.setLength(0);
                                responseBuilder.append(fullResponse);
                            }
                            Map<String, Object> doneData = new HashMap<>();
                            doneData.put("type", "done");
                            doneData.put("content", responseBuilder.toString());
                            doneData.put("sessionId", sessionId);
                            WebSocketMessenger.sendWsJson(wsSession, doneData);
                        }

                        @Override
                        public void onError(String error) {
                            WebSocketMessenger.sendError(wsSession,
                                error != null ? error : "AI request failed");
                        }
                    });
        } catch (Exception e) {
            logger.error("Remote AI chat failed", e);
            WebSocketMessenger.sendError(wsSession, e.getMessage());
        }
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

    private String getPendingAssistantText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (!"assistant".equals(message.getRole())) continue;
            String content = message.getContent();
            if (content == null || content.trim().isEmpty()) return null;
            return content;
        }
        return null;
    }

    /**
     * 处理工具确认请求（确认/拒绝/取消）。
     */
    public void handleConfirm(WebSocketSession wsSession, Map<String, Object> msg) {
        String sessionId = (String) msg.get("sessionId");
        String decision = (String) msg.get("decision");

        if (sessionId == null || decision == null) {
            WebSocketMessenger.sendError(wsSession, "Missing sessionId or decision");
            return;
        }

        if (agentManager == null) {
            WebSocketMessenger.sendError(wsSession, "Agent manager not available");
            return;
        }

        AgentSession session = agentManager.getOrCreate(sessionId);
        session.supplyConfirmDecision(decision);
    }

    /**
     * Handle user feedback injected during AI task execution.
     * The feedback is added to the agent's conversation history so the
     * AI can adjust its behavior in subsequent steps.
     */
    public void handleAiFeedback(WebSocketSession wsSession, Map<String, Object> msg) {
        String sessionId = (String) msg.get("sessionId");
        String text = (String) msg.get("text");
        if (sessionId == null || text == null || text.trim().isEmpty()) {
            return;
        }
        if (agentManager == null) {
            return;
        }
        AgentSession session = agentManager.getOrCreate(sessionId);
        if (session.getAgent() == null) {
            // Remote mode (gateway/adapter/api/child): no local ReActAgent to feed back into.
            logger.debug("AI feedback ignored in remote mode for session {}", sessionId);
            return;
        }
        session.getAgent().addFeedbackMessage(text);
        logger.info("AI feedback injected for session {}: {}", sessionId,
            text.length() > 80 ? text.substring(0, 80) + "..." : text);
    }

    /**
     * Compute a unified diff between old and new file content using JGit's DiffFormatter.
     * Returns a human-readable diff string for display in the frontend.
     */
    private String computeFileDiff(String filePath, String oldContent, String newContent) {
        try {
            if (oldContent == null && newContent == null) return "";
            if (oldContent == null) {
                // CREATE: standard unified diff format for frontend line counting
                String[] lines = newContent.split("\n");
                StringBuilder sb = new StringBuilder();
                sb.append("--- a/").append(filePath).append("\n");
                sb.append("+++ b/").append(filePath).append("\n");
                sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
                for (String line : lines) {
                    sb.append("+").append(line).append("\n");
                }
                return sb.toString();
            }
            if (newContent == null) {
                // DELETE: standard unified diff format for frontend line counting
                String[] lines = oldContent.split("\n");
                StringBuilder sb = new StringBuilder();
                sb.append("--- a/").append(filePath).append("\n");
                sb.append("+++ b/").append(filePath).append("\n");
                sb.append("@@ -1,").append(lines.length).append(" +0,0 @@\n");
                for (String line : lines) {
                    sb.append("-").append(line).append("\n");
                }
                return sb.toString();
            }

            // Use JGit RawText and EditList for Myers diff algorithm
            org.eclipse.jgit.diff.RawText oldText =
                new org.eclipse.jgit.diff.RawText(oldContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.eclipse.jgit.diff.RawText newText =
                new org.eclipse.jgit.diff.RawText(newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            org.eclipse.jgit.diff.EditList edits =
                new org.eclipse.jgit.diff.HistogramDiff().diff(
                    org.eclipse.jgit.diff.RawTextComparator.DEFAULT, oldText, newText);

            // Build unified diff string
            StringBuilder sb = new StringBuilder();
            sb.append("--- a/").append(filePath).append("\n");
            sb.append("+++ b/").append(filePath).append("\n");

            int oldLine = 0, newLine = 0;
            for (org.eclipse.jgit.diff.Edit edit : edits) {
                // Output context lines before this edit
                while (oldLine < edit.getBeginA() && newLine < edit.getBeginB()) {
                    String line = oldText.getString(oldLine);
                    sb.append(" ").append(line).append("\n");
                    oldLine++;
                    newLine++;
                }

                // Output deleted lines
                for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
                    sb.append("-").append(oldText.getString(i)).append("\n");
                }
                // Output added lines
                for (int i = edit.getBeginB(); i < edit.getEndB(); i++) {
                    sb.append("+").append(newText.getString(i)).append("\n");
                }
                oldLine = edit.getEndA();
                newLine = edit.getEndB();
            }

            // Output remaining context lines
            while (oldLine < oldText.size() && newLine < newText.size()) {
                sb.append(" ").append(oldText.getString(oldLine)).append("\n");
                oldLine++;
                newLine++;
            }

            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to compute diff for {}: {}", filePath, e.getMessage());
            return "// diff error: " + e.getMessage();
        }
    }

    /**
     * 重置指定会话的 AI 状态。
     */
    public void handleReset(WebSocketSession wsSession, Map<String, Object> msg) {
        String sessionId = (String) msg.get("sessionId");
        if (sessionId != null && agentManager != null) {
            agentManager.reset(sessionId);
        }
    }

    /**
     * 取消指定会话的 AI 任务并解除确认阻塞。
     */
    public void handleCancel(WebSocketSession wsSession, Map<String, Object> msg) {
        String sessionId = (String) msg.get("sessionId");
        if (sessionId == null) {
            WebSocketMessenger.sendError(wsSession, "Missing sessionId");
            return;
        }

        // Cancel the Future if exists
        Future<?> aiTask = aiTaskFutures.get(sessionId);
        if (aiTask != null && !aiTask.isDone()) {
            boolean cancelled = aiTask.cancel(true);
            logger.info("Cancelled AI task for session {}: {}", sessionId, cancelled);
            aiTaskFutures.remove(sessionId);
        }

        // Remove from active session maps only if we're the current entry
        ActiveAiInfo cancelledInfo = activeAiSessions.remove(sessionId);
        if (cancelledInfo != null && cancelledInfo.projectName != null && !cancelledInfo.projectName.isEmpty()) {
            activeAiSessionsByProject.remove(cancelledInfo.projectName, cancelledInfo);
        }

        // Clear progress context
        if (progressPublisher != null) {
            progressPublisher.markDone(sessionId);
            progressPublisher.clearContext();
        }

        // Also send a 'n' confirmation to unblock any pending confirmation wait
        if (agentManager != null) {
            AgentSession session = agentManager.getOrCreate(sessionId);
            session.supplyConfirm(false); // false = act as if user denied confirmation
        }

        // Send cancellation acknowledgment
        try {
            Map<String, Object> cancelData = new HashMap<>();
            cancelData.put("type", "cancelled");
            cancelData.put("sessionId", sessionId);
            WebSocketMessenger.sendWsJson(wsSession, cancelData);
        } catch (Exception e) {
            logger.warn("Failed to send cancel acknowledgment", e);
        }
    }

    /**
     * 查询指定项目或任意会话是否存在运行中的 AI 任务。
     */
    public void handleQueryActiveAi(WebSocketSession wsSession, Map<String, Object> msg) {
        try {
            // Support querying by projectName
            String projectName = msg != null ? (String) msg.get("projectName") : null;

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("type", "active_ai");

            ActiveAiInfo info = null;
            if (projectName != null && !projectName.isEmpty()) {
                info = activeAiSessionsByProject.get(projectName);
            } else {
                // No projectName - return the first active session (backward compat)
                info = activeAiSessions.isEmpty() ? null : activeAiSessions.values().iterator().next();
            }

            if (info != null && !info.taskFuture.isDone()) {
                responseData.put("hasActive", true);
                responseData.put("sessionId", info.sessionId);
                responseData.put("projectName", info.projectName);
            } else {
                responseData.put("hasActive", false);
                if (info != null) {
                    // Clean up stale entries
                    activeAiSessions.remove(info.sessionId);
                    if (info.projectName != null && !info.projectName.isEmpty()) {
                        activeAiSessionsByProject.remove(info.projectName, info);
                    }
                }
            }
            WebSocketMessenger.sendWsJson(wsSession, responseData);
        } catch (Exception e) {
            logger.warn("Failed to query active AI session", e);
        }
    }

    // ==================== AI File Context ====================

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
     * 设置 diatom.project.dir 系统属性，使 core SPI 命令（如 deploy）能解析正确的项目目录。
     */
    private void setProjectDirProperty(String projectName) {
        if (projectName != null && !projectName.isEmpty()) {
            String projectDir = Constants.workspacePath + "/" + projectName;
            System.setProperty("diatom.project.dir", projectDir);
        } else {
            System.clearProperty("diatom.project.dir");
        }
    }
}
