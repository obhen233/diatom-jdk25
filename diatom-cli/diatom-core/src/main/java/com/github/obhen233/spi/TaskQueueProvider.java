package com.github.obhen233.spi;

/**
 * SPI 接口：Gateway 任务队列提供者。
 * <p>
 * 实现此接口可以通过 {@link java.util.ServiceLoader} 注册自定义的任务队列，
 * 用于 Gateway 的异步处理模式（{@code --queue true}）。
 * </p>
 *
 * <p>当队列模式启用时，Gateway 的 {@code /gateway/v1/chat} 端点会：
 * <ol>
 *   <li>立即返回 {@code 202 Accepted}，包含 {@code taskId}</li>
 *   <li>将请求放入队列异步处理</li>
 *   <li>处理完成后可通过 {@code /gateway/v1/tasks/{taskId}} 获取结果</li>
 * </ol>
 * </p>
 *
 * <h3>内置实现</h3>
 * 默认使用 {@code InMemoryTaskQueue}（基于 {@link java.util.concurrent.BlockingQueue}），
 * 适用于单机场景。如需接入 Kafka、RabbitMQ、Redis 等，实现此接口并在
 * {@code META-INF/services/com.github.obhen233.spi.TaskQueueProvider} 中注册。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * public class KafkaTaskQueue implements TaskQueueProvider {
 *     private Producer<String, String> producer;
 *     private Consumer<String, String> consumer;
 *
 *     public void init(Properties config) {
 *         // 从 config 读取 Kafka 连接参数
 *         this.producer = new KafkaProducer<>(...);
 *         this.consumer = new KafkaConsumer<>(...);
 *     }
 *
 *     public void enqueue(QueuedTask task) {
 *         producer.send(new ProducerRecord<>("diatom-tasks", task.taskId, task.body));
 *     }
 *
 *     public QueuedTask dequeue() throws InterruptedException {
 *         ConsumerRecords records = consumer.poll(1000);
 *         // 转换记录为 QueuedTask
 *     }
 *
 *     public String getName() { return "kafka"; }
 * }
 * }</pre>
 */
public interface TaskQueueProvider {

    /**
     * A task waiting to be processed by the Gateway.
     */
    final class QueuedTask {
        private final String taskId;
        private final String sessionId;
        private final String message;
        private final String body;

        public QueuedTask(String taskId, String sessionId, String message, String body) {
            this.taskId = taskId;
            this.sessionId = sessionId;
            this.message = message;
            this.body = body;
        }

        public String getTaskId() { return taskId; }
        public String getSessionId() { return sessionId; }
        public String getMessage() { return message; }
        public String getBody() { return body; }
    }

    /**
     * Submit a task to the queue for asynchronous processing.
     */
    void enqueue(QueuedTask task);

    /**
     * Poll for the next task, blocking until one is available.
     *
     * @return the next task to process
     * @throws InterruptedException if interrupted while waiting
     */
    QueuedTask dequeue() throws InterruptedException;

    /**
     * Get the number of tasks currently waiting in the queue.
     */
    int getQueueDepth();

    /**
     * Initialize the queue provider with configuration properties.
     * <p>
     * Called once when the Gateway starts in queue mode.
     * Properties are read from {@code application.properties} with the prefix
     * {@code gateway.queue.}.
     * </p>
     */
    void init(java.util.Properties config);

    /**
     * Shutdown the queue provider and release resources.
     */
    void shutdown();

    /**
     * Provider name for logging and identification.
     */
    String getName();

    /**
     * Priority for SPI selection. Higher priority wins when multiple
     * implementations are registered.
     */
    default int getPriority() {
        return 0;
    }
}
