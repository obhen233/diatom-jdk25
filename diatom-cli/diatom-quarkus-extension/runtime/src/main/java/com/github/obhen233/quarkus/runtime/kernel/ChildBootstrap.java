package com.github.obhen233.quarkus.runtime.kernel;

import org.jboss.logging.Logger;

/**
 * 子 Gateway（{@code gateway:child}）模式装配。
 *
 * <p>与 {@link GatewayBootstrap} 相同的内核装配，额外以 {@code tier=gateway-proxy}
 * 向父 Gateway 注册（Phase 2 {@code ChildGatewayResource} + 注册/心跳）。
 */
public class ChildBootstrap extends GatewayBootstrap {

    private static final Logger LOGGER = Logger.getLogger(ChildBootstrap.class);

    public ChildBootstrap(DiatomKernel kernel) {
        super(kernel);
    }

    @Override
    public void start() {
        super.start();
        LOGGER.info("Child gateway mode: assembled with gateway-proxy tier");

        // 以 tier=gateway-proxy 向父 Gateway 注册（父 Gateway 的地址 = diatom.gateway.url）
        try {
            String workerId = kernel.config().gateway().instanceId()
                    .filter(s -> !s.isEmpty())
                    .orElseGet(() -> defaultWorkerId("gateway-proxy"));
            String externalHost = defaultExternalHost();
            int externalPort = kernel.config().gateway().externalPort()
                    .orElse(kernel.config().gateway().port());
            String gatewayUrl = kernel.config().gateway().url().orElse("");
            startWorkerRegistration(workerId, externalHost, externalPort,
                    kernel.getShared().appConfig.getModel(), "default",
                    "gateway-proxy", 1, gatewayUrl);
        } catch (Exception e) {
            LOGGER.warnf("Child gateway registration failed: %s", e.getMessage());
        }
    }

    @Override
    public void stop() {
        stopRegistration();
        super.stop();
    }
}
