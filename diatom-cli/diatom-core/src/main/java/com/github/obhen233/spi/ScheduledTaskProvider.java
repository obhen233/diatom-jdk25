package com.github.obhen233.spi;

/**
 * SPI 接口：定时任务调度系统接入。
 * <p>
 * 实现此接口可以将外部定时调度系统（XXL-Job、Quartz、DolphinScheduler 等）
 * 接入 diatom 的任务执行引擎。
 * </p>
 *
 * <p>SPI 实现方只需要：</p>
 * <ol>
 *   <li>在 {@link #init(SchedulerCallback)} 中保存 callback 引用</li>
 *   <li>在 {@link #register()} 中连接外部调度系统并注册 Job</li>
 *   <li>在调度系统触发时调用 {@link SchedulerCallback#submitTask(String)} 将任务交给 diatom 执行</li>
 * </ol>
 *
 * <p>diatom 内部的路由、分发、执行对 SPI 实现方完全透明。</p>
 *
 * <h3>使用示例（XXL-Job）</h3>
 * <pre>{@code
 * public class XxlJobProvider implements ScheduledTaskProvider {
 *     private SchedulerCallback callback;
 *     private XxlJobExecutor executor;
 *
 *     public void init(SchedulerCallback callback) {
 *         this.callback = callback;
 *     }
 *
 *     public void register() {
 *         executor = new XxlJobExecutor();
 *         executor.setAdminAddresses("http://xxl-job-admin:8080");
 *         executor.setAppName("diatom-worker");
 *         executor.start();
 *         XxlJobHelper.registerJobHandler("dailyCheck", (param) -> {
 *             String taskId = callback.submitTask(param);
 *             log.info("Scheduled task submitted: {}", taskId);
 *         });
 *     }
 *
 *     public void unregister() {
 *         if (executor != null) executor.stop();
 *     }
 * }
 * }</pre>
 */
public interface ScheduledTaskProvider {

    /**
     * 使用 diatom 提供的 callback 初始化 SPI 实现。
     * <p>
     * 在 {@link #register()} 之前调用。SPI 实现应在此方法中保存 callback 引用，
     * 供后续调度触发时调用。
     * </p>
     *
     * @param callback 用于向 diatom 提交任务的回调
     */
    default void init(SchedulerCallback callback) {
        // no-op
    }

    /**
     * 向外部调度系统注册本节点。
     * <p>
     * 在 {@link #init(SchedulerCallback)} 之后调用。
     * SPI 实现应在此方法中连接外部调度系统并注册 JobHandler。
     * </p>
     */
    default void register() {
        // no-op
    }

    /**
     * 从外部调度系统注销本节点。
     * <p>
     * Gateway 关闭时调用，SPI 实现应在此方法中释放调度系统资源。
     * </p>
     */
    default void unregister() {
        // no-op
    }

    /**
     * 提供者名称，用于日志和识别。
     */
    default String getName() {
        return getClass().getSimpleName();
    }

    /**
     * SPI 选择优先级。当注册了多个实现时，优先级高的优先。
     */
    default int getPriority() {
        return 0;
    }

    /**
     * diatom 注入给 SPI 实现的回调接口。
     * <p>
     * SPI 实现方在调度系统触发时调用此接口提交任务，无需关心内部路由和执行逻辑。
     * </p>
     */
    interface SchedulerCallback {

        /**
         * 提交一个定时任务给 diatom 执行。
         * <p>
         * diatom 内部会完成：创建任务记录 → 分析请求意图 → 路由到合适的 Worker →
         * 异步发送执行 → 完成任务。
         * </p>
         *
         * @param params 任务参数（JSON 字符串或纯文本）
         * @return taskId，可用于后续查询任务状态
         */
        String submitTask(String params);

        /**
         * 提交定时任务并指定 session，用于上下文延续。
         *
         * @param sessionId 会话 ID
         * @param params 任务参数
         * @return taskId
         */
        String submitTask(String sessionId, String params);

        /**
         * 查询已提交任务的状态。
         *
         * @param taskId 由 {@link #submitTask} 返回的任务 ID
         * @return 任务状态名称（如 RUNNING、COMPLETED、FAILED），或 UNKNOWN
         */
        String getTaskStatus(String taskId);
    }
}
