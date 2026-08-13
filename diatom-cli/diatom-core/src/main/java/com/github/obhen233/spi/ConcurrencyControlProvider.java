package com.github.obhen233.spi;

/**
 * SPI 接口：自定义 Gateway 并发控制策略。
 * <p>
 * 实现此接口并通过 {@link java.util.ServiceLoader} 注册，
 * 可以自定义 Gateway 的最大并发请求数和请求准入决策。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 在 diatom-custom 中创建实现类
 * public class MyConcurrencyControl implements ConcurrencyControlProvider {
 *     public int getMaxConcurrentRequests() { return 50; }
 *
 *     public boolean acceptRequest(String clientIp, int currentActive,
 *                                  int maxConcurrent, double workerLoad) {
 *         // 自定义准入逻辑：根据客户端 IP 或 Worker 负载决定
 *         if (currentActive >= maxConcurrent) return false;
 *         if (workerLoad > 0.9) return false; // Worker 过载时拒绝
 *         return true;
 *     }
 * }
 * }</pre>
 *
 * <h3>注册方式</h3>
 * 在 {@code diatom-custom/src/main/resources/META-INF/services/} 下创建文件
 * {@code com.github.obhen233.spi.ConcurrencyControlProvider}，
 * 内容为实现类的全限定名。
 */
public interface ConcurrencyControlProvider {

    /**
     * 返回 Gateway 最大并发请求数。
     * 返回 -1 或 0 表示使用默认值（{@code 20}）。
     */
    default int getMaxConcurrentRequests() {
        return -1;
    }

    /**
     * 判断是否接受新的请求。
     * <p>
     * 此方法在每个请求的准入控制点被调用。返回 {@code true} 表示接受，
     * 返回 {@code false} 表示拒绝（客户端收到 429）。
     * </p>
     *
     * @param clientIp        请求来源 IP
     * @param currentActive   当前活跃请求数
     * @param maxConcurrent   最大并发限制
     * @param avgWorkerLoad   当前可用 Worker 的平均负载 (0.0~1.0)，无 Worker 时为 0
     * @return {@code true} 接受请求，{@code false} 拒绝请求
     */
    default boolean acceptRequest(String clientIp, int currentActive,
                                  int maxConcurrent, double avgWorkerLoad) {
        return currentActive < maxConcurrent;
    }
}
