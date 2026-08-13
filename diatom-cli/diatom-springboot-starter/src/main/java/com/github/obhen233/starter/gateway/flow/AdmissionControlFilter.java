package com.github.obhen233.starter.gateway.flow;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.spi.ConcurrencyControlProvider;
import com.github.obhen233.spi.SpiLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 接入层限流 Filter。
 *
 * <p>基于 {@link Semaphore} 控制 Gateway API 的并发请求数，
 * 超过阈值返回 429 Too Many Requests。</p>
 *
 * <p>决策链路：</p>
 * <ol>
 *   <li>检查 {@link ConcurrencyControlProvider} SPI（如有自定义实现）</li>
 *   <li>尝试获取 {@link Semaphore} 许可</li>
 *   <li>任一环节拒绝则返回 429</li>
 * </ol>
 */
public class AdmissionControlFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionControlFilter.class);

    private final Semaphore semaphore;
    private final int maxConcurrent;
    private final WorkerRegistry workerRegistry;

    private ConcurrencyControlProvider concurrencyControl;

    public AdmissionControlFilter(int maxConcurrent, WorkerRegistry workerRegistry) {
        this.maxConcurrent = maxConcurrent > 0 ? maxConcurrent : 200;
        this.semaphore = new Semaphore(this.maxConcurrent, true);
        this.workerRegistry = workerRegistry;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        this.concurrencyControl = SpiLoader.getFirst(ConcurrencyControlProvider.class, null);
        if (concurrencyControl != null) {
            logger.info("AdmissionControl: using ConcurrencyControlProvider SPI: {}",
                    concurrencyControl.getClass().getName());
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // 1. 当前活跃数
        int currentActive = maxConcurrent - semaphore.availablePermits();

        // 2. Worker 平均负载
        double avgWorkerLoad = computeAvgWorkerLoad();

        // 3. 客户端 IP
        String clientIp = httpReq.getRemoteAddr();

        // 4. SPI 自定义准入决策
        if (concurrencyControl != null
                && !concurrencyControl.acceptRequest(clientIp, currentActive, maxConcurrent, avgWorkerLoad)) {
            logger.warn("AdmissionControl (SPI): rejected request from {}, active={}, workerLoad={}",
                    clientIp, currentActive, String.format("%.2f", avgWorkerLoad));
            sendTooManyRequests(httpResp,
                    "Request rejected by concurrency policy",
                    currentActive, maxConcurrent);
            return;
        }

        // 5. Semaphore 获取
        if (!semaphore.tryAcquire()) {
            logger.warn("AdmissionControl: rejected request (active={}, max={})",
                    currentActive, maxConcurrent);
            sendTooManyRequests(httpResp,
                    "Too many concurrent requests",
                    currentActive, maxConcurrent);
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            semaphore.release();
        }
    }

    private double computeAvgWorkerLoad() {
        if (workerRegistry == null) return 0.0;
        try {
            List<WorkerInfo> workers = workerRegistry.availableWorkers();
            if (workers == null || workers.isEmpty()) return 0.0;
            double total = 0;
            for (WorkerInfo w : workers) {
                total += w.getMetrics().getCurrentLoad();
            }
            return total / workers.size();
        } catch (Exception e) {
            logger.debug("Failed to compute avg worker load: {}", e.getMessage());
            return 0.0;
        }
    }

    private static void sendTooManyRequests(HttpServletResponse response,
                                             String message,
                                             int currentActive, int maxConcurrent) throws IOException {
        String json = "{\"error\":\"" + message + "\""
                + ",\"active\":" + currentActive
                + ",\"max\":" + maxConcurrent + "}";
        response.setStatus(429);
        response.setContentType("application/json; charset=utf-8");
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void destroy() {
        // No resources to release
    }
}
