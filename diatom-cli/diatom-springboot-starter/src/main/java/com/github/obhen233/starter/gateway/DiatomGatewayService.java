package com.github.obhen233.starter.gateway;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gateway 管理服务 — 纯 Java Bean，无任何 Web 依赖
 *
 * 提供 Worker 和 Task 的管理方法，供 HTTP Server 或宿主应用直接调用。
 */
public class DiatomGatewayService {

    private final TaskManager taskManager;
    private final WorkerRegistry registry;

    public DiatomGatewayService(TaskManager taskManager, WorkerRegistry registry) {
        this.taskManager = taskManager;
        this.registry = registry;
    }

    // ===== Worker 注册/心跳 =====

    public void registerWorker(WorkerInfo worker) {
        registry.register(worker);
    }

    public void deregisterWorker(String workerId) {
        registry.deregister(workerId);
    }

    public void heartbeat(String workerId, WorkerMetrics metrics) {
        registry.heartbeat(workerId, metrics);
    }

    public WorkerInfo getWorkerRaw(String workerId) {
        return registry.getWorker(workerId);
    }

    public List<WorkerInfo> listWorkersRaw() {
        return registry.availableWorkers();
    }

    // ===== Worker 摘要 =====

    public List<WorkerSummary> listWorkers() {
        return registry.availableWorkers().stream()
                .map(this::toWorkerSummary)
                .collect(Collectors.toList());
    }

    public WorkerSummary getWorker(String workerId) {
        WorkerInfo w = registry.getWorker(workerId);
        return w != null ? toWorkerSummary(w) : null;
    }

    public List<TaskSummary> listTasks(String status) {
        List<TaskState> taskList;
        if (status != null && !status.isEmpty()) {
            try {
                TaskStatus filter = TaskStatus.valueOf(status.toUpperCase());
                taskList = taskManager.getTasksByStatus(filter);
            } catch (IllegalArgumentException e) {
                taskList = taskManager.getAllTasks();
            }
        } else {
            taskList = taskManager.getAllTasks();
        }
        return taskList.stream().map(this::toTaskSummary).collect(Collectors.toList());
    }

    public TaskSummary getTask(String taskId) {
        TaskState state = taskManager.getTask(taskId);
        return state != null ? toTaskSummary(state) : null;
    }

    public boolean cancelTask(String taskId) {
        return taskManager.cancelTask(taskId);
    }

    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("onlineWorkers", registry.availableWorkers().size());
        result.put("activeTasks", taskManager.getActiveTaskCount());
        return result;
    }

    private WorkerSummary toWorkerSummary(WorkerInfo w) {
        WorkerSummary s = new WorkerSummary();
        s.workerId = w.getWorkerId();
        s.host = w.getHost();
        s.port = w.getPort();
        s.model = w.getModel();
        s.status = w.getStatus().name();
        s.currentLoad = w.getMetrics().getCurrentLoad();
        s.lastHeartbeat = w.getMetrics().getLastHeartbeat();
        return s;
    }

    private TaskSummary toTaskSummary(TaskState t) {
        TaskSummary s = new TaskSummary();
        s.taskId = t.getTaskId();
        s.status = t.getStatus().name();
        s.workerId = t.getWorkerId();
        s.currentStep = t.getCurrentStep();
        s.totalTokens = t.getTotalTokens();
        s.createdAt = t.getCreatedAt();
        s.updatedAt = t.getUpdatedAt();
        return s;
    }

    public static class WorkerSummary {
        public String workerId;
        public String host;
        public int port;
        public String model;
        public String status;
        public double currentLoad;
        public long lastHeartbeat;
    }

    public static class TaskSummary {
        public String taskId;
        public String status;
        public String workerId;
        public int currentStep;
        public int totalTokens;
        public long createdAt;
        public long updatedAt;
    }
}
