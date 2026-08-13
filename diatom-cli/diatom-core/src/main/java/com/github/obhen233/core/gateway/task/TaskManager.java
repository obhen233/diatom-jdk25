package com.github.obhen233.core.gateway.task;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.entity.GatewayTaskEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 任务管理器 — 任务状态机 + 并发控制
 */
public class TaskManager {
    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);
    private static final long ASSIGNED_TIMEOUT_MS = 30_000;
    private static final long SUSPECT_TIMEOUT_MS = 30_000;

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskCounter = new AtomicInteger(0);
    private final List<Consumer<TaskEvent>> listeners = new CopyOnWriteArrayList<>();
    private SessionFactory sf;
    private volatile long taskTimeoutMs;
    private volatile long taskTimeoutGraceMs;
    private volatile String taskTimeoutAction;
    private volatile int checkpointReportSteps;
    private volatile int checkpointReportTokens;

    public static class TaskEvent {
        private final String taskId;
        private final TaskStatus oldStatus;
        private final TaskStatus newStatus;
        private final TaskState taskState;

        public TaskEvent(String taskId, TaskStatus oldStatus, TaskStatus newStatus, TaskState taskState) {
            this.taskId = taskId;
            this.oldStatus = oldStatus;
            this.newStatus = newStatus;
            this.taskState = taskState;
        }

        public String getTaskId() { return taskId; }
        public TaskStatus getOldStatus() { return oldStatus; }
        public TaskStatus getNewStatus() { return newStatus; }
        public TaskState getTaskState() { return taskState; }
    }

    public void subscribe(Consumer<TaskEvent> listener) {
        listeners.add(listener);
    }

    public String createTask(String sessionId, String originalRequest) {
        String taskId = "task_" + System.currentTimeMillis() + "_" + taskCounter.incrementAndGet();
        TaskState state = new TaskState(taskId);
        state.setSessionId(sessionId);
        state.setOriginalRequest(originalRequest);
        tasks.put(taskId, state);
        fireEvent(taskId, null, TaskStatus.PENDING, state);
        persistTask(state);
        logger.info("Task created: {} (session={})", taskId, sessionId);
        return taskId;
    }

    public boolean assignTask(String taskId, String workerId) {
        TaskState state = tasks.get(taskId);
        if (state == null || state.getStatus() != TaskStatus.PENDING) return false;
        TaskStatus old = state.getStatus();
        state.setStatus(TaskStatus.ASSIGNED);
        state.setWorkerId(workerId);
        state.setAssignedAt(System.currentTimeMillis());
        fireEvent(taskId, old, TaskStatus.ASSIGNED, state);
        persistTask(state);
        logger.info("Task assigned: {} -> worker {}", taskId, workerId);
        return true;
    }

    public boolean startTask(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null || state.getStatus() != TaskStatus.ASSIGNED) return false;
        TaskStatus old = state.getStatus();
        state.setStatus(TaskStatus.IN_PROGRESS);
        fireEvent(taskId, old, TaskStatus.IN_PROGRESS, state);
        persistTask(state);
        return true;
    }

    public boolean completeTask(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) return false;
        TaskStatus old = state.getStatus();
        state.setStatus(TaskStatus.COMPLETED);
        fireEvent(taskId, old, TaskStatus.COMPLETED, state);
        persistTask(state);
        logger.info("Task completed: {}", taskId);
        return true;
    }

    public boolean failTask(String taskId, String reason) {
        TaskState state = tasks.get(taskId);
        if (state == null) return false;
        TaskStatus old = state.getStatus();
        state.setStatus(TaskStatus.FAILED);
        state.addAttribute("failReason", reason);
        fireEvent(taskId, old, TaskStatus.FAILED, state);
        persistTask(state);
        logger.warn("Task failed: {} (reason: {})", taskId, reason);
        return true;
    }

    public boolean cancelTask(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null || state.getStatus().isTerminal()) return false;
        TaskStatus old = state.getStatus();
        state.setStatus(TaskStatus.CANCELLING);
        fireEvent(taskId, old, TaskStatus.CANCELLING, state);
        persistTask(state);
        logger.info("Task cancelling: {}", taskId);
        return true;
    }

    public boolean confirmCancelled(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) return false;
        TaskStatus old = state.getStatus();
        state.setStatus(TaskStatus.CANCELLED);
        fireEvent(taskId, old, TaskStatus.CANCELLED, state);
        persistTask(state);
        return true;
    }

    public boolean suspendTask(String taskId, String llmSummary, String fileChangeSummary, int checkpointStep) {
        TaskState state = tasks.get(taskId);
        if (state == null) return false;
        TaskStatus old = state.getStatus();
        state.setStatus(TaskStatus.SUSPENDED);
        state.setLlmSummary(llmSummary);
        state.setFileChangeSummary(fileChangeSummary);
        state.setCheckpointStep(checkpointStep);
        fireEvent(taskId, old, TaskStatus.SUSPENDED, state);
        persistTask(state);
        logger.info("Task suspended: {} (checkpoint step={})", taskId, checkpointStep);
        return true;
    }

    public boolean markTokenExhausted(String taskId, String llmSummary, String fileChangeSummary, int checkpointStep) {
        TaskState state = tasks.get(taskId);
        if (state == null) return false;
        TaskStatus old = state.getStatus();
        state.setStatus(TaskStatus.TOKEN_EXHAUSTED);
        state.setLlmSummary(llmSummary);
        state.setFileChangeSummary(fileChangeSummary);
        state.setCheckpointStep(checkpointStep);
        fireEvent(taskId, old, TaskStatus.TOKEN_EXHAUSTED, state);
        persistTask(state);
        logger.info("Task token exhausted: {}", taskId);
        return true;
    }

    public boolean reAssignTask(String taskId, String newWorkerId) {
        TaskState state = tasks.get(taskId);
        if (state == null) return false;
        TaskStatus old = state.getStatus();
        state.setStatus(TaskStatus.ASSIGNED);
        state.setWorkerId(newWorkerId);
        state.setAssignedAt(System.currentTimeMillis());
        fireEvent(taskId, old, TaskStatus.ASSIGNED, state);
        persistTask(state);
        logger.info("Task reassigned: {} -> worker {}", taskId, newWorkerId);
        return true;
    }

    public void updateCheckpoint(String taskId, int stepCount, int tokenUsage, int messageCount) {
        TaskState state = tasks.get(taskId);
        if (state != null) {
            state.setCurrentStep(stepCount);
            state.setTotalTokens(tokenUsage);
            state.setMessageCount(messageCount);
        }
    }

    public void markWorkerSuspect(String workerId) {
        for (TaskState state : tasks.values()) {
            if (workerId.equals(state.getWorkerId()) && state.getStatus() == TaskStatus.IN_PROGRESS) {
                TaskStatus old = state.getStatus();
                state.setStatus(TaskStatus.SUSPECT);
                fireEvent(state.getTaskId(), old, TaskStatus.SUSPECT, state);
                persistTask(state);
                logger.warn("Task suspect due to worker heartbeat timeout: {} (worker={})",
                        state.getTaskId(), workerId);
            }
        }
    }

    public void handleWorkerOffline(String workerId) {
        for (TaskState state : tasks.values()) {
            if (workerId.equals(state.getWorkerId())) {
                if (state.getStatus() == TaskStatus.SUSPECT) {
                    TaskStatus old = state.getStatus();
                    state.setStatus(TaskStatus.FAILED);
                    state.addAttribute("failReason", "Worker offline: " + workerId);
                    fireEvent(state.getTaskId(), old, TaskStatus.FAILED, state);
                    persistTask(state);
                    logger.warn("Task failed due to worker offline: {} (worker={})",
                            state.getTaskId(), workerId);
                }
            }
        }
    }

    public TaskState getTask(String taskId) {
        return tasks.get(taskId);
    }

    public List<TaskState> getTasksByStatus(TaskStatus status) {
        return tasks.values().stream()
                .filter(s -> s.getStatus() == status)
                .collect(Collectors.toList());
    }

    public List<TaskState> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public List<TaskState> getActiveTasks() {
        return tasks.values().stream()
                .filter(s -> s.getStatus().isActive())
                .collect(Collectors.toList());
    }

    public int getActiveTaskCount() {
        return (int) tasks.values().stream()
                .filter(s -> s.getStatus().isActive())
                .count();
    }

    public int getTotalTaskCount() {
        return tasks.size();
    }

    // ========== 数据库持久化 ==========

    public void setDatabase(DatabaseManager db) {
        this.sf = db != null ? db.getSessionFactory() : null;
    }

    /**
     * 从数据库加载所有 gateway_tasks 记录，重建任务状态。
     * 将 IN_PROGRESS, ASSIGNED, TIMEOUT_SOON, SUSPECT → SUSPENDED（Worker 已断连）
     */
    public void loadFromDatabase() {
        if (sf == null) return;
        try (Session session = sf.openSession()) {
            List<GatewayTaskEntity> entities = session.createQuery(
                    "FROM GatewayTaskEntity ORDER BY createdAt DESC", GatewayTaskEntity.class).list();
            List<TaskState> loaded = new ArrayList<>();
            for (GatewayTaskEntity entity : entities) {
                TaskState state = new TaskState(entity.getTaskId());
                String statusStr = entity.getStatus();
                TaskStatus status = TaskStatus.valueOf(statusStr);
                if (status == TaskStatus.IN_PROGRESS || status == TaskStatus.ASSIGNED
                        || status == TaskStatus.TIMEOUT_SOON || status == TaskStatus.SUSPECT) {
                    status = TaskStatus.SUSPENDED;
                }
                state.setStatus(status);
                state.setOriginalRequest(entity.getOriginalRequest());
                try {
                    java.lang.reflect.Field f = TaskState.class.getDeclaredField("createdAt");
                    f.setAccessible(true);
                    f.set(state, entity.getCreatedAt());
                    f = TaskState.class.getDeclaredField("updatedAt");
                    f.setAccessible(true);
                    f.set(state, entity.getUpdatedAt());
                } catch (Exception ignored) {}
                String errorMsg = entity.getErrorMessage();
                if (errorMsg != null && !errorMsg.isEmpty()) {
                    state.addAttribute("failReason", errorMsg);
                }
                loaded.add(state);
            }
            for (TaskState state : loaded) {
                tasks.put(state.getTaskId(), state);
            }
            logger.info("Loaded {} tasks from database ({} suspended)", loaded.size(),
                    loaded.stream().filter(s -> s.getStatus() == TaskStatus.SUSPENDED).count());
        } catch (Exception e) {
            logger.warn("Failed to load tasks from database: {}", e.getMessage());
        }
    }

    /**
     * Persist current task state to database.
     */
    private void persistTask(TaskState state) {
        if (sf == null) return;
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            GatewayTaskEntity entity = session.createQuery(
                    "FROM GatewayTaskEntity WHERE taskId = :id", GatewayTaskEntity.class)
                    .setParameter("id", state.getTaskId())
                    .uniqueResult();
            if (entity == null) {
                entity = new GatewayTaskEntity();
                entity.setTaskId(state.getTaskId());
                entity.setCreatedAt(state.getCreatedAt());
            }
            entity.setStatus(state.getStatus().name());
            entity.setOriginalRequest(state.getOriginalRequest());
            entity.setErrorMessage(state.getAttribute("failReason") != null
                    ? state.getAttribute("failReason").toString() : null);
            entity.setUpdatedAt(state.getUpdatedAt());
            session.merge(entity);
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.debug("Failed to persist task {}: {}", state.getTaskId(), e.getMessage());
        }
    }

    // ========== 任务超时配置 ==========

    /**
     * 从系统属性加载超时和 checkpoint 配置
     */
    public void configureFromProperties() {
        this.taskTimeoutMs = parseLongProp("task.timeout", 1_800_000);
        this.taskTimeoutGraceMs = parseLongProp("task.timeout.grace", 30_000);
        this.taskTimeoutAction = System.getProperty("task.timeout.action", "suspend");
        this.checkpointReportSteps = parseIntProp("checkpoint.report.steps", 3);
        this.checkpointReportTokens = parseIntProp("checkpoint.report.tokens", 2000);
        logger.info("Task timeout configured: timeout={}ms, grace={}ms, action={}",
                taskTimeoutMs, taskTimeoutGraceMs, taskTimeoutAction);
    }

    public long getTaskTimeoutMs() { return taskTimeoutMs; }
    public long getTaskTimeoutGraceMs() { return taskTimeoutGraceMs; }
    public String getTaskTimeoutAction() { return taskTimeoutAction; }
    public int getCheckpointReportSteps() { return checkpointReportSteps; }
    public int getCheckpointReportTokens() { return checkpointReportTokens; }

    private static long parseLongProp(String key, long defaultValue) {
        String val = System.getProperty(key);
        if (val != null) {
            try { return Long.parseLong(val); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static int parseIntProp(String key, int defaultValue) {
        String val = System.getProperty(key);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private void fireEvent(String taskId, TaskStatus oldStatus, TaskStatus newStatus, TaskState state) {
        TaskEvent event = new TaskEvent(taskId, oldStatus, newStatus, state);
        for (Consumer<TaskEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Task event listener error: {}", e.getMessage());
            }
        }
    }
}
