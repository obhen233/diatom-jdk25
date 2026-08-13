package com.github.obhen233.quarkus.runtime.queue;

import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务结果存储，支持 TTL 自动清理（镜像 starter {@code TaskResultStore}）。
 *
 * <p>队列模式下，后台消费者处理完任务后将结果存入此存储，
 * 客户端通过轮询 {@code /gateway/v1/tasks/{taskId}} 获取结果。
 * 过期条目每 60 秒清理一次，默认 TTL 5 分钟。</p>
 */
public class TaskResultStore {

    private static final Logger LOGGER = Logger.getLogger(TaskResultStore.class);

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler;
    private final long ttlMs;

    public TaskResultStore(long ttlSeconds) {
        this.ttlMs = ttlSeconds > 0 ? TimeUnit.SECONDS.toMillis(ttlSeconds) : 300_000L;
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-result-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupExpired, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 存储任务结果。
     *
     * @param taskId 任务 ID
     * @param status 状态（"completed" / "failed"）
     * @param data   响应数据
     */
    public void store(String taskId, String status, Map<String, Object> data) {
        store.put(taskId, new Entry(status, data, System.currentTimeMillis()));
    }

    /**
     * 获取任务结果；不存在或已过期返回 null。
     */
    public Entry get(String taskId) {
        Entry entry = store.get(taskId);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.createdAt > ttlMs) {
            store.remove(taskId);
            return null;
        }
        return entry;
    }

    /** 移除指定任务结果。 */
    public void remove(String taskId) {
        store.remove(taskId);
    }

    /** 当前存储的结果数。 */
    public int size() {
        return store.size();
    }

    /** 关闭清理调度器。 */
    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            cleanupScheduler.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Entry> e : store.entrySet()) {
            if (now - e.getValue().createdAt > ttlMs) {
                store.remove(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debugf("Cleaned up %d expired task results, %d remaining", removed, store.size());
        }
    }

    /**
     * 结果条目。
     */
    public static class Entry {
        private final String status;
        private final Map<String, Object> data;
        private final long createdAt;

        Entry(String status, Map<String, Object> data, long createdAt) {
            this.status = status;
            this.data = data;
            this.createdAt = createdAt;
        }

        public String getStatus() { return status; }
        public Map<String, Object> getData() { return data; }
        public long getCreatedAt() { return createdAt; }
    }
}
