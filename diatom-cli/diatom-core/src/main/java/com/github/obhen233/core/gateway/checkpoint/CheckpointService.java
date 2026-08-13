package com.github.obhen233.core.gateway.checkpoint;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.entity.GatewayCheckpointEntity;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.util.JsonUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway 侧 checkpoint 接收 + 存储服务
 */
public class CheckpointService {
    private static final Logger logger = LoggerFactory.getLogger(CheckpointService.class);

    private final TaskManager taskManager;
    private final SessionFactory sf;
    private final Map<String, String> checkpointStore = new ConcurrentHashMap<>();

    public CheckpointService(TaskManager taskManager) {
        this(taskManager, null);
    }

    public CheckpointService(TaskManager taskManager, DatabaseManager db) {
        this.taskManager = taskManager;
        this.sf = db != null ? db.getSessionFactory() : null;
    }

    /**
     * 接收 Worker 上报的 checkpoint
     */
    public void receiveCheckpoint(CheckpointReport report) {
        if (report == null || report.getTaskId() == null) return;

        // Update task progress
        taskManager.updateCheckpoint(
                report.getTaskId(),
                report.getStepCount(),
                report.getTokenUsage(),
                report.getMessageCount()
        );

        // Persist to database
        persistToDatabase(report);

        // Store checkpoint data for potential migration (in-memory cache)
        String key = report.getTaskId() + ":" + report.getStepCount();
        Map<String, Object> cpMap = new java.util.LinkedHashMap<>();
        cpMap.put("taskId", report.getTaskId());
        cpMap.put("stepCount", report.getStepCount());
        cpMap.put("tokenUsage", report.getTokenUsage());
        cpMap.put("llmSummary", report.getLlmSummary());
        cpMap.put("fileChangeSummary", report.getFileChangeSummary());
        cpMap.put("progress", report.getProgress());
        cpMap.put("status", report.getStatus() != null ? report.getStatus() : "running");
        if (report.getWorkspacePath() != null) {
            cpMap.put("workspacePath", report.getWorkspacePath());
        }
        String cpJson = JsonUtils.toJson(cpMap);
        checkpointStore.put(key, cpJson);

        // Also store as latest
        checkpointStore.put(report.getTaskId() + ":latest", cpJson);

        logger.debug("Checkpoint received: task={}, step={}, tokens={}",
                report.getTaskId(), report.getStepCount(), report.getTokenUsage());
    }

    /**
     * Persist checkpoint report to database via Hibernate.
     */
    private void persistToDatabase(CheckpointReport report) {
        if (sf == null) return;
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            String cpId = report.getTaskId() + ":" + report.getStepCount();
            GatewayCheckpointEntity entity = session.createQuery(
                    "FROM GatewayCheckpointEntity WHERE checkpointId = :cpId", GatewayCheckpointEntity.class)
                    .setParameter("cpId", cpId)
                    .uniqueResult();
            if (entity == null) {
                entity = new GatewayCheckpointEntity();
                entity.setCheckpointId(cpId);
                entity.setTaskId(report.getTaskId());
                entity.setCreatedAt(System.currentTimeMillis());
            }
            entity.setStepCount(report.getStepCount());
            entity.setAgentState(report.getAgentState());
            entity.setConversationHistory(report.getConversationHistory() != null
                    ? String.join("\n---MSG_SEP---\n", report.getConversationHistory()) : null);
            entity.setToolResults(report.getToolResults() != null
                    ? String.join("\n---MSG_SEP---\n", report.getToolResults()) : null);
            entity.setUpdatedAt(System.currentTimeMillis());
            session.merge(entity);
            session.getTransaction().commit();
        } catch (Exception e) {
            logger.warn("Failed to persist checkpoint to database: {}", e.getMessage());
        }
    }

    /**
     * 读取 checkpoint（用于迁移时恢复上下文）
     */
    public String getCheckpoint(String taskId, int step) {
        String key = taskId + ":" + (step > 0 ? step : "latest");
        String cached = checkpointStore.get(key);
        if (cached != null) return cached;
        // Fallback: load from database if available
        if (sf != null) {
            return loadFullCheckpointFromDb(taskId, step);
        }
        return null;
    }

    /**
     * 从 database 加载完整 checkpoint 数据（用于任务恢复）
     */
    public String loadFullCheckpoint(String taskId) {
        if (sf == null) return null;
        return loadFullCheckpointFromDb(taskId, -1);
    }

    private String loadFullCheckpointFromDb(String taskId, int step) {
        if (sf == null) return null;
        try (Session session = sf.openSession()) {
            boolean latest = step <= 0;
            String hql = latest
                ? "FROM GatewayCheckpointEntity WHERE taskId = :taskId ORDER BY stepCount DESC"
                : "FROM GatewayCheckpointEntity WHERE checkpointId = :cpId";
            org.hibernate.query.Query<GatewayCheckpointEntity> query = session.createQuery(hql, GatewayCheckpointEntity.class);
            if (latest) {
                query.setParameter("taskId", taskId);
                query.setMaxResults(1);
            } else {
                query.setParameter("cpId", taskId + ":" + step);
            }
            GatewayCheckpointEntity entity = query.uniqueResult();
            if (entity != null) {
                Map<String, Object> cpMap = new java.util.LinkedHashMap<>();
                cpMap.put("taskId", entity.getTaskId());
                cpMap.put("stepCount", entity.getStepCount());
                cpMap.put("tokenUsage", entity.getTokenUsage() != null ? entity.getTokenUsage() : 0);
                if (entity.getLlmSummary() != null) cpMap.put("llmSummary", entity.getLlmSummary());
                if (entity.getFileChangeSummary() != null) cpMap.put("fileChangeSummary", entity.getFileChangeSummary());
                if (entity.getAgentState() != null) cpMap.put("agentState", entity.getAgentState());
                if (entity.getConversationHistory() != null) cpMap.put("conversationHistory", entity.getConversationHistory());
                cpMap.put("messageCount", entity.getMessageCount() != null ? entity.getMessageCount() : 0);
                cpMap.put("status", "running");
                return JsonUtils.toJson(cpMap);
            }
        } catch (Exception e) {
            logger.warn("Failed to load checkpoint from database: {}", e.getMessage());
        }
        return null;
    }

}
