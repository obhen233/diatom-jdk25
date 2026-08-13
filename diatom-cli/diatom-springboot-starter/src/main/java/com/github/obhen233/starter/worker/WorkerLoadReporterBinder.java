package com.github.obhen233.starter.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * {@link WorkerLoadReporter} SPI 生命周期管理。
 *
 * <p>探测 Spring 容器中用户注册的 {@link WorkerLoadReporter} Bean，存在时在启动后调用
 * {@link WorkerLoadReporter#start(WorkerLoadState)}、关闭时调用 {@link WorkerLoadReporter#stop()}。
 * 未注册任何实现则无动态负载上报（记录日志），不影响其它功能。
 */
public class WorkerLoadReporterBinder {
    private static final Logger logger = LoggerFactory.getLogger(WorkerLoadReporterBinder.class);

    private final ObjectProvider<WorkerLoadReporter> reporterProvider;
    private final WorkerLoadState loadState;

    public WorkerLoadReporterBinder(ObjectProvider<WorkerLoadReporter> reporterProvider,
                                    WorkerLoadState loadState) {
        this.reporterProvider = reporterProvider;
        this.loadState = loadState;
    }

    @PostConstruct
    public void start() {
        WorkerLoadReporter reporter = reporterProvider.getIfAvailable();
        if (reporter == null) {
            logger.info("No WorkerLoadReporter SPI implementation found; dynamic load reporting disabled");
            return;
        }
        try {
            reporter.start(loadState);
            logger.info("WorkerLoadReporter started: {}", reporter.getClass().getName());
        } catch (Exception e) {
            logger.warn("WorkerLoadReporter failed to start: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        WorkerLoadReporter reporter = reporterProvider.getIfAvailable();
        if (reporter != null) {
            try {
                reporter.stop();
                logger.info("WorkerLoadReporter stopped: {}", reporter.getClass().getName());
            } catch (Exception e) {
                logger.debug("WorkerLoadReporter stop error: {}", e.getMessage());
            }
        }
    }
}
