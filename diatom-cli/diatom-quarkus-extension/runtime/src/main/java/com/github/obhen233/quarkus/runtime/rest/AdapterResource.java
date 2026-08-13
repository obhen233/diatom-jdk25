package com.github.obhen233.quarkus.runtime.rest;

import com.github.obhen233.adapter.spi.AgentAdapter;
import com.github.obhen233.adapter.spi.AgentRequest;
import com.github.obhen233.adapter.spi.AgentResponse;
import com.github.obhen233.quarkus.runtime.components.DiatomRuntimeContext;
import com.github.obhen233.quarkus.runtime.kernel.WorkerLoadState;
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
import java.util.List;
import java.util.Map;

/**
 * Adapter 模式 REST 资源（JAX-RS，注册到 Quarkus 原生 web 容器）。
 *
 * <p>镜像 starter {@code AdapterRestController}：接收 Gateway 下发的任务，通过
 * {@link AgentAdapter} SPI（diatom-adapter，ServiceLoader 发现的 Agent 驱动，
 * 如 Claude Code / Cursor）转发到外部 AI Agent。端点：
 * {@code POST /worker/v1/chat}、{@code GET /worker/v1/health}。
 * 未装配 Agent 驱动时 {@code /worker/v1/chat} 返回 503。</p>
 */
@Path("/worker/v1")
@ApplicationScoped
public class AdapterResource {

    private static final Logger LOGGER = Logger.getLogger(AdapterResource.class);

    private final DiatomRuntimeContext context;

    @Inject
    public AdapterResource(DiatomRuntimeContext context) {
        this.context = context;
    }

    /**
     * 接收 Gateway 下发的任务，通过 AgentAdapter 转发到外部 AI Agent。
     */
    @POST
    @Path("/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleChat(Map<String, Object> request) {
        AgentAdapter adapter = context.agentAdapter();
        WorkerLoadState loadState = context.loadState();
        if (adapter == null) {
            LOGGER.warn("Adapter mode: no AgentAdapter driver found (ServiceLoader), returning 503");
            return error(503, error("No agent driver installed"));
        }
        if (loadState == null) {
            return error(503, error("Worker load state not available (not adapter mode)"));
        }
        String taskId = request != null ? (String) request.get("taskId") : null;
        String message = request != null ? (String) request.get("message") : null;

        if (message == null || message.isEmpty()) {
            return error(400, error("Missing message field"));
        }

        // 准入控制：达到 maxConcurrency 立即返回 503，让 Gateway 转排队或路由到其他 Worker
        if (!loadState.tryAcquire()) {
            LOGGER.warnf("Adapter at capacity (%d active/%d max), rejecting task %s",
                    loadState.getActiveTasks(), loadState.getMaxConcurrency(), taskId);
            return error(503, overloaded(taskId));
        }

        LOGGER.infof("Adapter forwarding task: %s, message: %s", taskId, truncate(message, 200));

        try {
            String sessionId = request.get("sessionId") != null ? request.get("sessionId").toString() : null;
            String workspacePath = request.get("workspacePath") != null
                    ? request.get("workspacePath").toString()
                    : System.getProperty("user.dir", ".");
            @SuppressWarnings("unchecked")
            Map<String, String> metadata = (Map<String, String>) request.get("metadata");

            AgentRequest adapterReq = new AgentRequest(
                    taskId, sessionId, message, workspacePath, List.of(), metadata);
            AgentResponse adapterResp = adapter.execute(adapterReq);

            Map<String, Object> response = new HashMap<>();
            boolean success = AgentResponse.STATUS_COMPLETED.equals(adapterResp.status());
            response.put("status", success ? "success" : "error");
            response.put("taskId", taskId != null ? taskId : "unknown");
            response.put("result", adapterResp.response());
            if (adapterResp.errorMessage() != null) {
                response.put("error", adapterResp.errorMessage());
            }
            return Response.ok(response).build();
        } catch (Exception e) {
            LOGGER.errorf(e, "Adapter task execution failed: %s", taskId);
            return error(500, error(taskId, e.getMessage()));
        } finally {
            loadState.release();
        }
    }

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleHealth() {
        AgentAdapter adapter = context.agentAdapter();
        QuarkusRegistrationService registration = context.kernel().registrationService();
        Map<String, Object> response = new HashMap<>();
        response.put("status", adapter != null ? "UP" : "DOWN");
        response.put("workerId", registration != null ? registration.getWorkerId() : "");
        response.put("driverType", adapter != null ? adapter.getAgentType() : "none");
        return Response.ok(response).build();
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
}
