package com.github.obhen233.starter.gateway.queue;

import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.spi.TaskQueueProvider;
import com.github.obhen233.spi.TaskQueueProvider.QueuedTask;
import com.github.obhen233.starter.gateway.SpringGatewayTransport;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task Queue 后台消费者。
 *
 * <p>后台线程池从 {@link TaskQueueProvider} 拉取任务，
 * 依次执行 analyzeRequest → route → transport → storeResult。</p>
 */
public class TaskQueueProcessor implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(TaskQueueProcessor.class);

    private final TaskQueueProvider queue;
    private final GatewayAgent gatewayAgent;
    private final CapabilityRouter capabilityRouter;
    private final SpringGatewayTransport transport;
    private final TaskManager taskManager;
    private final TaskResultStore resultStore;
    private final int workers;
    private final String gatewayUrl;

    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** 任务因 Worker 过载(503)被重新入队的次数，防止死循环 */
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();

    /** 单个任务最大入队重试次数 */
    private static final int MAX_REQUEUE_RETRIES = 5;

    public TaskQueueProcessor(TaskQueueProvider queue,
                               GatewayAgent gatewayAgent,
                               CapabilityRouter capabilityRouter,
                               SpringGatewayTransport transport,
                               TaskManager taskManager,
                               TaskResultStore resultStore,
                               int workers) {
        this.queue = queue;
        this.gatewayAgent = gatewayAgent;
        this.capabilityRouter = capabilityRouter;
        this.transport = transport;
        this.taskManager = taskManager;
        this.resultStore = resultStore;
        this.workers = Math.max(1, workers);
        this.gatewayUrl = System.getProperty("gateway.url", "");
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("task-queue-consumer-", 0).factory());
    }

    /**
     * 启动后台消费者线程。
     */
    public void start() {
        for (int i = 0; i < workers; i++) {
            executor.submit(this::consumeLoop);
        }
        logger.info("Task queue processor started with {} worker(s)", workers);
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                QueuedTask queued = queue.dequeue();
                processTask(queued);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Unexpected error in task consumer loop", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processTask(QueuedTask queued) {
        String taskId = queued.getTaskId();
        String message = queued.getMessage();
        String sessionId = queued.getSessionId();

        logger.info("Processing queued task: {} (session={})", taskId, sessionId);

        String workerId = null;
        try {
            // 1. Parse original body
            Map<String, Object> originalBody = JsonUtils.fromJson(queued.getBody(), Map.class);
            if (originalBody == null) originalBody = new LinkedHashMap<>();

            // 2. LLM analyze → route
            TaskRequirement requirement = gatewayAgent.analyzeRequest(message);
            WorkerInfo target = capabilityRouter.routeWithLLMSuggestion(requirement);
            if (target == null) {
                String err = "No available workers for queued task " + taskId;
                logger.warn(err);
                taskManager.failTask(taskId, err);
                resultStore.store(taskId, "failed", errorMap(err));
                return;
            }
            workerId = target.getWorkerId();
            capabilityRouter.incrementActive(workerId);

            logger.info("Task {} routed to worker {} (type={})", taskId, workerId, requirement.getTaskType());

            // 3. Task lifecycle (task already created by controller, assign + start)
            taskManager.assignTask(taskId, workerId);
            taskManager.startTask(taskId);

            // 4. Build worker request
            String workspacePath = System.getProperty("user.dir", ".");
            Map<String, Object> workerBody = new LinkedHashMap<>(originalBody.size() + 5);
            workerBody.putAll(originalBody);
            workerBody.put("taskId", taskId);
            workerBody.put("sessionId", sessionId);
            workerBody.put("workspacePath", workspacePath);
            String syncStrategy = requirement.getSyncStrategy();
            workerBody.put("syncStrategy", syncStrategy != null ? syncStrategy : "skip");
            if (!gatewayUrl.isEmpty()) {
                workerBody.put("gatewayUrl", gatewayUrl);
            }

            // 5. Forward to worker (携带状态码，识别 503 过载)
            String responseBody = null;
            SpringGatewayTransport.HttpResult httpResult =
                    transport.sendChatRequestResult(workerId, JsonUtils.toJson(workerBody));

            if (httpResult != null && httpResult.isOverloaded()) {
                int retries = retryCounts.containsKey(taskId) ? retryCounts.get(taskId) + 1 : 1;
                retryCounts.put(taskId, retries);
                if (retries <= MAX_REQUEUE_RETRIES) {
                    logger.warn("Task {} worker {} overloaded (HTTP {}), requeueing (retry {}/{})",
                            taskId, workerId, httpResult.getStatusCode(), retries, MAX_REQUEUE_RETRIES);
                    queue.enqueue(queued);
                    return; // finally 中释放活跃计数
                }
                logger.error("Task {} requeue limit exceeded ({}), failing",
                        taskId, MAX_REQUEUE_RETRIES);
            } else if (httpResult != null && httpResult.isSuccess()) {
                responseBody = httpResult.getBody();
            } else {
                logger.warn("Task {} worker {} failed (http={}), failing",
                        taskId, workerId, httpResult != null ? httpResult.getStatusCode() : -1);
            }

            if (responseBody == null) {
                taskManager.failTask(taskId, "Worker did not respond");
                resultStore.store(taskId, "failed", errorMap("Worker did not respond"));
                return;
            }

            // 6. Complete task
            taskManager.completeTask(taskId);

            // 7. Store result for polling
            Map<String, Object> result = buildResultMap(taskId, workerId, target, responseBody);
            resultStore.store(taskId, "completed", result);
            logger.info("Queued task completed: {} -> worker {}", taskId, workerId);

        } catch (Exception e) {
            logger.error("Failed to process queued task {}: {}", taskId, e.getMessage(), e);
            if (taskId != null) {
                try { taskManager.failTask(taskId, e.getMessage()); } catch (Exception ignored) {}
            }
            resultStore.store(taskId, "failed", errorMap(e.getMessage()));
        } finally {
            if (workerId != null) {
                capabilityRouter.decrementActive(workerId);
            }
        }
    }

    private Map<String, Object> buildResultMap(String taskId, String workerId,
                                                WorkerInfo worker, String workerBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", "completed");

        Map<String, Object> workerObj = new LinkedHashMap<>();
        workerObj.put("id", workerId);
        workerObj.put("url", worker.getBaseUrl());
        workerObj.put("model", worker.getModel() != null ? worker.getModel() : "");
        result.put("worker", workerObj);

        try {
            Map<String, Object> parsed = JsonUtils.fromJson(workerBody, Map.class);
            if (parsed != null) {
                if (parsed.containsKey("response")) {
                    result.put("response", parsed.get("response"));
                }
                if (parsed.containsKey("workerMeta")) {
                    result.put("workerMeta", parsed.get("workerMeta"));
                }
            } else {
                result.put("response", workerBody);
            }
        } catch (Exception e) {
            result.put("response", workerBody);
        }
        return result;
    }

    private static Map<String, Object> errorMap(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        return result;
    }

    @Override
    public void destroy() {
        running.set(false);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Task queue processor shut down");
    }
}
