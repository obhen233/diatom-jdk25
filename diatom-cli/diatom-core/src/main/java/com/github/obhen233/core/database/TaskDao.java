package com.github.obhen233.core.database;

import com.github.obhen233.core.database.entity.TaskEntity;
import com.github.obhen233.core.database.entity.TaskStepEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for task operations
 */
public class TaskDao {
    private static final Logger logger = LoggerFactory.getLogger(TaskDao.class);

    private final SessionFactory sf;

    public TaskDao(DatabaseManager db) {
        this.sf = db.getSessionFactory();
    }

    // ==================== Task Operations ====================

    public void insertTask(TaskInfo task) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                session.persist(toTaskEntity(task));
                tx.commit();
                logger.info("Inserted task (Hibernate): {}", task.id);
            } catch (Exception e) {
                tx.rollback();
                logger.error("Failed to insert task (Hibernate): {}", task.id, e);
            }
        }
    }

    public TaskInfo findTaskById(String id) {
        try (Session session = sf.openSession()) {
            TaskEntity entity = session.get(TaskEntity.class, id);
            if (entity != null) {
                return toTaskInfo(entity);
            }
        } catch (Exception e) {
            logger.error("Failed to find task (Hibernate): {}", id, e);
        }
        return null;
    }

    public List<TaskInfo> findTasksByStatus(String status) {
        List<TaskInfo> tasks = new ArrayList<>();
        try (Session session = sf.openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<TaskEntity> cq = cb.createQuery(TaskEntity.class);
            Root<TaskEntity> root = cq.from(TaskEntity.class);
            cq.select(root).where(cb.equal(root.get("status"), status))
              .orderBy(cb.desc(root.get("updatedAt")));
            List<TaskEntity> entities = session.createQuery(cq).getResultList();
            for (TaskEntity entity : entities) {
                tasks.add(toTaskInfo(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to find tasks by status (Hibernate): {}", status, e);
        }
        return tasks;
    }

    public List<TaskInfo> findAllTasks() {
        List<TaskInfo> tasks = new ArrayList<>();
        try (Session session = sf.openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<TaskEntity> cq = cb.createQuery(TaskEntity.class);
            Root<TaskEntity> root = cq.from(TaskEntity.class);
            cq.select(root).orderBy(cb.desc(root.get("updatedAt")));
            List<TaskEntity> entities = session.createQuery(cq).getResultList();
            for (TaskEntity entity : entities) {
                tasks.add(toTaskInfo(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to find all tasks (Hibernate)", e);
        }
        return tasks;
    }

    public void updateTaskStatus(String id, String status) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                TaskEntity entity = session.get(TaskEntity.class, id);
                if (entity != null) {
                    entity.setStatus(status);
                    entity.setUpdatedAt(Instant.now().toEpochMilli());
                    session.merge(entity);
                }
                tx.commit();
                logger.info("Updated task {} status to {} (Hibernate)", id, status);
            } catch (Exception e) {
                tx.rollback();
                logger.error("Failed to update task status (Hibernate): {}", id, e);
            }
        }
    }

    public void updateTaskStep(String id, int currentStep) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                TaskEntity entity = session.get(TaskEntity.class, id);
                if (entity != null) {
                    entity.setCurrentStep(currentStep);
                    entity.setUpdatedAt(Instant.now().toEpochMilli());
                    session.merge(entity);
                }
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                logger.error("Failed to update task step (Hibernate): {}", id, e);
            }
        }
    }

    public void updateTaskSnapshot(String id, int snapshotId) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                TaskEntity entity = session.get(TaskEntity.class, id);
                if (entity != null) {
                    entity.setLatestSnapshotId(snapshotId);
                    entity.setUpdatedAt(Instant.now().toEpochMilli());
                    session.merge(entity);
                }
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                logger.error("Failed to update task snapshot (Hibernate): {}", id, e);
            }
        }
    }

    public void updateTaskCheckpoint(String id, Integer checkpointId) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                TaskEntity entity = session.get(TaskEntity.class, id);
                if (entity != null) {
                    entity.setContextCheckpointId(checkpointId);
                    entity.setUpdatedAt(Instant.now().toEpochMilli());
                    session.merge(entity);
                }
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                logger.error("Failed to update task checkpoint (Hibernate): {}", id, e);
            }
        }
    }

    public void deleteTask(String id) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                TaskEntity entity = session.get(TaskEntity.class, id);
                if (entity != null) {
                    session.remove(entity);
                }
                tx.commit();
                logger.info("Deleted task (Hibernate): {}", id);
            } catch (Exception e) {
                tx.rollback();
                logger.error("Failed to delete task (Hibernate): {}", id, e);
            }
        }
    }

    // ==================== Task Step Operations ====================

    public void insertTaskStep(TaskStep step) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                session.persist(toTaskStepEntity(step));
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                logger.error("Failed to insert task step (Hibernate): {}", step.taskId, e);
            }
        }
    }

    public void updateTaskStepStatus(String taskId, int stepNumber, String status, String errorMessage) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                CriteriaBuilder cb = session.getCriteriaBuilder();
                CriteriaQuery<TaskStepEntity> cq = cb.createQuery(TaskStepEntity.class);
                Root<TaskStepEntity> root = cq.from(TaskStepEntity.class);
                cq.select(root).where(
                    cb.and(
                        cb.equal(root.get("taskId"), taskId),
                        cb.equal(root.get("stepNumber"), stepNumber)
                    )
                );
                List<TaskStepEntity> results = session.createQuery(cq).getResultList();
                if (!results.isEmpty()) {
                    TaskStepEntity entity = results.get(0);
                    entity.setStatus(status);
                    entity.setErrorMessage(errorMessage);
                    entity.setCompletedAt(Instant.now().toEpochMilli());
                    session.merge(entity);
                }
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                logger.error("Failed to update task step status (Hibernate): {} step {}", taskId, stepNumber, e);
            }
        }
    }

    public List<TaskStep> findStepsByTaskId(String taskId) {
        List<TaskStep> steps = new ArrayList<>();
        try (Session session = sf.openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<TaskStepEntity> cq = cb.createQuery(TaskStepEntity.class);
            Root<TaskStepEntity> root = cq.from(TaskStepEntity.class);
            cq.select(root).where(cb.equal(root.get("taskId"), taskId))
              .orderBy(cb.asc(root.get("stepNumber")));
            List<TaskStepEntity> entities = session.createQuery(cq).getResultList();
            for (TaskStepEntity entity : entities) {
                steps.add(toTaskStep(entity));
            }
        } catch (Exception e) {
            logger.error("Failed to find steps for task (Hibernate): {}", taskId, e);
        }
        return steps;
    }

    // ==================== Conversion Methods ====================

    private TaskEntity toTaskEntity(TaskInfo task) {
        TaskEntity entity = new TaskEntity();
        entity.setId(task.id);
        entity.setStatus(task.status);
        entity.setOriginalRequest(task.originalRequest);
        entity.setCurrentStep(task.currentStep);
        entity.setTotalSteps(task.totalSteps);
        entity.setWorkspacePath(task.workspacePath);
        entity.setProjectId(task.projectId);
        entity.setContextCheckpointId(task.contextCheckpointId);
        entity.setLatestSnapshotId(task.latestSnapshotId);
        entity.setCreatedAt(task.createdAt);
        entity.setUpdatedAt(task.updatedAt);
        return entity;
    }

    private TaskInfo toTaskInfo(TaskEntity entity) {
        TaskInfo task = new TaskInfo();
        task.id = entity.getId();
        task.status = entity.getStatus();
        task.originalRequest = entity.getOriginalRequest();
        task.currentStep = entity.getCurrentStep() != null ? entity.getCurrentStep() : 0;
        task.totalSteps = entity.getTotalSteps() != null ? entity.getTotalSteps() : 0;
        task.workspacePath = entity.getWorkspacePath();
        task.projectId = entity.getProjectId();
        task.contextCheckpointId = entity.getContextCheckpointId();
        task.latestSnapshotId = entity.getLatestSnapshotId();
        task.createdAt = entity.getCreatedAt() != null ? entity.getCreatedAt() : 0L;
        task.updatedAt = entity.getUpdatedAt() != null ? entity.getUpdatedAt() : 0L;
        return task;
    }

    private TaskStepEntity toTaskStepEntity(TaskStep step) {
        TaskStepEntity entity = new TaskStepEntity();
        entity.setTaskId(step.taskId);
        entity.setStepNumber(step.stepNumber);
        entity.setDescription(step.description);
        entity.setStatus(step.status);
        entity.setToolCalls(step.toolCalls);
        entity.setErrorMessage(step.errorMessage);
        entity.setCreatedAt(step.createdAt);
        entity.setCompletedAt(step.completedAt);
        return entity;
    }

    private TaskStep toTaskStep(TaskStepEntity entity) {
        TaskStep step = new TaskStep();
        step.taskId = entity.getTaskId();
        step.stepNumber = entity.getStepNumber() != null ? entity.getStepNumber() : 0;
        step.description = entity.getDescription();
        step.status = entity.getStatus();
        step.toolCalls = entity.getToolCalls();
        step.errorMessage = entity.getErrorMessage();
        step.createdAt = entity.getCreatedAt() != null ? entity.getCreatedAt() : 0L;
        step.completedAt = entity.getCompletedAt();
        return step;
    }

    // ==================== Data Classes ====================

    public static class TaskInfo {
        public String id;
        public String status;
        public String originalRequest;
        public int currentStep;
        public int totalSteps;
        public String workspacePath;
        public Long projectId;
        public Integer contextCheckpointId;
        public Integer latestSnapshotId;
        public long createdAt;
        public long updatedAt;

        public String getStatus() { return status; }
        public int getCurrentStep() { return currentStep; }
        public String getOriginalRequest() { return originalRequest; }
    }

    public static class TaskStep {
        public String taskId;
        public int stepNumber;
        public String description;
        public String status;
        public String toolCalls;
        public String errorMessage;
        public long createdAt;
        public Long completedAt;
    }
}
