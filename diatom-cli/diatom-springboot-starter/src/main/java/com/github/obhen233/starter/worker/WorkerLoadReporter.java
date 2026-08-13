package com.github.obhen233.starter.worker;

/**
 * Worker 动态负载上报 SPI。
 *
 * <p>由用户为各自的注册中心（Nacos/Eureka/Consul/自定义）实现本接口，将
 * {@link WorkerLoadState} 的实时负载刷新到注册中心实例 metadata
 * （如 Nacos 的 {@code diatom.current-load} / {@code diatom.active-tasks}），
 * 供 gateway 侧注册中心直读（{@code DiscoveryClientWorkerRegistry}）拿到实时负载。
 *
 * <p>starter 不内置任何实现（Nacos/Eureka/Consul 均为用户按需接入）。实现方式：注册一个
 * {@code WorkerLoadReporter} Spring Bean，starter 的 {@code WorkerLoadReporterBinder}
 * 会自动调用 {@link #start(WorkerLoadState)} / {@link #stop()} 管理生命周期。例如 Nacos：
 * <pre>
 * &#64;Bean
 * public WorkerLoadReporter nacosWorkerLoadReporter(WorkerLoadState loadState, Environment env) {
 *     return new NacosWorkerLoadReporter(loadState, env);
 * }
 * </pre>
 * 实现内可用 {@code NacosFactory.createNamingService(properties)} 建 {@code NamingService}，
 * 按 serviceName+ip+port 定位自身实例，修改 metadata 后以
 * {@code registerInstance}（Nacos 2.x 按 ip:port 幂等更新）提交。
 *
 * <p>未注册任何实现时无动态负载上报，容量控制仍由 worker 侧 503 准入
 * 与 gateway 侧 {@code activeRequests} 兜底。
 */
public interface WorkerLoadReporter {

    /**
     * 启动负载上报（由 starter 调用一次）。实现应自行按固定周期调度上报，
     * 所有失败仅打日志，优雅降级。
     *
     * @param loadState Worker 实时负载（{@link WorkerLoadState#getCurrentLoad()} /
     *                  {@link WorkerLoadState#getActiveTasks()}）
     */
    void start(WorkerLoadState loadState);

    /** 停止负载上报，释放资源（由 starter 在关闭时调用）。 */
    void stop();
}
