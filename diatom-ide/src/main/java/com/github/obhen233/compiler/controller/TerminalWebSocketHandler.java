package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.auth.SessionManager;
import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import com.github.obhen233.compiler.mcp.EditorContextService;
import com.github.obhen233.compiler.mcp.ProgressPublisher;
import com.github.obhen233.compiler.service.CoreCommandService;
import com.github.obhen233.compiler.deploy.DeployService;
import com.github.obhen233.compiler.service.TerminalService;
import com.github.obhen233.spi.DeployCallback;
import com.github.obhen233.spi.DeployProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.*;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket handler for terminal communication.
 *
 * Handles shell command execution (streaming output) and AI chat (streaming tokens)
 * over a single persistent WebSocket connection.
 *
 * AI 聊天相关逻辑见 {@link AiChatWebSocketHandler}。
 *
 * Message Protocol:
 *
 * Client -> Server:
 *   {"type":"exec","command":"ls -la","projectName":"myproject","cwd":"/path"}
 *   {"type":"ai","prompt":"explain this code","projectName":"myproject","sessionId":"xxx"}
 *   {"type":"confirm","sessionId":"xxx","decision":"y"}
 *   {"type":"reset","sessionId":"xxx"}
 *
 * Server -> Client:
 *   {"type":"stdout","data":"line of output\n"}
 *   {"type":"exit","code":0,"cwd":"/new/path"}
 *   {"type":"error","message":"error text"}
 *   {"type":"think","text":"token text"}
 *   {"type":"confirm","action":"...","tool":"...","sessionId":"..."}
 *   {"type":"done","content":"...","sessionId":"..."}
 */
