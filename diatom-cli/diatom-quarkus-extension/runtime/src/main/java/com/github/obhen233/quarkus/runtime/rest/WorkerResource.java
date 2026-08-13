package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.agent.ToolConfirmationException;
import com.github.obhen233.quarkus.runtime.components.DiatomRuntimeContext;
import com.github.obhen233.quarkus.runtime.kernel.WorkerLoadState;
import com.github.obhen233.spi.CoreCommandRegistry;
import com.github.obhen233.spi.command.CommandOutput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Worker 模式 REST 资源（JAX-RS，注册到 Quarkus 原生 web 容器）。
 *
 * <p>镜像 starter {@code WorkerRestController}：接收 Gateway 下发的任务，通过
 * {@link ReActAgent} 执行 LLM 调用和工具操作。端点：
 * {@code POST /worker/v1/chat}（执行任务）、{@code POST /worker/v1/cancel}（取消）、
 * {@code GET /worker/v1/health}（健康检查）。</p>
 */
@Path("/worker/v1")
@ApplicationScoped
public class WorkerResource {

    private static final Logger LOGGER = Logger.getLogger(WorkerResource.class);

    private final DiatomRuntimeContext context;
    private volatile String currentTaskId;

    @Inject
    public WorkerResource(DiatomRuntimeContext context) {
        this.context = context;
    }

    /**
     * 接收 Gateway 下发的任务，通过 ReActAgent 执行。
     */
    @POST
    @Path("/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleChat(Map<String, Object> request) {
        ReActAgent agent = context.reActAgent();
        WorkerLoadState loadState = context.loadState();
        if (agent == null || loadState == null) {
            return unavailable("Worker agent not available (not worker mode)");
        }
        String taskId = request != null ? (String) request.get("taskId") : null;
        String message = request != null ? (String) request.get("message") : null;

        if (message == null || message.isEmpty()) {
            return error(400, error("Missing message field"));
        }

        // 准入控制：达到 maxConcurrency 立即返回 503，让 Gateway 转排队或路由到其他 Worker
        if (!loadState.tryAcquire()) {
            LOGGER.warnf("Worker at capacity (%d active/%d max), rejecting task %s",
                    loadState.getActiveTasks(), loadState.getMaxConcurrency(), taskId);
            return error(503, overloaded(taskId));
        }

        currentTaskId = taskId;
        LOGGER.infof("Worker executing task: %s, message: %s", taskId, truncate(message, 200));

        try {
            // ReActAgent 为共享单例且非线程安全，必须在锁内串行执行（与 IDE 本地 AI 通道共享监视器）
            String result;
            synchronized (agent) {
                result = agent.run(message);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("taskId", taskId != null ? taskId : "unknown");
            response.put("result", result);
            return Response.ok(response).build();
        } catch (ToolConfirmationException e) {
            LOGGER.warnf("Task cancelled (confirmation rejected): %s", taskId);
            return Response.ok(cancelled(taskId, "Confirmation rejected")).build();
        } catch (Exception e) {
            LOGGER.errorf(e, "Task execution failed: %s", taskId);
            return error(500, error(taskId, e.getMessage()));
        } finally {
            currentTaskId = null;
            loadState.release();
        }
    }

    @POST
    @Path("/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleCancel(Map<String, Object> request) {
        String taskId = request != null ? (String) request.get("taskId") : null;
        LOGGER.infof("Cancel request received for task: %s", taskId);
        // Note: full cancellation would require agent interrupt support
        Map<String, Object> response = new HashMap<>();
        response.put("status", "cancelling");
        response.put("taskId", taskId);
        return Response.ok(response).build();
    }

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleHealth() {
        QuarkusRegistrationService registration = context.kernel().registrationService();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("workerId", registration != null ? registration.getWorkerId() : "");
        response.put("host", registration != null ? registration.getExternalHost() : "");
        response.put("port", registration != null ? registration.getExternalPort() : 0);
        return Response.ok(response).build();
    }

    /**
     * POST /worker/v1/command
     * 执行一条 CLI 命令（如 "config list" / "rules list --json"）并返回输出。
     * Body: {"command":"help"}
     * Response: {"status":"ok","output":"..."} 或 404/500/400。
     * 镜像 core {@code WorkerHttpServer#handleCommand}。
     */
    @POST
    @Path("/command")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleCommand(Map<String, Object> request) {
        CoreCommandRegistry registry = context.coreCommandRegistry();
        if (registry == null) {
            return error(503, error("Command registry not available"));
        }
        String command = request != null ? (String) request.get("command") : null;
        if (command == null || command.isEmpty()) {
            return error(400, error("Missing 'command' field in request body"));
        }
        // core 的分发按命令名精确匹配，剥掉前导 / 以支持 "/config list" 风格
        String normalized = command.startsWith("/") ? command.substring(1) : command;

        CommandOutput output = new CommandOutput() {
            private final StringBuilder sb = new StringBuilder();
            @Override public void print(String text) { sb.append(text); }
            @Override public void printSuccess(String text) { sb.append(text); }
            @Override public void printError(String text) { sb.append(text); }
            @Override public void printInfo(String text) { sb.append(text); }
            @Override public void printDim(String text) { sb.append(text); }
            @Override public void printWarning(String text) { sb.append(text); }
            @Override public void printBold(String text) { sb.append(text); }
            @Override public void printColored(String text, String ansiColor) { sb.append(text); }
            @Override public StringBuilder getBuffer() { return sb; }
        };

        try {
            String result = registry.execute(normalized, output);
            String outputText = output.getBuffer().toString();
            if (result != null && outputText.isEmpty()) {
                outputText = result;
            }
            if (result == null && outputText.isEmpty()) {
                LOGGER.warnf("Worker command not registered: %s", command);
                return error(404, error("No provider registered for command: " + command));
            }
            LOGGER.infof("Worker command executed: %s", command);
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "ok");
            resp.put("output", outputText);
            return Response.ok(resp).build();
        } catch (Exception e) {
            LOGGER.errorf("Worker command failed: %s: %s", command, e.getMessage());
            return error(500, error(command, e.getMessage()));
        }
    }

    // ===== 工具方法 =====

    private static Map<String, Object> error(String message) {
        return Collections.singletonMap("error", message);
    }

    private static Map<String, Object> error(String taskId, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "error");
        m.put("taskId", taskId);
        m.put("error", message);
        return m;
    }

    private static Map<String, Object> cancelled(String taskId, String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "cancelled");
        m.put("taskId", taskId);
        m.put("error", reason);
        return m;
    }

    private static Map<String, Object> overloaded(String taskId) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "overloaded");
        m.put("taskId", taskId);
        m.put("error", "Worker at capacity, please retry later");
        return m;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static Response error(int status, Map<String, Object> body) {
        return Response.status(status).entity(body).build();
    }

    private static Response unavailable(String message) {
        return error(503, error(message));
    }
}
