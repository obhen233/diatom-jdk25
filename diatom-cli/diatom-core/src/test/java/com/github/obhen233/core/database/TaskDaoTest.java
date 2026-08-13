package com.github.obhen233.core.database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

import com.github.obhen233.core.database.HibernateConfig;
import com.github.obhen233.core.database.HibernateDatabaseManager;

import static org.junit.Assert.*;

/**
 * TaskDao 测试用例
 */
public class TaskDaoTest {

    private DatabaseManager db;
    private TaskDao taskDao;
    private String testDbPath;

    @Before
    public void setUp() throws Exception {
        testDbPath = Paths.get(System.getProperty("java.io.tmpdir"), "diatom_taskdao_test_" + System.currentTimeMillis() + ".db").toString();
        HibernateConfig config = new HibernateConfig("sqlite", "jdbc:sqlite:" + testDbPath, "", "", 2);
        db = new HibernateDatabaseManager(config);
        db.initialize();
        taskDao = new TaskDao(db);
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
        File dbFile = new File(testDbPath);
        if (dbFile.exists()) {
            dbFile.delete();
        }
    }

    private TaskDao.TaskInfo createTaskInfo(String originalRequest, String workspacePath) {
        TaskDao.TaskInfo task = new TaskDao.TaskInfo();
        task.id = "task_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
        task.originalRequest = originalRequest;
        task.workspacePath = workspacePath;
        task.status = "CREATED";
        task.currentStep = 0;
        task.totalSteps = 0;
        task.createdAt = System.currentTimeMillis();
        task.updatedAt = System.currentTimeMillis();
        return task;
    }

    private TaskDao.TaskStep createTaskStep(String taskId, int stepNumber, String description) {
        TaskDao.TaskStep step = new TaskDao.TaskStep();
        step.taskId = taskId;
        step.stepNumber = stepNumber;
        step.description = description;
        step.status = "PENDING";
        step.createdAt = System.currentTimeMillis();
        return step;
    }

    @Test
    public void testInsertAndFindTask() {
        TaskDao.TaskInfo task = createTaskInfo("Test task", "/workspace");
        taskDao.insertTask(task);

        TaskDao.TaskInfo found = taskDao.findTaskById(task.id);
        assertNotNull("Task should be found", found);
        assertEquals("Task ID should match", task.id, found.id);
        assertEquals("Status should be CREATED", "CREATED", found.status);
        assertEquals("Original request should match", "Test task", found.originalRequest);
        assertEquals("Workspace should match", "/workspace", found.workspacePath);
    }

    @Test
    public void testUpdateTaskStatus() {
        TaskDao.TaskInfo task = createTaskInfo("Status test", "/workspace");
        taskDao.insertTask(task);

        taskDao.updateTaskStatus(task.id, "IN_PROGRESS");
        TaskDao.TaskInfo found = taskDao.findTaskById(task.id);
        assertEquals("Status should be updated to IN_PROGRESS", "IN_PROGRESS", found.status);
    }

    @Test
    public void testUpdateTaskStep() {
        TaskDao.TaskInfo task = createTaskInfo("Step test", "/workspace");
        taskDao.insertTask(task);

        taskDao.updateTaskStep(task.id, 5);
        TaskDao.TaskInfo found = taskDao.findTaskById(task.id);
        assertEquals("Current step should be 5", 5, found.currentStep);
    }

    @Test
    public void testFindAllTasks() {
        taskDao.insertTask(createTaskInfo("Task 1", "/workspace"));
        taskDao.insertTask(createTaskInfo("Task 2", "/workspace"));
        taskDao.insertTask(createTaskInfo("Task 3", "/workspace"));

        List<TaskDao.TaskInfo> tasks = taskDao.findAllTasks();
        assertEquals("Should have 3 tasks", 3, tasks.size());
    }

    @Test
    public void testFindTasksByStatus() {
        TaskDao.TaskInfo task1 = createTaskInfo("Task 1", "/workspace");
        task1.status = "IN_PROGRESS";
        taskDao.insertTask(task1);

        TaskDao.TaskInfo task2 = createTaskInfo("Task 2", "/workspace");
        taskDao.insertTask(task2);

        List<TaskDao.TaskInfo> inProgressTasks = taskDao.findTasksByStatus("IN_PROGRESS");
        assertEquals("Should have 1 IN_PROGRESS task", 1, inProgressTasks.size());
        assertEquals("Task 1 should be IN_PROGRESS", "IN_PROGRESS", inProgressTasks.get(0).status);
    }

    @Test
    public void testFindNonExistentTask() {
        TaskDao.TaskInfo found = taskDao.findTaskById("nonexistent");
        assertNull("Should return null for non-existent task", found);
    }

    @Test
    public void testInsertAndFindTaskSteps() {
        String taskId = "step_test_task";
        TaskDao.TaskInfo task = createTaskInfo("Step test", "/workspace");
        task.id = taskId;
        taskDao.insertTask(task);

        TaskDao.TaskStep step1 = createTaskStep(taskId, 1, "Step 1 description");
        TaskDao.TaskStep step2 = createTaskStep(taskId, 2, "Step 2 description");
        taskDao.insertTaskStep(step1);
        taskDao.insertTaskStep(step2);

        List<TaskDao.TaskStep> steps = taskDao.findStepsByTaskId(taskId);
        assertEquals("Should have 2 steps", 2, steps.size());
    }

    @Test
    public void testUpdateTaskStepStatus() {
        String taskId = "step_status_task";
        TaskDao.TaskInfo task = createTaskInfo("Step status test", "/workspace");
        task.id = taskId;
        taskDao.insertTask(task);

        TaskDao.TaskStep step = createTaskStep(taskId, 1, "Step 1");
        taskDao.insertTaskStep(step);

        taskDao.updateTaskStepStatus(taskId, 1, "COMPLETED", null);
        List<TaskDao.TaskStep> steps = taskDao.findStepsByTaskId(taskId);
        assertEquals("Step status should be COMPLETED", "COMPLETED", steps.get(0).status);
    }

    @Test
    public void testDeleteTask() {
        TaskDao.TaskInfo task = createTaskInfo("Delete test", "/workspace");
        taskDao.insertTask(task);

        assertNotNull("Task should exist before delete", taskDao.findTaskById(task.id));

        taskDao.deleteTask(task.id);

        assertNull("Task should not exist after delete", taskDao.findTaskById(task.id));
    }

    @Test
    public void testTaskTimestamps() {
        long beforeCreate = System.currentTimeMillis();
        TaskDao.TaskInfo task = createTaskInfo("Timestamp test", "/workspace");
        taskDao.insertTask(task);
        long afterCreate = System.currentTimeMillis();

        TaskDao.TaskInfo found = taskDao.findTaskById(task.id);
        assertTrue("Created at should be set", found.createdAt > 0);
        assertTrue("Updated at should be set", found.updatedAt > 0);
    }
}
