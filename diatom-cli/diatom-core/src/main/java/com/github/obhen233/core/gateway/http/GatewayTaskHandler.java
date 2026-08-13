package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.gateway.checkpoint.CheckpointReport;
import com.github.obhen233.core.gateway.checkpoint.CheckpointService;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Handles checkpoint and task management endpoints.
 */
class GatewayTaskHandler {

    private final GatewayHttpServer server;

    GatewayTaskHandler(GatewayHttpServer server) {
        this.server = server;
    }

    void registerRoutes() {
        server.getServerSpi().addHandler("GET", "/gateway/v1/checkpoint", this::handleCheckpoint);
        server.getServerSpi().addHandler("POST", "/gateway/v1/checkpoint", this::handleCheckpoint);
        server.getServerSpi().addHandler("GET", "/gateway/v1/tasks", this::handleTasks);
        server.getServerSpi().addHandler("POST", "/gateway/v1/tasks", this::handleTasks);
    }

    private TaskManager getTaskManager() { return server.getTaskManager(); }
    private CheckpointService getCheckpointService() { return server.getCheckpointService(); }

    private void handleCheckpoint(ServerRequest request, ServerResponse response) throws IOException {
        if ("POST".equals(request.getMethod())) {
            String body = readBody(request);
            CheckpointReport report = parseCheckpointReport(body);
            getCheckpointService().receiveCheckpoint(report);
            TaskStatus currentStatus = getTaskManager().getTask(report.getTaskId()) != null
                    ? getTaskManager().getTask(report.getTaskId()).getStatus() : null;
            String json = "{\"status\":\"ok\"";
            if (currentStatus == TaskStatus.CANCELLING) {
                json += ",\"taskStatus\":\"CANCELLING\",\"message\":\"用户请求取消，请停止执行\"";
            }
            json += "}";
            sendJson(response, 200, json);

        } else if ("GET".equals(request.getMethod())) {
            String taskId = request.getQueryParam("taskId");
            String stepStr = request.getQueryParam("step");
            if (taskId == null) {
                sendError(response, 400, "Missing taskId parameter");
                return;
            }
            int step = stepStr != null ? Integer.parseInt(stepStr) : -1;
            String checkpoint = getCheckpointService().getCheckpoint(taskId, step);
            if (checkpoint != null) {
                sendJson(response, 200, checkpoint);
            } else {
                sendError(response, 404, "Checkpoint not found");
            }
        } else {
            sendError(response, 405, "Method not allowed");
        }
    }

    private void handleTasks(ServerRequest request, ServerResponse response) throws IOException {
        String path = "";
        if (request instanceof JdkServerRequest) {
            path = ((JdkServerRequest) request).getExchange().getRequestURI().getPath();
        }
        String method = request.getMethod();

        // /gateway/v1/tasks/{taskId}/loglevel
        if (path.contains("/loglevel")) {
            if (!"POST".equals(method)) {
                sendError(response, 405, "Method not allowed");
                return;
            }
            String taskId = extractTaskIdFromPath(path, "/loglevel");
            if (taskId == null) {
                sendError(response, 400, "Missing taskId");
                return;
            }
            String level = request.getQueryParam("level");
            if (level == null || level.isEmpty()) {
                sendError(response, 400, "Missing level parameter");
                return;
            }
            String validLevel = level.toUpperCase();
            if (!"TRACE|DEBUG|INFO|WARN|ERROR".contains(validLevel)) {
                sendError(response, 400, "Invalid log level: " + level);
                return;
            }
            TaskState state = getTaskManager().getTask(taskId);
            if (state != null) {
                state.addAttribute("logLevel", validLevel);
                String json = "{\"status\":\"ok\",\"taskId\":\"" + taskId
                        + "\",\"logLevel\":\"" + validLevel + "\"}";
                sendJson(response, 200, json);
                LoggerFactory.getLogger(GatewayTaskHandler.class).info("Log level for task {} set to {}", taskId, validLevel);
            } else {
                sendError(response, 404, "Task not found");
            }
            return;
        }

        // /gateway/v1/tasks/{taskId}/cancel
        if (path.contains("/cancel")) {
            if (!"POST".equals(method)) {
                sendError(response, 405, "Method not allowed");
                return;
            }
            String taskId = extractTaskIdFromPath(path, "/cancel");
            if (taskId == null) {
                sendError(response, 400, "Missing taskId");
                return;
            }
            boolean cancelled = getTaskManager().cancelTask(taskId);
            String json = "{\"status\":\"" + (cancelled ? "cancelling" : "not_found") + "\"}";
            sendJson(response, cancelled ? 200 : 404, json);
            return;
        }

        // /gateway/v1/tasks/{taskId}
        String taskId = extractTaskIdFromPath(path, "");
        if (taskId != null) {
            if (!"GET".equals(method)) {
                sendError(response, 405, "Method not allowed");
                return;
            }
            TaskState state = getTaskManager().getTask(taskId);
            if (state != null) {
                sendJson(response, 200, taskStateToJson(state));
            } else {
                sendError(response, 404, "Task not found");
            }
            return;
        }

        // /gateway/v1/tasks
        if ("GET".equals(method)) {
            String statusFilter = request.getQueryParam("status");
            List<TaskState> taskList;
            if (statusFilter != null) {
                try {
                    TaskStatus filterStatus = TaskStatus.valueOf(statusFilter.toUpperCase());
                    taskList = getTaskManager().getTasksByStatus(filterStatus);
                } catch (IllegalArgumentException e) {
                    taskList = getTaskManager().getAllTasks();
                }
            } else {
                taskList = getTaskManager().getAllTasks();
            }
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (TaskState s : taskList) {
                if (!first) sb.append(",");
                sb.append(taskStateToJson(s));
                first = false;
            }
            sb.append("]");
            sendJson(response, 200, sb.toString());
        } else {
            sendError(response, 405, "Method not allowed");
        }
    }
}