@Component("ideTerminalWebSocketHandler")
public class TerminalWebSocketHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(TerminalWebSocketHandler.class);

    @PostConstruct
    public void init() {
        // Pre-load both locales into ResourceBundle classloader cache.
        // ResourceBundle.getBundle() caches by (baseName, locale) key,
        // so both zh and en bundles coexist independently in the cache.
        // The active locale is switched at runtime via reload() in handleAi().
        com.github.obhen233.util.I18n.reload("zh");
        com.github.obhen233.util.I18n.reload("en");
        logger.info("CLI I18n pre-loaded for zh and en");
    }

    @Autowired(required = false)
    private SessionManager sessionManager;

    @Autowired(required = false)
    private CoreCommandService coreCommandService;

    @Autowired(required = false)
    private EditorContextService editorContext;

    @Autowired(required = false)
    private ProgressPublisher progressPublisher;

    @Autowired
    private TerminalService terminalService;

    @Autowired(required = false)
    private DeployProvider deployProvider;

    @Autowired(required = false)
    private DeployService deployService;

    @Autowired(required = false)
    private AiChatWebSocketHandler aiChatHandler;

    // 虚拟线程执行器：WebSocket 的 shell 命令多为 I/O 密集，虚拟线程可大幅降低线程切换开销
    private final ExecutorService shellExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Terminal WebSocket connected: {}", session.getId());
        if (progressPublisher != null) {
            progressPublisher.registerSession(session.getId(), session);
        }
        super.afterConnectionEstablished(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if (payload == null || payload.trim().isEmpty()) return;

        Map<String, Object> msg = WebSocketMessenger.parseMessage(payload);
        if (msg == null) {
            WebSocketMessenger.sendError(wsSession, "Invalid JSON message");
            return;
        }

        String type = (String) msg.get("type");
        if (type == null) {
            WebSocketMessenger.sendError(wsSession, "Missing 'type' field");
            return;
        }

        switch (type) {
            case "exec":
                handleExec(wsSession, msg);
                break;
            case "ai":
                if (aiChatHandler != null) {
                    aiChatHandler.handleAi(wsSession, msg);
                } else {
                    WebSocketMessenger.sendError(wsSession, "AI chat not available");
                }
                break;
            case "confirm":
                if (aiChatHandler != null) {
                    aiChatHandler.handleConfirm(wsSession, msg);
                }
                break;
            case "reset":
                if (aiChatHandler != null) {
                    aiChatHandler.handleReset(wsSession, msg);
                }
                break;
            case "complete":
                handleComplete(wsSession, msg);
                break;
            case "cancel":
                if (aiChatHandler != null) {
                    aiChatHandler.handleCancel(wsSession, msg);
                }
                break;
            case "query_active_ai":
                if (aiChatHandler != null) {
                    aiChatHandler.handleQueryActiveAi(wsSession, msg);
                }
                break;
            case "ai_feedback":
                if (aiChatHandler != null) {
                    aiChatHandler.handleAiFeedback(wsSession, msg);
                }
                break;
            case "deploy":
                handleDeploy(wsSession, msg);
                break;
            case "deploy_detect":
                handleDeployDetect(wsSession, msg);
                break;
            default:
                WebSocketMessenger.sendError(wsSession, "Unknown message type: " + type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("Terminal WebSocket disconnected: {}, status: {}", session.getId(), status);
        terminalService.removeSession(session.getId());
        if (progressPublisher != null) {
            progressPublisher.unregisterSession(session.getId());
        }
        super.afterConnectionClosed(session, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("Terminal WebSocket transport error: {}", session.getId(), exception);
        terminalService.removeSession(session.getId());
    }

    // ==================== Auth (HandshakeInterceptor) ====================

    /**
     * Validate auth token during the WebSocket handshake phase.
     * This runs before the HTTP upgrade, so HttpServletRequest is available
     * with full query parameters — unlike WebSocketSession.getUri() which
     * may lose query params in some servlet containers.
     */
    @Override
    public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                    org.springframework.http.server.ServerHttpResponse response,
                                    org.springframework.web.socket.WebSocketHandler wsHandler,
                                    Map<String, Object> attributes) throws Exception {
        if (sessionManager == null) return true;

        // Extract _token from query parameters
        String token = null;
        if (request.getURI() != null) {
            String query = request.getURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] parts = param.split("=", 2);
                    if (parts.length == 2 && "_token".equals(parts[0])) {
                        token = parts[1];
                        break;
                    }
                }
            }
        }

        if (token == null || token.isEmpty()) {
            logger.warn("WebSocket handshake rejected: missing auth token");
            return false;
        }

        if (!sessionManager.validateToken(token)) {
            logger.warn("WebSocket handshake rejected: invalid token");
            return false;
        }

        return true;
    }

    @Override
    public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                org.springframework.http.server.ServerHttpResponse response,
                                org.springframework.web.socket.WebSocketHandler wsHandler,
                                Exception exception) {
        // No-op after handshake
    }

    // ==================== Shell Command Execution ====================

    private void handleExec(WebSocketSession wsSession, Map<String, Object> msg) {
        String command = (String) msg.get("command");
        String projectName = (String) msg.get("projectName");
        String clientCwd = (String) msg.get("cwd");

        if (command == null || command.trim().isEmpty()) {
            WebSocketMessenger.sendError(wsSession, "Command is empty");
            return;
        }
        command = command.trim();

        // Set locale for i18n resolution — WebSocket threads don't go through LocaleInterceptor
        String lang = editorContext != null ? editorContext.getCurrentState().language : "";
        Locale locale = "zh".equals(lang) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        LocaleContextHolder.setLocale(locale);
        try {
            // Validate command via TerminalService
            String validationError = terminalService.validateCommand(command);
            if (validationError != null) {
                WebSocketMessenger.sendError(wsSession, validationError);
                WebSocketMessenger.sendExit(wsSession, -1, terminalService.getCwd(wsSession.getId(), projectName));
                return;
            }

            // Handle "deploy" — one-click deploy (execute only, no config generation)
            String commandLower = command.toLowerCase();
            if ("deploy".equals(commandLower)) {
                handleTypedDeploy(wsSession, projectName, wsSession.getId());
                return;
            }

            // Check core commands first (mcp, reload-skills, etc.) — this includes "deploy help" etc.
            if (coreCommandService != null) {
                setProjectDirProperty(projectName);
                String coreOutput = coreCommandService.executeCommand(command);
                if (coreOutput != null) {
                    WebSocketMessenger.sendStdout(wsSession, coreOutput);
                    WebSocketMessenger.sendExit(wsSession, 0, terminalService.getCwd(wsSession.getId(), projectName));
                    return;
                }
            }
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }

        final String sessionId = wsSession.getId();
        final String finalCommand = command;

        // Update project name for this session
        if (projectName != null && !projectName.isEmpty()) {
            terminalService.updateSessionProject(sessionId, projectName);
        }

        shellExecutor.submit(() -> {
            try {
                terminalService.executeCommand(finalCommand, projectName, clientCwd, sessionId,
                    new TerminalService.ShellOutputCallback() {
                        @Override
                        public void onStdout(String data) {
                            WebSocketMessenger.sendStdout(wsSession, data);
                        }
                        @Override
                        public void onExit(int code, String cwd) {
                            WebSocketMessenger.sendExit(wsSession, code, cwd);
                        }
                        @Override
                        public void onError(String message) {
                            WebSocketMessenger.sendError(wsSession, message);
                        }
                    });
            } catch (Exception e) {
                logger.error("Shell exec error for session {}", sessionId, e);
                WebSocketMessenger.sendError(wsSession, "Execution error: " + e.getMessage());
                WebSocketMessenger.sendExit(wsSession, -1, terminalService.getCwd(sessionId, projectName));
            }
        });
    }

    // ==================== Tab Completion ====================

    /**
     * Handle tab completion request for file/directory path completion.
     * Delegates to TerminalService for candidate resolution.
     */
    private void handleComplete(WebSocketSession wsSession, Map<String, Object> msg) {
        String prefix = (String) msg.get("prefix");
        String clientCwd = (String) msg.get("cwd");
        String projectName = (String) msg.get("projectName");

        TerminalService.CompleterResult result = terminalService.getCompletionCandidates(
            prefix, projectName, clientCwd, wsSession.getId());

        Map<String, Object> response = new HashMap<>();
        response.put("type", "complete");
        response.put("candidates", result.getCandidates());
        WebSocketMessenger.sendWsJson(wsSession, response);
    }

    // ==================== Deploy Pipeline ====================

    /**
     * Set diatom.project.dir system property so core SPI commands (e.g., deploy)
     * can resolve the correct project directory in IDE mode.
     * Pattern follows FileMcpServer/ProjectFileMcpServer override approach.
     */
    private void setProjectDirProperty(String projectName) {
        if (projectName != null && !projectName.isEmpty()) {
            String projectDir = Constants.workspacePath + "/" + projectName;
            System.setProperty("diatom.project.dir", projectDir);
        } else {
            System.clearProperty("diatom.project.dir");
        }
    }

    /**
     * Handle deploy_detect: check if deploy.yaml exists for the given project.
     * Client sends: {"type":"deploy_detect","projectName":"myproject"}
     * Server responds: {"type":"deploy_detect_result","hasDeploy":true/false}
     */
    private void handleDeployDetect(WebSocketSession wsSession, Map<String, Object> msg) {
        String projectName = (String) msg.get("projectName");
        boolean hasDeploy = false;
        if (deployProvider != null && projectName != null && !projectName.isEmpty()) {
            hasDeploy = deployProvider.hasDeployConfig(projectName);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("type", "deploy_detect_result");
        response.put("hasDeploy", hasDeploy);
        response.put("projectName", projectName);
        WebSocketMessenger.sendWsJson(wsSession, response);
    }

    /**
     * Handle typed "deploy" command in the terminal.
     * Has config → execute directly (streaming output).
     * No config → guide user to AI mode for config generation.
     */
    private void handleTypedDeploy(WebSocketSession wsSession, String projectName, String sessionId) {
        if (deployProvider == null) {
            WebSocketMessenger.sendStdout(wsSession, I18n.get("deploy.service_unavailable") + "\n");
            WebSocketMessenger.sendExit(wsSession, -1, terminalService.getCwd(wsSession.getId(), projectName));
            return;
        }

        if (projectName == null || projectName.isEmpty()) {
            WebSocketMessenger.sendStdout(wsSession, I18n.get("deploy.no_project") + "\n");
            WebSocketMessenger.sendExit(wsSession, -1, terminalService.getCwd(wsSession.getId(), projectName));
            return;
        }

        boolean hasConfig = deployProvider.hasDeployConfig(projectName);
        if (hasConfig) {
            // Execute directly, streaming output via WebSocket
            WebSocketMessenger.sendStdout(wsSession,
                "\u001B[1;36m" + I18n.get("deploy.terminal.executing", projectName) + "\u001B[0m\n");
            if (deployService != null) {
                deployService.startDeploy(projectName, null, new DeployService.DeployEventCallback() {
                    @Override
                    public void onEvent(String event, String data) {
                        switch (event) {
                            case "stdout":
                                WebSocketMessenger.sendStdout(wsSession, data);
                                break;
                            case "scp_progress":
                                try {
                                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                    com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(data);
                                    WebSocketMessenger.sendScpProgress(wsSession,
                                            node.path("stepName").asText(),
                                            node.path("current").asLong(),
                                            node.path("total").asLong(),
                                            node.path("speedBps").asLong());
                                } catch (Exception e) {
                                    logger.warn("Failed to parse scp_progress: {}", e.getMessage());
                                }
                                break;
                            case "exit":
                                try {
                                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                    com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(data);
                                    int code = node.path("code").asInt(-1);
                                    WebSocketMessenger.sendExit(wsSession, code,
                                        terminalService.getCwd(wsSession.getId(), projectName));
                                } catch (Exception e) {
                                    WebSocketMessenger.sendExit(wsSession, -1,
                                        terminalService.getCwd(wsSession.getId(), projectName));
                                }
                                break;
                            case "error":
                                WebSocketMessenger.sendError(wsSession, data);
                                break;
                        }
                    }
                });
            } else {
                shellExecutor.submit(() -> {
                    try {
                        deployProvider.execute(projectName, new DeployCallback() {
                            @Override
                            public void onOutput(String text) { WebSocketMessenger.sendStdout(wsSession, text); }
                            @Override
                            public void onProgress(String stepName, long current, long total, long speedBps) { WebSocketMessenger.sendScpProgress(wsSession, stepName, current, total, speedBps); }
                            @Override
                            public void onStepComplete(String stepName, boolean success) {}
                            @Override
                            public void onPipelineComplete(boolean success) { WebSocketMessenger.sendExit(wsSession, success ? 0 : 1, terminalService.getCwd(wsSession.getId(), projectName)); }
                            @Override
                            public void onError(String message) { WebSocketMessenger.sendError(wsSession, message); }
                        });
                    } catch (Exception e) {
                        logger.error("Deploy error for project {}", projectName, e);
                        WebSocketMessenger.sendError(wsSession, "Deploy error: " + e.getMessage() + "\n");
                        WebSocketMessenger.sendExit(wsSession, -1,
                            terminalService.getCwd(wsSession.getId(), projectName));
                    }
                });
            }
        } else {
            // No config — guide to AI mode
            WebSocketMessenger.sendStdout(wsSession, I18n.get("deploy.terminal.goto_ai") + "\n");
            WebSocketMessenger.sendExit(wsSession, 0, terminalService.getCwd(wsSession.getId(), projectName));
        }
    }

    /**
     * Handle deploy: execute the deploy pipeline for a project.
     * Client sends: {"type":"deploy","projectName":"myproject"}
     * Output is streamed back via stdout messages, and completion via exit message.
     */
    private void handleDeploy(WebSocketSession wsSession, Map<String, Object> msg) {
        if (deployProvider == null) {
            WebSocketMessenger.sendError(wsSession, "Deploy service not available");
            return;
        }

        String projectName = (String) msg.get("projectName");
        if (projectName == null || projectName.isEmpty()) {
            WebSocketMessenger.sendError(wsSession, "Missing projectName");
            return;
        }

        String profile = (String) msg.get("profile");

        if (deployService != null) {
            deployService.startDeploy(projectName, profile, new DeployService.DeployEventCallback() {
                @Override
                public void onEvent(String event, String data) {
                    switch (event) {
                        case "stdout":
                            WebSocketMessenger.sendStdout(wsSession, data);
                            break;
                        case "scp_progress":
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(data);
                                WebSocketMessenger.sendScpProgress(wsSession,
                                        node.path("stepName").asText(),
                                        node.path("current").asLong(),
                                        node.path("total").asLong(),
                                        node.path("speedBps").asLong());
                            } catch (Exception e) {
                                logger.warn("Failed to parse scp_progress: {}", e.getMessage());
                            }
                            break;
                        case "exit":
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(data);
                                int code = node.path("code").asInt(-1);
                                WebSocketMessenger.sendExit(wsSession, code, "");
                            } catch (Exception e) {
                                WebSocketMessenger.sendExit(wsSession, -1, "");
                            }
                            break;
                        case "error":
                            WebSocketMessenger.sendError(wsSession, data);
                            break;
                    }
                }
            });
        } else {
            shellExecutor.submit(() -> {
                try {
                    if (profile != null && !profile.isEmpty()) {
                        deployProvider.execute(projectName, new DeployCallback() {
                            @Override
                            public void onOutput(String text) { WebSocketMessenger.sendStdout(wsSession, text); }
                            @Override
                            public void onProgress(String stepName, long current, long total, long speedBps) { WebSocketMessenger.sendScpProgress(wsSession, stepName, current, total, speedBps); }
                            @Override
                            public void onStepComplete(String stepName, boolean success) {}
                            @Override
                            public void onPipelineComplete(boolean success) { WebSocketMessenger.sendExit(wsSession, success ? 0 : 1, ""); }
                            @Override
                            public void onError(String message) { WebSocketMessenger.sendError(wsSession, message); }
                        }, profile);
                    } else {
                        deployProvider.execute(projectName, new DeployCallback() {
                            @Override
                            public void onOutput(String text) { WebSocketMessenger.sendStdout(wsSession, text); }
                            @Override
                            public void onProgress(String stepName, long current, long total, long speedBps) { WebSocketMessenger.sendScpProgress(wsSession, stepName, current, total, speedBps); }
                            @Override
                            public void onStepComplete(String stepName, boolean success) {}
                            @Override
                            public void onPipelineComplete(boolean success) { WebSocketMessenger.sendExit(wsSession, success ? 0 : 1, ""); }
                            @Override
                            public void onError(String message) { WebSocketMessenger.sendError(wsSession, message); }
                        });
                    }
                } catch (Exception e) {
                    logger.error("Deploy pipeline error for project {}", projectName, e);
                    WebSocketMessenger.sendError(wsSession, "Deploy error: " + e.getMessage());
                    WebSocketMessenger.sendExit(wsSession, -1, "");
                }
            });
        }
    }
}
